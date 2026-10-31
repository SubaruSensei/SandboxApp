package com.sandbox.app

import android.app.AlertDialog
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private val appList = mutableListOf<SandboxApp>()

    private val baseDir = File(Environment.getExternalStorageDirectory(), "Sandbox 32")
    private val apksDir = File(baseDir, "apks")
    private val dataDir = File(baseDir, "data")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!apksDir.exists()) apksDir.mkdirs()
        if (!dataDir.exists()) dataDir.mkdirs()

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        adapter = AppAdapter(appList) { app ->
            if (app.isInstalled) {
                runSandboxApp(app)
            } else {
                showInstallDialog(app)
            }
        }
        recyclerView.adapter = adapter

        // Top Bar Buttons
        findViewById<ImageButton>(R.id.btnPlus).setOnClickListener {
            if (checkPermission()) {
                scanDirectories()
                Toast.makeText(this, "Loaded APKs from Sandbox 32/apks", Toast.LENGTH_SHORT).show()
            } else {
                requestPermission()
            }
        }

        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("Refresh List")
            popup.menu.add("Open APKs Folder")
            popup.menu.add("About Sandbox 32")
            popup.menu.add("Display Settings")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Refresh List" -> { if (checkPermission()) scanDirectories() }
                    "Open APKs Folder" -> {
                        Toast.makeText(this, "Path: ${apksDir.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                    "Display Settings" -> { startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
                "About Sandbox 32" -> {
                        AlertDialog.Builder(this)
                            .setTitle("Sandbox 32")
                            .setMessage("A custom 32-bit runtime sandbox for Android 14+.\n\nCreated with ❤️ by SubaruSensei.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                true
            }
            popup.show()
        }

        findViewById<FloatingActionButton>(R.id.fabRefresh).setOnClickListener {
            if (checkPermission()) scanDirectories() else requestPermission()
        }

        if (checkPermission()) scanDirectories() else requestPermission()
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else { true }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivityForResult(intent, 2296)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, 2296)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2296) {
            if (checkPermission()) scanDirectories()
            else Toast.makeText(this, "Permission Denied!", Toast.LENGTH_LONG).show()
        }
    }

    private fun scanDirectories() {
        appList.clear()
        val apkFiles = apksDir.listFiles { file -> file.extension.equals("apk", true) }
        apkFiles?.forEach { file ->
            val info = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            if (info != null) {
                val appName = info.applicationInfo?.loadLabel(packageManager)?.toString() ?: file.name
                val pkgName = info.packageName
                val version = info.versionName ?: "Unknown"
                val installedApkFile = File(filesDir, "apps/$pkgName/base.apk")
                val isInstalled = installedApkFile.exists()
                
                info.applicationInfo?.sourceDir = file.absolutePath
                val icon = info.applicationInfo?.loadIcon(packageManager)
                val iconFile = File(cacheDir, "$pkgName.png")
                if (icon != null && !iconFile.exists()) {
                     FileOutputStream(iconFile).use { out ->
                        val bitmap = Bitmap.createBitmap(icon.intrinsicWidth, icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        icon.setBounds(0, 0, canvas.width, canvas.height)
                        icon.draw(canvas)
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }

                val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(file.lastModified()))

                appList.add(SandboxApp(appName, pkgName, version, file.absolutePath, iconFile.absolutePath, date, File(dataDir, pkgName).absolutePath, isInstalled))
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun showInstallDialog(app: SandboxApp) {
        AlertDialog.Builder(this)
            .setTitle("Install to Sandbox?")
            .setMessage("This will extract ${app.name} and create data folder.")
            .setPositiveButton("Install") { _, _ -> installAppToSandbox(app) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installAppToSandbox(app: SandboxApp) {
        try {
            val srcApk = File(app.apkPath)
            val destDir = File(filesDir, "apps/${app.packageName}").apply { mkdirs() }
            val destApk = File(destDir, "base.apk")
            srcApk.copyTo(destApk, overwrite = true)
            val appDataDir = File(dataDir, app.packageName)
            if (!appDataDir.exists()) appDataDir.mkdirs()
            Toast.makeText(this, "${app.name} Installed!", Toast.LENGTH_SHORT).show()
            scanDirectories()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyAssetFolder(assetManager: AssetManager, source: String, destination: String) {
        try {
            val files = assetManager.list(source)
            if (files == null || files.isEmpty()) {
                val destFile = File(destination)
                destFile.parentFile?.mkdirs()
                assetManager.open(source).use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
            } else {
                val destDir = File(destination)
                destDir.mkdirs()
                for (file in files) {
                    val src = "$source/$file"
                    val dest = "$destination/$file"
                    copyAssetFolder(assetManager, src, dest)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun runSandboxApp(app: SandboxApp) {
        Thread {
            try {
                val apkPath = File(filesDir, "apps/${app.packageName}/base.apk").absolutePath
                val extractDir = File(filesDir, "extracted/${app.packageName}").apply { mkdirs() }
                
                val zip = ZipFile(apkPath)
                var mainLib: String? = null
                for (entry in zip.entries()) {
                    if (entry.name == "classes.dex") { zip.getInputStream(entry).use { i -> FileOutputStream(File(extractDir, "classes.dex")).use { o -> i.copyTo(o) } } }
                    if (entry.name.startsWith("lib/armeabi-v7a/") && entry.name.endsWith(".so")) {
                        val targetFile = File(extractDir, File(entry.name).name)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                        }
                        if (mainLib == null || targetFile.name.contains("minecraft")) {
                            mainLib = targetFile.absolutePath
                        }
                    }
                }
                zip.close()

                if (mainLib == null) {
                    runOnUiThread { Toast.makeText(this, "No 32-bit .so found in APK", Toast.LENGTH_LONG).show() }
                    return@Thread
                }

                val runtimeDir = File(filesDir, "runtime32").apply { mkdirs() }
                copyAssetFolder(assets, "runtime32", runtimeDir.absolutePath)
                assets.open("Launcher.dex").use { i -> FileOutputStream(File(extractDir, "Launcher.dex")).use { o -> i.copyTo(o) } }

                val binFile = File(filesDir, "hello32")
                binFile.delete()
                if (!binFile.exists()) {
                    assets.open("hello32").use { input -> FileOutputStream(binFile).use { output -> input.copyTo(output) } }
                }
                binFile.setExecutable(true)

                val writableDataDir = File(filesDir, "app_data").apply { mkdirs() }
                
                val cmd = arrayOf(
                    "/system/bin/linker",
                    File(runtimeDir, "app_process32").absolutePath,
                    "-Djava.class.path=" + File(extractDir, "Launcher.dex").absolutePath + ":" + File(extractDir, "com.mojang.minecraftpe/classes.dex").absolutePath,
                    "-Xbootclasspath:" + File(runtimeDir, "javalib/core-oj.jar").absolutePath + ":" +
                            File(runtimeDir, "javalib/core-libart.jar").absolutePath + ":" +
                            File(runtimeDir, "javalib/core-icu4j.jar").absolutePath,
                    "-Xnoimage-dex2oat",
                    "-Xint",
                    "-Xusejit:false",
                    "/system/bin",
                    "com.sandbox.app.MinecraftLauncher"
                )
                val pb = ProcessBuilder(*cmd)
                pb.environment()["ANDROID_ROOT"] = "/system"
                pb.environment()["ANDROID_DATA"] = writableDataDir.absolutePath
                
                // LD_LIBRARY_PATH সেট করা
                val libPath = runtimeDir.absolutePath + ":" + File(extractDir, "com.mojang.minecraftpe").absolutePath
                pb.environment()["LD_LIBRARY_PATH"] = libPath
                
                // LD_PRELOAD সেট করা
                pb.environment()["LD_PRELOAD"] = File(runtimeDir, "libshim_abi.so").absolutePath
                
                // CLASSPATH সেট করা (Launcher.dex এবং Minecraft classes.dex)
                val launcherDex = File(extractDir, "Launcher.dex").absolutePath
                val minecraftDex = File(extractDir, "com.mojang.minecraftpe/classes.dex").absolutePath
                pb.environment()["CLASSPATH"] = "$launcherDex:$minecraftDex"
                
                val process = pb.redirectErrorStream(true).start()
                
                val reader = process.inputStream.bufferedReader()
                val outputBuilder = StringBuilder()
                var line: String? = reader.readLine()
                while (line != null) {
                    outputBuilder.append(line).append("\n")
                    line = reader.readLine()
                }
                
                val output = outputBuilder.toString()
                val exitCode = process.waitFor()

                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Sandbox Output")
                        .setMessage("Exit Code: $exitCode\n\n$output")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
