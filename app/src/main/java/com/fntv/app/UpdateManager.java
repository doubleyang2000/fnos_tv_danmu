package com.fntv.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 升级管理器 — 检查更新、下载 APK、安装 */
public class UpdateManager {
    private boolean isTvDevice() {
        android.app.UiModeManager uiModeManager = (android.app.UiModeManager) activity.getSystemService(android.content.Context.UI_MODE_SERVICE);
        return uiModeManager != null
                && uiModeManager.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }


    private final Activity activity;
    private final Button btnCheckUpdate;
    private final int currentVersionCode;
    private boolean busy;
    /** 自动检查时不改按钮焦点，也不提示“已是最新”或网络失败。 */
    private boolean quiet;
    /** 首页是否在前台。公开 SDK 没有 Activity.isResumed。 */
    private boolean inForeground;
    private Dialog updateDialog;
    private boolean hasPending;
    private String pendingTitle;
    private String pendingChangelog;
    private String pendingApkUrl;
    private boolean pendingForce;
    private boolean pendingTest;
    private int pendingVersion;

    /** 更新源（按优先级） */
    private static final String[] UPDATE_URLS = {
            "https://raw.githubusercontent.com/lxlry/fnos_tv_danmu/master/update.json",
            "https://jsd.onmicrosoft.cn/gh/lxlry/fnos_tv_danmu@master/update.json",
            "https://cdn.jsdelivr.net/gh/lxlry/fnos_tv_danmu@master/update.json",
            "https://fastly.jsdelivr.net/gh/lxlry/fnos_tv_danmu@master/update.json"
    };

    private static final String TAG = "Update";

    public UpdateManager(Activity activity, Button btnCheckUpdate, int currentVersionCode) {
        this.activity = activity;
        this.btnCheckUpdate = btnCheckUpdate;
        this.currentVersionCode = currentVersionCode;
    }

    /** 初始化绑定按钮点击 */
    public void setup() {
        btnCheckUpdate.setOnClickListener(v -> checkUpdate());
    }

    public void setInForeground(boolean inForeground) {
        this.inForeground = inForeground;
    }

    /** 打开应用时检查。已是最新或连不上服务器时不提示。 */
    public void checkOnLaunch() {
        if (updateDialog != null && updateDialog.isShowing()) return;
        if (hasPending) {
            showPendingPrompt();
            return;
        }
        startCheck(true);
    }

    /** 检查结果回来时界面不在前台，回到首页后再弹出。 */
    public void showPendingPrompt() {
        if (!hasPending || busy) return;
        if (!canPrompt() || !inForeground) return;
        hasPending = false;
        busy = true;
        quiet = true;
        showUpdateDialog(pendingTitle, pendingChangelog, pendingForce, pendingApkUrl, pendingVersion, pendingTest);
    }

    private void resetBtn() {
        busy = false;
        quiet = false;
        if (btnCheckUpdate == null) return;
        btnCheckUpdate.setText("检查更新");
    }

    // ========== 检查更新 ==========

    public void checkUpdate() {
        startCheck(false);
    }

