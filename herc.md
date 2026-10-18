# Hercules Recompiled — plan portu na Androida (ARM64 / arm64-v8a)

**Cel:** przeniesienie gry *Disney's Hercules* (PS1, 1997; wydania PAL SCES‑00891 … ‑00895,
tutaj niemieckie **SCES‑00893**) na Androida w architektrze **arm64‑v8a**.

Dokument to **kompletny plan architektoniczny i instrukcja wykonawcza** — nie jest gotowym
kodem. Traktuj go jako roadmapę: każdy moduł zawiera uzasadnienie, konkretne ścieżki w repozytorium
i przykładowe pliki (CMake, Gradle, manifest), które trzeba zaadaptować.

---

## 0. Co to jest ten projekt (streszczenie analizy)

`hercules-recompiled` to **recompilacja** gry PS1: oryginalny kod MIPS R3000A zostaje przekompilowany
w czasie budowania na natywne C/C++ i uruchamia się **bez zmian**, zastąpiono jedynie warstwę
platformową (rendering 1080p, 16:9, HD sprite'y, launcher).

| Element | Realizacja w tym repozytorium |
|---|---|
| Framework | `port/psxrecomp` — submodule **RetroPortingToolKit/psxrecomp** (fork psxrecomp mstana), statyczny recompiler MIPS→C + runtime SDL3/SDL2 + renderer OpenGL 3.3 core / Vulkan |
| Typ budowy | AOT: emiter `psxrecomp-game` generuje pliki C (`generated/*.c`) → kompilacja na x64; **na Androida trzeba przeprofilować pod arm64** |
| Okno/renderer | `runtime/src/main.cpp` → `SDL_CreateWindow`, renderer OpenGL: `gpu_gl_renderer.c` (**desktop GL 3.3 core**, nie ES); Vulkan: `gpu_vk_renderer.c` (łowany dynamicznie) |
| SDL | domyślnie **SDL3**, przełącznik `-DPSX_SDL_BACKEND=SDL2` |
| Konfiguracja gry | `port/game.toml`, `game_options.toml`; ścieżki zapisu: `[runtime] memcard_dir = "saves"` |
| Wskazywanie dysku | `disc_path.h::resolve_disc_path()`, cache `disc.cfg`/`disc-<id>.cfg`, loader ISO9660 w `cdrom.c` |
| Save states | `savestate.c` (sloty + miniatury HD), ścieżki przez `savestate_slot_path()` |
| Launcher | wbudowany w exe (`launcher_native/hl_launcher.cpp`); menu, opcje, aktualizacje |
| Narzędzia hosta | AOT recompiler C++20; overlay toolchain (i386‑win32 TCC) do kompilacji overlay z dysku w locie |

### Kluczowe fakty i wyzwania dla Androida

1. **Brak jakiejkolwiek warstwy Android/NGL/EGL/JNI** — framework jest czystą aplikacją desktopową
   (SDL3/SDL2 + OpenGL 3.3 core / Vulkan na X11/Wayland/DirectX/Metal). Port = dodanie powierzchni
   EGL‑GLES lub podpięcie oficjalnej `SDLActivity` z repo libsdl‑org/SDL.
2. **Renderer to desktop OpenGL 3.3 core**, nie ES — kod w `gpu_gl_renderer.c` trzeba przetłumaczyć na
   GLES 2/3, albo (zalecane) skorzystać z już dynamicznie ładanego backendu Vulkan (`gpu_vk_renderer.c`).
3. Budowa: **CMake ≥ 3.20 + Ninja**, runtime C11/C++17, recompiler C++20. SDL domyślnie 3 (Android wymaga
   pełnego checkout libsdl‑org/SDL z własnym `android/` — nie FetchContent desktopowego SDL).
4. AOT recompiler i pipeline overlay działają **po stronie hosta** (generują C w budowie); bundled TCC jest
   i386‑win32 → na Androida potrzebny ARM64 kompilator overlay (`overlay_toolchain/`).

---

## Moduł 1: Analiza Zależności i Architektury Projektu

### 1.1 Co to jest projekt (streszczenie)

`hercules-recompiled` to **recompilacja** gry *Disney's Hercules* (PS1, 1997; wydania PAL SCES‑00891 … ‑00895,
tutaj niemieckie **SCES‑00893**) — nie emulator. Oryginalny kod MIPS R3000A zostaje przekompilowany w czasie
budowania na natywne C/C++ i uruchamia się **bez zmian** (byte‑identyczna logika), zastąpiono jedynie warstwę
platformową: rendering 1080p, 16:9, HD sprite'y, launcher.

