package com.sandbox.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private val installedApps = mutableListOf<SandboxApp>()

    // ফাইল পিকার থেকে APK সিলেক্ট করার লঞ্চার
    private val getApkLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val apkUri = result.data!!.data
            if (apkUri != null) {
                installApk(apkUri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        adapter = AppAdapter(installedApps) { app ->
            // অ্যাপে ক্লিক করলে ৩২-বিট ইঞ্জিনে পাঠানোর টোস্ট
            Toast.makeText(this, "Launching ${app.name} in Sandbox...", Toast.LENGTH_SHORT).show()
            // এখানে ভবিষ্যতে আমাদের ৩২-বিট ইঞ্জিন কল হবে
        }
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabInstall).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/vnd.android.package-archive"
            getApkLauncher.launch(intent)
        }

        loadInstalledApps()
    }

    private fun installApk(uri: Uri) {
        try {
            val pm = packageManager
            val info = pm.getPackageArchiveInfo(uri.toString(), 0)
            if (info == null) {
                Toast.makeText(this, "Invalid APK", Toast.LENGTH_SHORT).show()
                return
            }

            val appName = info.applicationInfo?.loadLabel(pm)?.toString() ?: "Unknown"
            val packageName = info.packageName

            // স্যান্ডবক্স ফোল্ডার তৈরি
            val sandboxDir = File(filesDir, "apps/$packageName").apply { mkdirs() }
            val apkFile = File(sandboxDir, "base.apk")
            val iconFile = File(sandboxDir, "icon.png")

            // APK কপি করা
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            }

            // আইকন বের করা
            info.applicationInfo?.sourceDir = apkFile.absolutePath
            val icon = info.applicationInfo?.loadIcon(pm)
            if (icon != null) {
                FileOutputStream(iconFile).use { out ->
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        icon.intrinsicWidth, icon.intrinsicHeight, Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    icon.setBounds(0, 0, canvas.width, canvas.height)
                    icon.draw(canvas)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }

            val installDate = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
            val app = SandboxApp(appName, packageName, apkFile.absolutePath, iconFile.absolutePath, installDate)
            
            installedApps.add(app)
            saveInstalledApps()
            adapter.notifyDataSetChanged()

            Toast.makeText(this, "$appName Installed!", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadInstalledApps() {
        val jsonFile = File(filesDir, "installed_apps.json")
        if (jsonFile.exists()) {
            val json = JSONArray(jsonFile.readText())
            installedApps.clear()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                installedApps.add(
                    SandboxApp(
                        obj.getString("name"),
                        obj.getString("pkg"),
                        obj.getString("apk"),
                        obj.getString("icon"),
                        obj.getString("date")
                    )
                )
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun saveInstalledApps() {
        val jsonArray = JSONArray()
        for (app in installedApps) {
            val obj = JSONObject()
            obj.put("name", app.name)
            obj.put("pkg", app.packageName)
            obj.put("apk", app.apkPath)
            obj.put("icon", app.iconPath)
            obj.put("date", app.installDate)
            jsonArray.put(obj)
        }
        File(filesDir, "installed_apps.json").writeText(jsonArray.toString())
    }
}
