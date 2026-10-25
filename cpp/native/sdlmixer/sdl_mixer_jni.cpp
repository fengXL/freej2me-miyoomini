#include <jni.h>
#include <stdio.h>
#include <SDL2/SDL.h>
#include <SDL2/SDL_mixer.h>
#include <unordered_map>
#include <set>
#include "org_recompile_mobile_SdlMixerManager.h"

#define DEFAULT_BGM_FREQ    44100
#define DEFAULT_BGM_FORMAT  AUDIO_S16LSB
#define DEFAULT_BGM_CHAN    2
#define DEFAULT_BGM_CHUNK   4096

// 音效最大并发通道数（每个 chunk 句柄独占一个通道，音效可重叠播放）。
// 部分游戏（如Gameloft引擎）加载声音库时会为库内所有条目一次性建Player，
// 实测可达65个，32个通道会导致后面的音效永久无声
#define MAX_SFX_CHANNELS 96

// 全局状态
static Mix_Music* currentMusic = NULL;
static jlong currentMusicHandle = 0;   // 正在播放的 music 句柄（0=无）
static jint currentVolume = 128;
static SDL_Haptic *haptic = NULL;

// music自然播完待通知的句柄：音频线程只置标志，Java轮询线程(sdlMixerUpdate)消费。
// 在SDL音频回调线程里直接回调Java，游戏监听器再调播放API会死锁整个音频线程
static volatile jlong g_finishedHandle = 0;

// 我们自己pause的music标记：Mix_PlayingMusic()对暂停中的音乐返回0（内部是music_active），
// 看门狗需要区分"暂停"与"流死亡"，不依赖SDL各版本Paused语义
static volatile int g_musicPaused = 0;

// 主音量(0-128)：同时作用于music和音效通道；每个音效句柄另有游戏设定的期望音量
static int g_masterVolume = 128;
static std::unordered_map<jlong, int> g_chunkVolume;

// --- 全局变量用于保存 Java 引用 ---
static JavaVM *g_jvm = NULL; // 保存 JavaVM 引用

// Native 上下文（每个 Java PlatformPlayer 对应一个）
struct NativeContext {
    jobject jobj;           // Java 对象的全局引用
    jmethodID onCallBack;   // 回调方法 ID

    NativeContext() : jobj(nullptr), onCallBack(nullptr){}
};


// 全局映射表：Mix_Music指针 → NativeContext指针
static std::unordered_map<jlong, NativeContext*> g_contextMap;

// 音效通道分配：chunk句柄 → 通道号；已占用通道集合
static std::unordered_map<jlong, int> g_chunkChannel;
static std::set<int> g_usedChannels;

// 错误处理宏
#define CHECK_SDL_ERROR(msg) \
    do { \
        const char* err = SDL_GetError(); \
        if (err && err[0] != '\0') { \
            fprintf(stderr, "SDL Error [%s]: %s\n", msg, err); \
            SDL_ClearError(); \
        } \
    } while(0)

#define CHECK_MIX_ERROR(msg) \
    do { \
        const char* err = Mix_GetError(); \
        if (err && err[0] != '\0') { \
            fprintf(stderr, "SDL_mixer Error [%s]: %s\n", msg, err); \
            Mix_ClearError(); \
        } \
    } while(0)

JNIEXPORT jint JNICALL  JNI_OnLoad(JavaVM* vm, void* reserved)
{
	JNIEnv* env = NULL;

	g_jvm = vm;

    if(vm -> GetEnv((void**) &env, JNI_VERSION_10) != JNI_OK) {
       return -1;
    }

	if(env == NULL)
	{
		return -1;
	}

    return JNI_VERSION_10;
}

// JNI_OnUnload 中清理
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_10) == JNI_OK) {
        // 清理所有上下文
        for (auto& pair : g_contextMap) {
            NativeContext* ctx = pair.second;
            if (ctx->jobj != nullptr) {
                env->DeleteGlobalRef(ctx->jobj);
            }
            delete ctx;
        }
        g_contextMap.clear();
    }
    g_jvm=NULL;
}


// 按游戏期望音量×主音量设置某音效句柄所在通道的音量
static void applyChunkVolume(jlong handle) {
	auto cc = g_chunkChannel.find(handle);
	if (cc == g_chunkChannel.end()) { return; }
	auto cv = g_chunkVolume.find(handle);
	int desired = (cv != g_chunkVolume.end()) ? cv->second : 128;
	Mix_Volume(cc->second, desired * g_masterVolume / 128);
}

