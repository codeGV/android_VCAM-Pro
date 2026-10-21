package com.codegv.android.vcam.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.codegv.android.vcam.R;

import java.io.File;
import java.util.Locale;

/**
 * Lets the user trim and crop a picked video, then renders the result into the
 * shared virtual.mp4 used by the hook.
 */
public class VideoEditorActivity extends Activity {

    private static final String TAG = "VCAM-Editor";
    private static final int THUMB_COUNT = 10;
    private static final long TICK_MS = 40;

    private Uri source;
    private String sourceName;
    /** Size of the frame as displayed, i.e. after applying rotation metadata. */
    private int displayW, displayH;
    private long durationMs;

    private VideoPlayerView player;
    private CropOverlayView cropView;
    private TrimBarView trimBar;
    private ImageButton playButton;
    private TextView timeText, selectionText, outputSizeText;
    private RadioGroup aspectGroup, qualityGroup;
    private CheckBox keepAudio, rotate180;
    private Button applyButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean wantPlaying;
    private volatile boolean destroyed;
    private VideoTranscoder transcoder;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player.isPlaying()) {
                long pos = player.getCurrentPosition();
                if (pos >= trimBar.getEndMs()) {
                    player.seekTo((int) trimBar.getStartMs());
                    pos = trimBar.getStartMs();
                }
                trimBar.setPosition(pos);
                updateTimeText(pos);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_editor);

        source = getIntent().getData();
        if (source == null || !readMetadata()) {
            Toast.makeText(this, R.string.editor_unsupported, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        sourceName = queryDisplayName(source);

        player = findViewById(R.id.editor_player);
        cropView = findViewById(R.id.editor_crop);
        trimBar = findViewById(R.id.editor_trim);
        playButton = findViewById(R.id.editor_play);
        timeText = findViewById(R.id.editor_time);
        selectionText = findViewById(R.id.editor_selection);
        outputSizeText = findViewById(R.id.editor_output_size);
        aspectGroup = findViewById(R.id.editor_aspect_group);
        qualityGroup = findViewById(R.id.editor_quality_group);
        keepAudio = findViewById(R.id.editor_keep_audio);
        rotate180 = findViewById(R.id.editor_rotate_180);
        applyButton = findViewById(R.id.editor_apply);

        ((TextView) findViewById(R.id.editor_source_name)).setText(sourceName);
        findViewById(R.id.editor_close).setOnClickListener(v -> finish());

        AspectFrameLayout previewFrame = findViewById(R.id.editor_preview_frame);
        previewFrame.setAspectRatio((float) displayW / displayH);

        player.setVideoUri(source);
        player.setListener(new VideoPlayerView.Listener() {
            @Override
            public void onPrepared(int ms) {
                if (ms > 0 && Math.abs(ms - durationMs) > 200) {
                    // Trust the player if the metadata was off.
                    durationMs = ms;
                    trimBar.setDuration(durationMs);
                    updateSelectionText();
                }
                player.seekTo((int) trimBar.getStartMs());
            }

            @Override
            public void onCompletion() {
                if (wantPlaying) {
                    player.seekTo((int) trimBar.getStartMs());
                    player.play();
                }
            }

            @Override
            public void onError() {
                Toast.makeText(VideoEditorActivity.this, R.string.editor_preview_failed, Toast.LENGTH_SHORT).show();
            }
        });

        trimBar.setDuration(durationMs);
        trimBar.setListener(new TrimBarView.Listener() {
            @Override
            public void onRangeChanged(long startMs, long endMs, boolean draggingStart) {
                setPlaying(false);
                long pos = draggingStart ? startMs : endMs;
                player.seekTo((int) pos);
                updateTimeText(pos);
                updateSelectionText();
            }

            @Override
            public void onScrub(long positionMs) {
                player.seekTo((int) positionMs);
                updateTimeText(positionMs);
            }

            @Override
            public void onTouchFinished() {
            }
        });
        loadThumbnails();

        playButton.setOnClickListener(v -> setPlaying(!wantPlaying));

        cropView.setOnCropChangedListener(this::updateOutputSizeText);
        aspectGroup.setOnCheckedChangeListener((g, id) -> {
            cropView.setAspectRatio(aspectForChip(id));
            updateOutputSizeText();
        });
        qualityGroup.setOnCheckedChangeListener((g, id) -> updateOutputSizeText());
        cropView.setAspectRatio(aspectForChip(aspectGroup.getCheckedRadioButtonId()));

        applyButton.setOnClickListener(v -> startExport());

        updateTimeText(0);
        updateSelectionText();
        updateOutputSizeText();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) {
            handler.post(ticker);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (player != null) {
            setPlaying(false);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (transcoder != null) {
            transcoder.cancel();
        }
        if (player != null) {
            player.release();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (transcoder == null) {
            super.onBackPressed();
        }
    }

    private boolean readMetadata() {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, source);
            if (!"yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) {
                return false;
            }
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation = rot == null ? 0 : Integer.parseInt(rot);
            boolean swap = rotation % 180 != 0;
            displayW = swap ? h : w;
            displayH = swap ? w : h;
            durationMs = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            return displayW > 0 && displayH > 0 && durationMs > 0;
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + source, e);
            return false;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : uri.toString();
    }

    private void loadThumbnails() {
        final Bitmap[] thumbs = new Bitmap[THUMB_COUNT];
        final int thumbH = Math.round(56 * getResources().getDisplayMetrics().density);
        new Thread(() -> {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(this, source);
                for (int i = 0; i < THUMB_COUNT && !destroyed; i++) {
                    long t = durationMs * 1000 * (2L * i + 1) / (2L * THUMB_COUNT);
                    Bitmap frame = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame == null) continue;
                    int w = Math.max(1, frame.getWidth() * thumbH / frame.getHeight());
                    thumbs[i] = Bitmap.createScaledBitmap(frame, w, thumbH, true);
                    if (thumbs[i] != frame) frame.recycle();
                    handler.post(() -> trimBar.setThumbnails(thumbs.clone()));
                }
            } catch (Exception e) {
                Log.w(TAG, "thumbnail extraction failed", e);
            } finally {
                try { r.release(); } catch (Exception ignored) { }
            }
        }, "vcam-thumbs").start();
    }

    private void setPlaying(boolean playing) {
        wantPlaying = playing;
        if (playing) {
            long pos = player.getCurrentPosition();
            if (pos < trimBar.getStartMs() || pos >= trimBar.getEndMs() - 100) {
                player.seekTo((int) trimBar.getStartMs());
            }
            player.play();
        } else {
            player.pause();
        }
        playButton.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        playButton.setContentDescription(getString(playing ? R.string.editor_pause : R.string.editor_play));
    }

    /** Crop aspect (output width / height) for a chip; 0 = free. */
    private float aspectForChip(int id) {
        if (id == R.id.aspect_free) return 0;
        if (id == R.id.aspect_1_1) return 1f;
        if (id == R.id.aspect_4_3) return 4f / 3f;
        if (id == R.id.aspect_3_4) return 3f / 4f;
        if (id == R.id.aspect_16_9) return 16f / 9f;
        if (id == R.id.aspect_9_16) return 9f / 16f;
        return (float) displayW / displayH; // original
    }

    private int shortSideForQuality() {
        int id = qualityGroup.getCheckedRadioButtonId();
        if (id == R.id.quality_480) return 480;
        if (id == R.id.quality_1080) return 1080;
        return 720;
    }

    /** Output size for the current crop, aspect and quality, adjusted for the encoder. */
    private int[] computeOutputSize() {
        float aspect = aspectForChip(aspectGroup.getCheckedRadioButtonId());
        if (aspect <= 0) {
            RectF c = cropView.getCrop();
            aspect = (c.width() * displayW) / (c.height() * displayH);
        }
        int shortSide = shortSideForQuality();
        int w, h;
        if (aspect >= 1) {
            h = shortSide;
            w = Math.round(shortSide * aspect);
        } else {
            w = shortSide;
            h = Math.round(shortSide / aspect);
        }
        return VideoTranscoder.fitEncoderSize(w, h);
    }

    private void updateOutputSizeText() {
        int[] size = computeOutputSize();
        outputSizeText.setText(getString(R.string.editor_output_size, size[0], size[1]));
    }

    private void updateTimeText(long posMs) {
        timeText.setText(formatTime(posMs) + " / " + formatTime(durationMs));
    }

    private void updateSelectionText() {
        long len = trimBar.getEndMs() - trimBar.getStartMs();
        selectionText.setText(getString(R.string.editor_selected, String.format(Locale.US, "%.1f", len / 1000f)));
    }

    private static String formatTime(long ms) {
        long totalTenths = ms / 100;
        return String.format(Locale.US, "%02d:%02d.%d", totalTenths / 600, (totalTenths / 10) % 60, totalTenths % 10);
    }

    private void startExport() {
        setPlaying(false);

        final VideoTranscoder.Options opt = new VideoTranscoder.Options();
        opt.startUs = trimBar.getStartMs() * 1000;
        opt.endUs = trimBar.getEndMs() * 1000;
        opt.crop = cropView.getCrop();
        int[] size = computeOutputSize();
        opt.outWidth = size[0];
        opt.outHeight = size[1];
        opt.keepAudio = keepAudio.isChecked();
        opt.rotate180 = rotate180.isChecked();
        final long startMs = trimBar.getStartMs(), endMs = trimBar.getEndMs();

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_export_progress, null);
        final ProgressBar bar = dialogView.findViewById(R.id.export_progress);
        final TextView percent = dialogView.findViewById(R.id.export_percent);
        final VideoTranscoder job = new VideoTranscoder();
        transcoder = job;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.editor_exporting)
                .setView(dialogView)
                .setCancelable(false)
                .setNegativeButton(R.string.editor_cancel, (d, w) -> job.cancel())
                .show();
        applyButton.setEnabled(false);

        new Thread(() -> {
            File temp = VirtualVideoStore.tempFile();
            String error = null;
            boolean cancelled = false;
            try {
                File dir = VirtualVideoStore.cameraDir();
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new java.io.IOException("cannot create " + dir);
                }
                temp.delete();
                job.transcode(this, source, temp, opt, fraction -> handler.post(() -> {
                    bar.setProgress(Math.round(fraction * 1000));
                    percent.setText(Math.round(fraction * 100) + "%");
                }));
                VirtualVideoStore.commit(this, temp, sourceName, startMs, endMs);
            } catch (VideoTranscoder.CancelledException e) {
                cancelled = true;
            } catch (Exception e) {
                Log.e(TAG, "export failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            } finally {
                if (temp.exists()) {
                    temp.delete();
                }
            }

            final String err = error;
            final boolean wasCancelled = cancelled;
            handler.post(() -> {
                transcoder = null;
                if (destroyed) return;
                dialog.dismiss();
                applyButton.setEnabled(true);
                if (wasCancelled) {
                    return;
                }
                if (err != null) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.editor_export_failed)
                            .setMessage(err)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                Toast.makeText(this, R.string.editor_export_done, Toast.LENGTH_LONG).show();
                setResult(RESULT_OK);
                finish();
            });
        }, "vcam-export").start();
    }
}
