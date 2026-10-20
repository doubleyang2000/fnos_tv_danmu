package com.fntv.app;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 前景半透明提示，用来替代系统不透明 Toast。 */
final class AppToast {

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static TextView current;
    private static Runnable hide;

    private AppToast() {}

    static void show(Context context, CharSequence text) {
        show(context, text, false);
    }

    static void show(Context context, CharSequence text, boolean longer) {
        show(context, text, longer, false);
    }

    /** 显示在屏幕正中，用于「已刷新」这类短提示。 */
    static void showCenter(Context context, CharSequence text) {
        show(context, text, false, true);
    }

    private static void show(Context context, CharSequence text, boolean longer, boolean center) {
        if (context == null || text == null) return;
        Activity activity = activityOf(context);
        if (activity == null || activity.isFinishing()) {
            Toast toast = Toast.makeText(context, text, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
            if (center) toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
            return;
        }
        Runnable post = () -> present(activity, text, longer ? 3500 : 2000, center);
        if (Looper.myLooper() == Looper.getMainLooper()) post.run();
        else HANDLER.post(post);
    }

    private static void present(Activity activity, CharSequence text, int durationMs, boolean center) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (hide != null) HANDLER.removeCallbacks(hide);
        if (current != null && current.getParent() instanceof ViewGroup) {
            ((ViewGroup) current.getParent()).removeView(current);
        }
        current = null;
        float density = activity.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        int padH = Math.round(16 * density);
        int padV = Math.round(10 * density);
        tv.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3121212);
        bg.setCornerRadius(10 * density);
        tv.setBackground(bg);
        tv.setFocusable(false);
        tv.setClickable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                center ? Gravity.CENTER : (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        if (!center) lp.bottomMargin = Math.round(72 * density);
        lp.leftMargin = padH;
        lp.rightMargin = padH;
        decor.addView(tv, lp);
        current = tv;
        hide = () -> {
            if (current == tv && tv.getParent() instanceof ViewGroup) {
                ((ViewGroup) tv.getParent()).removeView(tv);
            }
            if (current == tv) current = null;
        };
        HANDLER.postDelayed(hide, durationMs);
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