// 主动停掉的music不算"自然播完"：清掉音频线程刚置上的标志，避免误发END_OF_MEDIA
static void suppressFinished(jlong handle) {
	if (g_finishedHandle == handle) {
		g_finishedHandle = 0;
	}
}

void musicFinished() {

	Mix_HookMusicFinished(NULL);

	// 只记录句柄，不在音频线程里回调Java（见g_finishedHandle说明）
	g_finishedHandle = currentMusicHandle;
	currentMusicHandle = 0;
	currentMusic = NULL;
	g_musicPaused = 0;
}

/**
 * Java轮询线程调用：转发music自然播完事件到对应Java播放器。
 * 兼做死流看门狗：currentMusicHandle非0但Mix_PlayingMusic()==0，
 * 说明流已终止而后端没有触发finished回调（无限循环BGM被悄悄杀死），
 * 补发结束事件让Java侧决定恢复/重启。
 */
JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerUpdate
  (JNIEnv *env, jobject obj) {

	// 看门狗：句柄在而流已死。注意Mix_PlayingMusic()对暂停中的音乐也返回0
	// （其内部是music_active），必须先排除我们主动暂停的情况，否则会把游戏暂停的音乐"补活"
	if (g_finishedHandle == 0 && currentMusicHandle != 0
		&& Mix_PlayingMusic() == 0 && g_musicPaused == 0) {
		jlong dead = currentMusicHandle;
		currentMusicHandle = 0;
		currentMusic = NULL;
		g_finishedHandle = dead;
		fprintf(stderr, "音乐看门狗: 流已终止(句柄%lld)，补发结束事件\n", (long long)dead);
	}

	jlong finished = g_finishedHandle;
	if (finished == 0) { return; }
	g_finishedHandle = 0;

	auto it = g_contextMap.find(finished);
	if (it != g_contextMap.end() && it->second->onCallBack != nullptr) {
		// 检查对象是否还有效
		if (env->IsSameObject(it->second->jobj, nullptr) == JNI_FALSE) {
			env->CallVoidMethod(it->second->jobj, it->second->onCallBack);
		}
	}
}


/**
 * 初始化SDL2和SDL2_mixer
 */
JNIEXPORT jint JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerInit
  (JNIEnv *env, jclass cls, jint frequency, jint format, jint channels, jint chunksize) {

    // 初始化SDL2音频子系统
    if (SDL_Init(SDL_INIT_AUDIO) < 0) {
        CHECK_SDL_ERROR("SDL_Init");
        return -1;
    }

    // 打开音频设备
    if (Mix_OpenAudio(DEFAULT_BGM_FREQ, DEFAULT_BGM_FORMAT, DEFAULT_BGM_CHAN, DEFAULT_BGM_CHUNK) < 0) {
        CHECK_MIX_ERROR("Mix_OpenAudio");
        SDL_Quit();
        return -1;
    }

    // 分配音效并发通道，每个音效句柄独占一个通道，互不打断
    Mix_AllocateChannels(MAX_SFX_CHANNELS);

    printf("✓ SDL2_mixer已初始化 (freq=%d, format=%d, ch=%d, chunk=%d, sfxChannels=%d)\n",
           frequency, format, channels, chunksize, MAX_SFX_CHANNELS);
    return 0;
}

/**
 * 初始化SDL2_Haptic
 */
JNIEXPORT jint JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlHapticInit
  (JNIEnv *env, jclass cls) {

    // 初始化SDL2震动子系统
    if (SDL_Init(SDL_INIT_HAPTIC) < 0) {
        CHECK_SDL_ERROR("SDL_Init HAPTIC");
        return -1;
    }

    int count_haptic = SDL_NumHaptics();
	printf("✓ Haptic num:%d \n",count_haptic);
	if(count_haptic>0)
	{
		haptic = SDL_HapticOpen(0);
		if (haptic) {
			if (SDL_HapticRumbleInit(haptic) == 0) {
				printf("✓ SDL2_Haptic已初始化\n");
				return 0;
			}
			else
			{
				printf("SDL2_Haptic初始化失败\n");
				SDL_HapticClose(haptic);
				haptic = NULL;
				return -1;
			}
		}
	}

	printf("SDL2_Haptic初始化失败\n");
	return -1;
}

