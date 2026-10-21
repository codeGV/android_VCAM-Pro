package com.codegv.android.vcam.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Draggable crop rectangle drawn over the video preview. The crop is kept in
 * normalized [0..1] coordinates of the displayed (rotation-applied) frame.
 */
public class CropOverlayView extends View {

    private static final int NONE = 0, MOVE = 1, TL = 2, TR = 3, BL = 4, BR = 5;

    private final RectF crop = new RectF(0, 0, 1, 1);
    /** Locked crop aspect in pixels (width / height), 0 = free. */
    private float aspect;

    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dimPath = new Path();
    private final float density;

    private int mode = NONE;
    private float lastX, lastY;
    private Runnable onCropChanged;

    public CropOverlayView(Context context) {
        this(context, null);
    }

    public CropOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        dimPaint.setColor(0x99000000);
        dimPath.setFillType(Path.FillType.EVEN_ODD);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStrokeWidth(1.5f * density);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(0x66FFFFFF);
        gridPaint.setStrokeWidth(1f * density);
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStrokeWidth(4f * density);
        handlePaint.setStrokeCap(Paint.Cap.SQUARE);
    }

    public void setOnCropChangedListener(Runnable r) {
        onCropChanged = r;
    }

    /** Normalized crop rect of the displayed frame (copy). */
    public RectF getCrop() {
        return new RectF(crop);
    }

    /**
     * Locks the crop to {@code pixelAspect} (width / height of the output) and
     * resets it to the largest centred rect. Pass 0 for a free crop.
     */
    public void setAspectRatio(float pixelAspect) {
        aspect = pixelAspect;
        resetCrop();
    }

    private void resetCrop() {
        int w = getWidth(), h = getHeight();
        if (aspect <= 0 || w == 0 || h == 0) {
            crop.set(0, 0, 1, 1);
        } else {
            float viewAspect = (float) w / h;
            if (aspect > viewAspect) {
                float nh = viewAspect / aspect;
                crop.set(0, (1 - nh) / 2, 1, (1 + nh) / 2);
            } else {
                float nw = aspect / viewAspect;
                crop.set((1 - nw) / 2, 0, (1 + nw) / 2, 1);
            }
        }
        invalidate();
        if (onCropChanged != null) {
            onCropChanged.run();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetCrop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float l = crop.left * w, t = crop.top * h, r = crop.right * w, b = crop.bottom * h;

        dimPath.reset();
        dimPath.addRect(0, 0, w, h, Path.Direction.CW);
        dimPath.addRect(l, t, r, b, Path.Direction.CW);
        canvas.drawPath(dimPath, dimPaint);

        float tw = (r - l) / 3, th = (b - t) / 3;
        for (int i = 1; i < 3; i++) {
            canvas.drawLine(l + tw * i, t, l + tw * i, b, gridPaint);
            canvas.drawLine(l, t + th * i, r, t + th * i, gridPaint);
        }
        canvas.drawRect(l, t, r, b, borderPaint);

        float len = Math.min(18 * density, Math.min(r - l, b - t) / 2);
        float o = handlePaint.getStrokeWidth() / 2;
        drawCorner(canvas, l - o, t - o, len, len);
        drawCorner(canvas, r + o, t - o, -len, len);
        drawCorner(canvas, l - o, b + o, len, -len);
        drawCorner(canvas, r + o, b + o, -len, -len);
    }

    private void drawCorner(Canvas c, float x, float y, float dx, float dy) {
        c.drawLine(x, y, x + dx, y, handlePaint);
        c.drawLine(x, y, x, y + dy, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = hitTest(x, y);
                lastX = x;
                lastY = y;
                if (mode != NONE) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return mode != NONE;
            case MotionEvent.ACTION_MOVE:
                if (mode == MOVE) {
                    move((x - lastX) / getWidth(), (y - lastY) / getHeight());
                } else if (mode != NONE) {
                    resize(x, y);
                }
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode != NONE && onCropChanged != null) {
                    onCropChanged.run();
                }
                mode = NONE;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private int hitTest(float x, float y) {
        int w = getWidth(), h = getHeight();
        float l = crop.left * w, t = crop.top * h, r = crop.right * w, b = crop.bottom * h;
        float slop = 28 * density;
        boolean nearL = Math.abs(x - l) < slop, nearR = Math.abs(x - r) < slop;
        boolean nearT = Math.abs(y - t) < slop, nearB = Math.abs(y - b) < slop;
        if (nearL && nearT) return TL;
        if (nearR && nearT) return TR;
        if (nearL && nearB) return BL;
        if (nearR && nearB) return BR;
        if (x > l && x < r && y > t && y < b) return MOVE;
        return NONE;
    }

    private void move(float dx, float dy) {
        dx = Math.max(-crop.left, Math.min(1 - crop.right, dx));
        dy = Math.max(-crop.top, Math.min(1 - crop.bottom, dy));
        crop.offset(dx, dy);
    }

    /** Resize by dragging a corner; the opposite corner stays anchored. Works in view pixels. */
    private void resize(float x, float y) {
        int w = getWidth(), h = getHeight();
        boolean left = mode == TL || mode == BL;
        boolean top = mode == TL || mode == TR;
        float ax = (left ? crop.right : crop.left) * w;
        float ay = (top ? crop.bottom : crop.top) * h;

        // Room available between the anchor and the view edge in the drag direction.
        float maxW = left ? ax : w - ax;
        float maxH = top ? ay : h - ay;
        float minSize = 48 * density;

        float nw = Math.min(maxW, Math.max(minSize, Math.abs(x - ax)));
        float nh = Math.min(maxH, Math.max(minSize, Math.abs(y - ay)));
        if (aspect > 0) {
            // Follow whichever axis the finger moved further along, then fit in bounds.
            nw = Math.max(nw, nh * aspect);
            nw = Math.min(nw, Math.min(maxW, maxH * aspect));
            nh = nw / aspect;
        }

        float l = left ? ax - nw : ax, r = left ? ax : ax + nw;
        float t = top ? ay - nh : ay, b = top ? ay : ay + nh;
        crop.set(l / w, t / h, r / w, b / h);
    }
}
