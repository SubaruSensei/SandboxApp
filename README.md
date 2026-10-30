# 🚀 Sandbox 32: 32-bit App Runtime for Android 14+ (64-bit Only)

**Sandbox 32** is an experimental, open-source Android application designed to run 32-bit native binaries and applications (APKs) on 64-bit Android devices (like Android 14, 15, and 16) that have completely dropped 32-bit (ARMv7) support.

This project was born out of a challenge: *Can we bring back 32-bit app compatibility without downgrading the OS or using heavy full-system emulators?* 

## 🧠 How It Works (The Architecture)

Instead of running a heavy Virtual Machine (like QEMU) or a full OS inside an app, Sandbox 32 uses a clever **Host API Bridging** technique:

1. **Extraction:** It extracts the 32-bit Android Runtime (ART) libraries (`libart.so`, `libnativehelper.so`, `libdexfile.so`, etc.) from an Android 14 firmware (where 32-bit was still present).
2. **The Bridge:** It uses a custom 64-bit Kotlin UI that spawns a 32-bit C executable (`hello32`).
3. **The Execution:** This C executable is run using the system's 32-bit linker (`/system/bin/linker`). It loads the extracted 32-bit ART libraries, initializes the JavaVM (`JNI_CreateJavaVM`), and bridges the system calls.
4. **Native Loading:** It extracts the `.so` files (like `libminecraftpe.so`) from the target APK and executes them inside this hybrid environment.

## ⚙️ Features

*   **APK Installer & Manager:** A clean UI to import APKs from internal storage into the sandbox environment.
*   **Multi-Version Support:** Install the same app (e.g., Minecraft) multiple times in different isolated folders.
*   **Native 32-bit Execution:** Runs ARMv7 ELF binaries directly on 64-bit kernels using Android's hidden `CONFIG_COMPAT`.
*   **32-bit ART Integration:** Successfully loads the 32-bit Java Virtual Machine from extracted Android 14 files.

## 🛠️ Current Status & Roadmap

- [x] Create APK Installer & Launcher UI.
- [x] Extract and run native 32-bit C code.
- [x] Load 32-bit `.so` files from inside target APKs.
- [x] Find and locate `JNI_OnLoad` and `android_main` entry points.
- [x] Extract 32-bit ART Runtime from Android 14 Firmware (`system.img` -> `apex`).
- [x] Integrate ART libraries to create `JavaVM`.
- [ ] **Currently Debugging:** Resolving missing ART dependencies (like `libartpalette.so`, `libnativeloader.so`).
- [ ] **Next Goal:** Fully boot Minecraft - Pocket Edition (v0.14.3) and render graphics.

## ⚠️ Disclaimer

*   This project is for **educational and research purposes only**. 
*   The extracted libraries (ART, Bionic libc) belong to their respective owners (Google, Qualcomm, Xiaomi). This repository does not distribute the firmware itself, only scripts to extract it.
*   This application requires **All Files Access** to read APKs and manage sandbox data.
*   Running apps this way is highly experimental and may crash. Do not use this for critical tasks.

## 🤝 Contributing

If you are a reverse engineer, Android security researcher, or just love a good challenge, feel free to fork this repository and help solve the missing dependencies!

---
*Made with ❤️ by SubaruSensei & AI Assistant.*