| Element | Realizacja w tym repozytorium |
|---|---|
| Framework | `port/psxrecomp` — submodule **RetroPortingToolKit/psxrecomp** (fork psxrecomp mstana), statyczny recompiler MIPS→C + runtime SDL3/SDL2 + renderer OpenGL 3.3 core / Vulkan |
| Typ budowy | AOT: emiter `psxrecomp-game` generuje pliki C (`generated/*.c`) → kompilacja na x64; **na Androida przeprofilowujemy pod arm64** |
| Okno/renderer | `runtime/src/main.cpp` → `SDL_CreateWindow`, renderer OpenGL: `gpu_gl_renderer.c` (**desktop GL 3.3 core**, nie ES); Vulkan: `gpu_vk_renderer.c` (łowany dynamicznie) |
| SDL | domyślnie **SDL3**, przełącznik `-DPSX_SDL_BACKEND=SDL2` |
| Konfiguracja gry | `port/game.toml`, `game_options.toml`; ścieżki zapisu: `[runtime] memcard_dir = "saves"` |
| Wskazywanie dysku | `disc_path.h::resolve_disc_path()`, cache `disc.cfg`/`disc-<id>.cfg`, loader ISO9660 w `cdrom.c` |
| Save states | `savestate.c` (sloty + miniatury HD), ścieżki przez `savestate_slot_path()` |
| Launcher | wbudowany w exe (`launcher_native/hl_launcher.cpp`); menu, opcje, aktualizacje |
| Narzędzia hosta | AOT recompiler C++20; overlay toolchain (i386‑win32 TCC) do kompilacji overlay z dysku w locie |

### 1.2 Punkt wejścia C/C++ oraz warstwa okna/renderera — dowody z kodu

Analiza oparta na rzeczywistym drzewie `port/psxrecomp` (commit `2a4824fc`). Wejściem jest `main()` w
`runtime/src/main.cpp`; okno tworzono bezpośrednio przez SDL.

| Co | Ścieżka | Linia | Dowód z kodu |
|---|---|---|---|
| Preferencja renderera (globalna) | `main.cpp::g_video_renderer` | L1304 | `static int g_video_renderer = PSXRecompV4::DEFAULT_VIDEO_RENDERER;` (enum: 0=software, 1=opengl, 2=vulkan) |
| Składnia flag okna | `main.cpp` ~L19235‑19248 | L19235-19248 | `win_flags = SDL_WINDOW_SHOWN \| SDL_WINDOW_RESIZABLE`; renderer 1 → `\| SDL_WINDOW_OPENGL` (+ optional high‑density), renderer 2 → `\| SDL_WINDOW_VULKAN` |
| Tworzenie okna | `main.cpp::SDL_CreateWindow` | L19265 | `sdl_window = SDL_CreateWindow(window_title.c_str(), …, win_flags)`; fail → `SDL_CreateWindow failed: %s` (L19272) |
| Kontekst GL (desktop core) | `main.cpp::configure_core_gl_context_attributes()` | L2246 | `SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_CORE_PROFILE)`; major/minor L2272‑2273, DOUBLEBUFFER/L_STENCIL_SIZE L2274‑2275 → **Core 3.3**, nie ES |
| Inicjalizacja subsystemów | `main.cpp` ~L19175 / L17391 | — | `SDL_Init(SDL_INIT_VIDEO \| SDL_INIT_GAMECONTROLLER)`; audio osobno (subsystem audio) |

**Renderer OpenGL (desktop).** `runtime/src/gpu_gl_renderer.c` (~668 KB): GL 3.x core + FBO + shadery z
dynamicznym łoadeniem entrypointów (`load_modern_gl()` — wskaźniki `PFN_*`, np. `glCreateProgram`). Rasteryzacja
rysuje VRAM PSX jako teksturalne kwady; prezentacja przez FBO + shader present/interpolation
(`build_program(PRESENT_VS, PRESENT_FS)`). **To jest OpenGL desktopowy — nie ES.**

**Renderer Vulkan.** `runtime/src/gpu_vk_renderer.c`: cały interfejs łowany dynamicznie przez
`SDL_Vulkan_LoadLibrary(NULL)`; brak linkowania Vulkan SDK w czasie budowy. Symboliki łowane w locie. To
najłatwiejsza ścieżka portowa na Androida (jeśli device ma Vulkan ICD), bo omija translację GL‑core→GLES.

