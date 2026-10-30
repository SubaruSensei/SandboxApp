package com.sandbox.app

data class SandboxApp(
    val name: String,
    val packageName: String,
    val versionName: String,
    val apkPath: String,
    val iconPath: String,
    val installDate: String,
    val sandboxDataFolder: String, // কাস্টম ফোল্ডারের পাথ
    val isInstalled: Boolean = false
)
