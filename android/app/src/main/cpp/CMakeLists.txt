# Native build for the Hercules Android port.
# This is invoked by Gradle's externalNativeBuild (app/build.gradle -> src/main/cpp/CMakeLists.txt).
# AGP injects the NDK toolchain automatically; -DHERCULES_PORT and -DHERCULES_VERSION come from app/build.gradle.

cmake_minimum_required(VERSION 3.20)

project(hercules_android LANGUAGES C CXX)

if(NOT DEFINED HERCULES_PORT)
    # Fallback for manual CLI builds (Gradle always passes -DHERCULES_PORT). Point it at the repo's port/ dir.
    set(HERCULES_PORT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../port" CACHE PATH "Path to psx-recomp port/")
endif()

# --- Runtime configuration -----------------------------------------------------
if(NOT DEFINED HERCULES_VERSION)
    # English PAL release (matches the Redump dump we build against). Build other
    # releases with -DHERCULES_VERSION=SCES-0089x (see port/versions/<VER>/).
    set(HERCULES_VERSION SCES-00891 CACHE STRING "Game version (see port/versions/<VER>/game.toml)")
endif()

# Vulkan is the primary renderer; GLES ES 3.0 is an optional fallback toggled by app/build.gradle's USE_GLES property.
if(NOT DEFINED PSX_ENABLE_VULKAN)
    set(PSX_ENABLE_VULKAN ON CACHE BOOL "Use Vulkan (primary); OFF enables GLES ES fallback via SDL2 backend")
endif()

# --- Pull in the psx-recomp runtime + generated C ------------------------------
# The port root builds the German release (SCES-00893). Every other PAL release
# builds from its own versions/<ID>/CMakeLists.txt (see port/README.md); that
# makes generated/ and hercules_addrs.h match the player's disc. Pick the dir
# that corresponds to HERCULES_VERSION.
set(HERCULES_PROJECT_DIR "${HERCULES_PORT}")
if(EXISTS "${HERCULES_PORT}/versions/${HERCULES_VERSION}/CMakeLists.txt")
    set(HERCULES_PROJECT_DIR "${HERCULES_PORT}/versions/${HERCULES_VERSION}")
endif()
message(STATUS "Hercules: HERCULES_VERSION=${HERCULES_VERSION}, project=${HERCULES_PROJECT_DIR}")
add_subdirectory(${HERCULES_PROJECT_DIR} hercules_build EXCLUDE_FROM_ALL)

# --- Android API < 29 compat shim ---------------------------------------------
# bionic hides timespec_get() below API 29; the framework's runtime/src/dma.c
# uses it for diagnostics. Force-include a portable definition into the runtime
# targets so minSdk 24 keeps working without patching the psxrecomp submodule.
# See compat/hercules_android_compat.h.
set(HERCULES_ANDROID_COMPAT "${CMAKE_CURRENT_SOURCE_DIR}/compat/hercules_android_compat.h")
foreach(_hercules_shim_target psx-runtime psx-runtime-pgxp)
    if(TARGET ${_hercules_shim_target})
        target_compile_options(${_hercules_shim_target} PRIVATE -include "${HERCULES_ANDROID_COMPAT}")
    endif()
endforeach()

# --- Integration seam: psx-runtime is an EXECUTABLE, but JNI loads a .so --------
# The framework's `psxrecomp_add_game_runtime(psx-runtime ...)` emits a self-contained exe with its own SDL main.
# For System.loadLibrary("hercules") we need the runtime exposed as a SHARED or STATIC target so our bridge can link it.
# Two options (documented in android/README.md):
#   (a) Add an exported extern "C" entry point to port/ and build a SHARED lib variant of the runtime, OR
#   (b) Link this .so against the generated C objects / static archive produced by add_subdirectory.
# If only `psx-runtime` (exe) exists, create a thin alias target here before linking:
#   add_library(hercules_runtime SHARED IMPORTED)  # -> point its LOCATION at the shared variant you export from port/

# --- JNI bridge library --------------------------------------------------------
add_library(hercules SHARED JniBridge.cpp)

target_include_directories(hercules PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Threads::Threads requires an explicit find_package (the NDK toolchain does not
# provide it implicitly). The framework's port/ CMake may also call it — find_package
# is idempotent, so this is safe either way.
find_package(Threads REQUIRED)

# System libraries required by the NDK + psx-recomp on Android (see herc.md Moduł 3).
# log: android logging; android: ANativeActivity/ANdroid_bitmap; EGL+GLESv3: Vulkan fallback / graphics;
# OpenSLES: PSX audio; z: decompression; Threads::Threads: runtime worker threads.
# NOTE: library names must match the NDK stub file names exactly (case-sensitive):
#   libGLESv3.so  -> GLESv3   (NOT "glesv3")
#   libOpenSLES.so -> OpenSLES (NOT "OpenSL_ES")
target_link_libraries(hercules PRIVATE
    log
    android
    EGL
    GLESv3
    OpenSLES
    z
    Threads::Threads
)
