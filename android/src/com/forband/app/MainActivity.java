package com.forband.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 单 Activity 的 WebView 壳。
 *
 * <p>宿主必须自己实现两件事，网页才可能录音和选文件：
 * {@link WebChromeClient#onPermissionRequest}（麦克风）与
 * {@link WebChromeClient#onShowFileChooser}（文件选择器）。手机自带浏览器建的
 * 桌面快捷方式正是缺了这两块，所以那里点了没反应。
 *
 * <p>安全边界：只允许 {@link BuildConfig#SITE_ORIGIN} 留在 WebView 内；外部链接交给系统
 * 浏览器；SSL 错误一律取消；不开启明文流量；JS 接口只注入 {@link HapticBridge} 一个
 * 无参数、无返回值的轻触事件（#245），并在每次调用时复核当前页面来源。
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1001;
    private static final int REQ_FILE = 1002;
    /** 网页端录音上限 8 分钟（暂停不计）；再多留 30 秒，覆盖保存/上传的收尾。 */
    private static final long RECORDING_MAX_MS = 480_000L;
    private static final long KEEP_SCREEN_EXTRA_MS = 30_000L;
    private static final String STATUS_BAR_COLOR = "#13211D";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingMicRequest;
    private UpdateChecker updateChecker;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable releaseKeepScreenOn = new Runnable() {
        @Override
        public void run() {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor(STATUS_BAR_COLOR));
        getWindow().setNavigationBarColor(Color.parseColor(STATUS_BAR_COLOR));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor(STATUS_BAR_COLOR));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor(STATUS_BAR_COLOR));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        configureWebView();
        updateChecker = new UpdateChecker(this);
        updateChecker.check();
        webView.loadUrl(BuildConfig.SITE_ORIGIN + "/events.html");
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // 节拍器由用户点击启动；关掉"必须手势"是为了避免音频被宿主静默挂起
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setUserAgentString(settings.getUserAgentString()
                + " ForBandApp/" + BuildConfig.VERSION_NAME);

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setWebViewClient(new ShellClient());
        webView.setWebChromeClient(new ShellChromeClient());
        // #245：仅一个无参数轻触事件；没有 VIBRATE 权限、不开放任意振动参数。
        webView.addJavascriptInterface(new HapticBridge(), "BandNative");
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url != null && isHttp(url)) {
                openExternal(url);
            }
        });
    }

    /**
     * #245 抽屉完全展开时的轻触反馈。
     *
     * <p>只暴露 {@link #openDrawerFeedback()} 一个无参数事件；调用线程是 WebView 的
     * JS 线程，因此切回主线程再执行。每次调用都复核当前页面仍是本站（桥一旦注入，
     * 页面内的任何脚本都能调用它，不能只信注入时的那次来源检查）。
     * {@link View#performHapticFeedback} 使用系统预设，不需要 VIBRATE 权限；
     * 系统关闭触觉时它会返回 false，这里静默忽略。
     */
    private final class HapticBridge {
        @JavascriptInterface
        public void openDrawerFeedback() {
            webView.post(() -> {
                if (!isSiteUrl(webView.getUrl())) {
                    return;
                }
                webView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            });
        }
    }

    private final class ShellClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String url = uri.toString();
            if (isSiteUrl(url)) {
                if (isApk(url)) {
                    // WebView 不该渲染二进制；.apk 交给系统浏览器下载
                    openExternal(url);
                    return true;
                }
                return false;
            }
            String scheme = uri.getScheme();
            if ("blob".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme)
                    || "about".equalsIgnoreCase(scheme)) {
                return false;
            }
            if (isHttp(url)) {
                openExternal(url);
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            // 只拦主框架：重定向到站外时停掉，别把用户困在一个假的地址里
            if (url != null && url.equals(view.getUrl()) && !isSiteUrl(url)) {
                view.stopLoading();
                if (isHttp(url)) {
                    openExternal(url);
                }
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                       android.net.http.SslError error) {
            // 绝不做 SSL 绕过：证书坏了就让用户看到失败，而不是把凭据送进中间人
            handler.cancel();
            Toast.makeText(MainActivity.this, R.string.ssl_error, Toast.LENGTH_LONG).show();
        }
    }

    private final class ShellChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            if (!isSiteOrigin(request.getOrigin())) {
                request.deny();
                return;
            }
            boolean wantsAudio = false;
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    wantsAudio = true;
                    break;
                }
            }
            if (!wantsAudio) {
                request.deny();
                return;
            }
            if (pendingMicRequest != null && pendingMicRequest != request) {
                try {
                    pendingMicRequest.deny();
                } catch (Exception ignored) {
                    // 旧请求可能已被取消
                }
                pendingMicRequest = null;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                onRecordingStarted();
                return;
            }
            pendingMicRequest = request;
            try {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            } catch (Exception e) {
                pendingMicRequest = null;
                request.deny();
            }
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingMicRequest == request) {
                pendingMicRequest = null;
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            // 页面连续点两次时，旧回调必须收尾，否则 input 会永远等不到 change
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Set<String> mimes = new LinkedHashSet<>();
            String[] accept = params == null ? null : params.getAcceptTypes();
            if (accept != null) {
                for (String raw : accept) {
                    String mime = normalizeMime(raw);
                    if (mime != null) {
                        mimes.add(mime);
                    }
                }
            }
            if (mimes.isEmpty()) {
                intent.setType("*/*");
            } else if (mimes.size() == 1) {
                intent.setType(mimes.iterator().next());
            } else {
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
            }
            if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }

            try {
                startActivityForResult(Intent.createChooser(intent, getString(R.string.pick_file)),
                        REQ_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this, R.string.no_browser, Toast.LENGTH_LONG).show();
                return false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        ValueCallback<Uri[]> callback = filePathCallback;
        filePathCallback = null;
        if (callback == null) {
            return;
        }
        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            if (results == null && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    List<Uri> uris = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        if (uri != null) {
                            uris.add(uri);
                        }
                    }
                    if (!uris.isEmpty()) {
                        results = uris.toArray(new Uri[0]);
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
        }
        callback.onReceiveValue(results);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode != REQ_MIC) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        PermissionRequest request = pendingMicRequest;
        pendingMicRequest = null;
        if (request == null) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            onRecordingStarted();
        } else {
            request.deny();
        }
    }

    /** 录音开始：保住屏幕与节拍器可闻；不做任何强制的音频路由改动。 */
    private void onRecordingStarted() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(releaseKeepScreenOn);
        handler.postDelayed(releaseKeepScreenOn, RECORDING_MAX_MS + KEEP_SCREEN_EXTRA_MS);
        warnIfMediaMuted();
    }

    private void warnIfMediaMuted() {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            return;
        }
        boolean muted;
        try {
            muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        } catch (Exception e) {
            muted = false;
        }
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || muted) {
            Toast.makeText(this, R.string.mic_volume_zero, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        if (updateChecker != null) {
            updateChecker.check();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (filePathCallback != null) {
            try {
                filePathCallback.onReceiveValue(null);
            } catch (Exception ignored) {
                // WebView 可能已经回收
            }
            filePathCallback = null;
        }
        if (pendingMicRequest != null) {
            try {
                pendingMicRequest.deny();
            } catch (Exception ignored) {
                // 请求可能已被取消
            }
            pendingMicRequest = null;
        }
        if (webView != null) {
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /** 交给系统浏览器。只接受本类验证过的 http(s) 链接。 */
    void openExternal(String url) {
        if (url == null || !isHttp(url)) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isHttp(String url) {
        return url != null
                && (url.regionMatches(true, 0, "https://", 0, 8)
                || url.regionMatches(true, 0, "http://", 0, 7));
    }

    private static boolean isApk(String url) {
        try {
            String path = Uri.parse(url).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".apk");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSiteUrl(String url) {
        if (url == null) {
            return false;
        }
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if ("blob".equals(scheme) || "data".equals(scheme) || "about".equals(scheme)) {
            return true;
        }
        if (!"https".equals(scheme)) {
            return false;
        }
        return sameOrigin(uri, Uri.parse(BuildConfig.SITE_ORIGIN));
    }

    private static boolean isSiteOrigin(Uri origin) {
        if (origin == null || !"https".equalsIgnoreCase(origin.getScheme())) {
            return false;
        }
        return sameOrigin(origin, Uri.parse(BuildConfig.SITE_ORIGIN));
    }

    private static boolean sameOrigin(Uri a, Uri b) {
        String hostA = a.getHost();
        String hostB = b.getHost();
        return hostA != null && hostB != null
                && hostA.equalsIgnoreCase(hostB)
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(Uri uri) {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** 页面 accept 里既有扩展名也有 MIME；统一成具体 MIME，避免落到录音机 App。 */
    private static String normalizeMime(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "*/*".equals(value)) {
            return null;
        }
        if (value.startsWith(".")) {
            switch (value) {
                case ".m4a":
                case ".mp4":
                case ".aac":
                    return "audio/mp4";
                case ".mp3":
                    return "audio/mpeg";
                case ".wav":
                case ".wave":
                    return "audio/wav";
                case ".ogg":
                case ".oga":
                    return "audio/ogg";
                case ".flac":
                    return "audio/flac";
                default:
                    return null;
            }
        }
        if ("audio/x-m4a".equals(value) || "audio/m4a".equals(value)) {
            return "audio/mp4";
        }
        return value.contains("/") ? value : null;
    }
}
