package com.example.hercules;

import android.os.Bundle;

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
        super.onCreate(savedInstanceState);
        // TODO: inject HerculesGameView overlay using approach (a) or (b) above.
    }

    // Optional app-specific lifecycle hooks can be added here (e.g. re-arming the pad overlay on resume).
    // Keep only overrides that provably exist on SDLActivity to avoid compile errors against the copied source.
}
