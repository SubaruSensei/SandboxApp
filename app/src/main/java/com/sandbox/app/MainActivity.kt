package com.sandbox.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnRun = findViewById<Button>(R.id.btnRun)
        val tvResult = findViewById<TextView>(R.id.tvResult)

        btnRun.setOnClickListener {
            tvResult.text = "Running 32-bit binary...\n"
            Thread {
                try {
                    val binFile = File(filesDir, "hello32")
                    if (!binFile.exists()) {
                        assets.open("hello32").use { input ->
                            FileOutputStream(binFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    binFile.setExecutable(true)

                    val cmd = arrayOf("/system/bin/linker", binFile.absolutePath)
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
