package com.codegv.android.vcam.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Thumbnail strip with two draggable handles selecting [start, end] and a playhead.
 * Tapping/dragging inside the selection scrubs the playhead.
 */
public class TrimBarView extends View {

    public interface Listener {
        /** @param draggingStart true when the start handle moved, false for the end handle */
        void onRangeChanged(long startMs, long endMs, boolean draggingStart);

        void onScrub(long positionMs);

        void onTouchFinished();
    }

    private static final int NONE = 0, START = 1, END = 2, SCRUB = 3;
    private static final long MIN_RANGE_MS = 500;

    private long durationMs = 1;
    private long startMs, endMs = 1, positionMs;
    private Bitmap[] thumbs = new Bitmap[0];

    private final float density;
    private final float handleW;
    private final Paint dimPaint = new Paint();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private int dragging = NONE;
    private Listener listener;

    public TrimBarView(Context context) {
        this(context, null);
    }

    public TrimBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        handleW = 14 * density;
        bgPaint.setColor(0xFF2A2A2A);
        dimPaint.setColor(0xB3000000);
        framePaint.setColor(0xFF4F8CFF);
        gripPaint.setColor(0xFFFFFFFF);
        gripPaint.setStrokeWidth(2 * density);
        gripPaint.setStrokeCap(Paint.Cap.ROUND);
        playheadPaint.setColor(0xFFFFFFFF);
        playheadPaint.setStrokeWidth(2 * density);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setDuration(long ms) {
        durationMs = Math.max(1, ms);
        startMs = 0;
        endMs = durationMs;
        positionMs = 0;
        invalidate();
    }

    public void setThumbnails(Bitmap[] bitmaps) {
        thumbs = bitmaps;
        invalidate();
    }

    public void setPosition(long ms) {
        positionMs = ms;
        invalidate();
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    /** Left edge of the track (right edge of the start handle at 0). */
    private float trackLeft() {
        return handleW;
    }

    private float trackWidth() {
        return getWidth() - 2 * handleW;
    }

    private float timeToX(long ms) {
        return trackLeft() + trackWidth() * ms / durationMs;
    }

    private long xToTime(float x) {
        float f = (x - trackLeft()) / trackWidth();
        return Math.round(Math.max(0, Math.min(1, f)) * durationMs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int h = getHeight();
        float pad = 3 * density;
        float tl = trackLeft(), tr = tl + trackWidth();

        canvas.drawRect(tl, pad, tr, h - pad, bgPaint);
        if (thumbs.length > 0) {
            float cell = trackWidth() / thumbs.length;
            for (int i = 0; i < thumbs.length; i++) {
                Bitmap b = thumbs[i];
                if (b == null) continue;
                // Center-crop each thumbnail into its cell.
                float cellAspect = cell / (h - 2 * pad);
                float bmpAspect = (float) b.getWidth() / b.getHeight();
                if (bmpAspect > cellAspect) {
                    int sw = Math.round(b.getHeight() * cellAspect);
                    src.set((b.getWidth() - sw) / 2, 0, (b.getWidth() + sw) / 2, b.getHeight());
                } else {
                    int sh = Math.round(b.getWidth() / cellAspect);
                    src.set(0, (b.getHeight() - sh) / 2, b.getWidth(), (b.getHeight() + sh) / 2);
                }
                dst.set(tl + cell * i, pad, tl + cell * (i + 1), h - pad);
                canvas.drawBitmap(b, src, dst, null);
            }
        }

        float sx = timeToX(startMs), ex = timeToX(endMs);
        canvas.drawRect(tl, pad, sx, h - pad, dimPaint);
        canvas.drawRect(ex, pad, tr, h - pad, dimPaint);

        // Selection frame: top/bottom bars + two handles.
        float bar = 3 * density;
        canvas.drawRect(sx, 0, ex, bar, framePaint);
        canvas.drawRect(sx, h - bar, ex, h, framePaint);
        float r = 4 * density;
        dst.set(sx - handleW, 0, sx, h);
        canvas.drawRoundRect(dst, r, r, framePaint);
        dst.set(ex, 0, ex + handleW, h);
        canvas.drawRoundRect(dst, r, r, framePaint);
        float gh = h * 0.18f;
        canvas.drawLine(sx - handleW / 2, h / 2f - gh, sx - handleW / 2, h / 2f + gh, gripPaint);
        canvas.drawLine(ex + handleW / 2, h / 2f - gh, ex + handleW / 2, h / 2f + gh, gripPaint);

        float px = timeToX(positionMs);
        canvas.drawLine(px, 0, px, h, playheadPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float sx = timeToX(startMs), ex = timeToX(endMs);
                float slop = 20 * density;
                float ds = Math.abs(x - (sx - handleW / 2)), de = Math.abs(x - (ex + handleW / 2));
                if (ds < slop + handleW / 2 && ds <= de) {
                    dragging = START;
                } else if (de < slop + handleW / 2) {
                    dragging = END;
                } else {
                    dragging = SCRUB;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                handleMove(x);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                handleMove(x);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = NONE;
                if (listener != null) listener.onTouchFinished();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void handleMove(float x) {
        long minRange = Math.min(MIN_RANGE_MS, durationMs);
        if (dragging == START) {
            // The handle sits left of the selection, so its finger x is offset by half a handle.
            startMs = Math.max(0, Math.min(xToTime(x + handleW / 2), endMs - minRange));
            positionMs = startMs;
            if (listener != null) listener.onRangeChanged(startMs, endMs, true);
        } else if (dragging == END) {
            endMs = Math.min(durationMs, Math.max(xToTime(x - handleW / 2), startMs + minRange));
            positionMs = endMs;
            if (listener != null) listener.onRangeChanged(startMs, endMs, false);
        } else if (dragging == SCRUB) {
            positionMs = Math.max(startMs, Math.min(endMs, xToTime(x)));
            if (listener != null) listener.onScrub(positionMs);
        }
        invalidate();
    }
}