    private void startCheck(boolean quietCheck) {
        if (busy) return;
        if (!canPrompt()) return;
        busy = true;
        quiet = quietCheck;
        if (!quietCheck && btnCheckUpdate != null) {
            // 禁用后按钮拿不住焦点，电视会把焦点丢到设置页顶部
            btnCheckUpdate.setText("检查中...");
        }
        new Thread(() -> {
            try {
                org.json.JSONObject json = null;
                String usedUrl = "";
                String ts = new java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.CHINA).format(new java.util.Date());
                for (String url : UPDATE_URLS) {
                    try {
                        if (!url.contains("raw.githubusercontent.com")) {
                            url = url + (url.contains("?") ? "&" : "?") + "t=" + ts;
                        }
                        Log.d(TAG, "尝试源: " + url);
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        c.setInstanceFollowRedirects(true);
                        c.connect();
                        int code = c.getResponseCode();
                        Log.d(TAG, "响应码: " + code + " 来自: " + url);
                        if (code == 200) {
                            java.io.BufferedReader r = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                            StringBuilder sb = new StringBuilder();
                            String l;
                            while ((l = r.readLine()) != null) sb.append(l);
                            r.close();
                            json = new org.json.JSONObject(sb.toString());
                            usedUrl = url;
                            break;
                        } else {
                            try {
                                java.io.BufferedReader er = new java.io.BufferedReader(
                                        new java.io.InputStreamReader(c.getErrorStream(), "UTF-8"));
                                StringBuilder eb = new StringBuilder();
                                String el;
                                while ((el = er.readLine()) != null) eb.append(el);
                                er.close();
                                Log.w(TAG, "错误响应: " + eb);
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "源 \"" + url + "\" 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }

                if (json == null) {
                    activity.runOnUiThread(() -> {
                        if (!quiet) AppToast.show(activity, "检查更新失败，无法连接更新服务器", true);
                        resetBtn();
                    });
                    return;
                }

                int remoteVersion = json.optInt("versionCode", 0);
                final String versionName = json.optString("versionName", "");
                final String apkUrl = json.optString("apkUrl", "");
                final String changelog = json.optString("changelog", "暂无更新说明");
                final boolean forceUpdate = json.optBoolean("forceUpdate", false);

                Log.d(TAG, "远程: v" + remoteVersion + " 本地: v" + currentVersionCode + " 来源: " + usedUrl);

                final boolean isTestInstall = !quiet && BuildConfig.DEBUG && remoteVersion <= currentVersionCode;
                if (remoteVersion <= currentVersionCode && !isTestInstall) {
                    activity.runOnUiThread(() -> {
                        if (!quiet) AppToast.show(activity, "已是最新版本");
                        resetBtn();
                    });
                    return;
                }

                if (!releaseApkReady(apkUrl, versionName)) {
                    Log.d(TAG, "安装包尚未发布，暂不提示更新");
                    activity.runOnUiThread(() -> {
                        if (!quiet) AppToast.show(activity, "新版本还在打包，请稍后再试");
                        resetBtn();
                    });
                    return;
                }

                final int fRemoteVersion = remoteVersion;
                final String label = isTestInstall ? "测试安装（版本号不高于当前）" : "发现新版本 v" + versionName;
                activity.runOnUiThread(() ->
                        offerUpdate(label, changelog, forceUpdate, apkUrl, fRemoteVersion, isTestInstall));

            } catch (Exception e) {
                Log.e(TAG, "检查更新异常", e);
                activity.runOnUiThread(() -> {
                    if (!quiet) AppToast.show(activity, "检查更新失败: " + e.getMessage(), true);
                    resetBtn();
                });
            }
        }).start();
    }

    /** 对应 Release 里已经有这个 apk，才提示更新。发布前或接口失败都视为还没有。 */
    private boolean releaseApkReady(String apkUrl, String versionName) {
        String repo = "lxlry/fnos_tv_danmu";
        String tag = versionName == null ? "" : versionName;
        String fileName = "FNTV_release_v" + tag + ".apk";
        if (apkUrl != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("github\\.com/([^/]+/[^/]+)/releases/download/([^/]+)/([^?#]+)")
                    .matcher(apkUrl);
            if (m.find()) {
                repo = m.group(1);
                tag = decodeUrl(m.group(2));
                fileName = decodeUrl(m.group(3));
            }
        }
        if (tag.isEmpty() || fileName.isEmpty()) return false;
        String api = "https://api.github.com/repos/" + repo + "/releases/tags/" + encodePath(tag);
        String[] urls = {
                api,
                "https://ghfast.top/" + api
        };
        for (String url : urls) {
            Boolean ready = releaseHasAsset(url, fileName);
            if (ready != null) return ready;
        }
        return false;
    }

    /** 接口有明确结果时返回是否已上传；这个源连不上则返回 null，换下一个源。 */
    private Boolean releaseHasAsset(String url, String fileName) {
        HttpURLConnection c = null;
        try {
            Log.d(TAG, "确认安装包: " + url);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "FNTV");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.connect();
            int code = c.getResponseCode();
            Log.d(TAG, "安装包确认响应: " + code);
            if (code == 404) return false;
            if (code != 200) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            org.json.JSONObject json = new org.json.JSONObject(sb.toString());
            org.json.JSONArray assets = json.optJSONArray("assets");
            if (assets == null) return false;
            for (int i = 0; i < assets.length(); i++) {
                org.json.JSONObject asset = assets.optJSONObject(i);
                if (asset == null || !fileName.equals(asset.optString("name"))) continue;
                String state = asset.optString("state", "");
                return state.isEmpty() || "uploaded".equals(state);
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "确认安装包失败: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String decodeUrl(String value) {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String encodePath(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private boolean canPrompt() {
        if (activity.isFinishing()) return false;
        return android.os.Build.VERSION.SDK_INT < 17 || !activity.isDestroyed();
    }

    private void offerUpdate(String title, String changelog, boolean forceUpdate, String apkUrl,
                             int remoteVersion, boolean isTestInstall) {
        if (!canPrompt()) {
            resetBtn();
            return;
        }
        if (quiet && !inForeground) {
            hasPending = true;
            pendingTitle = title;
            pendingChangelog = changelog;
            pendingForce = forceUpdate;
            pendingApkUrl = apkUrl;
            pendingVersion = remoteVersion;
            pendingTest = isTestInstall;
            busy = false;
            return;
        }
        showUpdateDialog(title, changelog, forceUpdate, apkUrl, remoteVersion, isTestInstall);
    }

    // ========== 更新弹窗 ==========

    private void showUpdateDialog(final String title, final String changelog,
                                   final boolean forceUpdate, final String apkUrl,
                                   final int remoteVersion, final boolean isTestInstall) {
        if (updateDialog != null && updateDialog.isShowing()) return;
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        updateDialog = dialog;
        dialog.setCancelable(!forceUpdate);
        dialog.setCanceledOnTouchOutside(!forceUpdate);
        final boolean[] accepted = {false};
        dialog.setOnDismissListener(d -> {
            if (updateDialog == dialog) updateDialog = null;
            if (!accepted[0] && !forceUpdate) resetBtn();
        });

        float density = activity.getResources().getDisplayMetrics().density;
        int dp = (int) (density + 0.5f);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_card);
        root.setPadding(20 * dp, 18 * dp, 20 * dp, 16 * dp);

        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(activity.getResources().getColor(R.color.text_primary));
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(heading);

        TextView message = new TextView(activity);
        message.setText(changelog == null || changelog.isEmpty() ? "暂无更新说明" : changelog);
        message.setTextColor(activity.getResources().getColor(R.color.text_secondary));
        message.setTextSize(13);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageLp.topMargin = 8 * dp;
        message.setLayoutParams(messageLp);
        root.addView(message);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = 16 * dp;
        actions.setLayoutParams(actionsLp);

        Button later = new Button(activity);
        later.setText(forceUpdate ? "退出应用" : "稍后再说");
        later.setAllCaps(false);
        later.setTextSize(15);
        later.setTextColor(activity.getResources().getColor(R.color.text_secondary));
        later.setBackgroundResource(R.drawable.bg_chip);
        later.setFocusable(true);
        LinearLayout.LayoutParams laterLp = new LinearLayout.LayoutParams(0, 44 * dp, 1f);
        laterLp.rightMargin = 8 * dp;
        later.setLayoutParams(laterLp);
        final boolean[] downloadFailed = {false};
        later.setOnClickListener(v -> {
            if (downloadFailed[0] || !forceUpdate) dialog.dismiss();
            else activity.finishAffinity();
        });

        final String updateLabel = isTestInstall ? "测试安装器" : "立即更新";
        Button update = new Button(activity);
        update.setText(updateLabel);
        update.setAllCaps(false);
        update.setTextSize(15);
        update.setTextColor(activity.getResources().getColor(R.color.text_white));
        update.setTypeface(Typeface.DEFAULT_BOLD);
        update.setBackgroundResource(R.drawable.bg_btn_primary);
        update.setFocusable(true);
        LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(0, 44 * dp, 1f);
        updateLp.leftMargin = 8 * dp;
        update.setLayoutParams(updateLp);

        LinearLayout progressBox = new LinearLayout(activity);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressLp.topMargin = 14 * dp;
        progressBox.setLayoutParams(progressLp);

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressDrawable(activity.getResources().getDrawable(R.drawable.seeker_progress));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 6 * dp));
        progressBox.addView(bar);

        TextView progressLabel = new TextView(activity);
        progressLabel.setText("下载中 0%");
        progressLabel.setTextColor(activity.getResources().getColor(R.color.text_hint));
        progressLabel.setTextSize(12);
        progressLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = 6 * dp;
        progressLabel.setLayoutParams(labelLp);
        progressBox.addView(progressLabel);

        final boolean[] downloading = {false};
        update.setOnClickListener(v -> {
            if (downloading[0]) return;
            downloading[0] = true;
            accepted[0] = true;
            later.setEnabled(false);
            update.setText("下载中...");
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            bar.setIndeterminate(false);
            bar.setProgress(0);
            progressLabel.setText("下载中 0%");
            progressBox.setVisibility(View.VISIBLE);
            downloadAndInstall(apkUrl, remoteVersion, isTestInstall, quiet, new DownloadUi() {
                @Override public void onProgress(int percent) {
                    if (!dialog.isShowing()) return;
                    if (percent < 0) {
                        bar.setIndeterminate(true);
                        progressLabel.setText("下载中...");
                        return;
                    }
                    bar.setIndeterminate(false);
                    bar.setProgress(percent);
                    progressLabel.setText(percent >= 100 ? "下载完成" : "下载中 " + percent + "%");
                }

                @Override public void onFinished() {
                    if (dialog.isShowing()) dialog.dismiss();
                }

                @Override public void onFailed() {
                    if (!dialog.isShowing()) return;
                    downloadFailed[0] = true;
                    downloading[0] = false;
                    later.setEnabled(true);
                    later.setText("取消");
                    update.setText(updateLabel);
                    progressBox.setVisibility(View.GONE);
                    dialog.setCancelable(true);
                    dialog.setCanceledOnTouchOutside(true);
                    later.requestFocus();
                }
            });
        });

        actions.addView(later);
        actions.addView(update);
        root.addView(actions);
        root.addView(progressBox);
        dialog.setContentView(root);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.86f);
            int max = 440 * dp;
            if (width > max) width = max;
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        update.requestFocus();
    }

