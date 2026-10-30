package com.sandbox.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etInput = findViewById<EditText>(R.id.etInput)
        val btnRun = findViewById<Button>(R.id.btnRun)
        val tvResult = findViewById<TextView>(R.id.tvResult)

        btnRun.setOnClickListener {
            tvResult.text = "Extracting and running 32-bit library...\n"
            
            Thread {
                try {
                    // বাইনারি এবং লাইব্রেরি ফাইল বের করা
                    val binFile = File(filesDir, "hello32")
                    val libFile = File(filesDir, "libmath32.so")

                    if (!binFile.exists()) {
                        assets.open("hello32").use { input ->
                            FileOutputStream(binFile).use { output -> input.copyTo(output) }
                        }
                    }
                    if (!libFile.exists()) {
                        assets.open("libmath32.so").use { input ->
                            FileOutputStream(libFile).use { output -> input.copyTo(output) }
                        }
                    }
                    binFile.setExecutable(true)
                    libFile.setExecutable(true)

                    // লাইব্রেরির অ্যাবসোলিউট পাথ আর্গুমেন্ট হিসেবে পাঠানো হচ্ছে
                    val cmd = arrayOf("/system/bin/linker", binFile.absolutePath, libFile.absolutePath)
                    val pb = ProcessBuilder(*cmd)
                    pb.environment().remove("LD_PRELOAD")
                    pb.redirectErrorStream(true)

                    val process = pb.start()
                    val output = process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()

                    runOnUiThread {
                        tvResult.text = "Exit Code: $exitCode\n\nOutput:\n$output"
                    }
                } catch (e: Exception) {
                    runOnUiThread { tvResult.text = "Error: ${e.message}" }
                }
            }.start()
        }
    }
}
