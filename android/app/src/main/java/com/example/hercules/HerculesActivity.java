package com.example.hercules;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

// SDLActivity is COPIED from the pinned checkout of libsdl-org/SDL (see android/README.md).
// It is NOT part of the psxrecomp framework — that framework ships no Android layer at all.
import org.libsdl.app.SDLActivity;

/**
 * Thin launcher Activity for the Hercules port.
 *
 * It extends the SDL-managed {@link SDLActivity} copied from the pinned libsdl-org/SDL checkout and is
 * responsible only for loading our shared library (libhercules.so) and exposing app-specific hooks. The
 * game window, input dispatch and rendering all come from that SDL base class unchanged.
 */
public class HerculesActivity extends SDLActivity {

    static {
        // Loads libhercules.so — the JNI bridge in cpp/JniBridge.cpp plus the glue to psx-recomp's runtime.
        // Loading here is idempotent with JniBridge's own System.loadLibrary("hercules").
        System.loadLibrary("hercules");
    }

    /**
     * SDL3 is linked statically into libmain.so: that shared object is the
     * psx-recomp runtime, built as a library (see patch 0013 in
     * port/patches/psxrecomp/) because an APK cannot launch the desktop
     * executable. SDLActivity then calls SDL_main from it.
     *
     * SDL's default list also names "SDL3" for builds that ship SDL as its own
     * shared library; this port does not, so loading "main" alone is correct.
     * getMainSharedObject() uses the last entry, i.e. libmain.so.
     */
    @Override
    protected String[] getLibraries() {
        return new String[] { "main" };
    }

    /**
     * INTEGRATION SEAM — do not guess this against the pinned SDL3 source.
     *
     * The on-screen D-pad (HerculesGameView) must be layered ON TOP of SDL's surface WITHOUT swallowing the
     * touches that belong to it. A full-screen touchable overlay passed straight through to SDL would block
     * gameplay, so one of two approaches is required against the copied Activity/Surface source:
     *
     *   (a) Provide a custom SDLSurfaceView subclass whose onTouch() dispatches unhandled areas back to SDL,
     *       and register it via SDLActivity's surface-creation hook; or
     *   (b) Draw the D-pad as a floating SYSTEM_ALERT_WINDOW window layered above the app.
     *
     * Both are adaptation points in the pinned SDL3 sources — see android/README.md. This stub intentionally
     * leaves that override empty so the build is honest about what still needs porting from the base class.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Runs on every launch, including a restored/recents one where
        // BootstrapActivity is not in the path: stage the runtime data and set
        // the CWD (plus PSX_NO_LAUNCHER) before SDL starts its native thread.
        java.io.File runtimeDir = RuntimeFiles.runtimeDir(this);
        try {
            RuntimeFiles.extractAssets(this, "runtime", runtimeDir);
        } catch (java.io.IOException e) {
            android.util.Log.e("Hercules", "cannot extract runtime assets", e);
        }
        RuntimeFiles.ensureSettings(runtimeDir, BuildConfig.INTERNAL_RESOLUTION);
        JniBridge.setDataDir(runtimeDir.getAbsolutePath());
        super.onCreate(savedInstanceState);
        enterImmersiveMode();
        // TODO: inject HerculesGameView overlay using approach (a) or (b) above.
    }

    /**
     * Hide the status/navigation bars. The theme's windowFullscreen alone is not
     * enough on API 30+ (and Android 15+ enforces edge-to-edge), which left the
     * status bar and the nav bar visible around the picture.
     */
    private void enterImmersiveMode() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30 /* Android 11 (R) */) {
            WindowInsetsController c = getWindow().getInsetsController();
            android.util.Log.i("Hercules", "immersive: sdk=" + Build.VERSION.SDK_INT
                    + " controller=" + (c != null));
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    /**
     * SDL passes these as argv[1..]; the runtime's desktop default in game.toml
     * is OpenGL, whose GLSL 330 shaders cannot run on GLES. Vulkan is the port's
     * primary renderer (see herc.md §1.5) and is dynamically loaded.
     */
    @Override
    protected String[] getArguments() {
        return new String[] { "--renderer", "vulkan" };
    }

    // Optional app-specific lifecycle hooks can be added here (e.g. re-arming the pad overlay on resume).
    // Keep only overrides that provably exist on SDLActivity to avoid compile errors against the copied source.
}
