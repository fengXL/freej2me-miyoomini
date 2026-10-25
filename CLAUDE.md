# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A J2ME (Java ME feature-phone) emulator for retro handhelds (Miyoo Mini, RG35XX, TrimUI Brick, etc.), adapted from FreeJ2ME with M3G (JSR-184) and MascotCapsule v3 3D support. GPL-3.0.

The project is written and documented in Chinese — comments, README, config keys (`"左键"`, `"右键"` in keymap.cfg), and commit messages are Chinese. Follow that convention in commits and user-facing strings.

## Build

Three separately built pieces that must all land in the same runtime directory:

```bash
# 1. Java emulator core (JDK 17) → build/freej2me-sdl.jar, main class org.recompile.freej2me.Anbu
ant

# 2. SDL2 frontend (C++) → sdl_interface executable. One .cpp per device in cpp/sdl2/;
#    compile commands per target are in cpp/sdl2/run.sh, e.g.:
cd cpp/sdl2
arm-linux-gnueabihf-g++ -DDIRECTFB -std=c++11 -O3 ... miyoomini.cpp cJSON.c -o sdl_interface

# 3. JNI native libs → libaudio.so (required), libm3g.so, libmicro3d.so (optional 3D)
cd cpp/native
make -f Makefile.sdlmixer.arm32   # or .arm64 / .x64
make -f Makefile.m3g.arm32        # GLES1 + EGL
make -f Makefile.micro3d.arm32    # GLES2 + EGL
```

**CRITICAL for arm64 device builds**: binaries must reference only `GLIBC_2.17` symbols (check with `objdump -T <bin> | grep GLIBC_`). The handheld firmware's glibc is old — anything compiled against a modern distro toolchain (Ubuntu 24 g++ references GLIBC_2.34+ and `__isoc23_*` from 2.38) fails to load on the device with no visible error. Use the same legacy toolchain the original release used: ARM gcc 9.2 (`gcc-arm-9.2-2019.12-x86_64-aarch64-none-linux-gnu`). When linking in a modern host, also: copy SDL2 headers to a private include dir (host `/usr/include` glibc headers conflict with the toolchain sysroot), link against legacy Debian 11 arm64 `libSDL2-2.0.so.0`/`libSDL2_mixer-2.0.so.0` (modern ones have `.relr.dyn` sections the old ld can't parse), and pass `-Wl,--allow-shlib-undefined` + `-static-libstdc++ -static-libgcc`. Verify the SDL symbol set is a subset of the original release binary's (`nm -D --undefined-only`). A working script: `build/rebuild_legacy.sh` (paths point at a WSL home toolchain).

There is no test suite. Verification is running a game JAR on target hardware (or x64 Linux with `ubuntu.cpp`/`sdl2_interface_general.cpp` as frontend).

### Run

```bash
export JAVA_TOOL_OPTIONS='-Xverify:none -Djava.util.prefs.systemRoot=./.java -Djava.util.prefs.userRoot=./.java/.userPrefs -Djava.library.path=./'
java -jar freej2me-sdl.jar <game.jar> <width> <height> <volume>
```

Everything resolves relative to CWD: `sdl_interface`, `libaudio.so` (via `java.library.path`), `font.ttf`, `keymap.cfg`, per-game config written to `./config/<appnameWxH>/game.conf`. Runs headless (`-Djava.awt.headless=true` on device); java.awt is used only as a software image/Graphics API, never for windows.

## Architecture

Two OS processes plus in-process JNI libs:

### Process split: JVM ⇄ sdl_interface (stdin/stderr pipe protocol)

`Anbu.main` spawns `./sdl_interface <width> <height>` as a child process and talks a binary protocol over pipes:

- **Frames out**: Java converts the LCD `BufferedImage` to raw little-endian RGB565 (`width*height*2` bytes, no header) and writes it to the child's **stdin** on every repaint (the `painter` Runnable that `MobilePlatform.setPainter` invokes; `Anbu.paintFrame` is the locked implementation — menu input can trigger it from the key thread too).
- **Input in**: the child writes **5-byte packets to its stderr**, which Java polls every 5 ms. Byte 0 high nibble = type: `0` = key (bytes 1–4 are a big-endian SDL keycode), `1` = touch (bytes 1–2 = x, 3–4 = y, 16-bit BE). Byte 0 low bit: even = released, odd = pressed. `SDLK_F1` (0x4000003A) toggles the Java-side system menu (`SystemMenu`), rendered as an overlay on the LCD frame (the MENU button on the device is remapped in the C++ frontend to send F1).

Gotcha: the frontends write input packets to **stderr**, not stdout, and current `Anbu` reads `proc.getErrorStream()`. Older comments mention stdout; don't trust them. When adding a frontend or touching either end of this protocol, keep both sides in sync — the packet size (5 bytes) is hardcoded on both sides (`din = new byte[6]` in `SDLKeyTimerTask`, `fwrite(..., 5, stderr)` in C++).

### C++ device frontends (`cpp/sdl2/`)

One standalone `.cpp` per device (`miyoomini`, `rg28xx`, `rg35xxPlus`, `rg35xxH`, `gkdminiplus`, `trimuiBrick`, `ubuntu`, plus generic `sdl2_interface_general[_with_menu]`). They are heavily duplicated by design (each ~800–2100 lines); a fix usually needs to be replicated across the relevant device files. All use `cJSON.c` to parse `keymap.cfg` (game phone-key → device button, Chinese key names) and implement: aspect-fit scaling, 90° rotation (SELECT+B), virtual touchscreen cursor mode, hotkey combos, and screen-off/CPU tweaks specific to the device.

