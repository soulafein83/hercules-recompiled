# Android port — start-of-port scaffold for Hercules Recompiled

This directory is **greenfield scaffolding**. The `psxrecomp` framework ships **no Android layer at all** —
there is no `android/`, no Gradle project, and no `SDLActivity`. Everything here was created from scratch to
match the plan in `herc.md`. Copying/pinning three things is still required before anything builds:

1. The SDL-managed `SDLActivity` (from a pinned checkout of **libsdl-org/SDL**).
2. The Gradle Wrapper (`gradlew`, `gradle/wrapper/…`).
3. A way to expose the runtime to JNI — see **Integration seam** below.

---

## Layout

```
android/
├── settings.gradle              # :app only; rootProject.name = hercules-recompiled
├── build.gradle                 # AGP 8.x plugin DSL lives in app/build.gradle (kept minimal)
├── gradle.properties            # AndroidX, JVM args, android.ndk.version=27.0.12077973
├── local.properties.example     # copy to local.properties with your sdk.dir / ndk.dir (NEVER commit)
├── .gitignore                   # local.properties, build/, *.apk …
├── gradlew / gradlew.bat        # GENERATE: ./gradlew wrapper --gradle-version 8.7
└── app/
    ├── build.gradle             # arm64-v8a only; Vulkan default; USE_GLES → GLES ES fallback
    └── src/main/
        ├── AndroidManifest.xml      # landscape, configChanges, hasFragileUserData
        ├── java/com/example/hercules/   ← OUR code (this package)
        │   ├── HerculesActivity.java    # extends org.libsdl.app.SDLActivity; System.loadLibrary("hercules")
        │   ├── HerculesGameView.java    # on-screen D-pad + face buttons → JniBridge.setTouchPad()
        │   └── JniBridge.java           # native setTouchPad(int,float,float) + PSX button bitmasks
        ├── java/org/libsdl/app/         ← COPIED from pinned libsdl-org/SDL (see below)
        │   ├── SDLActivity.java (+ SDLSurfaceView, SDLNativeLib, …)  — placeholder marker only
        └── cpp/
            ├── CMakeLists.txt     # add_subdirectory(/port); builds libhercules.so; links system NDK libs
            ├── JniBridge.h         # shared TouchPadState read by main.cpp pad hook
            └── JniBridge.cpp       # JNI impl of Java_com_example_hercules_JniBridge_setTouchPad
├── res/values/{strings,themes,colors}.xml
├── res/drawable/hercules_foreground.xml
└── res/mipmap-anydpi-v26/ic_launcher*.xml   (placeholder adaptive icons)
```

---

## 1. Copy SDLActivity from a pinned libsdl-org/SDL checkout

`psxrecomp` uses a desktop-only, pinned `sdl2-android` via FetchContent — that does **not** build under the NDK.
Instead pin a full checkout of **libsdl-org/SDL** (it ships `android/`) and copy its app sources in:

```bash
# <sdl> is your pinned SDL checkout (SDL3 recommended; PSX_SDL_BACKEND=SDL2 for GLES fallback)
mkdir -p android/src/main/java/org/libsdl/app
cp "$<sdl>/android/src/org/libsdl/app/SDLActivity.java" \
   "$<sdl>/android/src/org/libsdl/app/*.java" android/src/main/java/org/libsdl/app/ 2>/dev/null
```

> The `org/libsdl/app/` dir currently holds only a placeholder marker. Paste the real SDL sources there before building.

The D-pad overlay (`HerculesGameView`) is layered **above** SDL's surface in `HerculesActivity.onCreate`. A full-screen
touchable view would swallow touches meant for the game, so pick one of: (a) a custom `SDLSurfaceView` subclass whose
`onTouch()` dispatches unhandled areas back to SDL and register it via SDL's surface hook; or (b) draw the pad as a floating
`SYSTEM_ALERT_WINDOW`. Both are adaptation points in the copied sources — see `HerculesActivity.onCreate`.

## 2. Generate the Gradle Wrapper

```bash
./gradlew wrapper --gradle-version 8.7 --distribution-type all
# then set ndk.dir / sdk.dir from local.properties.example, or export ANDROID_HOME + ANDROID_NDK_HOME
```

`app/build.gradle` pins `ndkVersion = "27.0.12077973"`; AGP injects the NDK toolchain automatically.

## 3. Integration seam — expose the runtime to JNI (IMPORTANT)

The framework's `psxrecomp_add_game_runtime(psx-runtime …)` emits a self-contained **executable** with its own SDL
`main()`. `System.loadLibrary("hercules")`, however, needs a **shared library**. Two options:

- **(a)** Export an `extern "C"` entry point from `port/` and build a SHARED variant of the runtime; wire it into
  `src/main/cpp/CMakeLists.txt` (the commented `add_library(hercules_runtime SHARED IMPORTED)` block).
- **(b)** Link `libhercules.so` against the generated C objects / static archive that `add_subdirectory(/port …)` produces.

`HerculesGameView → JniBridge.setTouchPad()` writes the shared `TouchPadState` (`JniBridge.h`); psx-recomp's
`main.cpp::capture_local_human_pad()` reads it through `pad_external_input.c::pad_ext_resolve()`. Whether that symbol is
visible depends on which option you take above.

---

## Build variants

| Flag / property | Effect |
|---|---|
| *(default)* | Vulkan primary (`-DPSX_ENABLE_VULKAN=ON`) |
| `-PUSE_GLES` | Vulkan OFF + `-DPSX_SDL_BACKEND=SDL2` for GLES ES 3.0 fallback |
| `-DHERCULES_VERSION=SCES-00893` | Game version; `game.toml` at `port/versions/SCES-00893/game.toml` |

The APK ships **arm64-v8a only** (`ndk.abiFilters = ["arm64-v8a"]`). Vulkan ICD requires API 24+, so
`minSdk = 24`. Disc loading goes through `resolve_disc_path()`; save dir is set via `[runtime] memcard_dir` in the
version's `game.toml`; save-state slots use the `.pst` extension (see `herc.md`).

## Build & install

```bash
./gradlew assembleDebug            # -> app/build/outputs/apk/debug/*.apk  (arm64-v8a)
adb install -r app/build/outputs/apk/debug/*.apk
```