//震动
JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlHaptic
  (JNIEnv *env, jclass cls, jint duration) {

	if(haptic)
	{
		SDL_HapticRumblePlay(haptic, 0.7f, duration);
	}
}

/**
 * 加载MIDI文件（实际用于 music 通道：MIDI/MP3/OGG 等流式音频）
 */
JNIEXPORT jlong JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerLoadMidi
  (JNIEnv *env, jobject obj, jstring filePath, jobject player) {

    if (filePath == NULL) {
        fprintf(stderr, "文件路径为空\n");
        return -1;
    }

    // 转换jstring为C字符串
    const char* path = env->GetStringUTFChars(filePath, NULL);
    if (path == NULL) {
        return -1;
    }

    printf("📂 加载音频文件: %s\n", path);

    // 加载音乐
	Mix_Music* music = Mix_LoadMUS(path);
	// 释放Java字符串
    env->ReleaseStringUTFChars(filePath, path);
	if (music == NULL) {
		CHECK_MIX_ERROR("Mix_LoadMUS");
		return -1;
	}
	jlong musicHandle = (jlong)(intptr_t)music;

    printf("✓ 音频文件加载成功\n");

	// 检查是否已存在
    auto it = g_contextMap.find( musicHandle );
    if (it == g_contextMap.end()) {//不存在
        // 创建新的上下文
		NativeContext* ctx = new NativeContext();
		ctx->jobj = env->NewGlobalRef(player);

		if (ctx->jobj == nullptr) {
			delete ctx;
			return -1;
		}

		// 获取回调方法 ID
		jclass localCls = env->GetObjectClass(player);
		if (localCls != nullptr) {
			ctx->onCallBack = env->GetMethodID(localCls, "onPlaybackComplete", "()V");
			if (ctx->onCallBack == nullptr) {
				env->ExceptionClear();  // 方法不存在，清空异常
			}
			env->DeleteLocalRef(localCls);
		}

		// 存入映射表
		g_contextMap[musicHandle] = ctx;
    }

    return musicHandle;
}

/**
 * 加载WAV音效（chunk通道，多路并发）
 */
JNIEXPORT jlong JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerLoadWav
  (JNIEnv *env, jobject obj, jstring filePath) {

    if (filePath == NULL) {
        fprintf(stderr, "文件路径为空\n");
        return -1;
    }

    // 转换jstring为C字符串
    const char* path = env->GetStringUTFChars(filePath, NULL);
    if (path == NULL) {
        return -1;
    }

    printf("📂 加载音效文件: %s\n", path);

    // 加载音效
	Mix_Chunk* music = Mix_LoadWAV(path);
	// 释放Java字符串
    env->ReleaseStringUTFChars(filePath, path);
	if (music == NULL) {
		CHECK_MIX_ERROR("Mix_LoadWAV");
		return -1;
	}
	jlong musicHandle = (jlong)(intptr_t)music;

	// 为该音效分配一个独占通道
	auto it = g_chunkChannel.find(musicHandle);
	if (it == g_chunkChannel.end()) {
		int chan = -1;
		for (int i = 0; i < MAX_SFX_CHANNELS; i++) {
			if (g_usedChannels.find(i) == g_usedChannels.end()) {
				chan = i;
				break;
			}
		}
		if (chan < 0) {
			// 通道耗尽（极少见：同时存在32个音效Player）
			fprintf(stderr, "音效通道耗尽\n");
			Mix_FreeChunk(music);
			return -1;
		}
		g_chunkChannel[musicHandle] = chan;
		g_usedChannels.insert(chan);
	}

    printf("✓ 音效文件加载成功\n");

    return musicHandle;
}

/**
 * 播放音乐（music单流：新的播放会替换旧的，符合游戏切BGM的行为）
 */
