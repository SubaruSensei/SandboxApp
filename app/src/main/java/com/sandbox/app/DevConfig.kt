package com.sandbox.app

import android.os.Environment
import java.io.File
import java.util.Properties

class DevConfig {
    var mode = "app_process32"
    var appProcessArgs = "-Xnoimage-dex2oat\n-Xint\n-Xusejit:false"
    var extraBootJars = "framework/framework.jar"
    var helloExtraArgs = ""
    var extraEnv = ""
    var useShim = true
    var skipRuntimeCopy = true
    var useOverlay = true
    var timeoutSec = 180

    fun save() {
        val p = Properties()
        p.setProperty("mode", mode)
        p.setProperty("appProcessArgs", appProcessArgs)
        p.setProperty("extraBootJars", extraBootJars)
        p.setProperty("helloExtraArgs", helloExtraArgs)
        p.setProperty("extraEnv", extraEnv)
        p.setProperty("useShim", useShim.toString())
        p.setProperty("skipRuntimeCopy", skipRuntimeCopy.toString())
        p.setProperty("useOverlay", useOverlay.toString())
        p.setProperty("timeoutSec", timeoutSec.toString())
        baseDir().mkdirs()
        configFile().writer(Charsets.UTF_8).use { p.store(it, "Sandbox 32 dev config") }
    }

    companion object {
        fun baseDir() = File(Environment.getExternalStorageDirectory(), "Sandbox 32")
        fun configFile() = File(baseDir(), "dev_config.properties")
        fun overlayDir() = File(baseDir(), "overlay")
        fun lastOutputFile() = File(baseDir(), "last_output.txt")

        fun splitLines(s: String): List<String> =
            s.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        fun load(): DevConfig {
            val c = DevConfig()
            try {
                val f = configFile()
                if (f.exists()) {
                    val p = Properties()
                    f.reader(Charsets.UTF_8).use { p.load(it) }
                    c.mode = p.getProperty("mode", c.mode)
                    c.appProcessArgs = p.getProperty("appProcessArgs", c.appProcessArgs)
                    c.extraBootJars = p.getProperty("extraBootJars", c.extraBootJars)
                    c.helloExtraArgs = p.getProperty("helloExtraArgs", c.helloExtraArgs)
                    c.extraEnv = p.getProperty("extraEnv", c.extraEnv)
                    c.useShim = p.getProperty("useShim", "true") == "true"
                    c.skipRuntimeCopy = p.getProperty("skipRuntimeCopy", "true") == "true"
                    c.useOverlay = p.getProperty("useOverlay", "true") == "true"
                    c.timeoutSec = p.getProperty("timeoutSec", "180").toIntOrNull() ?: 180
                }
            } catch (e: Exception) { }
            return c
        }
    }
}
