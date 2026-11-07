#include <jni.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <cstring>
#include <cstdarg>
#include <cstdio>
#include <cerrno>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>

static int g_initialized = 0;
static EGLDisplay display = EGL_NO_DISPLAY;
static EGLSurface surface = EGL_NO_SURFACE;
static EGLContext context = EGL_NO_CONTEXT;
static int g_egl_initialized = 0;
static ANativeWindow* g_win = NULL;
static int width = 0, height = 0;
static GLuint prog, vao, vbo, tex;
/* B-2: mmap IPC reader */
#define IPC_LOG_TAG "IPC64"
#define IPC_SIZE (1024*1024*64)
#define RING_MAGIC 0x52494E47U
#define IPC_LOG(...) __android_log_print(ANDROID_LOG_INFO, IPC_LOG_TAG, __VA_ARGS__)

static volatile unsigned int *g_ipc = NULL;
static unsigned int g_ipc_last_seq = 0;

#define MAX_OBJS 512
static unsigned int g_shader_map[MAX_OBJS];
static unsigned int g_program_map[MAX_OBJS];
static unsigned int g_buffer_map[MAX_OBJS];
static unsigned int g_texture_map[MAX_OBJS];
static unsigned int g_cur_buffer[16] = {0};
static unsigned int g_cur_texture[32] = {0};
static unsigned int g_active_tex_unit = 0;
static unsigned int g_cur_program = 0;
static FILE* g_evlog = NULL;

static void evlog(const char *fmt, ...) {
    if (!g_evlog) {
        g_evlog = fopen("/data/user/0/com.sandbox.app/files/ipc64_events.txt", "a");
    }
    if (!g_evlog) return;
    va_list ap; va_start(ap, fmt);
    vfprintf(g_evlog, fmt, ap);
    va_end(ap);
    fflush(g_evlog);
}


static void ipc_status_write(const char *s) {
    int fd = open("/data/user/0/com.sandbox.app/files/ipc64_status.txt",
                  O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd >= 0) { write(fd, s, strlen(s)); close(fd); }
}

static void ipc_reader_init(void) {
    if (g_ipc) return;
    int fd = open("/data/user/0/com.sandbox.app/files/glipc.bin", O_RDWR);
    if (fd < 0) { IPC_LOG("reader open failed errno=%d", errno); ipc_status_write("ERR: open failed\n"); return; }
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < IPC_SIZE) {
        IPC_LOG("reader fstat failed or size %ld < %d", (long)st.st_size, IPC_SIZE);
        ipc_status_write("ERR: file too small\n");
        close(fd); return;
    }
    void *p = mmap(NULL, IPC_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) { IPC_LOG("reader mmap failed errno=%d", errno); ipc_status_write("ERR: mmap failed\n"); return; }
    g_ipc = (volatile unsigned int *)p;
    if (g_ipc[0] != RING_MAGIC) {
        IPC_LOG("reader bad magic 0x%x (expected 0x%x)", g_ipc[0], RING_MAGIC);
        ipc_status_write("ERR: bad magic\n");
        munmap(p, IPC_SIZE); g_ipc = NULL; return;
    }
    g_ipc[2] = 16;  /* C-1c: force replay from start */
    IPC_LOG("reader mmap OK head=%u tail_reset=16", g_ipc[1]);
    ipc_status_write("reader init OK (replay)\n");
    evlog("=== READER INIT head=%u ===\n", g_ipc[1]);
}

static unsigned int g_default_vao = 0;
static unsigned int g_arr = 0, g_elm = 0, g_uni = 0, g_ppack = 0, g_punpack = 0, g_copy = 0, g_trans = 0;
static unsigned int* cur_buf_slot(unsigned int target) {
    switch (target) {
        case 0x8892: return &g_arr;
        case 0x8893: return &g_elm;
        case 0x8A11: return &g_uni;
        case 0x88EB: return &g_ppack;
        case 0x88EC: return &g_punpack;
        case 0x8F36: return &g_copy;
        case 0x8F37: return &g_trans;
        default: return 0;
    }
}