JNIEXPORT jint JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerPlayMusic
  (JNIEnv *env, jobject obj, jlong musicHandle, jint loops) {

    if (musicHandle == 0) {
        fprintf(stderr, "无效的音乐句柄\n");
        return -1;
    }

    Mix_Music* music = (Mix_Music*)(intptr_t)musicHandle;

    // 停止当前播放（Mix_HaltMusic会触发musicFinished置标志，需抑制掉这次"被顶掉"）。
    // 切换期间currentMusicHandle保持0：避免sdlMixerUpdate的看门狗在间隙里误判
    jlong oldHandle = currentMusicHandle;
    if (oldHandle != 0 && oldHandle != musicHandle) {
        Mix_HaltMusic();
        suppressFinished(oldHandle);
    }
    currentMusic = NULL;
    currentMusicHandle = 0;

	if(loops!=-1)
	{
		Mix_HookMusicFinished(musicFinished);
	}
	else
	{
		Mix_HookMusicFinished(NULL);
	}

    // 播放新音乐
    if (Mix_PlayMusic(music, loops) < 0) {
		Mix_HookMusicFinished(NULL);
        CHECK_MIX_ERROR("Mix_PlayMusic");
        return -1;
    }
    currentMusic = music;
    currentMusicHandle = musicHandle;
    g_musicPaused = 0;

    printf("🎵 播放开始 (loops=%d)\n", loops);
    return 0;
}

/**
 * 播放音效（每个句柄在自己的通道上播放，不打断其它音效）
 */
JNIEXPORT jint JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerPlayWav
  (JNIEnv *env, jobject obj, jlong musicHandle, jint loops) {

    if (musicHandle == 0) {
        fprintf(stderr, "无效的音效句柄\n");
        return -1;
    }

    Mix_Chunk* music = (Mix_Chunk*)(intptr_t)musicHandle;

    // 找到该句柄的通道
    auto it = g_chunkChannel.find(musicHandle);
    if (it == g_chunkChannel.end()) {
        fprintf(stderr, "音效句柄没有分配通道\n");
        return -1;
    }
    int chan = it->second;

    // 播放音效（重复触发时从通道头重新播放，其它音效不受影响；
    // Mix_PlayChannel会清除通道的paused标志，修复"暂停后再start无声"）
    if (Mix_PlayChannel(chan, music, loops) < 0) {
        CHECK_MIX_ERROR("Mix_PlayWav");
        return -1;
    }

    applyChunkVolume(musicHandle);

    return 0;
}

/**
 * 暂停音乐（只作用于属于该句柄的流）
 */
JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerPauseMusic
  (JNIEnv *env, jobject obj, jlong musicHandle) {
    if (musicHandle != 0 && musicHandle == currentMusicHandle) {
        Mix_PauseMusic();
        g_musicPaused = 1;
        printf("⏸ 音乐已暂停\n");
    }
}

JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerPauseWav
  (JNIEnv *env, jobject obj, jlong musicHandle) {
    auto it = g_chunkChannel.find(musicHandle);
    if (it != g_chunkChannel.end()) {
        Mix_Pause(it->second);
        printf("⏸ 音效已暂停\n");
    }
}


/**
 * 恢复播放（只作用于属于该句柄的流）
 */
JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerResumeMusic
  (JNIEnv *env, jobject obj, jlong musicHandle) {
    if (musicHandle != 0 && musicHandle == currentMusicHandle) {
        Mix_ResumeMusic();
        g_musicPaused = 0;
        printf("▶ 音乐已恢复\n");
    }
}

JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerResumeWav
  (JNIEnv *env, jobject obj, jlong musicHandle) {
    auto it = g_chunkChannel.find(musicHandle);
    if (it != g_chunkChannel.end()) {
        Mix_Resume(it->second);
        printf("▶ 音效已恢复\n");
    }
}


/**
 * 停止音乐（只作用于属于该句柄的流）
 */
JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerStopMusic
  (JNIEnv *env, jobject obj, jlong musicHandle) {
    if (musicHandle != 0 && musicHandle == currentMusicHandle) {
        Mix_HaltMusic();
        suppressFinished(musicHandle);
        currentMusicHandle = 0;
        currentMusic = NULL;
        g_musicPaused = 0;
        printf("⏹ 音乐已停止\n");
    }
}

JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerStopWav
  (JNIEnv *env, jobject obj, jlong musicHandle) {
    auto it = g_chunkChannel.find(musicHandle);
    if (it != g_chunkChannel.end()) {
        Mix_HaltChannel(it->second);
        printf("⏹ 音效已停止\n");
    }
}

/**
 * 检查是否正在播放
 */
