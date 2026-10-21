package com.codegv.android.vcam.editor;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

/**
 * EGL window surface wrapping an encoder input Surface (adapted from the AOSP
 * CTS ExtractDecodeEditEncodeMuxTest). Owns the GL context used for rendering.
 */
class CodecInputSurface {

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private Surface surface;

    CodecInputSurface(Surface surface) {
        if (surface == null) {
            throw new NullPointerException("surface");
        }
        this.surface = surface;

        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new RuntimeException("unable to initialize EGL14");
        }

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] == 0) {
            throw new RuntimeException("unable to find RGB888+recordable ES2 EGL config");
        }

        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        checkEglError("eglCreateContext");

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, surfaceAttribs, 0);
        checkEglError("eglCreateWindowSurface");
    }

    void makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    boolean swapBuffers() {
        return EGL14.eglSwapBuffers(display, eglSurface);
    }

    void setPresentationTime(long nsecs) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nsecs);
    }

    void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, eglSurface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        if (surface != null) {
            surface.release();
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
        surface = null;
    }

    private static void checkEglError(String msg) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(msg + ": EGL error 0x" + Integer.toHexString(error));
        }
    }
}
