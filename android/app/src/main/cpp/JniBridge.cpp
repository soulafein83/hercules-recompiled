// JNI glue for the Hercules Android port: implements Java_com_example_hercules_JniBridge_setTouchPad().
//
// The on-screen D-pad reports its button mask + analog values here; this feeds the shared TouchPadState that
// main.cpp::capture_local_human_pad() reads. Physical/BT gamepads are masked out by pad_external_input.c, so
// touch input only reaches the game when no physical pad is connected (the AND-masking gate).
#include "JniBridge.h"

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

static TouchPadState g_state;

extern "C" TouchPadState* hercules_touch_state() { return &g_state; }

JNIEXPORT void JNICALL
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
JNIEXPORT void JNICALL
Java_com_example_hercules_JniBridge_setDataDir(JNIEnv *env, jclass /*clazz*/, jstring path) {
    if (!path) return;
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (!p) return;
    if (chdir(p) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "Hercules", "setDataDir: chdir(%s) failed", p);
    }
    env->ReleaseStringUTFChars(path, p);
}

// Register native methods / keep the JVM happy at load time. System.loadLibrary("hercules") triggers this.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_OK;
}
