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
    private var isRunning = false
    private var loadingOverlay: View? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private val appList = mutableListOf<SandboxApp>()

    private val baseDir = File(Environment.getExternalStorageDirectory(), "Sandbox 32")
    private val apksDir = File(baseDir, "apks")
    private val dataDir = File(baseDir, "data")

    
    private fun showLoading() {
        if (loadingOverlay != null) return
        val layout = findViewById<android.view.ViewGroup>(android.R.id.content)
        val overlay = android.widget.FrameLayout(this)
        overlay.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlay.setBackgroundColor(0xAA000000.toInt()) // অর্ধ-স্বচ্ছ কালো
        overlay.isClickable = true // ক্লিক ব্লক করবে
        overlay.isFocusable = true

        val spinner = android.widget.ProgressBar(this)
        val params = android.widget.FrameLayout.LayoutParams(150, 150)
        params.gravity = android.view.Gravity.CENTER
        spinner.layoutParams = params
        overlay.addView(spinner)

        layout.addView(overlay)
        loadingOverlay = overlay
    }

    private fun hideLoading() {
        loadingOverlay?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
        }
        loadingOverlay = null
        isRunning = false
    }

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
            popup.menu.add("Developer Settings")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Refresh List" -> { if (checkPermission()) scanDirectories() }
                    "Open APKs Folder" -> {
                        Toast.makeText(this, "Path: ${apksDir.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                    "Developer Settings" -> { startActivity(android.content.Intent(this, DevSettingsActivity::class.java)) }
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

    private fun overlayCopy(src: File, dst: File, log: StringBuilder) {
        if (!src.exists()) return
        if (src.isDirectory) {
            dst.mkdirs()
            val kids = src.listFiles()
            if (kids != null) for (k in kids) overlayCopy(k, File(dst, k.name), log)
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, true)
            dst.setReadable(true, false)
            dst.setExecutable(true, false)
            log.append("[dev] overlay: ").append(src.absolutePath).append(" -> ").append(dst.name).append("\n")
        }
    }

    private fun runSandboxApp(app: SandboxApp) {
        if (isRunning) return
                isRunning = true
                runOnUiThread { showLoading() }
        Thread {
            val out = StringBuilder()
            var exitCode = -999
            var mode = "?"
            var cmdLine = ""
            try {
                val cfg = DevConfig.load()
                mode = cfg.mode
                val apkPath = File(filesDir, "apps/${app.packageName}/base.apk").absolutePath
                val extractDir = File(filesDir, "extracted/${app.packageName}").apply { mkdirs() }

                var mainLibPath: String? = null
                val zip = ZipFile(apkPath)
                for (entry in zip.entries()) {
                    if (entry.name == "classes.dex") {
                        zip.getInputStream(entry).use { i -> FileOutputStream(File(extractDir, "classes.dex")).use { o -> i.copyTo(o) } }
                    }
                    if (entry.name.startsWith("lib/armeabi-v7a/") && entry.name.endsWith(".so")) {
                        val targetFile = File(extractDir, File(entry.name).name)
                        zip.getInputStream(entry).use { input -> FileOutputStream(targetFile).use { output -> input.copyTo(output) } }
                        if (mainLibPath == null || targetFile.name.contains("minecraft")) mainLibPath = targetFile.absolutePath
                    }
                }
                zip.close()
                val gameLib: String = mainLibPath ?: run {
                    runOnUiThread { Toast.makeText(this, "No 32-bit .so found in APK", Toast.LENGTH_LONG).show() }
                    return@Thread
                }

                val runtimeDir = File(filesDir, "runtime32").apply { mkdirs() }
                val stamp = File(filesDir, "runtime32.stamp")
                val installTime = try { packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString() } catch (e: Exception) { "0" }
                if (!cfg.skipRuntimeCopy || !stamp.exists() || stamp.readText() != installTime) {
                    copyAssetFolder(assets, "runtime32", runtimeDir.absolutePath)
                    stamp.writeText(installTime)
                    out.append("[dev] runtime32 copied from assets\n")
                } else {
                    out.append("[dev] runtime32 copy skipped (unchanged)\n")
                }

                val launcherFile = File(extractDir, "Launcher.dex")
                assets.open("Launcher.dex").use { i -> FileOutputStream(launcherFile).use { o -> i.copyTo(o) } }
                val binFile = File(filesDir, "hello32")
                binFile.delete()
                assets.open("hello32").use { i -> FileOutputStream(binFile).use { o -> i.copyTo(o) } }

                if (cfg.useOverlay) {
                    val ov = DevConfig.overlayDir()
                    overlayCopy(File(ov, "hello32"), binFile, out)
                    overlayCopy(File(ov, "Launcher.dex"), launcherFile, out)
                    overlayCopy(File(ov, "runtime32"), runtimeDir, out)
                }
                binFile.setExecutable(true)

                val writableDataDir = File(filesDir, "app_data").apply { mkdirs() }
                File(writableDataDir, "launcher_log.txt").delete()
                val dexCp = launcherFile.absolutePath + ":" + File(extractDir, "classes.dex").absolutePath

                val cmd = ArrayList<String>()
                cmd.add("/system/bin/linker")
                if (cfg.mode == "hello32") {
                    cmd.add(binFile.absolutePath)
                    cmd.add(runtimeDir.absolutePath)
                    cmd.add(gameLib)
                    cmd.add(writableDataDir.absolutePath)
                    cmd.add(dexCp)
                    for (a in DevConfig.splitLines(cfg.helloExtraArgs)) cmd.add(a)
                } else {
                    cmd.add(File(runtimeDir, "app_process32").absolutePath)
                    cmd.add("-Djava.class.path=$dexCp")
                    val boot = ArrayList<String>()
                    for (j in listOf("javalib/core-oj.jar", "javalib/core-libart.jar", "javalib/core-icu4j.jar")) {
                        boot.add(File(runtimeDir, j).absolutePath)
                    }
                    for (j in cfg.extraBootJars.split(":", "\n").map { it.trim() }.filter { it.isNotEmpty() }) {
                        boot.add(if (j.startsWith("/")) j else File(runtimeDir, j).absolutePath)
                    }
                    cmd.add("-Xbootclasspath:" + boot.joinToString(":"))
                    for (a in DevConfig.splitLines(cfg.appProcessArgs)) cmd.add(a)
                    cmd.add("/system/bin")
                    cmd.add("com.sandbox.app.MinecraftLauncher")
                }
                cmdLine = cmd.joinToString(" ")

                val pb = ProcessBuilder(cmd)
                pb.environment()["ANDROID_ROOT"] = "/system"
                pb.environment()["ANDROID_DATA"] = writableDataDir.absolutePath
                pb.environment()["CLASSPATH"] = dexCp
                if (cfg.mode == "hello32") {
                    pb.environment()["LD_LIBRARY_PATH"] = runtimeDir.absolutePath + ":" + extractDir.absolutePath
                } else {
                    pb.environment()["LD_LIBRARY_PATH"] = runtimeDir.absolutePath + ":" + extractDir.absolutePath
                    if (cfg.useShim) pb.environment()["LD_PRELOAD"] = File(runtimeDir, "libshim_abi.so").absolutePath
                }
                for (line in DevConfig.splitLines(cfg.extraEnv)) {
                    val idx = line.indexOf('=')
                    if (idx > 0) pb.environment()[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }

                val process = pb.redirectErrorStream(true).start()
                val buf = StringBuffer()
                val reader = Thread {
                    try { process.inputStream.bufferedReader().forEachLine { buf.append(it).append("\n") } } catch (e: Exception) { }
                }
                reader.start()
                val finished = process.waitFor(cfg.timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor()
                    buf.append("\n[dev] TIMEOUT after ").append(cfg.timeoutSec).append("s, process killed\n")
                }
                reader.join(2000)
                exitCode = process.exitValue()
                out.append(buf.toString())

                val ll = File(writableDataDir, "launcher_log.txt")
                if (ll.exists()) out.append("\n--- launcher_log.txt ---\n").append(ll.readText())
            } catch (e: Exception) {
                out.append("\n[dev] EXCEPTION: ").append(e.toString()).append("\n")
            }

            val dialogText = "Mode: $mode\nExit Code: $exitCode\n\n$out"
            try {
                val f = DevConfig.lastOutputFile()
                f.parentFile?.mkdirs()
                f.writeText(dialogText + "\n--- cmd ---\n" + cmdLine + "\n")
            } catch (e: Exception) { }
            runOnUiThread {
                hideLoading()
                AlertDialog.Builder(this)
                    .setTitle("Sandbox Output")
                    .setMessage(dialogText)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy") { _, _ ->
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("sandbox", dialogText))
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }.start()
    }
}
