# Android port — Hercules Recompiled (arm64-v8a)

Gradle/Android project for the Hercules port. This is **not** a drop-in build of the game yet — the
`psxrecomp` framework ships **no Android layer** (no `android/`, no Gradle, no `SDLActivity`), so everything
below was created from scratch to match the plan in [`../herc.md`](../herc.md).

## Current status

| Layer | State |
|---|---|
| Gradle project (AGP 8.7.2, Gradle 8.10 wrapper) | ✅ scaffolded + wrapper generated |
| `org/libsdl/app/` (SDL3 app sources) | ✅ **SDL 3.4.18** sources copied in (real `SDLActivity`, not a placeholder) |
| Java layer (`com/example/hercules/` + SDL) | ✅ **compiles** against `compileSdk 34` |
| JNI bridge (`cpp/JniBridge.*`) | ✅ **builds standalone** to `libhercules.so` (arm64-v8a, minSdk 24); exports `hercules_touch_state()` + `Java_com_example_hercules_JniBridge_setTouchPad` |
| `cpp/CMakeLists.txt` | ✅ corrected: `find_package(Threads)`, NDK stub names (`GLESv3`, `OpenSLES`) |
| `port/` framework + game ROM | ⛔ **not present** — see *Remaining work* |

The full APK build is blocked by two external dependencies (see below). Everything in-tree is buildable and
correct; only the framework + game data are missing.

---

## Layout

```
android/
├── settings.gradle              # :app only; rootProject.name = hercules-recompiled
├── build.gradle                 # root (empty); AGP plugin DSL lives in app/build.gradle
├── gradle.properties            # AndroidX, JVM args, android.ndkVersion=30.0.16248370
├── local.properties.example     # copy to local.properties with your sdk.dir / ndk.dir (NEVER commit)
├── .gitignore                   # local.properties, build/, *.apk …
├── gradlew / gradlew.bat        # Gradle 8.10 wrapper (generated)
├── gradle/wrapper/              # gradle-wrapper.jar + .properties (generated)
└── app/
    ├── build.gradle             # arm64-v8a only; Vulkan default; USE_GLES → GLES ES fallback
    └── src/main/
        ├── AndroidManifest.xml      # landscape, configChanges, hasFragileUserData
        ├── java/com/example/hercules/   ← OUR code (this package)
        │   ├── HerculesActivity.java    # extends org.libsdl.app.SDLActivity; System.loadLibrary("hercules")
        │   ├── HerculesGameView.java    # on-screen D-pad + face buttons → JniBridge.setTouchPad()
        │   └── JniBridge.java           # native setTouchPad(int,float,float) + PSX button bitmasks
        ├── java/org/libsdl/app/         ← COPIED from pinned libsdl-org/SDL 3.4.18
        │   ├── SDLActivity.java (+ SDLSurface, SDLControllerManager, HID*, …)
        └── cpp/
            ├── CMakeLists.txt     # add_subdirectory(/port); builds libhercules.so; links system NDK libs
            ├── JniBridge.h         # shared TouchPadState read by main.cpp pad hook
            └── JniBridge.cpp       # JNI impl of Java_com_example_hercules_JniBridge_setTouchPad
├── res/values/{strings,themes,colors}.xml
├── res/drawable/hercules_foreground.xml
└── res/mipmap-anydpi-v26/ic_launcher*.xml   (adaptive icons)
```

## SDL sources (pinned 3.4.18)

`psxrecomp` pins a desktop-only SDL via FetchContent that does **not** build under the NDK, so we copy a full
checkout of **libsdl-org/SDL** (it ships the Android app layer). This tree pins **SDL 3.4.18**
(`release-3.4.18` tag) and copies `android-project/app/src/main/java/org/libsdl/app/*.java` into
`app/src/main/java/org/libsdl/app/`.

