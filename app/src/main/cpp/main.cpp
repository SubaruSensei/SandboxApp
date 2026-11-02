#include <jni.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Engine64", __VA_ARGS__)

// ১. গ্লোবাল ভেরিয়েবল (মেমোরিতে এদের জন্য জায়গা তৈরি হলো)
static EGLDisplay display = EGL_NO_DISPLAY;
static EGLSurface surface = EGL_NO_SURFACE;
static EGLContext context = EGL_NO_CONTEXT;
static int width = 0, height = 0;

static GLuint prog = 0, vao = 0, vbo = 0, ibo = 0;
static GLint uMVP = -1, uTime = -1;
static float time_var = 0.0f;

// ২. শেডার সোর্স (গ্রাফিক্স কার্ডকে কীভাবে আঁকতে হবে তার নির্দেশ)
static const char* VS = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aColor;
uniform mat4 uMVP;
uniform float uTime;
out vec3 vColor;
void main() {
    float s = sin(uTime), c = cos(uTime);
    mat3 rot = mat3(c,0,-s, 0,1,0, s,0,c);
    gl_Position = uMVP * vec4(rot * aPos, 1.0);
    vColor = aColor;
})";

static const char* FS = R"(#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 fragColor;
void main() { fragColor = vec4(vColor, 1.0); })";

// ৩. শেডার কম্পাইল করার হেল্পার ফাংশন
static GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    return s;
}

// ৪. জাভা থেকে কল আসলে এখানে ঢুকবে (JNI Bridge)
extern "C" JNIEXPORT void JNICALL
Java_com_sandbox_app_DisplayTestActivity_nativeInit(JNIEnv* env, jobject thiz, jobject jsurface) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, jsurface);
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, nullptr, nullptr);

    const EGLint cfgAttrs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig cfg; EGLint n = 0;
    eglChooseConfig(display, cfgAttrs, &cfg, 1, &n);

    const EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    context = eglCreateContext(display, cfg, EGL_NO_CONTEXT, ctxAttrs);
    surface = eglCreateWindowSurface(display, cfg, win, nullptr);
    eglMakeCurrent(display, surface, surface, context);

    eglQuerySurface(display, surface, EGL_WIDTH, &width);
    eglQuerySurface(display, surface, EGL_HEIGHT, &height);
    glViewport(0, 0, width, height);

    GLuint vs = compile(GL_VERTEX_SHADER, VS);
    GLuint fs = compile(GL_FRAGMENT_SHADER, FS);
    prog = glCreateProgram();
    glAttachShader(prog, vs); glAttachShader(prog, fs);
    glLinkProgram(prog);
    uMVP = glGetUniformLocation(prog, "uMVP");
    uTime = glGetUniformLocation(prog, "uTime");

    // ত্রিভুজের ডেটা (ম্যাথ: x, y, z এবং R, G, B)
    float verts[] = {
         0.0f,  0.6f, 0.0f,   1.0f, 0.2f, 0.2f, // ওপরের পয়েন্ট (লাল)
        -0.5f, -0.4f, 0.0f,   0.2f, 1.0f, 0.2f, // বামের পয়েন্ট (সবুজ)
         0.5f, -0.4f, 0.0f,   0.2f, 0.2f, 1.0f, // ডানের পয়েন্ট (নীল)
    };
    uint16_t idx[] = { 0, 1, 2 }; // কোন পয়েন্টগুলো জোড়া লাগবে

    glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    glGenBuffers(1, &vbo); glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glGenBuffers(1, &ibo); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(idx), idx, GL_STATIC_DRAW);
    
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 6*sizeof(float), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 6*sizeof(float), (void*)(3*sizeof(float)));
}

// ৫. প্রতি ফ্রেমে (সেকেন্ডে ৬০ বার) এই ফাংশনটা কল হবে
extern "C" JNIEXPORT void JNICALL
Java_com_sandbox_app_DisplayTestActivity_nativeRender(JNIEnv* env, jobject thiz) {
    if (surface == EGL_NO_SURFACE) return;
    time_var += 0.016f; // সময় বাড়ছে (রোটেশনের জন্য)
    
    glClearColor(0.08f, 0.08f, 0.12f, 1.0f); // ব্যাকগ্রাউন্ড কালার
    glClear(GL_COLOR_BUFFER_BIT);

    // ম্যাথ: অর্থোগ্রাফিক প্রোজেকশন ম্যাট্রিক্স (স্ক্রিনের মাপ ঠিক রাখার জন্য)
    float mvp[16] = { 
        2.0f/(float)width, 0, 0, 0, 
        0, -2.0f/(float)height, 0, 0, 
        0, 0, 1, 0, 
        0, 0, 0, 1 
    };

    glUseProgram(prog);
    glUniformMatrix4fv(uMVP, 1, GL_FALSE, mvp);
    glUniform1f(uTime, time_var);
    glBindVertexArray(vao);
    glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, nullptr);
    
    eglSwapBuffers(display, surface); // স্ক্রিনে দেখানো
}

// ৬. অ্যাপ বন্ধ হলে মেমোরি ক্লিনআপ
extern "C" JNIEXPORT void JNICALL
Java_com_sandbox_app_DisplayTestActivity_nativeDestroy(JNIEnv* env, jobject thiz) {
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        eglTerminate(display);
    }
    display = EGL_NO_DISPLAY; surface = EGL_NO_SURFACE; context = EGL_NO_CONTEXT;
}
