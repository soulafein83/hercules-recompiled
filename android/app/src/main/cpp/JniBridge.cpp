// JNI glue for the Hercules Android port: implements Java_com_example_hercules_JniBridge_setTouchPad().
//
// The on-screen D-pad reports its button mask + analog values here; this feeds the shared TouchPadState that
// main.cpp::capture_local_human_pad() reads. Physical/BT gamepads are masked out by pad_external_input.c, so
// touch input only reaches the game when no physical pad is connected (the AND-masking gate).
#include "JniBridge.h"

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>

static TouchPadState g_state;

extern "C" TouchPadState* hercules_touch_state() { return &g_state; }

// ---------------------------------------------------------------------------
// stdout/stderr -> logcat
//
// The psx-recomp runtime reports disc/BIOS/init problems on stdout/stderr.
// Android discards both for an app process, so those failures are invisible in
// logcat. Pipe them through a reader thread that forwards everything under the
// "HerculesRT" tag.
// ---------------------------------------------------------------------------
namespace {

void* stdio_log_thread(void* arg) {
    int fd = static_cast<int>(reinterpret_cast<intptr_t>(arg));
    char buf[1024];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        __android_log_write(ANDROID_LOG_INFO, "HerculesRT", buf);
    }
    return nullptr;
}

void redirect_stdio_to_logcat() {
    static bool done = false;
    if (done) return;
    done = true;

    int fds[2];
    if (pipe(fds) != 0) return;
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);

    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);

    pthread_t t;
    if (pthread_create(&t, nullptr, stdio_log_thread,
                       reinterpret_cast<void*>(static_cast<intptr_t>(fds[0]))) == 0) {
        pthread_detach(t);
    }
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_example_hercules_JniBridge_setTouchPad(JNIEnv *env, jclass /*clazz*/, int32_t buttons,
                                                jfloat lx, jfloat ry) {
    (void)env;
    g_state.buttons = static_cast<uint32_t>(buttons);
    // Analog mapping: joystick reports [0..1] with neutral at 0.5 -> [-32768..32767].
    // HerculesGameView currently drives only digital buttons (analog sticks are a TODO in the overlay).
    g_state.lx = static_cast<int16_t>((lx - 0.5f) * 65535.0f);
    g_state.ly = static_cast<int16_t>((ry - 0.5f) * 65535.0f);
}

// Point the runtime's data anchor at the app's private runtime directory.
// The psx-recomp runtime resolves game.toml, bios/, disc.cfg and saves relative
// to the "exe directory", which it derives from argv[0]; SDL passes
// argv[0]="app_process" on Android, so that resolves to the process CWD.
// chdir() before SDL_main makes every relative path land in this directory.
extern "C" JNIEXPORT void JNICALL
Java_com_example_hercules_JniBridge_setDataDir(JNIEnv *env, jclass /*clazz*/, jstring path) {
    if (!path) return;
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (!p) return;
    if (chdir(p) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "Hercules", "setDataDir: chdir(%s) failed", p);
    }
    // The framework's launcher "START GAME" spawns the game executable with
    // --no-launcher (SDL_CreateProcessWithProperties). An APK cannot spawn its
    // own executable, so boot straight into the game instead of the launcher.
    setenv("PSX_NO_LAUNCHER", "1", 1);
    // TEMP: on-screen FPS readout for on-device performance tuning.
    setenv("PSX_FPS_TELEMETRY", "1", 1);
    // Rewind snapshots the whole machine every 15 frames (~12.5 s history); it
    // is a debug convenience and a large per-frame cost on a phone. Off by default.
    setenv("PSX_REWIND", "0", 1);
    env->ReleaseStringUTFChars(path, p);
}

// Register native methods / keep the JVM happy at load time. System.loadLibrary("hercules") triggers this.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    redirect_stdio_to_logcat();
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    // JNI_OnLoad must return the JNI *version*, not JNI_OK (0): returning 0 makes
    // the runtime reject the library with "Bad JNI version returned from JNI_OnLoad".
    return JNI_VERSION_1_6;
}
