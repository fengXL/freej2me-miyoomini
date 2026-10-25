# 音频问题排查上下文文档

> 用途：跨会话延续排查工作。新对话读到本文即可恢复全部上下文。
> 最后更新：2026-10-25（第三轮修复后，等待真机复测）
> 相关分支：`trimui-fix`（已推送到 fork `fengXL/freej2me-miyoomini`，upstream 为 `aweigit/freej2me-miyoomini`）

## 一、问题与症状演进

测试游戏：**蝙蝠侠-黑暗骑士崛起BT版.jar**（Gameloft，位于 trimuiBrick-freej2me/roms/）
目标设备：TrimUI Brick（SD 卡部署包 = `D:\MyProject\trimuiBrick-freej2me\Emus\JAVA\`）

| 轮次 | 症状 | 结论 |
|------|------|------|
| 第1轮 | 部分游戏 BGM/音效发声不全，时有时无 | 修了 4 个静态 bug（见二），明显好转 |
| 第2轮 | 开局有声，**游玩过程中逐渐消失** | 修了 3 个渐进性机制（见三），仍会消失 |
| 第3轮 | 仍会消失（真机 audio.log 证据） | 修复 JNI 并发竞态 + 加埋点，**待真机复测**（见五、六） |

## 二、第一轮修复的 4 个静态 bug（提交 013585b）

1. **音效通道泄漏**：`PlatformPlayer.close()` 只调 stop 不释放 chunk/通道 → 游戏换声音库整库 close，32 通道漏光后全部音效无声。修复：close() 补 `deallocate()`。
2. **通道上限 32 < 库条目数**：该游戏声音库一次预建 38~65 个 Player（实测 65），第 33 个起无声。修复：JNI `MAX_SFX_CHANNELS` 32→96。
3. **暂停后永久无声**：`stop()`=Mix_Pause，而暂停中的通道 `Mix_Playing()` 返回 1，`start()` 的 `if(!isOpen||!IsPlaying)` 守卫跳过播放也不 Resume。修复：音效 start() 总是从头 `Mix_PlayChannel`（自动清 paused 标志）。
4. **BGM 单流互顶丢失**：SDL_mixer 只有一条 music 流，新音乐顶掉 BGM 后游戏不重启。修复：自然播完时代为重启"游戏没显式停过"的 BGM（resumeStackedMusic）。

连带修复：`Manager.playTone` 通道泄漏、主音量只作用于 music（现按句柄期望音量×主音量缩放至音效通道）、EOM 回调从 SDL 音频线程改为"native 置标志 + Java 守护线程轮询转发"（原实现游戏监听器里调 start() 会死锁整个音频线程）。

## 三、第二轮修复的 3 个渐进机制（提交 19f26c7）

1. **句柄按内容共享+引用计数**（`SdlMixerManager.handleCache`，MD5 缓存路径为 key）：游戏引擎反复为同一段音频创建 Player，原实现每次新分配 chunk+通道；共享后通道占用只与"不同声音数"相关，结构上杜绝漏光。⚠️ 测试时 bgm 与事件音乐必须用**不同内容**（否则共享句柄，"顶掉"断言失效）。
2. **stop()/close() 路径也触发 BGM 栈恢复**：第一轮只在自然播完时恢复；若事件音乐被游戏 close/暂停（更常见），流静音且游戏以为 BGM 还在播。
3. **music 死流看门狗**：`loops=-1` 不注册结束回调，流被后端悄悄终止时无人通知。轮询线程检测"句柄在而 `Mix_PlayingMusic()==0` 且非自有暂停"→ 补发 EOM → Java 代游戏重启循环 BGM。⚠️ `Mix_PlayingMusic()` 对**暂停中的音乐也返回 0**（内部是 music_active），必须用自有标志 `g_musicPaused` 区分，不能用 `Mix_PausedMusic()`。

另加 **audio.log** 落盘日志（运行目录，256KB 自动清空）——设备 stdout 丢失，这是唯一证据渠道。

## 四、第三轮：真机日志证据与并发竞态修复（提交 77d79b6）

真机 audio.log（第2轮构建）显示：
```
t=0    标题曲播放 loops=1（21秒）
t+21s  菜单短曲 ×3（各约5秒，全部正常自然播完 ✓ EOM链路正常）
t+47s  之后游戏再无任何音乐请求（不是播放失败，是引擎不发命令了）
```
→ EOM/看门狗/自动恢复在设备上均正常；**游戏自己的音频引擎停摆**（BGM 与音效命令同源，故同时消失）。

**本轮修复——JNI 并发竞态**：轮询线程（50ms 读 `g_contextMap`）与游戏线程（关卡加载时大量插表/释放）无锁并发访问 `unordered_map` 等共享结构 = 未定义行为；死亡时间点（曲目播完查表）恰与关卡加载风暴重合。所有 JNI 入口已用 `std::recursive_mutex g_lock` 串行化（递归：轮询线程持锁回调 Java→自动恢复→start 会重入 PlayMusic）；`musicFinished` 钩子在音频线程**保持无锁** volatile 写（取锁会与 Mix_LockAudio 互死）。

**本轮新增埋点**（audio.log）：系统菜单打开/关闭、`Display.setCurrent` 屏切换。

## 五、头号未验证嫌疑（复测若仍消失，优先查这个）

反编译确认（`n.class`）：
```
hideNotify() → o() → n.ae = true  = 全局禁音（d.run 拦截所有播放命令）
showNotify() → p() → n.ae = false = 恢复
```
若设备上发生过一次未配对的 hideNotify（或等效禁音），BGM+音效同时永久消失，游戏画面正常——与症状完全吻合。模拟器侧 `Display.setCurrent` 只调新屏 showNotify、从不调 hideNotify，理论上不该触发……但**真实触发路径未找到**。复测日志里若"音频消失前"出现异常的"系统菜单 打开"/"lcd切换"事件即为此因；若日志干净则嫌疑回到游戏引擎内部（其 K[] 命令队列无锁共享，游戏自身竞态在真机上可能偶发炸线程——只能从日志时间点推断）。

其他已排除项：timidity 后端正常上报 MIDI 播完（源码+真机日志确认）；设备无 fluidsynth（dlopen 不到库），MIDI 走内置 libtimidity（TIMIDITY_CFG 优先）。

## 六、真机复测步骤（下一步行动）

1. SD 卡插回 Brick 正常游玩，**玩到音频消失**，记下大概时刻（如"进第2关时"）
2. 卡插回电脑，取 `F:\Emus\JAVA\zulu17\bin\audio.log` 分析（时间戳为毫秒 epoch；设备时钟可能不准，看相对间隔）
3. 判读要点：
   - 出现 `系统菜单 打开` 后无 `关闭` → 菜单吃键/禁音路径
   - 出现 `lcd切换` 后即死 → 游戏屏切换触发了禁音
   - `music播放失败`/`sfx加载失败` → 加载路径（通道/内存）
   - `看门狗` 条目 → 后端杀流，自恢复是否生效
   - 日志完全干净地停止（像上次那样）→ 游戏引擎内部停摆，需查竞态修复是否已解决；若未解决考虑用 ASM 插桩（MIDletLoader 已内嵌 ObjectWeb ASM）在 d.run/n.o/p 上打点
4. 若**不再消失** → 互斥锁已根治，关闭此问题，把埋点保留一版后可精简

## 七、当前构建与部署状态（截至本文更新）

| 产物 | MD5 | 部署位置 |
|------|-----|---------|
| freej2me-sdl.jar | `a0711933...` | build/ + D盘包 + SD卡 `F:\Emus\JAVA\zulu17\bin\` |
| libaudio.so (arm64) | `5a42cfcc...` | cpp/native/ + D盘包 + SD卡 `F:\Emus\JAVA\zulu17\lib\` |

- arm64 仅依赖 GLIBC_2.17（老固件兼容，rebuild_legacy.sh 会校验）
- SD 卡回滚备份（**修复前原版**）：`F:\Emus\JAVA\backup_20261025\`（jar `da0bb9f2`、lib `a3fa2af1`），D 盘镜像 `D:\MyProject\trimuiBrick-freej2me\Emus\JAVA\backup_sdcard_20261025\`
- `sdl_interface`（卡上为用户自建版本，与仓库源码构建不一致）**未动**，音频工作不涉及它

## 八、构建/测试/部署工作流（速查）

```bash
# Java（Windows 无 ant，手工等价 build.xml：--release 17, Main-Class=org.recompile.freej2me.Anbu, 根目录META-INF/）
cd D:\MyProject\freej2me-miyoomini
find src -name "*.java" > /tmp/srcs.txt
javac --release 17 -Xlint:none -encoding UTF-8 -cp "lib/jsr305-3.0.2.jar" -d build/classes @/tmp/srcs.txt
printf 'Main-Class: org.recompile.freej2me.Anbu\nImplementation-Title: FreeJ2ME\n' > /tmp/mf.txt
jar cfm build/freej2me-sdl.jar /tmp/mf.txt -C build/classes .
jar uf build/freej2me-sdl.jar -C . META-INF   # 仓库根有 META-INF/freej2me-build.version

