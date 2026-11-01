#include <stdio.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/mman.h>
#include <unistd.h>

typedef int (*JNI_CreateJavaVM_t)(void**, void**, void*);
typedef int (*JNI_OnLoad_t)(void*, void*);

typedef struct { const char *optionString; void *extraInfo; } JavaVMOption;
typedef struct { int version; int nOptions; JavaVMOption *options; unsigned char ignoreUnrecognized; } JavaVMInitArgs;

int main(int argc, char *argv[]) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    if (argc < 4) return 1;
    char *libDir = argv[1];
    char *gameLib = argv[2];
    char *dataDir = argv[3];

    printf("📁 Runtime Dir: %s\n", libDir);
    printf("🎮 Game Lib: %s\n", gameLib);
    printf("💾 Data Dir: %s\n", dataDir);

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
        if (!h) { printf("❌ Failed to load %s: %s\n", libs[i], dlerror()); return 1; }
        printf("✅ Loaded %s\n", libs[i]);
        if (strcmp(libs[i], "libart.so") == 0) artHandle = h; 
    }

    JNI_CreateJavaVM_t JNI_CreateJavaVM = (JNI_CreateJavaVM_t)dlsym(artHandle, "JNI_CreateJavaVM");

    printf("🚀 Attempting to create JavaVM...\n");

    char javalib_dir[512];
    snprintf(javalib_dir, sizeof(javalib_dir), "%s/javalib", libDir);

    char bootcp[2048]; 
    snprintf(bootcp, sizeof(bootcp), 
        "-Xbootclasspath:%s/core-oj.jar:%s/core-libart.jar:%s/core-icu4j.jar:%s/apache-xml.jar:%s/bouncycastle.jar:%s/okhttp.jar:%s/service-art.jar:%s/classes.jar:%s/framework.jar",
        javalib_dir, javalib_dir, javalib_dir, javalib_dir, javalib_dir, javalib_dir, javalib_dir, javalib_dir, libDir);

    JavaVMOption options[5];
    options[0].optionString = bootcp;
    options[1].optionString = "-Xnoimage-dex2oat";
    options[2].optionString = "-Xint";
    options[3].optionString = "-Xusejit:false";
    options[4].optionString = "-Xnorelocate";

    JavaVMInitArgs vm_args;
    vm_args.version = 0x00010006;
    vm_args.nOptions = 5;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = 1; 

    void *jvm = NULL;
    void *jniEnv = NULL;
    int ret = JNI_CreateJavaVM(&jvm, &jniEnv, &vm_args);

    if (ret != 0) {
        printf("❌ JNI_CreateJavaVM failed with code: %d\n", ret);
        return 1;
    }

    printf("🎉 SUCCESS! JavaVM Created at %p\n", jvm);
    
    printf("\n🎮 Loading game lib: %s\n", gameLib);
    void *gameHandle = dlopen(gameLib, RTLD_NOW | RTLD_GLOBAL);
    if (!gameHandle) {
        printf("❌ Game dlopen failed: %s\n", dlerror());
        return 1;
    }
    printf("✅ Game library loaded successfully!\n");

    JNI_OnLoad_t JNI_OnLoad = (JNI_OnLoad_t)dlsym(gameHandle, "JNI_OnLoad");

    // === 🛠️ Runtime Patching ===
    if (JNI_OnLoad) {
        Dl_info info;
        if (dladdr((void*)JNI_OnLoad, &info) && info.dli_fbase) {
            unsigned char *base = (unsigned char *)info.dli_fbase;
            
            long page_size = sysconf(_SC_PAGESIZE);
            void *page_start = (void *)((uintptr_t)base & ~(page_size - 1));
            
            if (mprotect(page_start, page_size * 2, PROT_READ | PROT_WRITE | PROT_EXEC) == 0) {
                // ১. তিনটা init ফাংশন NOP করা
                unsigned int nop = 0xbf00bf00;
                memcpy(base + 0x2ddf90, &nop, 4);
                memcpy(base + 0x2ddf94, &nop, 4);
                memcpy(base + 0x2ddf98, &nop, 4);

                // ২. AppPlatform_android::init কলটা স্কিপ করে সরাসরি r7 = 0x00010006 সেট করা
                // 0x2ddf88: bl AppPlatform_android::init -> mov.w r7, #0x00010006 (4f f0 06 07)
                unsigned int patch_mov_r7 = 0x0706f04f;
                memcpy(base + 0x2ddf88, &patch_mov_r7, 4);

                // 0x2ddf8c: mov r7, r0 -> nop (00 bf)
                unsigned short nop_short = 0xbf00;
                memcpy(base + 0x2ddf8c, &nop_short, 2);

                printf("🔧 Patched JNI_OnLoad to return 0x00010006!\n");
            }
        }
    }
    // ==========================

    if (JNI_OnLoad) {
        printf("📞 Calling JNI_OnLoad...\n");
        int result = JNI_OnLoad(jvm, NULL);
        printf("✅ JNI_OnLoad returned: %d (0x%x)\n", result, result);
    } else {
        printf("❌ JNI_OnLoad not found in game library\n");
    }

    printf("\n🎉 ALL DONE! Game engine is initialized.\n");
    return 0;
}