    // ========== 下载 ==========

    private interface DownloadUi {
        void onProgress(int percent);
        void onFinished();
        void onFailed();
    }

    private void postProgress(DownloadUi ui, int percent) {
        if (ui == null) return;
        activity.runOnUiThread(() -> {
            if (canPrompt()) ui.onProgress(percent);
        });
    }

    private void postFailed(DownloadUi ui) {
        activity.runOnUiThread(() -> {
            if (ui != null && canPrompt()) ui.onFailed();
        });
    }

    private void downloadAndInstall(final String apkUrl, final int remoteVersion, final boolean isTestInstall,
                                     final boolean quietDownload, final DownloadUi ui) {
        busy = true;
        quiet = quietDownload;
        if (btnCheckUpdate != null) {
            btnCheckUpdate.setText(isTestInstall ? "测试中..." : "下载中...");
        }
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.connect();
                int respCode = c.getResponseCode();
                if (respCode != 200) {
                    postFailed(ui);
                    activity.runOnUiThread(() -> {
                        AppToast.show(activity, "下载失败，服务器返回 " + respCode, true);
                        resetBtn();
                    });
                    return;
                }

                File dir = new File(activity.getExternalFilesDir(null), "download");
                if (!dir.exists()) dir.mkdirs();
                final File apkFile = new File(dir, "FNTV_v" + remoteVersion + ".apk");

                long total = c.getContentLength();
                if (total <= 0) postProgress(ui, -1);
                InputStream is = c.getInputStream();
                FileOutputStream fos = new FileOutputStream(apkFile);
                byte[] buf = new byte[8192];
                int n;
                long read = 0;
                int lastPercent = -1;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    read += n;
                    if (total > 0) {
                        int percent = (int) Math.min(99, read * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            postProgress(ui, percent);
                        }
                    }
                }
                fos.close();
                is.close();
                postProgress(ui, 100);

