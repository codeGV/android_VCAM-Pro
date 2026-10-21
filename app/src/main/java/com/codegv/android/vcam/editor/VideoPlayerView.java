package com.codegv.android.vcam.editor;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

/**
 * TextureView backed by a MediaPlayer. Rotation metadata is honoured by the
 * TextureView transform, so the parent only has to give it the display aspect.
 */
public class VideoPlayerView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "VCAM-Player";

    public interface Listener {
        void onPrepared(int durationMs);

        void onCompletion();

        void onError();
    }

    private MediaPlayer player;
    private Surface surface;
    private Uri source;
    private boolean prepared;
    private boolean playWhenReady;
    private boolean muted;
    private boolean looping;
    private int pendingSeekMs = -1;
    private Listener listener;

    public VideoPlayerView(Context context) {
        super(context);
        init();
    }

    public VideoPlayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setSurfaceTextureListener(this);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (player != null) {
            float v = muted ? 0f : 1f;
            player.setVolume(v, v);
        }
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
        if (player != null) {
            player.setLooping(looping);
        }
    }

    public void setVideoUri(Uri uri) {
        releasePlayer();
        source = uri;
        openIfReady();
    }

    public void play() {
        playWhenReady = true;
        if (prepared) {
            player.start();
        }
    }

    public void pause() {
        playWhenReady = false;
        if (prepared && player.isPlaying()) {
            player.pause();
        }
    }

    public boolean isPlaying() {
        return prepared && player.isPlaying();
    }

    public int getCurrentPosition() {
        return prepared ? player.getCurrentPosition() : 0;
    }

    /** Frame-accurate seek where the platform supports it. */
    public void seekTo(int ms) {
        if (!prepared) {
            pendingSeekMs = ms;
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        } else {
            player.seekTo(ms);
        }
    }

    public void release() {
        source = null;
        releasePlayer();
    }

    private void openIfReady() {
        if (source == null || surface == null || player != null) {
            return;
        }
        player = new MediaPlayer();
        try {
            player.setDataSource(getContext(), source);
            player.setSurface(surface);
            player.setLooping(looping);
            float v = muted ? 0f : 1f;
            player.setVolume(v, v);
            player.setOnPreparedListener(mp -> {
                prepared = true;
                if (pendingSeekMs >= 0) {
                    seekTo(pendingSeekMs);
                    pendingSeekMs = -1;
                } else {
                    // Render the first frame so a paused player is not black.
                    seekTo(0);
                }
                if (playWhenReady) {
                    mp.start();
                }
                if (listener != null) {
                    listener.onPrepared(mp.getDuration());
                }
            });
            player.setOnCompletionListener(mp -> {
                if (listener != null) {
                    listener.onCompletion();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "playback error " + what + "/" + extra);
                if (listener != null) {
                    listener.onError();
                }
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "cannot open " + source, e);
            releasePlayer();
            if (listener != null) {
                listener.onError();
            }
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = new Surface(st);
        openIfReady();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        int pos = getCurrentPosition();
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        pendingSeekMs = pos;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }
}
