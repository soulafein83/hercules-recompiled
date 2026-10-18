# Native build for the Hercules Android port.
# This is invoked by Gradle's externalNativeBuild (app/build.gradle -> src/main/cpp/CMakeLists.txt).
# AGP injects the NDK toolchain automatically; -DHERCULES_PORT and -DHERCULES_VERSION come from app/build.gradle.

cmake_minimum_required(VERSION 3.20)

project(hercules_android LANGUAGES C CXX)

if(NOT DEFINED HERCULES_PORT)
    # Fallback for manual CLI builds (Gradle always passes -DHERCULES_PORT). Point it at the repo's port/ dir.
    set(HERCULES_PORT "${CMAKE_CURRENT_SOURCE_DIR}/../../../port" CACHE PATH "Path to psx-recomp port/")
endif()

# --- Runtime configuration -----------------------------------------------------
if(NOT DEFINED HERCULES_VERSION)
    set(HERCULES_VERSION SCES-00893 CACHE STRING "Game version (see port/versions/<VER>/game.toml)")
endif()

# Vulkan is the primary renderer; GLES ES 3.0 is an optional fallback toggled by app/build.gradle's USE_GLES property.
if(NOT DEFINED PSX_ENABLE_VULKAN)
    set(PSX_ENABLE_VULKAN ON CACHE BOOL "Use Vulkan (primary); OFF enables GLES ES fallback via SDL2 backend")
endif()

# --- Pull in the psx-recomp runtime + generated C ------------------------------
# add_subdirectory pulls port/ into our build and defines the `psx-runtime` target(s). EXCLUDE_FROM_ALL keeps
# this subproject out of default builds (Gradle only needs libhercules.so; the game exe is never launched here).
add_subdirectory(${HERCULES_PORT} hercules_build EXCLUDE_FROM_ALL)

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

# System libraries required by the NDK + psx-recomp on Android (see herc.md Moduł 3).
# log: android logging; android: ANativeActivity/ANdroid_bitmap; EGL+glesv3: Vulkan fallback / graphics;
# OpenSL_ES: PSX audio; z: decompression; Threads::Threads: runtime worker threads.
target_link_libraries(hercules PRIVATE
    log
    android
    EGL
    glesv3
    OpenSL_ES
    z
    Threads::Threads
)
