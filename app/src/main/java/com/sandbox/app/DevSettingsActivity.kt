package com.sandbox.app

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class DevSettingsActivity : AppCompatActivity() {
    private var collect: (() -> DevConfig)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = DevConfig.load()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        fun label(t: String): TextView {
            val tv = TextView(this)
            tv.text = t
            tv.textSize = 13f
            tv.setPadding(0, pad, 0, pad / 4)
            root.addView(tv)
            return tv
        }
        fun edit(v: String, lines: Int): EditText {
            val e = EditText(this)
            e.setText(v)
            e.minLines = lines
            e.gravity = Gravity.TOP or Gravity.START
            e.typeface = Typeface.MONOSPACE
            e.textSize = 12f
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            root.addView(e)
            return e
        }
        fun check(t: String, v: Boolean): CheckBox {
            val c = CheckBox(this)
            c.text = t
            c.isChecked = v
            root.addView(c)
            return c
        }
        fun button(t: String, onClick: () -> Unit) {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { onClick() }
            root.addView(b)
        }

        val title = label("Developer Settings")
        title.textSize = 20f

        label("Launch mode")
        val rg = RadioGroup(this)
        val rbApp = RadioButton(this)
        rbApp.text = "app_process32 (Android-style start)"
        rbApp.id = View.generateViewId()
        val rbHello = RadioButton(this)
        rbHello.text = "hello32 (JNI_CreateJavaVM, runs MinecraftLauncher.main)"
        rbHello.id = View.generateViewId()
        rg.addView(rbApp)
        rg.addView(rbHello)
        root.addView(rg)
        if (cfg.mode == "hello32") rbHello.isChecked = true else rbApp.isChecked = true

        label("app_process32: VM args (one per line)")
        val eApp = edit(cfg.appProcessArgs, 3)
        label("Extra boot jars (relative to runtime32, one per line or ':' separated). Clear to remove framework.jar")
        val eBoot = edit(cfg.extraBootJars, 2)
        label("hello32: extra VM args (one per line, needs hello32 with extra-args support)")
        val eHello = edit(cfg.helloExtraArgs, 2)
        label("Extra env (KEY=VALUE per line)")
        val eEnv = edit(cfg.extraEnv, 3)
        label("Timeout (seconds)")
        val eTimeout = edit(cfg.timeoutSec.toString(), 1)
        val cShim = check("LD_PRELOAD libshim_abi.so (app_process32 mode)", cfg.useShim)
        val cSkip = check("Skip runtime32 re-copy unless APK was updated", cfg.skipRuntimeCopy)
        val cOver = check("Apply overlay folder on every launch", cfg.useOverlay)

        val build: () -> DevConfig = {
            val c = DevConfig()
            c.mode = if (rbHello.isChecked) "hello32" else "app_process32"
            c.appProcessArgs = eApp.text.toString()
            c.extraBootJars = eBoot.text.toString()
            c.helloExtraArgs = eHello.text.toString()
            c.extraEnv = eEnv.text.toString()
            c.useShim = cShim.isChecked
            c.skipRuntimeCopy = cSkip.isChecked
            c.useOverlay = cOver.isChecked
            c.timeoutSec = eTimeout.text.toString().trim().toIntOrNull() ?: 180
            c
        }
        collect = build

        button("Save & close") {
            try { build().save() } catch (e: Exception) { }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
        button("Show last output") {
            val f = DevConfig.lastOutputFile()
            val text = if (f.exists()) f.readText() else "No output yet"
            android.app.AlertDialog.Builder(this).setTitle("Last output").setMessage(text).setPositiveButton("OK", null).show()
        }
        button("Force runtime32 re-copy on next launch") {
            File(filesDir, "runtime32.stamp").delete()
            Toast.makeText(this, "Will re-copy runtime32", Toast.LENGTH_SHORT).show()
        }
        button("Reset to defaults") {
            collect = null
            try { DevConfig().save() } catch (e: Exception) { }
            recreate()
        }

        val info = label(
            "Config: " + DevConfig.configFile().absolutePath + "\n" +
            "Overlay: " + DevConfig.overlayDir().absolutePath + "\n" +
            "  hello32 -> replaces hello32 binary\n" +
            "  Launcher.dex -> replaces Launcher.dex\n" +
            "  runtime32/... -> merged into runtime32\n" +
            "Last output: " + DevConfig.lastOutputFile().absolutePath
        )
        info.textSize = 11f
    }

    override fun onPause() {
        super.onPause()
        try { collect?.invoke()?.save() } catch (e: Exception) { }
    }
}