> **Why 3.4.18 and not `main`?** The `main` branch targets Android 16 (API 36): its `SDLSurface.java` calls
> `Display.getHighestHdrSdrRatio()` / `isHdrSdrRatioAvailable()` / `registerHdrSdrRatioChangedListener()`
> (API 36) and contains a typo (`setDesiredHdrHeadroom` vs the defined `setDesiredHDRHeadroom`). Those do not
> compile against `compileSdk 34`. The 3.4.18 release is clean and compiles against android-34.

To re-sync with a different SDL version, re-copy `*.java` from the pinned checkout's
`android-project/app/src/main/java/org/libsdl/app/`.

---

## Integration seam — expose the runtime to JNI (IMPORTANT)

The framework's `psxrecomp_add_game_runtime(psx-runtime …)` emits a self-contained **executable** with its own
SDL `main()`. `System.loadLibrary("hercules")`, however, needs a **shared library**, and the game runtime must
be reachable from the JNI bridge. Two options (also documented in `src/main/cpp/CMakeLists.txt`):

- **(a)** Export an `extern "C"` entry point from `port/` and build a SHARED variant of the runtime; wire it
  into `src/main/cpp/CMakeLists.txt` (commented `add_library(hercules_runtime SHARED IMPORTED)` block).
- **(b)** Link `libhercules.so` against the generated C objects / static archive that `add_subdirectory(/port …)`
  produces.

The seam in this tree is the exported `hercules_touch_state()` (verified in the standalone `.so`):
`HerculesGameView → JniBridge.setTouchPad()` writes the shared `TouchPadState` (`JniBridge.h`), and
psx-recomp's `main.cpp::capture_local_human_pad()` reads it through
`pad_external_input.c::pad_ext_resolve()`.

---

## Remaining work (external dependencies)

The in-tree code is complete and buildable. Two things outside this repo are required for a full APK:

1. **`psxrecomp` framework** — initialize the submodule:
   ```bash
   git submodule update --init --deep   # port/psxrecomp ← RetroPortingToolKit/psxrecomp
   ```
2. **Game data** — the recompilation is generated from the PS1 disc (`SCES-00893`). Without the disc image the
   game C is never generated, so `add_subdirectory(/port)` has nothing to build. Provide the disc and generate
   the runtime per `port/README.md` / `herc.md`.

Once both are present, the Gradle native build (`add_subdirectory(/port)`) will produce the runtime, and the
exe→shared-lib seam above exposes it to JNI.

---

## Build variants

| Flag / property | Effect |
|---|---|
| *(default)* | Vulkan primary (`-DPSX_ENABLE_VULKAN=ON`) |
| `-DUSE_GLES` | Vulkan OFF + `-DPSX_SDL_BACKEND=SDL2` for GLES ES 3.0 fallback |
| `-DHERCULES_VERSION=SCES-00893` | Game version; `game.toml` at `port/versions/SCES-00893/game.toml` |

The APK ships **arm64-v8a only** (`ndk.abiFilters = ["arm64-v8a"]`). Vulkan ICD requires API 24+, so
`minSdk = 24`. Disc loading goes through `resolve_disc_path()`; save dir is set via `[runtime] memcard_dir` in
the version's `game.toml`; save-state slots use the `.pst` extension (see `herc.md`).

## Build & install

```bash
./gradlew assembleDebug            # -> app/build/outputs/apk/debug/*.apk  (arm64-v8a)
adb install -r app/build/outputs/apk/debug/*.apk
```

### Verifying the JNI bridge without the framework

`JniBridge.cpp` is self-contained (only `JniBridge.h` + NDK `jni.h`), so the `.so` can be built and inspected
without `port/`:

```bash
NDK=$ANDROID_SDK_ROOT/ndk/30.0.16248370
cmake -S <jni-test-dir> -B build \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_MIN_SDK=24 -DANDROID_PLATFORM=android-24
cmake --build build
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D build/libhercules.so
# expect: T hercules_touch_state, T JNI_OnLoad, T Java_com_example_hercules_JniBridge_setTouchPad*
```
