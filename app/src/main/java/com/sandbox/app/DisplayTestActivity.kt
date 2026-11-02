package com.sandbox.app

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class DisplayTestActivity : AppCompatActivity(), SurfaceHolder.Callback {
    
    private lateinit var surfaceView: SurfaceView
    private var renderThread: Thread? = null
    @Volatile private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("engine64")
        
        // স্ক্রিন যেন চালু থাকে (বন্ধ হয়ে না যায়)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // একটি ফাঁকা ক্যানভাস (SurfaceView) তৈরি করছি
        surfaceView = SurfaceView(this)
        // ক্যানভাসের খবরাখবর জানার জন্য Callback যোগ করছি
        surfaceView.holder.addCallback(this)
        
        // স্ক্রিনে এই ক্যানভাসটা সেট করছি
        setContentView(surfaceView)
    }

    // ক্যানভাস তৈরি হলে এই ফাংশনটা একবার কল হয়
    override fun surfaceCreated(holder: SurfaceHolder) {
        nativeInit(holder.surface)
        
        // 🔄 RENDER LOOP: এখন আমরা C++ কে বারবার ছবি আঁকতে বলব
        isRunning = true
        renderThread = Thread {
            while (isRunning) {
                nativeRender()
                Thread.sleep(16) // 60 FPS (1000ms / 60)
            }
        }
        renderThread?.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    // ক্যানভাস বন্ধ হলে বা অ্যাপ মিনিমাইজ হলে
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        renderThread?.join() // থ্রেড বন্ধ হওয়া পর্যন্ত অপেক্ষা করবে
        nativeDestroy()
    }

    // এই ৩টি ফাংশনের ডিক্লারেশন শুধু এখানে আছে, আসল কোড C++ (main.cpp) তে আছে
    private external fun nativeInit(surface: android.view.Surface)
    private external fun nativeRender()
    private external fun nativeDestroy()
}
