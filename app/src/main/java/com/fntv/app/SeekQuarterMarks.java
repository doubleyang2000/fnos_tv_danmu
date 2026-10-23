package com.fntv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;

/** 盖在进度条上的小节点。默认按轨道四等分，也可按实际数值定位。不抢触摸和焦点。 */
public class SeekQuarterMarks extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] fractions = {0f, 0.25f, 0.5f, 0.75f, 1f};

    /** 节点在轨道上的位置，0 是最左，1 是最右。 */
    public void setFractions(float[] stops) {
        if (stops == null || stops.length == 0) return;
        fractions = stops;
        invalidate();
    }

    public SeekQuarterMarks(Context context) {
        this(context, null);
    }

    public SeekQuarterMarks(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xE6FFFFFF);
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        SeekBar bar = siblingSeekBar();
        if (bar == null || bar.getWidth() <= 0) return;
        float originX = bar.getLeft() - getLeft();
        float originY = bar.getTop() - getTop();
        float cy = originY + (bar.getHeight() + bar.getPaddingTop() - bar.getPaddingBottom()) / 2f;
        float radius = 3f * getResources().getDisplayMetrics().density;
        for (float fraction : fractions) {
            float clamped = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
            canvas.drawCircle(originX + thumbCenterX(bar, clamped), cy, radius, paint);
        }
    }

    /** 同一条滑条上的 SeekBar。不能按 dm_seekbar 找，焦点链会改掉这个 id。 */
    private SeekBar siblingSeekBar() {
        ViewParent parent = getParent();
        if (!(parent instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) parent;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof SeekBar) return (SeekBar) child;
        }
        return null;
    }

    /** 与 AbsSeekBar 滑块中心同一套位移，节点才会落在圆点上。 */
    private float thumbCenterX(SeekBar bar, float fraction) {
        int padL = bar.getPaddingLeft();
        int padR = bar.getPaddingRight();
        int span = bar.getWidth() - padL - padR;
        int thumbW = 0;
        Drawable thumb = bar.getThumb();
        if (thumb != null) {
            thumbW = thumb.getIntrinsicWidth();
            if (thumbW <= 0) thumbW = thumb.getBounds().width();
        }
        if (thumbW <= 0 || span <= 0) return padL + span * fraction;
        int offset = bar.getThumbOffset();
        int travel = span - thumbW + offset * 2;
        return padL - offset + travel * fraction + thumbW / 2f;
    }
}
