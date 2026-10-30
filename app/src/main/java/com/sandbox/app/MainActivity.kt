package com.sandbox.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private val appList = mutableListOf<SandboxApp>()

    // ইন্টারনাল স্টোরেজ পাথ
    private val baseDir = File(Environment.getExternalStorageDirectory(), "Sandbox 32")
    private val apksDir = File(baseDir, "apks")
    private val dataDir = File(baseDir, "data")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ফোল্ডার তৈরি করা (যদি না থাকে)
        if (!apksDir.exists()) apksDir.mkdirs()
        if (!dataDir.exists()) dataDir.mkdirs()

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        adapter = AppAdapter(appList) { app ->
            if (app.isInstalled) {
                Toast.makeText(this, "Launching ${app.name} in Sandbox...", Toast.LENGTH_SHORT).show()
            } else {
                // যদি ইনস্টল না হয়ে থাকে, তবে ইনস্টল ডায়ালগ দেখাবে
                showInstallDialog(app)
            }
        }
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabRefresh).setOnClickListener {
            if (checkPermission()) {
                scanDirectories()
            } else {
                requestPermission()
            }
        }

        if (checkPermission()) {
            scanDirectories()
        } else {
            requestPermission()
        }
    }

    // ১. পারমিশন চেক ও রিকোয়েস্ট
    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
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
            if (checkPermission()) {
                scanDirectories()
            } else {
                Toast.makeText(this, "Permission Denied. Cannot read APKs.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ২. ফোল্ডার স্ক্যান করা
    private fun scanDirectories() {
        appList.clear()

        // 'apks' ফোল্ডার থেকে ফাইলগুলো খোঁজা
        val apkFiles = apksDir.listFiles { file -> file.extension.equals("apk", true) }
        apkFiles?.forEach { file ->
            val info = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            if (info != null) {
                val appName = info.applicationInfo?.loadLabel(packageManager)?.toString() ?: file.name
                val pkgName = info.packageName
                val version = info.versionName ?: "Unknown"
                
                // ইনস্টল করা আছে কিনা চেক করা (আমাদের internal files/apps ফোল্ডারে)
                val installedApkFile = File(filesDir, "apps/$pkgName/base.apk")
                val isInstalled = installedApkFile.exists()
                
                // আইকন লোড করা (মেমোরিতে)
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

                appList.add(
                    SandboxApp(
                        name = appName,
                        packageName = pkgName,
                        versionName = version,
                        apkPath = file.absolutePath,
                        iconPath = iconFile.absolutePath,
                        installDate = date,
                        sandboxDataFolder = File(dataDir, pkgName).absolutePath,
                        isInstalled = isInstalled
                    )
                )
            }
        }
        adapter.notifyDataSetChanged()
        
        if (appList.isEmpty()) {
            Toast.makeText(this, "No APK found in ${apksDir.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    // ৩. ইনস্টল ডায়ালগ ও প্রসেস
    private fun showInstallDialog(app: SandboxApp) {
        AlertDialog.Builder(this)
            .setTitle("Install to Sandbox?")
            .setMessage("This will extract ${app.name} and create data folder.")
            .setPositiveButton("Install") { _, _ ->
                installAppToSandbox(app)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installAppToSandbox(app: SandboxApp) {
        try {
            val srcApk = File(app.apkPath)
            val destDir = File(filesDir, "apps/${app.packageName}").apply { mkdirs() }
            val destApk = File(destDir, "base.apk")

            // APK কপি করা ইন্টারনাল স্টোরেজ থেকে অ্যাপের প্রাইভেট ফোল্ডারে
            srcApk.copyTo(destApk, overwrite = true)

            // ডেটা ফোল্ডার তৈরি করা (External Storage-এ)
            val appDataDir = File(dataDir, app.packageName)
            if (!appDataDir.exists()) appDataDir.mkdirs()

            Toast.makeText(this, "${app.name} Installed to Sandbox!", Toast.LENGTH_SHORT).show()
            scanDirectories() // লিস্ট রিফ্রেশ করা

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
