# Android port — Hercules Recompiled (arm64-v8a)

Gradle/Android project for the Hercules port. The `psxrecomp` framework ships **no Android layer**, so the
Gradle project, the SDL3 app sources, the JNI bridge and the platform shims were created here to match the
plan in [`../herc.md`](../herc.md).

## Current status

| Layer | State |
|---|---|
| Gradle project (AGP 8.7.2, Gradle 8.10 wrapper) | ✅ builds |
| `org/libsdl/app/` (SDL3 app sources) | ✅ SDL 3.4.18 (`SDLActivity`, not a placeholder) |
| `psxrecomp` submodule + framework patches | ✅ `2a4824f` + patches 0001–0013 (`port/patches/psxrecomp/`) |
| Game C (codegen, SCES-00891) | ✅ 536 functions / 284 765 lines + 40 186 static overlay variants |
| Native runtime | ✅ built as **`libmain.so`** (patch 0013) — an APK cannot launch a desktop exe |
| JNI bridge | ✅ `libhercules.so` (`setTouchPad`, `setDataDir`, `hercules_touch_state`) |
| **APK** | ✅ **`app-debug.apk` (49 MB, arm64-v8a)** — `libmain.so` + `libhercules.so` + `assets/runtime/` |
| Runtime data | ✅ `BootstrapActivity` extracts `game.toml`/OpenBIOS/AOT, SAF disc picker, `chdir` before `SDL_main` |
| On-device run | ⚠️ **not yet smoke-tested** (no device/AVD in the build environment) |

Known follow-ups: the on-screen pad overlay is not injected yet, the preloaded mods are not bundled, and the
GLES fallback path may need shader-level fixes on real hardware (Vulkan is the primary renderer).

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

## Building from a clean checkout

1. Initialize the framework and apply its patches (`port/patches/psxrecomp/`):
   ```bash
   git submodule update --init --recursive
   (cd port/psxrecomp && git apply ../patches/psxrecomp/*.patch)
   ```
2. Build the recompiler emitters and generate the game C from your own SCES-00891 dump:
   ```bash
   (cd port && ./psxrecomp/tools/ci/build_emitters.sh)
   (cd port && python3 psxrecomp/psxrecomp_cli.py generate \
        --config versions/SCES-00891/game.toml \
        --project-root versions/SCES-00891 \
        --disc "$PWD/versions/SCES-00891/disc/<your>.cue")
   ```
   `disc/` is gitignored; the generated code lands in `versions/SCES-00891/generated/` (~137 MB).
3. Build the APK:
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```

The APK is self-contained except for the disc: on first launch `BootstrapActivity` stages the runtime data and
opens the Storage Access Framework picker for a `.cue`+`.bin`, `.bin`, `.img`, `.iso` or `.chd` image of
SCES-00891, copies it into app-private storage and records it in `disc.cfg`.

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