### Java emulator core (`src/`)

- `org.recompile.mobile` — the heart:
  - `Mobile` — static accessors + all phone keycode constants (standard MIDP, Nokia, Siemens, Motorola variants).
  - `MobilePlatform` — LCD buffer, key/pointer event dispatch, the `painter` runnable that triggers frame sends, text-input emulation for `TextField` (left/right cycles letters, 1/3 cycles digits).
  - `MIDletLoader extends URLClassLoader` — loads the game JAR. **Game classes are bytecode-instrumented at load time** by bundled ObjectWeb ASM (`org.objectweb.asm`, via `MyProducer`/`MyClassVisitor`/`MyMethodVisitor`): redirects like `Thread.sleep` → `MidletSystem`, `System.getProperty`/resource/stream calls → `MidletRuntime` shims, and exception `printStackTrace`. Framework packages (`java.*`, `javax.*`, `com.nokia.*`, `com.mascotcapsule*`, `com.siemens*`, `com.samsung*`, `org.recompile*`) are loaded without instrumentation.
  - `PlatformImage`/`PlatformGraphics` — lcdui rendering backed by `java.awt.image.BufferedImage`.
  - `PlatformPlayer` — javax.microedition.media player (audio/video).
  - `SdlMixerManager` — JNI bridge to `libaudio.so` (SDL2_mixer).
- `javax.microedition.*` — the CLDC/MIDP API surface games compile against (lcdui, midlet, rms, media, m3g, io, ...). `javax.microedition.shell` contains the `MidletSystem`/`MidletRuntime` redirection targets.
- Vendor API stubs games may reference: `com.nokia.mid.*`, `com.siemens.mp.*`, `com.samsung.util.*`, `javax.wireless.messaging`, `com.mascotcapsule.micro3d.v3`.
- `org.microemu.*` — GCF (`Connector`) implementation stack for `javax.microedition.io`.
- `org.lwjgl.*` — trimmed LWJGL EGL/GLES bindings used by the M3G Java renderer; prebuilt `liblwjl.so`/`liblwjgl_opengles.so` for arm32/arm64/x64Linux sit in `jlib/`.

### Audio stack

`PlatformPlayer` (JSR-234 Player impl) reads the whole stream, sniffs format by magic bytes (`AudioUtil.sniffFormat`: MThd/RIFF-WAVE/ID3·0xFFEx/OggS/#!AMR), caches under `./rms/<suite>/<full-data-md5>.<ext>` and routes: WAV → chunk channel, MIDI/MP3/OGG → the single music stream. In JNI (`sdl_mixer_jni.cpp`) **every chunk handle owns a dedicated Mix_Channel** (32 channels) so sound effects overlap instead of cutting each other; music APIs are per-handle so one player's stop/pause cannot act on another's stream. AMR has no decoder in SDL2_mixer (silently unsupported). `com.nokia.mid.sound.Sound` is implemented (WAV bytes + synthesized beeps via `AudioUtil.synthesizeWav`); `Manager.playTone` synthesizes a WAV and plays it as a chunk.

### System menu

`SystemMenu` (Java, `org.recompile.freej2me`) draws a translucent Chinese overlay using `Anbu.getFont()` (font.ttf) onto a copy of the LCD frame inside `paintFrame()`. While visible, `Anbu`'s key task routes raw SDL keycodes to it (never to the game). Items: exit, key mode (p/n/e/s/m), D-pad mapping (`dpad` config: `nav` = phone navigation keys -1..-4/OK=-5, `num` = 2468; default `nav`), master volume (applied via `SdlMixerManager.setMasterVolume`), FPS cap. MENU key on device → C++ sends `SDLK_F1` → menu toggle.

### D-pad diagonals

The handheld D-pad reports as an SDL joystick hat; diagonal = bitmask (e.g. `SDL_HAT_UP|SDL_HAT_RIGHT`). The frontend's hat handler (`handleHatMotion` in each device .cpp) must track each direction independently and emit per-direction press/release — a naive `value == SDL_HAT_UP` comparison kills diagonals (the original bug in trimuiBrick.cpp).

### Native 3D libs (`cpp/native/`)

`m3g/` (M3G/JSR-184, GLES1, loaded lazily from `javax.microedition.m3g.Platform` via `System.loadLibrary("m3g")`) and `micro3d/` (MascotCapsule v3, GLES2, loaded from `com.mascotcapsule.micro3d.v3.Utils`). They render into an EGL pbuffer/shared context, not the SDL window — SDL only blits the 2D frame stream. `cpp/native/include/` holds the JNI headers for cross-compilation.

### Key modes

`game.conf` `phone` setting selects one of 5 keycode translations applied in `Anbu.getMobileKey`: `p` = plain MIDP keypad, `n` = Nokia, `e` = extended Nokia (letters m/r/y/v/n/u/j stand in for 0/1/3/7/9/*/ #), `s` = Siemens, `m` = Motorola. SELECT+START cycles modes; the active mode letter is overlaid on-screen for ~60 frames (the `keyPix` bitmap in `Anbu`). `KEYMAP.md`/`KEYMAP_ENG.md` document SDL keycode → phone key mappings.

## Related directory

`D:\MyProject\trimuiBrick-freej2me` (added as an additional working directory) is the **TrimUI Brick SD-card deployment package**, not a source repo: `Emus/JAVA/` holds `launch.sh` (runs zulu17 JRE, picks 240×320 / 320×240 / 176×208 / 640×360 resolution based on the ROM folder name like `240320`), timidity soundfonts, and the packaged emulator. Built artifacts from this repo get deployed there.
