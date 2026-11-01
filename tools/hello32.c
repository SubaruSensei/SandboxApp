#include <stdio.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

typedef int (*JNI_CreateJavaVM_t)(void**, void**, void*);
typedef void* (*FindClass_t)(void*, const char*);
typedef void* (*GetStaticMID_t)(void*, void*, const char*, const char*);
typedef void* (*NewObjArr_t)(void*, int, void*, void*);
typedef void (*CallStaticVoidA_t)(void*, void*, void*, void*);
typedef void (*EnvVoid_t)(void*);
typedef void* (*EnvPtr_t)(void*);
typedef struct { const char *optionString; void *extraInfo; } JavaVMOption;
typedef struct { int version; int nOptions; JavaVMOption *options; unsigned char ignoreUnrecognized; } JavaVMInitArgs;
typedef union { void *l; long long j; double d; } jvalue_t;

int main(int argc, char *argv[]) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    printf("[hello32 v4 probe]\n");
    if (argc < 5) { printf("usage: runtimeDir gameLib dataDir classpath [vmopts...]\n"); return 1; }
    char *libDir = argv[1]; char *gameLib = argv[2]; char *dataDir = argv[3]; char *dexPath = argv[4];
    printf("Runtime Dir: %s\nGame Lib: %s\nData Dir: %s\nDex: %s\n", libDir, gameLib, dataDir, dexPath);

    mkdir(dataDir, 0777);
    char cache_dir[512];
    snprintf(cache_dir, sizeof(cache_dir), "%s/dalvik-cache", dataDir);
    mkdir(cache_dir, 0777);

    setenv("ANDROID_ROOT", "/system", 1);
    setenv("ANDROID_DATA", dataDir, 1);
    setenv("ANDROID_ART_ROOT", libDir, 1);
    setenv("ANDROID_I18N_ROOT", libDir, 1);
    setenv("ANDROID_TZDATA_ROOT", libDir, 1);

    char path[512];
    void *artHandle = NULL;
    const char *libs[] = {"libc++.so", "libbase.so", "libartbase.so", "libdexfile.so", "libnativehelper.so", "libart.so"};
    for (int i = 0; i < 6; i++) {
        snprintf(path, sizeof(path), "%s/%s", libDir, libs[i]);
        void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (!h) { printf("Failed to load %s: %s\n", libs[i], dlerror()); return 1; }
        printf("Loaded %s\n", libs[i]);
        if (strcmp(libs[i], "libart.so") == 0) artHandle = h;
    }
    JNI_CreateJavaVM_t JNI_CreateJavaVM = (JNI_CreateJavaVM_t)dlsym(artHandle, "JNI_CreateJavaVM");
    if (!JNI_CreateJavaVM) { printf("JNI_CreateJavaVM not found\n"); return 1; }

    char bootcp[4096];
    snprintf(bootcp, sizeof(bootcp),
        "-Xbootclasspath:%s/javalib/core-oj.jar:%s/javalib/core-libart.jar:%s/javalib/core-icu4j.jar:%s/javalib/apache-xml.jar:%s/javalib/bouncycastle.jar:%s/javalib/okhttp.jar:%s/javalib/service-art.jar:%s",
        libDir, libDir, libDir, libDir, libDir, libDir, libDir, dexPath);
    const char *eb = getenv("HELLO_EXTRA_BOOT");
    if (eb && *eb) { strncat(bootcp, ":", sizeof(bootcp) - strlen(bootcp) - 1); strncat(bootcp, eb, sizeof(bootcp) - strlen(bootcp) - 1); }
    char cp[2048];
    snprintf(cp, sizeof(cp), "-Djava.class.path=%s", dexPath);
    printf("BootClassPath: %s\n", bootcp);

    JavaVMOption options[32];
    options[0].optionString = bootcp;
    options[1].optionString = "-Xnoimage-dex2oat";
    options[2].optionString = "-Xint";
    options[3].optionString = "-Xusejit:false";
    options[4].optionString = "-Xnorelocate";
    options[5].optionString = cp;
    int nopt = 6;
    for (int i = 5; i < argc && nopt < 32; i++) {
        options[nopt].optionString = argv[i];
        printf("Extra VM option: %s\n", argv[i]);
        nopt++;
    }
    JavaVMInitArgs vm_args;
    vm_args.version = 0x00010006;
    vm_args.nOptions = nopt;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = 1;

    void *jvm = NULL, *jniEnv = NULL;
    int ret = JNI_CreateJavaVM(&jvm, &jniEnv, &vm_args);
    if (ret != 0) { printf("JNI_CreateJavaVM failed: %d\n", ret); return 1; }
    printf("JavaVM Created at %p\n", jvm);

    void **envtab = *(void***)jniEnv;
    FindClass_t FindClass = (FindClass_t)envtab[6];
    EnvVoid_t ExceptionDescribe = (EnvVoid_t)envtab[16];
    EnvVoid_t ExceptionClear = (EnvVoid_t)envtab[17];
    EnvPtr_t ExceptionOccurred = (EnvPtr_t)envtab[15];

    void *strc = FindClass(jniEnv, "java/lang/String");
    printf("FindClass(String)=%p\n", strc);

    void *lcls = FindClass(jniEnv, "com/sandbox/app/MinecraftLauncher");
    printf("FindClass(MinecraftLauncher)=%p\n", lcls);
    if (!lcls) { ExceptionDescribe(jniEnv); ExceptionClear(jniEnv); printf("Launcher class not found\n"); return 3; }

    void *mmid = ((GetStaticMID_t)envtab[113])(jniEnv, lcls, "main", "([Ljava/lang/String;)V");
    printf("main method id=%p\n", mmid);
    if (!mmid) { ExceptionDescribe(jniEnv); ExceptionClear(jniEnv); printf("main not found\n"); return 4; }

    const char *probes[] = {"com/mojang/android/net/HTTPResponse", "com/mojang/android/net/HTTPRequest", "com/mojang/minecraftpe/HardwareInformation", "com/mojang/minecraftpe/MainActivity", NULL};
    for (int pi = 0; probes[pi]; pi++) {
        void *pc = FindClass(jniEnv, probes[pi]);
        printf("probe %s = %p\n", probes[pi], pc);
        if (!pc) { ExceptionDescribe(jniEnv); ExceptionClear(jniEnv); }
    }
    {
        typedef void* (*GetMID_t)(void*, void*, const char*, const char*);
        void *hr = FindClass(jniEnv, "com/mojang/android/net/HTTPResponse");
        if (hr) {
            void *mid = ((GetMID_t)envtab[33])(jniEnv, hr, "getStatus", "()I");
            printf("probe GetMethodID(HTTPResponse.getStatus)=%p\n", mid);
            if (!mid) { ExceptionDescribe(jniEnv); ExceptionClear(jniEnv); }
        } else { printf("probe HTTPResponse null\n"); ExceptionDescribe(jniEnv); ExceptionClear(jniEnv); }
        void *hdr = FindClass(jniEnv, "org/apache/http/Header");
        printf("probe org/apache/http/Header = %p\n", hdr);
        if (!hdr) { ExceptionDescribe(jniEnv); ExceptionClear(jniEnv); }
    }
    if (getenv("HELLO_DIRECT")) {
        printf("DIRECT mode: JNI_OnLoad from main thread\n");
        void *gh = dlopen(gameLib, RTLD_NOW | RTLD_GLOBAL);
        printf("dlopen game = %p\n", gh);
        if (!gh) printf("dlerror: %s\n", dlerror());
        if (gh) {
            int (*onload)(void*, void*) = (int(*)(void*, void*))dlsym(gh, "JNI_OnLoad");
            printf("JNI_OnLoad = %p\n", (void*)onload);
            if (onload) { int r = onload(jvm, NULL); printf("JNI_OnLoad returned: 0x%x\n", r); }
            if (ExceptionOccurred(jniEnv)) { ExceptionDescribe(jniEnv); ExceptionClear(jniEnv); }
        }
        return 0;
    }
    void *arr = ((NewObjArr_t)envtab[172])(jniEnv, 0, strc, NULL);
    jvalue_t args[1];
    args[0].l = arr;
    printf("Calling MinecraftLauncher.main...\n");
    ((CallStaticVoidA_t)envtab[143])(jniEnv, lcls, mmid, args);
    if (ExceptionOccurred(jniEnv)) { printf("Java exception thrown\n"); ExceptionDescribe(jniEnv); ExceptionClear(jniEnv); return 5; }
    printf("MinecraftLauncher.main returned\n");
    return 0;
}
