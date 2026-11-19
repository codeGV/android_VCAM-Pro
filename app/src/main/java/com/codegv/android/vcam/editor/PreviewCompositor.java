package com.codegv.android.vcam.editor;

import android.graphics.RectF;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import de.robv.android.xposed.XposedBridge;

/**
 * Plays virtual.mp4 through GLES into the host app's own camera preview Surface.
 *
 * Feeding the video straight to the Surface stretches it into the buffer aspect the
 * app asked for, and the app then rotates the buffer for display. So we rotate the
 * frame 90 degrees and fill the buffer: a portrait video becomes a 16:9 landscape
 * frame that the app turns back into a full-size, undistorted vertical preview.
 */
public class PreviewCompositor {

    private final HandlerThread thread = new HandlerThread("vcam-preview");
    private final Surface appSurface;
    private final String path;
    private volatile boolean running;

    private CodecInputSurface egl;
    private DecoderOutputSurface video;
    private MediaPlayer player;

    public PreviewCompositor(Surface appSurface, String path) {
        this.appSurface = appSurface;
        this.path = path;
        thread.start();
        new Handler(thread.getLooper()).post(this::start);
    }

    private void start() {
        try {
            egl = new CodecInputSurface(appSurface, false);
            egl.makeCurrent();
            int[] buf = egl.getSurfaceSize();
            video = new DecoderOutputSurface(new RectF(0, 0, 1, 1), 90, false, new RectF(-1f, -1f, 1f, 1f));
            player = new MediaPlayer();
            player.setSurface(video.getSurface());
            player.setVolume(0, 0);
            player.setLooping(true);
            player.setDataSource(path);
            player.prepare();
            player.start();
            running = true;
            XposedBridge.log("[VCAM] [preview] start " + buf[0] + "x" + buf[1]);
            loop(buf[0], buf[1]);
        } catch (Throwable t) {
            XposedBridge.log("[VCAM] [preview] " + t);
            stop();
        }
    }

    private void loop(int w, int h) {
        while (running) {
            try {
                video.awaitNewImage();
                video.drawImage(w, h);
                egl.swapBuffers();
            } catch (Throwable t) {
                break;
            }
        }
    }

    /** Must be called from a thread other than the GL thread. */
    public void stop() {
        running = false;
        new Handler(thread.getLooper()).post(() -> {
            if (player != null) {
                try { player.release(); } catch (Throwable ignored) { }
                player = null;
            }
            if (video != null) {
                try { video.release(); } catch (Throwable ignored) { }
                video = null;
            }
            if (egl != null) {
                try { egl.release(); } catch (Throwable ignored) { }
                egl = null;
            }
        });
        thread.quitSafely();
    }
}
