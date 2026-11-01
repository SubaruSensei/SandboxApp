# Milestone: libminecraftpe.so JNI_OnLoad returns 0x10004 (JNI_VERSION_1_4)

## Works
- hello32 v4 (tools/hello32.c) boots ART via JNI_CreateJavaVM on a 64-bit-only device.
- Boot classpath: core jars + Launcher.dex + game classes.dex + framework/org.apache.http.legacy.jar + framework/framework.jar.
- HELLO_DIRECT=1 calls the game's JNI_OnLoad from the main thread, returns 0x10004.
- MainActivity needs framework.jar (NativeActivity superclass).
- Working config: tools/dev_config.milestone.properties (copy to /sdcard/Sandbox 32/dev_config.properties).

## Not working / unexplained
- System.load via MinecraftLauncher.main: abort in GetMethodID inside initHTTPRequestAndroid (cause unknown).
- app_process32 mode: exit code 1 (cause unconfirmed).

## Blocker
- No 32-bit GPU driver: /vendor/lib/egl missing, only /vendor/lib64/egl (adreno).
- Rendering needs GL forwarding to 64-bit side or software GL.

## Game entry points
- Exports ANativeActivity_onCreate, android_main, JNI_OnLoad.
- Imports AAssetManager_*, ALooper_*, AInputQueue_*, ANativeWindow_setBuffersGeometry.
- Java side needs a real MainActivity/Context (getAssets, getFileDataBytes).

## Dev workflow
- Developer Settings in app; overlay folder /sdcard/Sandbox 32/overlay; output in /sdcard/Sandbox 32/last_output.txt.
