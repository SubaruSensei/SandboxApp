package com.sandbox.app

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class DisplayTestActivity : AppCompatActivity(), SurfaceHolder.Callback {
    
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
        // C++ এর nativeInit ফাংশনকে ক্যানভাসটা দিয়ে দিচ্ছি
        nativeInit(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    // ক্যানভাস বন্ধ হলে বা অ্যাপ মিনিমাইজ হলে
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // C++ মেমোরি ক্লিনআপ করবে
        nativeDestroy()
    }

    // এই ৩টি ফাংশনের ডিক্লারেশন শুধু এখানে আছে, আসল কোড C++ (main.cpp) তে আছে
    private external fun nativeInit(surface: android.view.Surface)
    private external fun nativeRender()
    private external fun nativeDestroy()
}