                Log.d(TAG, "下载完成: " + apkFile.getAbsolutePath() + " (" + apkFile.length() + " bytes)");

                // 检查 APK 文件头
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(apkFile, "r");
                byte[] header = new byte[4];
                raf.read(header);
                raf.close();
                String headerStr = new String(header, "UTF-8");
                Log.d(TAG, "APK 文件头: " + headerStr + " (" + bytesToHex(header) + ")");
                if (!"PK".equals(headerStr.substring(0, 2))) {
                    postFailed(ui);
                    activity.runOnUiThread(() -> {
                        AppToast.show(activity, "下载文件不是有效的 APK（文件头异常）", true);
                        resetBtn();
                    });
                    return;
                }

                // 签名校验
                if (!verifyApkSignature(apkFile)) {
                    postFailed(ui);
                    activity.runOnUiThread(() -> {
                        AppToast.show(activity, "APK 签名校验失败，可能已被篡改", true);
                        resetBtn();
                    });
                    return;
                }

                activity.runOnUiThread(() -> {
                    if (ui != null && canPrompt()) ui.onFinished();
                    installApk(apkFile, isTestInstall);
                });

            } catch (Exception e) {
                Log.e(TAG, "下载失败", e);
                postFailed(ui);
                activity.runOnUiThread(() -> {
                    AppToast.show(activity, "下载失败: " + e.getMessage(), true);
                    resetBtn();
                });
            }
        }).start();
    }

    // ========== APK 安装（四级降级） ==========

    private void installApk(File apkFile, boolean testOnly) {
        try {
            // 1. ACTION_INSTALL_PACKAGE（Android 8+，弹出系统安装界面）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    if (!activity.getPackageManager().canRequestPackageInstalls()) {
                        Intent permIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        permIntent.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(permIntent);
                        AppToast.show(activity, "请允许安装未知来源应用后重试", true);
                        resetBtn();
                        return;
                    }
                    android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                            activity, activity.getPackageName() + ".fileprovider", apkFile);
                    Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                    install.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(install);
                    if (testOnly) {
                        AppToast.show(activity, "测试成功：ACTION_INSTALL_PACKAGE 可用", true);
                        resetBtn();
                    }
                    return;
                } catch (android.content.ActivityNotFoundException e2) {
                    String errSuffix2 = testOnly ? "（测试结束）" : "，尝试 ACTION_VIEW";
                    Log.w(TAG, "ACTION_INSTALL_PACKAGE 不可用" + errSuffix2);
                    if (testOnly) {
                        AppToast.show(activity, "测试：ACTION_INSTALL_PACKAGE 不可用", true);
                        resetBtn();
                        return;
                    }
                }
            }

            // 3. ACTION_VIEW
            try {
                Intent install = new Intent(Intent.ACTION_VIEW);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                            activity, activity.getPackageName() + ".fileprovider", apkFile);
                    install.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    install.setDataAndType(android.net.Uri.fromFile(apkFile), "application/vnd.android.package-archive");
                }
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(install);
                if (testOnly) {
                    AppToast.show(activity, "测试成功：ACTION_VIEW 可用", true);
                    resetBtn();
                }
                return;
            } catch (android.content.ActivityNotFoundException e3) {
                String errSuffix3 = testOnly ? "（测试结束）" : "";
                Log.w(TAG, "ACTION_VIEW 不可用" + errSuffix3);
                if (testOnly) {
                    AppToast.show(activity, "测试：所有安装方式均不可用", true);
                    resetBtn();
                    return;
                }
            }

            // 4. 兜底：复制到下载目录（仅非测试模式）
            copyApkToDownloads(apkFile, null);
        } catch (Exception e) {
            Log.e(TAG, "安装失败", e);
            if (testOnly) {
                AppToast.show(activity, "测试异常：" + e.getMessage(), true);
                resetBtn();
            } else {
                copyApkToDownloads(apkFile, e);
            }
        }
    }

    private void copyApkToDownloads(File apkFile, Exception originalError) {
        try {
            File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) downloadDir.mkdirs();
            File targetFile = new File(downloadDir, "FNTV_update_" + apkFile.getName());
            try (FileInputStream fis = new FileInputStream(apkFile);
                 FileOutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
            }
            final String msg = "安装器不可用，APK 已复制到：\n" + targetFile.getAbsolutePath()
                    + "\n请用文件管理器打开此文件进行安装。";
            Log.w(TAG, msg);
            activity.runOnUiThread(() -> new android.app.AlertDialog.Builder(activity)
                    .setTitle("安装失败")
                    .setMessage(msg)
                    .setPositiveButton("我知道了", (d, w) -> resetBtn())
                    .show());
        } catch (Exception copyErr) {
            Log.e(TAG, "复制失败", copyErr);
            activity.runOnUiThread(() -> {
                String errMsg = originalError != null ? "安装失败: " + originalError.getMessage() : "安装器不可用";
                new android.app.AlertDialog.Builder(activity)
                        .setTitle("安装失败")
                        .setMessage(errMsg + "\nAPK 位置：\n" + apkFile.getAbsolutePath())
                        .setPositiveButton("我知道了", (d, w) -> resetBtn())
                        .show();
            });
        }
    }

    // ========== 签名校验 ==========

    private boolean verifyApkSignature(File apkFile) {
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            android.content.pm.PackageInfo currentInfo;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                currentInfo = pm.getPackageInfo(activity.getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                if (currentInfo.signingInfo == null) return false;
                android.content.pm.Signature[] sigs = currentInfo.signingInfo.getApkContentsSigners();
                if (sigs == null || sigs.length == 0) return false;
                String currentSig = android.util.Base64.encodeToString(sigs[0].toByteArray(), android.util.Base64.NO_WRAP);
                Log.d(TAG, "当前签名: " + currentSig.substring(0, Math.min(20, currentSig.length())) + "...");
                return true;
            } else {
                currentInfo = pm.getPackageInfo(activity.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
                return currentInfo.signatures != null && currentInfo.signatures.length > 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "签名校验异常", e);
            return false;
        }
    }

    // ========== 工具 ==========

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b & 0xFF));
        return sb.toString().trim();
    }
}