**Fuzja SDL2/SDL3.** Jedno miejsce mostkujące API: `runtime/include/psx_sdl.h` (`#if defined(PSX_SDL3)` z makrami
aliasującymi; L237‑274). Tutaj trzeba dodać EGL/GLES + audio pod Androida.

**Punkt wstrynięcia inputu (dotyk/BT).** `runtime/src/pad_external_input.h` → `PadExtHooks.capture_local()`
(`main.cpp::capture_local_human_pad`, ~L6365) — jedyna oficjalna furtka do symulowania padu; blokowana przez
`PadExtGate` (netplay/rewind/save).

### 1.3 Wersja SDL — confirm z `runtime.cmake`

Framework **domyślnie buduje z SDL3**; backend wybiera się flagą `-DPSX_SDL_BACKEND=SDL2`. Potwierdzenie w
`port/psxrecomp/runtime/runtime.cmake`:

```cmake
# L124-130: wybór backendu (tylko SDL3 | SDL2)
set(PSX_SDL_BACKEND "SDL3" CACHE STRING "SDL backend (SDL3 or SDL2)")
set_property(CACHE PSX_SDL_BACKEND PROPERTY STRINGS SDL3 SDL2)
string(TOUPPER "${PSX_SDL_BACKEND}" _psx_sdl_backend)

# L245: wykrywanie systemowe — wymaga SDL 3.4 w CONFIG
find_package(SDL3 3.4 CONFIG QUIET COMPONENTS SDL3 …)   # fail, jeśli brakuje <SDL3/SDL.h>
# L301: fallback FetchContent (pinned SDL3), inaczej błąd konfiguracji (#308-309)

# L342-345: ścieżka SDL2 (legacy backend A/B) — SDL2_LIBRARIES / STATIC_LDFLAGS
```

**Konsekwencia dla Androida:** musimy podać **pełny checkout libsdl‑org/SDL** (ma własny `android/` + Gradle +
aktywność EGL). Desktopowy pinned SDL w FetchContent go nie ma — na NDK się nie skompiluje.

### 1.4 Wybór backendu renderera — flow (main.cpp)

```text
g_video_renderer ∈ {0 software, 1 opengl, 2 vulkan}   // default = software/fallback
        │  ustawiane z: DEFAULT_VIDEO_RENDERER / config.toml / CLI (--renderer) / launcher seed
        ▼
PSX_HAVE_VULKAN? (runtime.cmake włączony?) ──NIE──► renderer 2 → zgłoś "Vulkan not available", przełącz na OpenGL
        │ TAK
        ▼
gr_set_backend( backend==2 ? GR_BACKEND_VULKAN :
                backend==1 ? GR_BACKEND_OPENGL :
                             GR_BACKEND_SOFTWARE )   // main.cpp ~L18647,przed gpu_init()
```

- **Software** (0) = domyślny fallback; `SDL_CreateRenderer` z backing texture. Na Androida dla tej gry **nie
  realny** (zbyt wolny).
- Netplay wymusza OpenGL retained‑textures (L18590: netplay profile requires GL); Vulkan w netplay nie jest
  CPU‑auth → fallback software.

### 1.5 Decyzja: renderer — OpenGL ES vs Vulkan (na Androida)

- **Zalecana primary: Vulkan.** `gpu_vk_renderer.c` ładuje bibliotekę i symboliki w locie; na Androida wystarczy
  ICD (`libvulkan.so`, wbudowany w większość SoC ARM) + SPIR‑V wygenerowany przez `glslc` z Vulkan SDK na hoście
  (`tools/embed_spirv.py`). Omija problem translacji GL‑core→GLES. Wymaga `-DPSX_ENABLE_VULKAN=ON`.
- **Alternatywa: OpenGL ES 3.0.** Tylko gdy Vulkan niedostępny. Wymaga: EGL za `psx_sdl.h` + kontekst
  `SDL_GL_CreateContext(...)` z atrybutami GLES 3.0 (`SDL_GL_CONTEXT_PROFILE_ES`, major 3, minor 0), przetłumaczenia
  desktop‑GL entrypointów w `gpu_gl_renderer.c` (FBO/VAO/shadery) on GLES oraz uwzględnienia różnic: brak
  fixed‑functiona, `glReadPixels` wymagają flusha, inne domeny shaderów.
- **Software** (`SDL_CreateRenderer`) — odradzane na Androida dla tej gry (zbyt wolny).

### 1.6 Wymagane biblioteki zależne — host (budowa), z dowodów w `runtime.cmake`

