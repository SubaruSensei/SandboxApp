package com.sandbox.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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

    // ৩. অ্যাপ চালু করা (Extract .so and run)
    private fun runSandboxApp(app: SandboxApp) {
        Thread {
            try {
                val apkPath = File(filesDir, "apps/${app.packageName}/base.apk").absolutePath
                val extractDir = File(filesDir, "extracted/${app.packageName}").apply { mkdirs() }
                
                // APK থেকে .so ফাইল বের করা
                val zip = ZipFile(apkPath)
                var mainLib: String? = null
                for (entry in zip.entries()) {
                    if (entry.name.startsWith("lib/armeabi-v7a/") && entry.name.endsWith(".so")) {
                        val targetFile = File(extractDir, File(entry.name).name)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
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

                // runtime32 অ্যাসেট থেকে ফোনে এক্সট্রাক্ট করা
                val runtimeDir = File(filesDir, "runtime32").apply { mkdirs() }
                val assetList = assets.list("runtime32") ?: emptyArray()
                for (asset in assetList) {
                    val target = File(runtimeDir, asset)
                    if (!target.exists()) {
                        assets.open("runtime32/$asset").use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output) }
                        }
                    }
                }

                // hello32 ইঞ্জিন প্রস্তুত করা
                val binFile = File(filesDir, "hello32")
                if (!binFile.exists()) {
                    assets.open("hello32").use { input -> FileOutputStream(binFile).use { output -> input.copyTo(output) } }
                }
                binFile.setExecutable(true)

                val writableDataDir = File(filesDir, "app_data").apply { mkdirs() }
                val cmd = arrayOf("/system/bin/linker", binFile.absolutePath, runtimeDir.absolutePath, mainLib, writableDataDir.absolutePath)
                val pb = ProcessBuilder(*cmd)
                pb.environment().remove("LD_PRELOAD")
                pb.environment()["LD_LIBRARY_PATH"] = runtimeDir.absolutePath + ":" + extractDir.absolutePath
                pb.redirectErrorStream(true)

                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText()
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
