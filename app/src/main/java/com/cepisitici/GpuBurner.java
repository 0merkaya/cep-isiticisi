package com.cepisitici;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Runs a heavy fragment shader on an offscreen OpenGL ES surface and forces the
 * GPU to finish every frame, keeping the graphics unit at full load even while
 * the display is off.
 */
public class GpuBurner {

    private static final String TAG = "GpuBurner";
    private static final int SURFACE_SIZE = 256;
    private static final int DRAW_CALLS_PER_FRAME = 4;

    private static final String VERTEX_SHADER =
            "attribute vec2 aPos;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision highp float;\n" +
            "void main() {\n" +
            "    vec2 p = gl_FragCoord.xy * 0.015625;\n" +
            "    float v = 1.0;\n" +
            "    for (int i = 0; i < 256; i++) {\n" +
            "        v = sin(v * 3.1415927 + p.x * 0.013) * cos(v * 1.618034 + p.y * 0.017) + 1.0001;\n" +
            "        v = fract(v) + 0.1;\n" +
            "    }\n" +
            "    gl_FragColor = vec4(v, v * 0.5, v * 0.25, 1.0);\n" +
            "}\n";

    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean failed;
    private Thread thread;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    public void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::runGl, "gpu-burner");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean hasFailed() {
        return failed;
    }

    public void stop() {
        running = false;
        Thread current = thread;
        if (current == null) {
            return;
        }
        try {
            current.join(2500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (current.isAlive()) {
            current.interrupt();
        }
        thread = null;
    }

    private void runGl() {
        try {
            if (!initEgl()) {
                failed = true;
                return;
            }

            int program = buildProgram();
            if (program == 0) {
                failed = true;
                return;
            }

            GLES20.glUseProgram(program);
            int positionLocation = GLES20.glGetAttribLocation(program, "aPos");

            FloatBuffer vertices = ByteBuffer
                    .allocateDirect(6 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vertices.put(new float[]{-1f, -1f, 3f, -1f, -1f, 3f}).position(0);

            GLES20.glViewport(0, 0, SURFACE_SIZE, SURFACE_SIZE);

            while (running) {
                if (paused) {
                    Thread.sleep(120);
                    continue;
                }
                for (int i = 0; i < DRAW_CALLS_PER_FRAME && running && !paused; i++) {
                    GLES20.glClearColor(0f, 0f, 0f, 1f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    GLES20.glEnableVertexAttribArray(positionLocation);
                    GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT,
                            false, 0, vertices);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
                    GLES20.glFinish();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "GPU stress test stopped", t);
            failed = true;
        } finally {
            releaseEgl();
        }
    }

    private boolean initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            return false;
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            return false;
        }

        int[] configAttributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] configCount = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1,
                configCount, 0) || configCount[0] == 0) {
            return false;
        }

        int[] surfaceAttributes = {
                EGL14.EGL_WIDTH, SURFACE_SIZE,
                EGL14.EGL_HEIGHT, SURFACE_SIZE,
                EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttributes, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            return false;
        }

        int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                contextAttributes, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            return false;
        }

        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private int buildProgram() {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }
}
