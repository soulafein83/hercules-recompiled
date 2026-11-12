// Android API < 29 compatibility shim for the Hercules port.
//
// bionic declares timespec_get() only from API 29 (__INTRODUCED_IN(29)); with
// minSdk 24 the declaration is hidden, so the psxrecomp runtime's call in
// runtime/src/dma.c ("C11; diagnostics only") turns into an
// implicit-function-declaration error:
//   runtime/src/dma.c:833: error: call to undeclared function 'timespec_get'
//
// This header provides a portable definition routed to clock_gettime()
// (available since API 17). It is force-included (-include) into the runtime
// targets only when building for API < 29, so it never clashes with bionic's
// own declaration on newer platforms.
//
// It is a no-op on non-Android or API >= 29 builds. This keeps minSdk 24
// working without patching the psxrecomp submodule.
#pragma once

#if defined(__ANDROID__) && defined(__ANDROID_API__) && (__ANDROID_API__ < 29)

#include <time.h>

#ifndef HERCULES_ANDROID_TIMESPEC_GET_SHIM
#define HERCULES_ANDROID_TIMESPEC_GET_SHIM

// dma.c only ever passes TIME_UTC, which maps to CLOCK_REALTIME.
static inline int timespec_get(struct timespec *ts, int base) {
    return (clock_gettime(CLOCK_REALTIME, ts) == 0) ? base : 0;
}

#endif /* HERCULES_ANDROID_TIMESPEC_GET_SHIM */
#endif /* __ANDROID__ && __ANDROID_API__ < 29 */
