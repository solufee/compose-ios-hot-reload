// #60 stage 2 item 3 (measurement only): time dyld loading the prelinked library image inside the real app process.
// Injected with DYLD_INSERT_LIBRARIES; its constructor runs before the app's main, dlopen()s HR_PRELINK_DYLIB with RTLD_NOW
// (map + rebase + bind + the image's initializers, in the order of its __init_offsets), prints one line, then _exit(0)s so the
// app never continues (the same code would otherwise be JIT-loaded a second time by libKaldo).
#include <dlfcn.h>
#include <mach/mach_time.h>
#include <mach-o/dyld.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int inits_seen = 0;
static double ms_since(uint64_t t0) {
    mach_timebase_info_data_t tb; mach_timebase_info(&tb);
    return (double)(mach_absolute_time() - t0) * tb.numer / tb.denom / 1e6;
}
__attribute__((constructor)) static void gate(void) {
    const char *path = getenv("HR_PRELINK_DYLIB");
    if (!path || !*path) return;
    uint32_t before = _dyld_image_count();
    uint64_t t0 = mach_absolute_time();
    void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    double ms = ms_since(t0);
    uint32_t after = _dyld_image_count();
    fprintf(stderr, "PRELINK-GATE dlopen_ms=%.1f ok=%d images_added=%u%s%s\n", ms, h != NULL, after - before,
            h ? "" : " error=", h ? "" : dlerror());
    fflush(stderr);
    if (!getenv("HR_PRELINK_CONTINUE")) _exit(h ? 0 : 1);
}