| Biblioteka / target | Gdzie linkowany | Źródło / uwagi |
|---|---|---|
| **SDL3** (lub SDL2) | L1941 `${PSX_SDL_LIBRARIES}` / L1939 `static` | domyślnie 3.4 CONFIG; fallback FetchContent (#308). Android: pełny checkout libsdl‑org/SDL, nie desktopowy FetchContent |
| **ZLIB::ZLIB** | L1948 | system lub zlibstatic via FetchContent; na Androida `libz` z NDK |
| **chdr‑static**, **psx_texture_image_decode** | L1616 (zawsze) | decompress media + decode obrazów tekstur |
| **Threads::Threads** | L2327 | C++17 pthreads |
| `CMAKE_DL_LIBS` | L2297 | dynładowanie symboli Vulkan/GL |
| optional: **recomp_net** | L2172 | netplay (wyłączony domyślnie) |
| optional: **retcomm_rbengine** | L2174 | savestate‑snap |
| optional: **openxr_loader** | L2281 | VR (GL 4.6); niepotrzebne na Androida |

> Na Windowsie framework linkuje dodatkowo `ws2_32 iphlpapi dbghelp comdlg32 opengl32` (+ winpthread przy MinGW,
> L2288‑2293) — te sygnatury **nie** występują na Androidu; zamiast OpenGL desktopowego idziemy Vulkan/GLES.

### 1.7 Rozwiązywanie GL (Windows vs reszta) — dla kontekstu portu

```cmake
# runtime.cmake ~L2304-2321: non-Windows
if(COMMAND recomp_resolve_gl)        # z recomp-ui/cmake/recomp_gl.cmake
    recomp_resolve_gl(_psx_gl_target); target_link_libraries(... ${_psx_gl_target})
else()
    find_package(OpenGL)             # fallback systemowy OpenGL::GL / OpenGL::OpenGL
endif()
```

Na Androida **żaden** z tych ścieżek desktopowych (opengl32/OpenGL::GL) się nie przyda — podajemy EGL/GLESv3 lub
Vulkan przez NDK; `PSX_ENABLE_VULKAN=ON` omija cały ten mechanizm.

### 1.8 Wymagane biblioteki zależne — device (arm64‑v8a)

| Biblioteka | Pakiet systemowy Androida | Gdzie użyta |
|---|---|---|
| `liblog` (`-llog`) | system | logi JNI/runtime, tag `Hercules` w `adb logcat -s Hercules` |
| `libandroid` (`-laNDroid`) | system | eventy okna/AInputQueue, lifecycle Activity |
| EGL / **GLESv3** (`-lEGL -lglesv3`) | system (sofc) | renderer OpenGL ES 3.0 (ścieżka alternatywna) |
| Vulkan (`libvulkan.so`, dynamiczne) | system SoC/ICD | **renderer primary** (`gpu_vk_renderer.c`) |
| OpenSL_ES / **AAudio** (`-lOpenSL_ES` / `-laaudio`) | system | audio PSX (SPU) — AAudio zalecane dla niskiego opóźnienia |
| `libz` (`-lz`, zlib) | system | decompress danych dysku/overlay cache |
| `libdl`, `libm`, `libpthread` | system | runtime C++17, Threads::Threads |

### 1.9 Pipeline budowy (host → device) — schemat

```
[HOST] git clone --recursive hercules-recompiled        # submodule psxrecomp + patches
       cd port && apply ../patches/psxrecomp/*.patch     # patchy frameworku (nie upstream)
       mkdir disc && cp "Disneys Hercules ...(.cue/.bin)"   # legalny dump, SCES-00893

[HOST] ./psxrecomp/tools/ci/build_emitters.sh            # AOT: psxrecomp-game + psxrecomp-bios (C++20)
       python3 psxrecomp/psxrecomp_cli.py generate \
           --config game.toml --project-root . \
           --disc "disco/...(.cue)"                       # -> generated/*.c (byte‑identyczne)

[HOST] cmake -S . -B build-android-arm64 -G Ninja \
        -DANDROID_ABI=arm64-v8a -DCMAKE_TOOLCHAIN_FILE=<NDK>/android-cmake/android.toolchain.cmake \
        -DPSX_SDL_BACKEND=SDL2 ...                          # (patrz Moduł 3)
       cmake --build build-android-arm64 --target psx-runtime

[DEVICE] adb install hercules-debug.apk && adb logcat -s Hercules
```

**Kluczowe:** `generated/*.c` są generowane **na hoście** z dysku i skompilowane do natywnego kodu — to jest
"silnik gry". Na Androida zmieniamy tylko cel budowy (target arch) oraz warstwę platformową; logika gry zostaje
identyczna.
---

## Moduł 2: Konfiguracja Warstwy Androida (JNI / SDL3 Activity)

### 2.1 Struktura katalogu `android/`

Tworzymy ją w korzeniu repozytorium, obok `port/`. To standardowy projekt Gradle dla aplikacji
Android + biblioteki natywnej (`*.so`).

```
hercules-recompiled/android/
├── settings.gradle                 # projekty app + native-lib
├── build.gradle                    # wersje pluginów/SDK (repozytoria, zależności)
├── gradle.properties               # ndk.dir, android.useAndroidX=true
├── gradlew / gradlew.bat           # Gradle Wrapper (10.x)
├── local.properties                # sdk.dir=<ANDROID_HOME>  (nie commitować!)
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml     # <application android:hasFragileUserData>, storage perms
        ├── java/org/libsdl/app/    # SDL3Activity (oficjalny z libsdl-org/SDL) — kopiujemy/pinujemy
        │   └── SDLActivity.java  (+ SDLNativeLib, SDLJoystickDevice …)
        ├── java/com/example/hercules/
        │   └── HerculesGameView.java   # widok + wstrzynięcie inputu dotykowego (D-pad na ekranie)
        ├── res/
        │   ├── layout/activity_main.xml  # SurfaceView + nakładka D‑pada / przycisków akcji
        │   ├── values/strings.xml, styles.xml, themes.xml
        │   └── drawable/*, mipmap/ic_launcher* …
        └── cpp/
            ├── CMakeLists.txt           # buduje libhercules.so (patrz Moduł 3)
            ├── android_main.cpp         # JNI: attach Activity → SDLActivity event loop
            └── JniBridge.h/.cpp             # mapowanie gestów na PadExtHooks.capture_local
```

### 2.2 Integracja z `SDLActivity` jako kontenerem aplikacji

Framework psxrecomp tworzy okno przez `SDL_CreateWindow`. Na Androida **nie chcemy** własnego okna —
zamiast tego:

- **Kopiujemy oficjalną aktywność z repo libsdl‑org/SDL** (musi to być pełny checkout SDL 3.1+, który
  zawiera `android/` + `SDLActivity.java` oparty o EGL; desktopowy pinned SDL w FetchContent go nie ma).
- `SDLActivity` dostarcza: powierzchnia EGL pod renderer, eventy okna do `AInputQueue`, audio przez SDL,
  gamepad‑HIDAPI (Bluetooth) i joystick. Nasz `HerculesGameView` rozszerza `SurfaceView` i jest nakładką
  nad nią; rysuje wirtualny D‑pad / przyciski akcji jako widoczne elementy UI Androida.
- Aktywność ustawia natywne bibliotekę (`System.loadLibrary("hercules")`) i przekazuje `surface`, a runtime
  psxrecomp tworzy kontekst Vulkan/GLES na podanej powierzchni zamiast wywoływać `SDL_CreateWindow`.

### 2.3 Sterowanie ekranowe (Touch / On‑screen D‑pad)

- **Punkt wstrzynięcia:** `runtime/src/pad_external_input.h` → `PadExtHooks.capture_local()`
  (`main.cpp::capture_local_human_pad`, ~L6365). To jedyna oficjalna furtka do symulowania inputu padu.
- W Java/C++ implementujemy nakładkę D‑pada w `activity_main.xml`; gesty (down/move/up na obszarach ekranu)
  mapujeme na cyfry PSX (D‑pad, ✕/○/□/△, L1/R1, Start/Select) i przekazujemy do natywki przez JNI.
- `JniBridge` zbiera frame inputu (`uint8_t buttons`, float stick XY) i wywołuje `capture_local()`. Musi to
  robić na wątku gry/main‑thread (framework blokuje wstrzynięcie podczas netplay/rewind/save).
- Zalecane: zachować domyślnie cyfrowy (`[controller] default_mode = "digital"` z game.toml) i dodać tryb
  analogowy dla lewego joysticka.

### 2.4 Wsparcie dla kontrolerów Bluetooth

- `SDLActivity` (z libsdl‑org/SDL) już enumeruje gamepady przez **HIDAPI** — parowanie BT działa z pudełka;
  wystarczy włączyć `[controller]` i podłaczyć urządzenie.
- Framework mapuje osie/przyciski na PSX przez `pad_buttons_for/pad_sticks_for/axes_to_pad_pair`
  (`main.cpp`). Na Androida upewnij się, że `SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER)` jest wywołane
  (już tak jest w main.cpp ~L19175) oraz że `android:renderedAsTexture=false`, by SurfaceView nie blokowało inputu.

---

## Moduł 3: Konfiguracja Toolchaina CMake & NDK

### 3.1 Wymagania środowiska (host)

```sh
# Android NDK r26+ (z android-cmake), CMake ≥ 3.20, Ninja — z SDK Manager lub standalone NDK
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973
```

### 3.2 Plik toolchaina NDK (`android/app/cpp/android.toolchain.cmake` — symlink do NDK)

NDK dostarcza gotowy plik: `<NDK>/build/cmake/android.toolchain.cmake`. My dodatkowo ustawiamy ABI i wersję
API w CMakeLists/app. Nie piszemy toolchaina od zera — dziedziczymy ten z NDK (jak `toolchain-mingw-w64.cmake`
dla Windowsa).

### 3.3 `android/app/cpp/CMakeLists.txt` (cel arm64‑v8a)

```cmake
cmake_minimum_required(VERSION 3.20)
project(HerculesNative LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)          # runtime psxrecomp pisany pod C++17 (MinGW/GCC default jest inny)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_BUILD_TYPE Release)       # NIE -O0: generated/*.c mają ~270 MB kodu

# --- Herkules: port gry (wskazujemy framework psxrecomp jako PSXRECOMP_ROOT) ---
add_subdirectory(${HERCULES_PORT} hercules_build EXCLUDE_FROM_ALL)
#   HERCULES_PORT = ścieżka do /port/ w repozytorium; tam CMakeLists.txt robi include(hercules_game.cmake)

# --- Biblioteki systemowe Androida (Moduł 1.4) ---
target_link_libraries(psx-runtime PRIVATE
    log          # -llog        : logi JNI/runtime
    android      # -laNDroid    : eventy okna / AInputQueue
    EGL          # -lEGL        : powierzchnia pod GLES (ścieżka alternatywna)
    glesv3       # -lglesv3     : renderer OpenGL ES 3.0
    OpenSL_ES    # -lOpenSL_ES  : audio PSX (SPU); lub AAudio poniżej
    z            # -lz          : decompress danych/overlay cache
    Threads::Threads)

# --- Vulkan: tylko jeśli włączony backend (dynamically loaded, brak link-time dep) ---
if(PSX_ENABLE_VULKAN)
    target_link_libraries(psx-runtime PRIVATE vulkan)   # libvulkan.so z device
endif()
```

### 3.4 `android/app/build.gradle` — ABI i NDK

```gradle
plugins { id 'com.android.application' }
android {
    namespace = "com.example.hercules"
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "com.example.hercules.recompiled"
        targetSdk = 34; minSdk = 24      // Vulkan + Scoped Storage
        externalNativeBuild {
            cmake {
                arguments << "-DANDROID_ABI=arm64-v8a"          // JEDYNE ABI w paczce
                args << "-DHERCULES_PORT=${projectDir}/../../port"
                // Wymuszenie SDL2 (działa stabilniej z aktywnością Androida niż domyślne SDL3):
                if (System.getenv("PSX_SDL_BACKEND") == "SDL3") {
                    args << "-DPSX_SDL_BACKEND=SDL2"
                }
            }
        }
        ndk { abfilters = ["arm64-v8a"] }                     // wycina x86/x86_64/armeabi-v7a
    }
    buildFeatures { buildConfig = true }
}
```

### 3.5 Konfiguracja budowy (host) — analogia do `scripts/build_linux_release.sh`

```sh
cd port
cmake -S . -B build-android-arm64 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM_LEVEL=34 \
  -DPSX_SDL_BACKEND=SDL2 \
  -DPSXRECOMP_REQUIRE_GAME_C=ON            # błąd jeśli brakuje generated/*.c (tak jak w CI)
cmake --build build-android-arm64 --target psx-runtime
```

- `-DANDROID_PLATFORM_LEVEL` decyduje o minimalnym `minSdk`; Vulkan ICD wymaga zwykle API 24+.
- Syntetyczna uwaga: ramka pins SDL3 (desktopowy FetchContent) **nie skompiluje się** pod NDK — użyj pełnego
  checkout libsdl‑org/SDL (`PSX_SDL3_FETCH=OFF -DSDL3_DIR=<checkout-android>/cmake`).

---

## Moduł 4: System Wskazywania Zasobów i Danych Gry (SCES‑00893)

### 4.1 Wskazywanie obrazu płyty (.cue / .bin) przez Scoped Storage + SAF

Od Androida 10 (API 29+) aplikacja **nie ma** dostępu do dowolnego pliku z pamięci masowej — trzeba
wybrać go przez **Storage Access Framework (SAF)**. Runtime psxrecomp już potrafi przyjąć dowolną ścieżkę:
`game.toml [runtime] disc`, argument `--disc`, cache `disc.cfg`, picker launchera i okno wyboru pierwszej
uruchomienia — wszystkie przechodzą przez `resolve_disc_path()` (`disc_path.h`).

- **Picker:** wywołujemy `Intent.ACTION_OPEN_DOCUMENT` (MIME `*/*`) z filtrem na `.cue`/`.bin`; użytkownik
  wybiera `.cue` dumpa SCES‑00893. Zwracamy `documentId` i zapisujemy trwały dostęp:

  ```java
  getContentResolver().persistTreePermission(uri,
      ResolverConstants.DOCUMENT_ROOT_ID,
      ContentResolver.FLAG_GRANT_READ_URI_PERMISSION |
      ContentResolver.FLAG_GRANT_WRITE_URI_PERMISSION);
  ```

- **Mount:** musimy dostarczyć natywne czytelny strumień z `DocumentFile`/`openInputStream()` i podać go do
  ISO9660 loadera (`cdrom.c`). Najprościej: odczytać fragment danych dysku do bufora pamięci (lub tymczaso‑
  wy plik w cache) i przekazać ścieżkę; `resolve_disc_path` preferuje `.cue`, ale nigdy go nie wymaga.
- **Weryfikacja:** framework sprawdza digesty (`[prepare_disc] known_md5/sha1/crc32` w game.toml — dla SCES‑00893:
  md5 `ec808ac8484dbc50e56866c1fdff3132`, CRC32 `9a51eebf`). Działa identycznie z SAF, bo czyta te same bajty.

### 4.2 Ścieżki danych aplikacji (zapisy + save states)

| Dane | Ścieżka na Androidu | Mechanizm |
|---|---|---|
| Save states / memcard | `getFilesDir()/saves/` **lub** `/sdcard/Android/data/com.example.hercules/files/saves/` | `[runtime] memcard_dir = "saves"` (game.toml); runtime robi `create_directories(memcard_dir)` (~L17081). Druga ścieżka (`files/`) nie wymaga permusji storage. |
| Konfiguracja dysku / keybindy | `disc.cfg`, `disc-<id>.cfg`, `keybinds.ini` — w `memcard_dir` (obok exe) | Te same pliki co na desktopie; zapisywane przy wyborze dysku i edycji klawiszy. |
| Save state slot + HD miniatury | `<save>/savestate/<slot>.psp`, `.thumb.png`, HD path | `savestate_slot_path()` / `savestate_thumb_path()` (`savestate.c`). F7/Select+R1 otwiera menu 12 slotów. |
| Zasoby HD (HD texture packs) | `/sdcard/Android/data/com.example.hercules/files/cache/hd/` **lub** internal cache dir | Paki ~1,6 GB; pobierane z release `hd-packs`, weryfikowane SHA256SUMS. Brak packa → generowanie z dysku (patrz niżej). |

Zalecenie: użyć **własnego katalogu app** (`getExternalFilesDir(null)` / `getFilesDir()`), by nie wymagać
`MANIFEST_EXTERNAL_STORAGE` ani runtime‑permusji na Androidzie 13+. HD cache trzymać w `getCodeCacheDir()`.

### 4.3 Generowanie zasobów HD z dysku (mobile)

- Launcher (`launcher_native/hl_launcher.cpp`) po pierwszej grze prosi o pobranie packa HD; jeśli nie ma go
  dla SCES‑00893, generuje **z dysku gracza** w tle (~4 h na CPU laptopa → na telefonie wolniej).
- Na Androidzie ten etap wymaga Pythona z torch/spandrel + trzech modeli (IllustrationJaNai, HDcube) — **nie jest
  wymagany do gry w oryginalnej jakości**. Do podstawowego portu wystarczy: wybór dysku przez SAF + gra.
- Narzędzia decomp (`decomp/tools/hd_pack_build.py` i podmoduły `iso_extract.py`, `sprites.py`, …) działają z
  dysku; na Androida najlepiej wywołać je **na hoście** i wgrać gotowy pack do cache app.

---

## Moduł 5: Skrypt Budujący i Generowanie APK

### 5.1 Wymaganie wstępne — wygenerowanie `generated/*.c` na hoście (Moduł 1.5)

APK nie zbudujemy bez skompilowanego silnika gry. Najpierw, na hoście:

```sh
git clone --recursive https://github.com/obaidr1/hercules-recompiled && cd hercules-recompiled/port
cd psxrecomp && for p in ../patches/psxrecomp/*.patch; do git apply "$p"; done && cd ..
mkdir -p disc && cp "disco/Disneys Hercules Action-Spiel (Germany).cue" \
               "disco/Disneys Hercules Action-Spiel (Germany).bin" disc/   # SCES-00893
./psxrecomp/tools/ci/build_emitters.sh
python3 psxrecomp/psxrecomp_cli.py generate --config game.toml --project-root . \
    --disc "disc/Disneys Hercules Action-Spiel (Germany).cue"
```

### 5.2 Budowanie APK przez Gradle Wrapper

Z korzenia repozytorium (`android/` jest w `android/`, wrapper `gradlew` tam też):

```sh
# Debug — szybki, self-signed signaturą, do testów:
./gradlew assembleDebug

# Release — wymaga podpisu (patrz 5.4); dla sklepu / dystrybucji:
./gradlew assembleRelease
```

Wynik:
- `android/app/build/outputs/apk/debug/app-debug.apk`
- `android/app/build/outputs/apk/release/app-release-signed.apk`

Wskazówki budowy:
- Ustaw `local.properties` → `sdk.dir=<ANDROID_HOME>` (nie commitować) i `gradle.properties`:
  `ndk.dir=<ANDROID_NDK_HOME>`, `android.useAndroidX=true`.
- Do debugu z włączonym Vulkan: `-DPSX_ENABLE_VULKAN=ON`; dla GLES ES — zostaw domyślnie, a `-DPSX_SDL_BACKEND=SDL2`.
- Optymalizacja wielkości APK: w `app/build.gradle` `splits { abi { enable true } }` + tylko `arm64-v8a`
  (patrz Moduł 3.4) → mniejszy `.aab`/`.apk`.

### 5.3 Instalacja i debugowanie na urządzeniu

```sh
# Włączenie debugowania USB w ustawieniach developera; urządzenie widoczne:
adb devices

# Instal APK debug:
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# Uruchomienie aktywności (bez klikania ikony):
adb shell am start -n com.example.hercules/.SDLActivity

# Podgląd logów — filtr po tagu "Hercules" (tak jak w poleceniu):
adb logcat -s Hercules
```

Podczas pierwszego uruchomienia launcher poprosi o wybór dysku `.cue` SCES‑00893 przez SAF; po wyborze
zapamięta go (`disc.cfg`) i przejdnie bezpośrednio do wybranego poziomu.

### 5.4 Podpisywanie APK release (opcjonalnie)

```sh
keytool -genkeypair -v -keystore hercules.keystore \
    -storetype JKS -keyalg RSA -keysize 2048 -validity 10000 \
    -alias hercules
./gradlew assembleRelease -Pandroid.signing.store=/path/hercules.keystore \
    -Pandroid.signing.store.password=... -Pandroid.signing.key.password=... \
    -Pandroid.signing.key.alias=hercules
adb install -r android/app/build/outputs/apk/release/app-release-signed.apk
```

### 5.5 Checklist końcowa portu (podsumowanie modułów)

- [ ] **M1** — wybrany renderer Vulkan (primary) lub GLES 3.0; biblioteki systemowe z Modułu 1.4.
- [ ] **M2** — `SDLActivity` z libsdl‑org/SDL pod Androidem; D‑pad mapowany na `PadExtHooks.capture_local`.
- [ ] **M3** — arm64‑v8a, toolchain NDK, `-lglesv3/-lEGL/OpenSL_ES/log/android/z`, ABI wycięte do samej arm64.
- [ ] **M4** — SAF picker `.cue/.bin` z `persistTreePermission`; saves w katalogu app; cache HD.
- [ ] **M5** — `./gradlew assembleDebug/release`, `adb install -r …apk`, `adb logcat -s Hercules`.

---

*Plan wygenerowany na podstawie analizy repozytorium `obaidr1/hercules-recompiled` (podmoduł psxrecomp
v0.3.2‑alpha, commit 2a4824fc) i ramki RetroPortingToolKit/psxrecomp.*





