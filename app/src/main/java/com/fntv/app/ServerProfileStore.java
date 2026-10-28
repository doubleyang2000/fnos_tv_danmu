package com.fntv.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 服务器档案：保存登录过的飞牛影视服务器，支持快速切换。
 *
 * 存在 SharedPreferences("fntv_prefs") 的 server_profiles 字段里，是一个 JSON 数组。
 * 只负责 HTTP 直连模式（FN ID 模式的字段不参与）。
 */
final class ServerProfileStore {

    static final String PREF_PROFILES = "server_profiles";

    /** 一台服务器的完整登录信息 */
    static final class Profile {
        String host = "";      // http://192.168.1.10:5666
        String user = "";
        String pass = "";
        String token = "";
        long lastUsed = 0;

        /** 列表里显示的名字，直接用 IP:端口 */
        String displayName() {
            String h = host == null ? "" : host;
            h = h.replaceFirst("^https?://", "");
            h = h.replaceAll("/+$", "");
            return h.isEmpty() ? "未知服务器" : h;
        }

        boolean sameHost(String other) {
            return normalize(host).equals(normalize(other));
        }

        private static String normalize(String h) {
            if (h == null) return "";
            String s = h.trim().replaceFirst("^https?://", "");
            return s.replaceAll("/+$", "");
        }
    }

    private ServerProfileStore() {}

    // ========== 读 ==========

    static List<Profile> list(Context ctx) {
        List<Profile> out = new ArrayList<>();
        String raw = prefs(ctx).getString(PREF_PROFILES, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Profile p = new Profile();
                p.host = o.optString("host", "");
                p.user = o.optString("user", "");
                p.pass = o.optString("pass", "");
                p.token = o.optString("token", "");
                p.lastUsed = o.optLong("lastUsed", 0);
                if (!p.host.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) {}
        // 最近用过的排前面
        Collections.sort(out, new Comparator<Profile>() {
            @Override public int compare(Profile a, Profile b) {
                return Long.compare(b.lastUsed, a.lastUsed);
            }
        });
        return out;
    }

    /** 当前正在用的服务器（按 prefs 里的 host 匹配） */
    static Profile current(Context ctx) {
        String host = prefs(ctx).getString("host", "");
        if (host == null || host.isEmpty()) return null;
        for (Profile p : list(ctx)) {
            if (p.sameHost(host)) return p;
        }
        return null;
    }

    // ========== 写 ==========

    /**
     * 登录成功后调用：把当前 prefs 里的登录信息存进档案。
     * 同 host 的会被覆盖，不会重复。
     */
    static void saveCurrent(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String host = sp.getString("host", "");
        if (host == null || host.isEmpty()) return;

        Profile p = new Profile();
        p.host = host;
        p.user = sp.getString("user", "");
        p.pass = sp.getString("pass", "");
        p.token = sp.getString("auth_token", "");
        p.lastUsed = System.currentTimeMillis();
        upsert(ctx, p);
    }

    static void upsert(Context ctx, Profile p) {
        if (p == null || p.host == null || p.host.isEmpty()) return;
        List<Profile> all = list(ctx);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).sameHost(p.host)) {
                all.set(i, p);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(p);
        write(ctx, all);
    }

    static void remove(Context ctx, String host) {
        List<Profile> all = list(ctx);
        List<Profile> keep = new ArrayList<>();
        for (Profile p : all) {
            if (!p.sameHost(host)) keep.add(p);
        }
        write(ctx, keep);
    }

    /**
     * 切换到某个服务器：把它写进 prefs 的当前字段。
     * 调用方随后应重启到 MainActivity，由 restoreSession() 用 token 免登录进主页。
     */
    static void applyToPrefs(Context ctx, Profile p) {
        if (p == null || p.host == null || p.host.isEmpty()) return;
        SharedPreferences.Editor e = prefs(ctx).edit();
        e.putString("host", p.host);
        e.putString("user", p.user);
        e.putString("login_mode", "http");
        e.putBoolean("_was_fnid", false);

        if (p.pass != null && !p.pass.isEmpty()) {
            e.putString("pass", p.pass);
            e.putBoolean("remember", true);
        } else {
            e.remove("pass");
            e.putBoolean("remember", false);
        }

        if (p.token != null && !p.token.isEmpty()) {
            e.putString("auth_token", p.token);
        } else {
            e.remove("auth_token");
        }

        // 观看记录按服务器隔离，切了就清掉（沿用项目原有 last_host 机制）
        e.putString("last_host", p.host);
        e.remove("watch_history");
        e.apply();
    }

    // ========== 内部 ==========

    private static void write(Context ctx, List<Profile> all) {
        JSONArray arr = new JSONArray();
        for (Profile p : all) {
            try {
                JSONObject o = new JSONObject();
                o.put("host", p.host);
                o.put("user", p.user == null ? "" : p.user);
                o.put("pass", p.pass == null ? "" : p.pass);
                o.put("token", p.token == null ? "" : p.token);
                o.put("lastUsed", p.lastUsed);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        prefs(ctx).edit().putString(PREF_PROFILES, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE);
    }
}
