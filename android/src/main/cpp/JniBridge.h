// Shared touch-pad state for the Hercules Android port.
//
// This struct is written by the JNI bridge (JniBridge.cpp) from on-screen D-pad / face-button touches,
// and read by psx-recomp's pad hook: main.cpp::capture_local_human_pad() -> pad_external_input.c::pad_ext_resolve().
// See android/README.md for how this symbol becomes visible to the runtime (exe vs shared-lib seam).
#pragma once

#include <cstdint>

struct TouchPadState {
    uint32_t buttons = 0;   // PSX button bitmask — matches JniBridge.java bit layout.
    int16_t  lx = 0, ly = 0; // left analog, range [-32768..32767] (neutral ~ 0).
    int16_t  rx = 0, ry = 0; // right analog, range [-32768..32767] (neutral ~ 0).
};

// Exposed so the game runtime can query touch-pad state from its pad hook.
extern "C" TouchPadState* hercules_touch_state();
