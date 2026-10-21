package com.codegv.android.vcam.editor;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * FrameLayout that sizes itself to the largest box of the given aspect ratio
 * that fits in the space offered by its parent.
 */
public class AspectFrameLayout extends FrameLayout {

    private float aspect = 16f / 9f;

    public AspectFrameLayout(Context context) {
        super(context);
    }

    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** @param aspect width / height */
    public void setAspectRatio(float aspect) {
        if (aspect > 0 && aspect != this.aspect) {
            this.aspect = aspect;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxW = MeasureSpec.getSize(widthMeasureSpec);
        int maxH = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            maxH = Integer.MAX_VALUE;
        }
        int w = maxW;
        int h = Math.round(w / aspect);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * aspect);
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
