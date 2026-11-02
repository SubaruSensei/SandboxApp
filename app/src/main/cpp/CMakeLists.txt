cmake_minimum_required(VERSION 3.18)
project(engine64 CXX)

# আমাদের C++ ফাইল থেকে একটি ৬৪-বিট শেয়ার্ড লাইব্রেরি (.so) তৈরি করবে
add_library(engine64 SHARED main.cpp)
target_compile_features(engine64 PRIVATE cxx_std_17)

# অ্যান্ড্রয়েডের সিস্টেম লাইব্রেরিগুলো খোঁজা (Log, Android, EGL, OpenGL ES 3.0)
find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)

# লাইব্রেরিগুলোকে আমাদের ইঞ্জিনের সাথে লিংক করা
target_link_libraries(engine64 
    ${log-lib} 
    ${android-lib} 
    ${egl-lib} 
    ${gles-lib}
)
