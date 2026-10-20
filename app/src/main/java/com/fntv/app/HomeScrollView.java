package com.fntv.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/** 里面的横向海报行在滑动时，竖向滚动不要先把事件抢走。 */
public class HomeScrollView extends ScrollView {
    private final int touchSlop;
    private float startX;
    private float startY;
    private boolean horizontal;
    private boolean holdScroll;

    public HomeScrollView(Context context) {
        this(context, null);
    }

    public HomeScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean requestChildRectangleOnScreen(android.view.View child, android.graphics.Rect rectangle,
                                                 boolean immediate) {
        if (holdScroll) return false;
        return super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }

    @Override
    public void requestChildFocus(android.view.View child, android.view.View focused) {
        if (holdScroll) return;
        super.requestChildFocus(child, focused);
    }

    /** 恢复滚动位置时，焦点不要再把列表拽走。 */
    public void setHoldScroll(boolean hold) {
        holdScroll = hold;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = ev.getX();
                startY = ev.getY();
                horizontal = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - startX);
                float dy = Math.abs(ev.getY() - startY);
                if (horizontal || (dx > touchSlop && dx > dy)) {
                    horizontal = true;
                    return false;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                horizontal = false;
                break;
            default:
                break;
        }
        if (horizontal) return false;
        return super.onInterceptTouchEvent(ev);
    }
}