static void ipc_reader_poll(void) {
    if (!g_ipc) return;
    unsigned int head = __atomic_load_n(&g_ipc[1], __ATOMIC_ACQUIRE);
    unsigned int tail = g_ipc[2];
    int processed = 0;
    while (tail + 16 <= head && tail + 16 <= IPC_SIZE) {
        unsigned char *p = (unsigned char*)g_ipc + tail;
        unsigned int type = ((unsigned int*)p)[0];
        unsigned int id   = ((unsigned int*)p)[1];
        unsigned int len  = ((unsigned int*)p)[2];
        unsigned int total = 16 + ((len + 3) & ~3U);
        if (tail + total > head) break;
        switch (type) {
            case 1: {
                unsigned int gl_type = *(unsigned int*)(p+16);
                unsigned int real = glCreateShader(gl_type);
                if (id < MAX_OBJS) g_shader_map[id] = real;
                evlog("[CREATE_SHADER] id=%u type=%u real=%u\n", id, gl_type, real);
                break;
            }
            case 2: {
                unsigned int real = (id < MAX_OBJS) ? g_shader_map[id] : 0;
                if (real) {
                    const char *srcp = (const char*)(p+16);
                    int srclen = (int)len;
                    glShaderSource(real, 1, &srcp, &srclen);
                }
                evlog("[SOURCE] id=%u real=%u len=%u\n", id, real, len);
                break;
            }
            case 3: {
                unsigned int real = (id < MAX_OBJS) ? g_shader_map[id] : 0;
                if (real) {
                    glCompileShader(real);
                    GLint st = 0;
                    glGetShaderiv(real, GL_COMPILE_STATUS, &st);
                    if (!st) {
                        char glog[512]; GLsizei n = 0;
                        glGetShaderInfoLog(real, sizeof(glog), &n, glog);
                        for (int i=0; glog[i]; i++) if (glog[i]=='\n'||glog[i]=='\r') glog[i]=' ';
                        evlog("[COMPILE_SHADER FAIL] id=%u real=%u err=%.400s\n", id, real, glog);
                    } else {
                        evlog("[COMPILE_SHADER OK] id=%u real=%u\n", id, real);
                    }
                }
                break;
            }
            case 4: {
                unsigned int real = glCreateProgram();
                if (id < MAX_OBJS) g_program_map[id] = real;
                evlog("[CREATE_PROGRAM] id=%u real=%u\n", id, real);
                break;
            }
            case 5: {
                unsigned int shid = *(unsigned int*)(p+16);
                unsigned int rp = (id < MAX_OBJS) ? g_program_map[id] : 0;
                unsigned int rs = (shid < MAX_OBJS) ? g_shader_map[shid] : 0;
                if (rp && rs) glAttachShader(rp, rs);
                evlog("[ATTACH] prog=%u rp=%u shader=%u rs=%u\n", id, rp, shid, rs);
                break;
            }
            case 6: {
                unsigned int rp = (id < MAX_OBJS) ? g_program_map[id] : 0;
                if (rp) {
                    glLinkProgram(rp);
                    GLint st = 0;
                    glGetProgramiv(rp, GL_LINK_STATUS, &st);
                    if (!st) {
                        char plog[512]; GLsizei n = 0;
                        glGetProgramInfoLog(rp, sizeof(plog), &n, plog);
                        for (int i=0; plog[i]; i++) if (plog[i]=='\n'||plog[i]=='\r') plog[i]=' ';
                        evlog("[LINK_PROGRAM FAIL] id=%u real=%u err=%.400s\n", id, rp, plog);
                    } else {
                        evlog("[LINK_PROGRAM OK] id=%u real=%u\n", id, rp);
                    }
                }
                break;
            }
            case 7: {
                unsigned int count = len / 4;
                for (unsigned int i = 0; i < count && i < MAX_OBJS; i++) {
                    unsigned int fake_id = ((unsigned int*)(p+16))[i];
                    unsigned int real;
                    glGenBuffers(1, &real);
                    if (fake_id < MAX_OBJS) g_buffer_map[fake_id] = real;
                    evlog("[GEN_BUFFER] fake=%u real=%u\n", fake_id, real);
                }
                break;
            }
            case 8: {
                unsigned int target = ((unsigned int*)(p+16))[0];
                unsigned int real = (id < MAX_OBJS) ? g_buffer_map[id] : 0;
                unsigned int* slot = cur_buf_slot(target);
                if (slot) *slot = real;
                glBindBuffer(target, real);
                break;
            }
            case 9: {
                unsigned int target = ((unsigned int*)(p+16))[0];
                unsigned int sz     = ((unsigned int*)(p+16))[1];
                unsigned int usage  = ((unsigned int*)(p+16))[2];
                unsigned int* slot = cur_buf_slot(target);
                if (slot && *slot) {
                    glBufferData(target, sz, p+28, usage);
                    evlog("[BUFFER_DATA] target=%x size=%u\n", target, sz);
                } else {
                    evlog("[BUFFER_DATA SKIP] target=%x sz=%u cur=%u\n", target, sz, slot ? *slot : 0xFFFFFFFF);
                }
                break;
            }
            case 10: {
                unsigned int count = len / 4;
                for (unsigned int i = 0; i < count && i < MAX_OBJS; i++) {
                    unsigned int fake_id = ((unsigned int*)(p+16))[i];
                    unsigned int real;
                    glGenTextures(1, &real);
                    if (fake_id < MAX_OBJS) g_texture_map[fake_id] = real;
                    evlog("[GEN_TEXTURE] fake=%u real=%u\n", fake_id, real);
                }
                break;
            }
            case 11: {
                unsigned int target = ((unsigned int*)(p+16))[0];
                unsigned int real = (id < MAX_OBJS) ? g_texture_map[id] : 0;
                if (g_active_tex_unit < 32) g_cur_texture[g_active_tex_unit] = real;
                glBindTexture(target, real);
                break;
            }
            case 12: {
                unsigned int target = ((unsigned int*)(p+16))[0];
                int level = (int)((unsigned int*)(p+16))[1];
                int ifmt  = (int)((unsigned int*)(p+16))[2];
                int w     = (int)((unsigned int*)(p+16))[3];
                int h     = (int)((unsigned int*)(p+16))[4];
                unsigned int fmt = ((unsigned int*)(p+16))[5];
                glTexImage2D(target, level, ifmt, w, h, 0, fmt, GL_UNSIGNED_BYTE, p+40);
                evlog("[TEX_IMAGE] %dx%d\n", w, h);
                break;
            }
            case 13: {
                unsigned int target = ((unsigned int*)(p+16))[0];
                unsigned int pname  = ((unsigned int*)(p+16))[1];
                int param = (int)((unsigned int*)(p+16))[2];
                glTexParameteri(target, pname, param);
                break;
            }
            case 14: {
                unsigned int index = ((unsigned int*)(p+16))[0];
                int sz   = (int)((unsigned int*)(p+16))[1];
                unsigned int type = ((unsigned int*)(p+16))[2];
                int stride = (int)((unsigned int*)(p+16))[3];
                void *ptr = (void*)(size_t)((unsigned int*)(p+16))[4];
                glVertexAttribPointer(index, sz, type, GL_FALSE, stride, ptr);
                glEnableVertexAttribArray(index);
                break;
            }
            case 15: {
                unsigned int target = ((unsigned int*)(p+16))[0];
                int level = (int)((unsigned int*)(p+16))[1];
                int xoff  = (int)((unsigned int*)(p+16))[2];
                int yoff  = (int)((unsigned int*)(p+16))[3];
                int w     = (int)((unsigned int*)(p+16))[4];
                int h     = (int)((unsigned int*)(p+16))[5];
                unsigned int fmt  = ((unsigned int*)(p+16))[6];
                unsigned int type = ((unsigned int*)(p+16))[7];
                glTexSubImage2D(target, level, xoff, yoff, w, h, fmt, type, p+48);
                evlog("[TEX_SUBIMAGE] %dx%d\n", w, h);
                break;
            }
            case 16: {
                unsigned int target = ((unsigned int*)(p+16))[0];
                unsigned int offset = ((unsigned int*)(p+16))[1];
                unsigned int sz     = ((unsigned int*)(p+16))[2];
                unsigned int* slot = cur_buf_slot(target);
                if (slot && *slot) {
                    glBufferSubData(target, offset, sz, p+28);
                    evlog("[BUFFER_SUBDATA] target=%x off=%u size=%u\n", target, offset, sz);
                }
                break;
            }
            case 17: {
                unsigned int real = (id < MAX_OBJS) ? g_program_map[id] : 0;
                if (real) glUseProgram(real);
                g_cur_program = real;
                break;
            }
            case 18: {
                glActiveTexture(id);
                g_active_tex_unit = (id >= 0x84C0 && id < 0x84E0) ? (id - 0x84C0) : 0;
                break;
            }
            case 19: {
                unsigned int mode   = ((unsigned int*)(p+16))[0];
                unsigned int count  = ((unsigned int*)(p+16))[1];
                unsigned int dtype  = ((unsigned int*)(p+16))[2];
                unsigned int offset = ((unsigned int*)(p+16))[3];
                if (g_cur_program) {
                    /* C-2b-2: MCPE 0.14.3 vertex layout — stride 24, pos/uv/color */
                    /* C-2b-3: Force WORLDVIEWPROJ=identity for pre-transformed vertices */
                    GLint wvpLoc = glGetUniformLocation(g_cur_program, "WORLDVIEWPROJ");
                    static int wvp_log = 0;
                    if (wvp_log < 3) { wvp_log++; evlog("[MVP] prog=%u loc=%d\n", g_cur_program, wvpLoc); }
                    if (wvpLoc >= 0) {
                        float ortho[16] = {
                            2.0f/width, 0, 0, 0,
                            0, -2.0f/height, 0, 0,
                            0, 0, 1.0f, 0,
                            -1.0f, 1.0f, 0, 1
                        };
                        glUniformMatrix4fv(wvpLoc, 1, GL_FALSE, ortho);
                    }
                    if (g_default_vao) glBindVertexArray(g_default_vao);
                    if (g_arr) {
                        glBindBuffer(0x8892, g_arr);
                        glEnableVertexAttribArray(0);
                        glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 28, (void*)0);
                        glEnableVertexAttribArray(1);
                        glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, GL_TRUE, 28, (void*)16);
                        glEnableVertexAttribArray(2);
                        glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, 28, (void*)20);
                    }
                    while (glGetError() != GL_NO_ERROR) {}  /* drain pre-existing errors */
                    glDrawElements(mode, count, dtype, (void*)(size_t)offset);
                    GLenum err = glGetError();
                    static int err_log = 0;
                    if (err != GL_NO_ERROR && err_log < 10) {
                        err_log++;
                        evlog("[DRAW ERR #%d] err=0x%x mode=%x count=%u prog=%u\n", err_log, err, mode, count, g_cur_program);
                    }
                    static int draw_log = 0;
                    if (draw_log < 20) { draw_log++; evlog("[DRAW] mode=%x count=%u off=%u\n", mode, count, offset); }
                }
                break;
            }
            case 20: {
                unsigned int mask = id;
                glClear(mask);
                static int cl_log = 0;
                if (cl_log < 3) { cl_log++; evlog("[CLEAR] mask=%x\n", mask); }
                break;
            }
            case 21: {
                float *rgba = (float*)(p+16);
                glClearColor(rgba[0], rgba[1], rgba[2], rgba[3]);
                static int cc_log = 0;
                if (cc_log < 3) { cc_log++; evlog("[CLEAR_COLOR] %.2f %.2f %.2f %.2f\n", rgba[0], rgba[1], rgba[2], rgba[3]); }
                break;
            }
            case 22: {
                unsigned int tgt = ((unsigned int*)(p+16))[0];
                unsigned int off = ((unsigned int*)(p+16))[1];
                unsigned int sz  = ((unsigned int*)(p+16))[2];
                unsigned int* slot = cur_buf_slot(tgt);
                if (slot && *slot) {
                    glBindBuffer(tgt, *slot);
                    glBufferSubData(tgt, off, sz, p+28);
                    static int mp_log = 0;
                    if (mp_log < 10) { mp_log++; evlog("[MAP_DATA] target=%x off=%u sz=%u\n", tgt, off, sz); }
                }
                break;
            }
            default:
                evlog("[UNKNOWN] type=%u id=%u len=%u\n", type, id, len);
                break;
        }
        tail += total;
        processed++;
    }
    if (processed > 0) {
        g_ipc[2] = tail;
        char buf[128];
        snprintf(buf, sizeof(buf), "tail=%u head=%u last_batch=%d\n", tail, head, processed);
        ipc_status_write(buf);
        evlog("=== BATCH %d events, tail=%u ===\n", processed, tail);
    }
}