# arm64 libaudio.so（WSL 老工具链 gcc9.2，输出到 cpp/native/libaudio.so）
wsl.exe -e bash -c "bash /mnt/d/MyProject/freej2me-miyoomini/build/rebuild_legacy.sh"

# x64 冒烟测试（22项断言，WSL，SDL dummy 驱动；测试源 build/test/AudioSmokeTest.java）
wsl.exe -e bash -c "bash /mnt/d/MyProject/freej2me-miyoomini/build/x64test_run.sh"

# 部署（D盘包 + SD卡 F:）
cp build/freej2me-sdl.jar  D:/MyProject/trimuiBrick-freej2me/Emus/JAVA/zulu17/bin/
cp cpp/native/libaudio.so  D:/MyProject/trimuiBrick-freej2me/Emus/JAVA/zulu17/lib/
cp build/freej2me-sdl.jar  F:/Emus/JAVA/zulu17/bin/
cp cpp/native/libaudio.so  F:/Emus/JAVA/zulu17/lib/

# 推送（直连 github 被断，走本机 7897 代理 + openssl 后端绕开 schannel 问题）
git -c http.proxy=http://127.0.0.1:7897 -c http.sslBackend=openssl push origin trimui-fix
```

x64 无头复现设施（**已知局限：dummy 视频下游戏不推进菜单，只能复现"库加载后无音乐"**）：
`build/repro2_run.sh` + `build/make_inject_frontend.py`（补丁 ubuntu.cpp：按键包改写 fd2、`dup(2)` 后再 `freopen` 净化 stderr——glibc 的 freopen(stderr) 会劫持 fd2 本身、注入按键线程、屏蔽 blit 失败退出信号）。

## 九、关键文件索引

| 文件 | 角色 |
|------|------|
| `src/org/recompile/mobile/PlatformPlayer.java` | Player 实现；sdlPlayer(music单流)/sdlWavPlayer(chunk)；句柄缓存 acquire/release；resumeStackedMusic；audioLog 埋点 |
| `src/org/recompile/mobile/SdlMixerManager.java` | JNI 桥；handleCache；EOM 轮询线程；audioLog |
| `src/javax/microedition/media/Manager.java` | createPlayer/playTone |
| `cpp/native/sdlmixer/sdl_mixer_jni.cpp` | 全部 native：96通道、EOM标志位、看门狗、g_musicPaused、递归互斥锁 |
| `build/test/AudioSmokeTest.java` | x64 回归测试（不入库，build/ 已 gitignore） |
| 游戏反编译产物 | `build/batman_extract/d.txt`（音频引擎 d 类）、`n.txt`（含 ae 禁音门）——build/ 下可能被清理，必要时 `javap -c -p d.class` 重新生成 |

## 十、SDL_mixer 关键语义备忘（排查时易踩）

- 部署包 libSDL2_mixer ≈2.8（"zw"构建）；WSL Debian 为 2.8.1
- `Mix_Playing(通道)`：**暂停中的通道返回 1**；`Mix_PlayChannel` 清 paused 并从头播
- `Mix_PlayingMusic()`：**暂停返回 0**（=music_active）；判断暂停用自有标志
- `Mix_HaltMusic` 会触发 musicFinished 钩子（需抑制误发 EOM）
- fluidsynth 后端对 MIDI 不上报自然播完（设备不受影响：无 libfluidsynth 可 dlopen，走 timidity；TIMIDITY_CFG 环境变量优先）
