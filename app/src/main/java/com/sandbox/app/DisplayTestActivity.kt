package com.sandbox.app

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class DisplayTestActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "DisplayTestActivity"
        private const val FB_W = 720
        private const val FB_H = 1540
        init { System.loadLibrary("engine64") }
    }

    private external fun nativeInit(surface: Surface)
    private external fun nativeRender()
    private external fun nativeDestroy()

    private lateinit var surfaceView: SurfaceView
    @Volatile private var renderThread: Thread? = null
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        surfaceView = SurfaceView(this)
        surfaceView.holder.setFixedSize(FB_W, FB_H)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)
        WindowInsetsControllerCompat(window, surfaceView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        stopRenderThread()
        val surface = holder.surface
        running = true
        val t = Thread({
            try {
                nativeInit(surface)
                while (running) {
                    nativeRender()
                    try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "render thread error", e)
            } finally {
                try { nativeDestroy() } catch (e: Throwable) { Log.e(TAG, "nativeDestroy", e) }
            }
        }, "engine64-render")
        renderThread = t
        t.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) { stopRenderThread() }

    private fun stopRenderThread() {
        running = false
        val t = renderThread
        renderThread = null
        if (t != null) {
            t.interrupt()
            try { t.join(2000) } catch (_: InterruptedException) { }
        }
    }

    override fun onDestroy() {
        stopRenderThread()
        if (isFinishing) MainActivity.gameProcess?.destroyForcibly()
        super.onDestroy()
    }
}
