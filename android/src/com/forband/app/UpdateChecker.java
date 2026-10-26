package com.forband.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 壳自身的版本检查：只有壳能力变化才需要重发 APK；网页更新不走这里。
 *
 * <p>静默失败是刻意的：版本检查不能因为网络不好而打扰正常使用。
 */
final class UpdateChecker {

    private static final String PREFS = "forband_update";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_PROMPTED = "prompted_version";
    private static final long INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int MAX_BYTES = 8192;

    private final MainActivity activity;
    private volatile boolean running;
    private boolean dialogShowing;

    UpdateChecker(MainActivity activity) {
        this.activity = activity;
    }

    void check() {
        if (running || dialogShowing) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < INTERVAL_MS) {
            return;
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();
        running = true;
        new Thread(this::fetch, "forband-update").start();
    }

    private void fetch() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BuildConfig.SITE_ORIGIN + "/app/version.json?ts="
                    + System.currentTimeMillis());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (conn.getResponseCode() != 200) {
                return;
            }
            JSONObject json = new JSONObject(readLimited(conn.getInputStream(), MAX_BYTES));
            final int versionCode = json.optInt("versionCode", 0);
            if (versionCode <= BuildConfig.VERSION_CODE) {
                return;
            }
            final String versionName = json.optString("versionName", String.valueOf(versionCode));
            final String notes = json.optString("notes", "").trim();
            final String apkUrl = safeApkUrl(json.optString("apk", ""));

            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.getInt(KEY_PROMPTED, 0) >= versionCode) {
                return;
            }
            prefs.edit().putInt(KEY_PROMPTED, versionCode).apply();
            activity.runOnUiThread(() -> show(versionName, notes, apkUrl));
        } catch (Exception ignored) {
            // 网络/解析失败静默
        } finally {
            running = false;
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void show(String versionName, String notes, String apkUrl) {
        if (activity.isFinishing()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) {
            return;
        }
        if (dialogShowing) {
            return;
        }
        dialogShowing = true;
        String tail = "下载后请手动安装覆盖，数据会保留。";
        String message = TextUtils.isEmpty(notes) ? tail : notes + "\n\n" + tail;
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_title, versionName))
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton(R.string.update_now, (dialog, which) -> activity.openExternal(apkUrl))
                .setNegativeButton(R.string.update_later, null)
                .setOnDismissListener(dialog -> dialogShowing = false)
                .show();
    }

    private static String safeApkUrl(String candidate) {
        Uri site = Uri.parse(BuildConfig.SITE_ORIGIN);
        if (!TextUtils.isEmpty(candidate)) {
            Uri uri = Uri.parse(candidate);
            String path = uri.getPath();
            boolean sameHost = site.getHost() != null
                    && site.getHost().equalsIgnoreCase(uri.getHost());
            boolean samePort = effectivePort(site) == effectivePort(uri);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean isApk = path != null && path.toLowerCase(Locale.ROOT).endsWith(".apk");
            if (sameHost && samePort && https && isApk) {
                return candidate;
            }
        }
        return BuildConfig.SITE_ORIGIN + "/app/band.apk";
    }

    private static int effectivePort(Uri uri) {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String readLimited(InputStream in, int limit) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = limit - out.size();
                if (remaining <= 0) {
                    break;
                }
                out.write(buffer, 0, Math.min(read, remaining));
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
