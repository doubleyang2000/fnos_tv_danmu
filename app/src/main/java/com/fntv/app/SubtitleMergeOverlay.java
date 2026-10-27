package com.fntv.app;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.text.Cue;

import java.util.ArrayList;
import java.util.List;

/**
 * 双语字幕合并覆层
 * 严格按时间匹配：连续两次 onCues 在极短时间内交替出现不同文本 → 合并显示
 */
public class SubtitleMergeOverlay {

    private static final long MATCH_WINDOW_MS = 80;

    /** 字幕字号偏好（相对父容器高度的比例） */
    public static final String PREF_FONT_SCALE = "subtitle_font_scale";
    /** 默认 6.5% —— 比 ExoPlayer 官方 5.33% 大一档，电视机三米外看得清 */
    public static final float DEFAULT_FONT_SCALE = 0.065f;

    private final TextView overlay;
    private final FrameLayout parent;
    private List<CharSequence> prevTexts = new ArrayList<>();
    private long prevCueTime = 0;
    private boolean hasMerged = false;
    private boolean enabled = true;
    private View subtitleView; // ExoPlayer 原版字幕视图
    private float fontScale = DEFAULT_FONT_SCALE;

    public SubtitleMergeOverlay(FrameLayout parent) {
        this.parent = parent;
        overlay = new TextView(parent.getContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = (int) (20 * parent.getResources().getDisplayMetrics().density);
        overlay.setLayoutParams(lp);
        overlay.setTextColor(Color.WHITE);
        overlay.setGravity(Gravity.CENTER);
        overlay.setVisibility(View.GONE);
        overlay.setPadding((int)(16 * parent.getResources().getDisplayMetrics().density), 0,
                (int)(16 * parent.getResources().getDisplayMetrics().density), 0);
        // 字号按屏高百分比算（原来写死 22sp，4K 电视上只有屏高 2%）
        fontScale = parent.getContext()
                .getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE)
                .getFloat(PREF_FONT_SCALE, DEFAULT_FONT_SCALE);
        applyTextSize();
        // 首帧布局完成、切分辨率、旋转时重算
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((b - t) != (ob - ot) || (r - l) != (or - ol)) applyTextSize();
        });
        parent.addView(overlay, 1); // 插入在 PlayerView 之后、UI 控件之前
    }

    /** 按父容器高度百分比设置字号，行距与阴影等比缩放。 */
    private void applyTextSize() {
        int h = parent.getHeight();
        if (h <= 0) h = parent.getResources().getDisplayMetrics().heightPixels;
        float px = h * fontScale;
        // 竖屏手机这类高窄画面按高度算会过大，纵向时再用宽度设一道上限
        int w = parent.getWidth();
        if (w <= 0) w = parent.getResources().getDisplayMetrics().widthPixels;
        if (h > w) {
            float maxPx = w * 0.06f;
            if (px > maxPx) px = maxPx;
        }
        if (px < 8f) px = 8f;
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        overlay.setLineSpacing(px * 0.30f, 1f);
        overlay.setShadowLayer(px * 0.07f, 0f, px * 0.025f, Color.BLACK);
    }

    /** 重新读取偏好并立即生效（设置里改完字号后调用） */
    public void reloadFontScale() {
        fontScale = overlay.getContext()
                .getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE)
                .getFloat(PREF_FONT_SCALE, DEFAULT_FONT_SCALE);
        applyTextSize();
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public TextView getView() { return overlay; }
    /** 设置 ExoPlayer 原版字幕视图，图形字幕退回原版渲染 */
    public void setSubtitleView(View v) { this.subtitleView = v; }
    public View getSubtitleView() { return subtitleView; }

    /** 是否有图形字幕（PGS/DVB 等，text=null） */
    public boolean hasBitmapCue(List<Cue> cues) {
        if (cues == null) return false;
        for (Cue c : cues) {
            if (c.text == null && c.bitmap != null) return true;
        }
        return false;
    }

    public void onNewCues(List<Cue> cues) {
        if (!enabled || cues == null) return;
        if (cues.isEmpty()) { clear(); return; }

        // 图形字幕 → 显示原版 SubtitleView，隐藏覆层
        if (hasBitmapCue(cues)) {
            if (subtitleView != null) subtitleView.setVisibility(View.VISIBLE);
            overlay.setVisibility(View.GONE);
            return;
        }
        // 文字字幕 → 隐藏原版 SubtitleView，使用覆层
        if (subtitleView != null) subtitleView.setVisibility(View.GONE);

        long now = System.currentTimeMillis();
        List<CharSequence> curTexts = new ArrayList<>();
        for (Cue c : cues) {
            if (c.text != null && c.text.length() > 0) curTexts.add(c.text);
        }
        if (curTexts.isEmpty()) { clear(); return; }
        if (textsEqual(prevTexts, curTexts)) return;

        long gap = now - prevCueTime;

        if (gap < MATCH_WINDOW_MS && !prevTexts.isEmpty() && !hasMerged) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence t : prevTexts) { sb.append(t).append("\n"); }
            for (CharSequence t : curTexts) { sb.append(t).append("\n"); }
            if (sb.length() > 0) sb.setLength(sb.length() - 1);
            overlay.setText(sb.toString());
            overlay.setVisibility(View.VISIBLE);
            hasMerged = true;
        } else {
            StringBuilder sb = new StringBuilder();
            for (CharSequence t : curTexts) { sb.append(t).append("\n"); }
            if (sb.length() > 0) sb.setLength(sb.length() - 1);
            overlay.setText(sb.toString());
            overlay.setVisibility(View.VISIBLE);
            hasMerged = false;
        }

        prevTexts = curTexts;
        prevCueTime = now;
    }

    public void clear() {
        prevTexts.clear();
        prevCueTime = 0;
        hasMerged = false;
        overlay.setVisibility(View.GONE);
    }

    private boolean textsEqual(List<CharSequence> a, List<CharSequence> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).toString().equals(b.get(i).toString())) return false;
        }
        return true;
    }
}