static const char* VS = R"(#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTexCoord = aTexCoord;
})";

static const char* FS = R"(#version 300 es
precision mediump float;
in vec2 vTexCoord;
uniform sampler2D uTexture;
out vec4 fragColor;
void main() {
    fragColor = texture(uTexture, vTexCoord);
})";



static EGLConfig g_egl_cfg = nullptr;

static void reset_state_no_egl(void) {
    if (g_ipc) { munmap((void*)g_ipc, IPC_SIZE); g_ipc = NULL; }
    if (g_evlog) { fclose(g_evlog); g_evlog = NULL; }
    g_default_vao = 0;
    prog = vao = vbo = tex = 0;
    width = height = 0;
    surface = EGL_NO_SURFACE;
    g_win = NULL;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sandbox_app_DisplayTestActivity_nativeInit(JNIEnv* env, jobject thiz, jobject jsurface) {
    if (!jsurface) return;
    if (g_initialized) { /* log removed */ return; }
    g_initialized = 1;

    ANativeWindow* win = ANativeWindow_fromSurface(env, jsurface);
    if (!win) { /* log removed */ g_initialized = 0; return; }

    if (display == EGL_NO_DISPLAY) {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY) { ANativeWindow_release(win); g_initialized = 0; return; }
        if (!eglInitialize(display, nullptr, nullptr)) { display = EGL_NO_DISPLAY; ANativeWindow_release(win); g_initialized = 0; return; }
        
        const EGLint cfgAttrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        EGLint n = 0;
        if (!eglChooseConfig(display, cfgAttrs, &g_egl_cfg, 1, &n) || n == 0) { 
            display = EGL_NO_DISPLAY; ANativeWindow_release(win); g_initialized = 0; return; 
        }
    }

    if (context == EGL_NO_CONTEXT) {
        const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, g_egl_cfg, EGL_NO_CONTEXT, ctxAttrs);
        if (context == EGL_NO_CONTEXT) { ANativeWindow_release(win); g_initialized = 0; return; }
    }

    surface = eglCreateWindowSurface(display, g_egl_cfg, win, nullptr);
    ANativeWindow_release(win);
    
    if (surface == EGL_NO_SURFACE) {
        /* log removed */
        g_initialized = 0;
        return; /* CRITICAL FIX: Prevent eglMakeCurrent crash on NULL surface */
    }

    eglMakeCurrent(display, surface, surface, context);
    eglQuerySurface(display, surface, EGL_WIDTH, &width);
    eglQuerySurface(display, surface, EGL_HEIGHT, &height);
    glViewport(0, 0, width, height);

    GLuint vs = glCreateShader(GL_VERTEX_SHADER); glShaderSource(vs, 1, &VS, nullptr); glCompileShader(vs);
    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER); glShaderSource(fs, 1, &FS, nullptr); glCompileShader(fs);
    prog = glCreateProgram(); glAttachShader(prog, vs); glAttachShader(prog, fs); glLinkProgram(prog);

    float verts[] = {-1.0f, -1.0f, 0.0f, 1.0f, 1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f};
    glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    glGenBuffers(1, &vbo); glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 16, (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 16, (void*)8);

    static unsigned char pixels[256 * 256 * 4];
    for(int y=0; y<256; y++) { for(int x=0; x<256; x++) { int i = (y*256+x)*4; pixels[i]=x; pixels[i+1]=y; pixels[i+2]=128; pixels[i+3]=255; } }
    glGenTextures(1, &tex); glBindTexture(GL_TEXTURE_2D, tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 256, 256, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glGenVertexArrays(1, &g_default_vao);
    ipc_reader_init();
}








