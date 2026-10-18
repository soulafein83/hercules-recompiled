package com.example.hercules;

/**
 * Java-side bridge to the native JNI glue in cpp/JniBridge.cpp.
 *
 * The virtual on-screen D-pad (see HerculesGameView) reports its state here, and the C++ side feeds it
 * into psx-recomp's pad hook (`PadExtHooks.capture_local()`). Physical/BT gamepads take precedence —
 * see HerculesActivity / pad_external_input.c::pad_ext_resolve() for the AND-masking gate.
 */
public final class JniBridge {

    static {
        // Loads libhercules.so (built by src/main/cpp/CMakeLists.txt). System.loadLibrary is also
        // triggered from HerculesActivity; both are idempotent.
        System.loadLibrary("hercules");
    }

    /** PSX button bitmask — matches the bits psx-recomp expects in `buttons`. */
    public static final int TRIANGLE = 1 << 0;   // △
    public static final int CIRCLE   = 1 << 1;   // ○
    public static final int CROSS    = 1 << 2;   // ✕
    public static final int SQUARE   = 1 << 3;   // □
    public static final int L1       = 1 << 4;
    public static final int R1       = 1 << 5;
    public static final int L2       = 1 << 6;
    public static final int R2       = 1 << 7;
    public static final int START    = 1 << 8;
    public static final int SELECT   = 1 << 9;

    /** D-pad directions as a single nibble (matches dpad bit layout). */
    public static final int DPAD_UP      = 1 << 12;
    public static final int DPAD_DOWN    = 1 << 13;
    public static final int DPAD_LEFT    = 1 << 14;
    public static final int DPAD_RIGHT   = 1 << 15;

    /** Update the virtual D-pad / face buttons + analog stick from touch input. */
    public static native void setTouchPad(int buttons, float leftStickX, float rightStickY);

    private JniBridge() {}
}
