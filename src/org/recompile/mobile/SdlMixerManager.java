package org.recompile.mobile;
import org.recompile.mobile.PlatformPlayer;

public class SdlMixerManager{
    // ========== Native方法声明 ==========

    /**
     * 初始化SDL2和SDL2_mixer
     * @param frequency 采样率 (如44100)
     * @param format 音频格式 (如SDL_AUDIO_S16SYS)
     * @param channels 声道数 (2=立体声)
     * @param chunksize 音频块大小 (如2048)
     * @return 0=成功, -1=失败
     */
    public static native int sdlMixerInit(int frequency, int format, int channels, int chunksize);

    public static native int sdlHapticInit();
	public static native void sdlHaptic(int duration);

    /**
     * 加载音频文件到music通道（MIDI/MP3/OGG等流式音频，全局单流）
     * @param filePath 文件路径
     * @return 句柄 (>=0), -1=失败
     */
    public native long sdlMixerLoadMidi(String filePath, PlatformPlayer.sdlPlayer player);

	public native long sdlMixerLoadWav(String filePath);

    /**
     * 播放音乐（music通道全局单流，新播放替换旧播放）
     * @param musicHandle 音乐句柄
     * @param loops 循环次数 (-1=无限循环)
     * @return 0=成功, -1=失败
     */
    public native int sdlMixerPlayMusic(long musicHandle, int loops);

	/**
	 * 播放音效（每个句柄独占一个通道，多音效可重叠，互不打断）
	 */
	public native int sdlMixerPlayWav(long musicHandle, int loops);

    /**
     * 暂停（只作用于属于该句柄的流/通道）
     */
    public native void sdlMixerPauseMusic(long musicHandle);

	public native void sdlMixerPauseWav(long musicHandle);

    /**
     * 恢复播放
     */
    public native void sdlMixerResumeMusic(long musicHandle);

	public native void sdlMixerResumeWav(long musicHandle);

    /**
     * 停止
     */
    public native void sdlMixerStopMusic(long musicHandle);

	public native void sdlMixerStopWav(long musicHandle);

    /**
     * 检查是否正在播放
     */
    public native boolean sdlMixerIsPlaying(long musicHandle);
	public native boolean sdlMixerIsPlayingWav(long musicHandle);

    /**
     * 设置音量 (0-100)
     */
    public native void sdlMixerSetVolume(int volume);
	public native void sdlMixerSetVolumeWav(long musicHandle, int volume);

    /**
     * 获取当前音量
     * @return 音量值
     */
    public native int sdlMixerGetVolume();

    /**
     * 卸载资源
     */
    public native void sdlMixerFreeMusic(long musicHandle);

	public native void sdlMixerFreeWav(long musicHandle);

    /**
     * 关闭SDL2_mixer
     */
    public static native void sdlMixerQuit();

    /**
     * 轮询music自然播完事件。native的musicFinished回调运行在SDL音频线程，
     * 不能直接回调Java（游戏监听器里再调start会死锁整个音频线程），
     * 只置标志位，由本方法在普通Java线程里转发END_OF_MEDIA。
     */
    public native void sdlMixerUpdate();

    // ========== Java封装方法 ==========

    private static volatile boolean initialized = false;

    // 全局音量（0-100），在init后以及菜单调节时应用
    private static volatile int masterVolume = 100;

    // music播完事件轮询线程
    private static Thread pollThread = null;

    /**
     * 全局初始化音频系统
     */
    public static synchronized boolean init() {
        if (initialized) {
            return true;
        }

        // SDL_AUDIO_S16SYS = 32784
        int result = sdlMixerInit(44100, 32784, 2, 4096);
        if (result == 0) {
            initialized = true;
            System.out.println("✓ [java]SDL2_mixer初始化成功");
            applyMasterVolume();
            startPollThread();
            return true;
        } else {
            System.err.println("❌ [java]SDL2_mixer初始化失败");
            return false;
        }
    }

    /**
     * 轮询native侧music自然播完标志并转发END_OF_MEDIA（普通线程，安全调播放API）
     */
    private static void startPollThread() {
        final SdlMixerManager m = new SdlMixerManager();
        pollThread = new Thread(new Runnable() {
            public void run() {
                while (initialized) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                    try { m.sdlMixerUpdate(); } catch (Throwable t) { }
                }
            }
        }, "SdlMixerEomPoll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    /**
     * 设置全局音量（音乐通道立即生效；音效通道在下次播放时应用）
     */
    public static void setMasterVolume(int volume) {
        if (volume < 0) volume = 0;
        if (volume > 100) volume = 100;
        masterVolume = volume;
        if (initialized) {
            applyMasterVolume();
        }
    }

    public static int getMasterVolume() {
        return masterVolume;
    }

    private static void applyMasterVolume() {
        try {
            SdlMixerManager m = new SdlMixerManager();
            m.sdlMixerSetVolume(masterVolume);
        } catch (Throwable e) { }
    }

    //初始化震动子系统
	public static boolean initHaptic() {

        int result = sdlHapticInit();
        if (result == 0) {
            System.out.println("✓ [java]SDL2_Haptic初始化成功");
            return true;
        } else {
            System.err.println("❌ [java]SDL2_Haptic初始化失败");
            return false;
        }
    }

	public static void vibrate(int duration) {

        sdlHaptic(duration);
    }

    /**
     * 全局清理退出
     */
    public static void shutdown() {
		if(initialized)
		{
			initialized = false;
			// 先停轮询线程，避免sdlMixerQuit清理映射表时它还在回调
			if (pollThread != null) {
				pollThread.interrupt();
				try { pollThread.join(500); } catch (InterruptedException e) { }
				pollThread = null;
			}
			sdlMixerQuit();
		}
        System.out.println("✓ [java]SDL2_mixer已关闭");
    }
}