void update_texture_from_file() {
    FILE* f = fopen("/sdcard/Sandbox 32/overlay/framebuffer.rgba", "rb");
    if (f) {
        unsigned char* buf = (unsigned char*)malloc(width * height * 4);
        if (buf) {
            size_t read = fread(buf, 1, width * height * 4, f);
            if (read == width * height * 4) {
                glBindTexture(GL_TEXTURE_2D, tex);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            }
            free(buf);
        }
        fclose(f);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sandbox_app_DisplayTestActivity_nativeRender(JNIEnv* env, jobject thiz) {
    if (surface == EGL_NO_SURFACE) return;
    ipc_reader_poll();
    static int beacon_frames = 0;
    if (g_ipc && g_ipc[2] == 16 && beacon_frames < 180) {
        beacon_frames++;
        glClearColor(1.0f, 0.0f, 1.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        eglSwapBuffers(display, surface);
        return;
    }
    ipc_reader_poll();
    eglSwapBuffers(display, surface);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sandbox_app_DisplayTestActivity_nativeDestroy(JNIEnv* env, jobject thiz) {
    if (!g_initialized) return; /* Prevent double destroy from surfaceDestroyed and onDestroy */
    g_initialized = 0;
    
    if (g_ipc) { munmap((void*)g_ipc, IPC_SIZE); g_ipc = NULL; }
    if (g_evlog) { fclose(g_evlog); g_evlog = NULL; }
    g_default_vao = 0;
    prog = vao = vbo = tex = 0;
    width = height = 0;
}






