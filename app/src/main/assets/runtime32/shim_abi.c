#define _GNU_SOURCE
#include <dlfcn.h>
#include <string.h>
#include <stdarg.h>
#include <stdio.h>
#include <unistd.h>

typedef int (*real_get_t)(const char*, char*);

int __system_property_get(const char* name, char* value) {
    static real_get_t real_get = NULL;
    if (!real_get) real_get = (real_get_t)dlsym(RTLD_NEXT, "__system_property_get");
    if (strcmp(name, "ro.product.cpu.abilist32") == 0 || strcmp(name, "ro.product.cpu.abilist") == 0) {
        strcpy(value, "armeabi-v7a,armeabi");
        return strlen(value);
    }
    if (real_get) return real_get(name, value);
    return 0;
}

void cpp_abort_stub(const char* format, ...) __asm__("_ZNSt3__122__libcpp_verbose_abortEPKcz");
void cpp_abort_stub(const char* format, ...) {
    va_list args; va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args); fprintf(stderr, "\n");
}

int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
    va_end(args); return 1;
}

int __android_log_write(int prio, const char* tag, const char* text) {
    fprintf(stderr, "[%s] %s\n", tag, text);
    return 1;
}

// নতুন হুক: logcat-এর বাফারে লেখা ধরার জন্য
int __android_log_buf_write(int bufID, int prio, const char* tag, const char* msg) {
    fprintf(stderr, "[%s] %s\n", tag, msg);
    return 1;
}