JNIEXPORT jboolean JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerIsPlaying
  (JNIEnv *env, jobject obj, jlong musicHandle) {
    if (musicHandle != 0 && musicHandle == currentMusicHandle) {
        return (Mix_PlayingMusic() == 1) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerIsPlayingWav
  (JNIEnv *env, jobject obj, jlong musicHandle) {
    auto it = g_chunkChannel.find(musicHandle);
    if (it != g_chunkChannel.end()) {
        return Mix_Playing(it->second) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

/**
 * 设置音量
 */
JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerSetVolume
  (JNIEnv *env, jobject obj, jint volume) {
	volume = 128*volume/100;

    if (volume < 0) volume = 0;
    if (volume > 128) volume = 128;

    // 主音量：music立即生效，音效通道按各自期望音量等比缩放
    g_masterVolume = volume;
    Mix_VolumeMusic(volume);
    currentVolume = volume;
    for (auto& pair : g_chunkChannel) {
        applyChunkVolume(pair.first);
    }
    printf("🔊 主音量设置为: %d/128\n", volume);
}

JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerSetVolumeWav
  (JNIEnv *env, jobject obj, jlong musicHandle, jint volume) {
	volume = 128*volume/100;

    if (volume < 0) volume = 0;
    if (volume > 128) volume = 128;

    auto it = g_chunkChannel.find(musicHandle);
    if (it != g_chunkChannel.end()) {
        g_chunkVolume[musicHandle] = volume;
        applyChunkVolume(musicHandle);
        printf("🔊 音效音量设置为: %d/128\n", volume);
    }
}

/**
 * 获取当前音量
 */
JNIEXPORT jint JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerGetVolume
  (JNIEnv *env, jobject obj) {
    return Mix_VolumeMusic(-1);  // -1表示只查询不设置
}

/**
 * 卸载音乐资源
 */
JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerFreeMusic
  (JNIEnv *env, jobject obj, jlong musicHandle) {

    if (musicHandle == 0) {
        return;
    }

    // 如果正在播放则先停止
    if (musicHandle == currentMusicHandle) {
        Mix_HaltMusic();
        suppressFinished(musicHandle);
        currentMusicHandle = 0;
        currentMusic = NULL;
        g_musicPaused = 0;
    }

	auto it = g_contextMap.find(musicHandle);
    if (it != g_contextMap.end()) {//存在
        NativeContext* ctx = it->second;

        // 释放全局引用
        if (ctx->jobj != nullptr) {
            env->DeleteGlobalRef(ctx->jobj);
        }

        delete ctx;
        g_contextMap.erase(it);
    }

    Mix_Music* music = (Mix_Music*)(intptr_t)musicHandle;
    Mix_FreeMusic(music);
    printf("🗑 音乐资源已释放\n");
}

JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerFreeWav
  (JNIEnv *env, jobject obj, jlong musicHandle) {

    if (musicHandle == 0) {
        return;
    }

    // 释放通道
    auto it = g_chunkChannel.find(musicHandle);
    if (it != g_chunkChannel.end()) {
        Mix_HaltChannel(it->second);
        g_usedChannels.erase(it->second);
        g_chunkChannel.erase(it);
        g_chunkVolume.erase(musicHandle);
    }

    Mix_Chunk* music = (Mix_Chunk*)(intptr_t)musicHandle;
    Mix_FreeChunk(music);
    printf("🗑 音效资源已释放\n");
}

/**
 * 关闭SDL2_mixer
 */
JNIEXPORT void JNICALL Java_org_recompile_mobile_SdlMixerManager_sdlMixerQuit
  (JNIEnv *env, jclass cls) {

    // 停止所有播放
    Mix_HaltMusic();
    Mix_HaltChannel(-1);

    // 清空句柄映射
    g_chunkChannel.clear();
    g_usedChannels.clear();
    g_chunkVolume.clear();
    g_finishedHandle = 0;
    g_musicPaused = 0;
    currentMusicHandle = 0;
    currentMusic = NULL;

    // 关闭mixer
    Mix_CloseAudio();

    // 退出mixer扩展
    Mix_Quit();

    if(haptic)
	{
		SDL_HapticClose(haptic);
	}

    // 退出SDL2
    SDL_Quit();

    printf("✓ SDL2_mixer已关闭\n");
}
