package com.codegv.android.vcam.editor;

import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * SurfaceTexture the decoder renders into, plus a GLES2 renderer that draws the
 * cropped and rotation-corrected frame onto the current EGL surface.
 * Must be created and used on the thread that owns the current EGL context.
 */
class DecoderOutputSurface implements SurfaceTexture.OnFrameAvailableListener {

    private static final String VERTEX_SHADER =
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private static final long FRAME_TIMEOUT_MS = 2500;

    private final SurfaceTexture surfaceTexture;
    private final Surface surface;
    private final HandlerThread callbackThread;
    private final Object frameSyncObject = new Object();
    private boolean frameAvailable;

    private final int program;
    private final int textureId;
    private final int aPositionLoc, aTextureCoordLoc, uSTMatrixLoc;
    private final float[] stMatrix = new float[16];
    private final FloatBuffer vertices;

    /**
     * @param crop     normalized crop of the displayed frame (top-left origin)
     * @param rotation clockwise rotation (0/90/180/270) needed to display the decoded frame upright
     * @param flip180  additionally turn the cropped output upside down
     */
    DecoderOutputSurface(RectF crop, int rotation, boolean flip180) {
        program = createProgram();
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord");
        uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("texture setup");

        // The transcode thread has no Looper, so deliver frame callbacks on a dedicated one.
        callbackThread = new HandlerThread("vcam-frame-callback");
        callbackThread.start();
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(this, new Handler(callbackThread.getLooper()));
        surface = new Surface(surfaceTexture);

        vertices = buildQuad(crop, rotation, flip180);
    }

    /**
     * Triangle strip: BL, BR, TL, TR of the output. Each vertex carries the source
     * texture coordinate that should land there after crop + rotation.
     */
    private static FloatBuffer buildQuad(RectF crop, int rotation, boolean flip180) {
        float[][] corners = {{0, 1}, {1, 1}, {0, 0}, {1, 0}}; // output (u, v), v down
        float[][] ndc = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
        float[] data = new float[4 * 4];
        for (int i = 0; i < 4; i++) {
            float cu = flip180 ? 1 - corners[i][0] : corners[i][0];
            float cv = flip180 ? 1 - corners[i][1] : corners[i][1];
            // Point in the displayed (rotated) frame, top-left origin.
            float u = crop.left + cu * crop.width();
            float v = crop.top + cv * crop.height();
            // Map back to the decoded frame, top-left origin.
            float s, t;
            switch (rotation) {
                case 90:
                    s = v;
                    t = 1 - u;
                    break;
                case 180:
                    s = 1 - u;
                    t = 1 - v;
                    break;
                case 270:
                    s = 1 - v;
                    t = u;
                    break;
                default:
                    s = u;
                    t = v;
            }
            data[i * 4] = ndc[i][0];
            data[i * 4 + 1] = ndc[i][1];
            data[i * 4 + 2] = s;
            data[i * 4 + 3] = 1 - t; // texture space has a bottom-left origin
        }
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    Surface getSurface() {
        return surface;
    }

    /** Waits for the decoder to deliver a frame and latches it into the texture. */
    void awaitNewImage() {
        synchronized (frameSyncObject) {
            long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
            while (!frameAvailable) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new RuntimeException("timed out waiting for decoded frame");
                }
                try {
                    frameSyncObject.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            frameAvailable = false;
        }
        surfaceTexture.updateTexImage();
    }

    void drawImage(int width, int height) {
        surfaceTexture.getTransformMatrix(stMatrix);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        vertices.position(0);
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(aPositionLoc);
        vertices.position(2);
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(aTextureCoordLoc);

        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");
    }

    void release() {
        surface.release();
        surfaceTexture.release();
        callbackThread.quitSafely();
        GLES20.glDeleteProgram(program);
        GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (frameSyncObject) {
            frameAvailable = true;
            frameSyncObject.notifyAll();
        }
    }

    private static int createProgram() {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("could not link program: " + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("could not compile shader " + type + ": " + log);
        }
        return shader;
    }

    private static void checkGlError(String op) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new RuntimeException(op + ": glError 0x" + Integer.toHexString(error));
        }
    }
}
