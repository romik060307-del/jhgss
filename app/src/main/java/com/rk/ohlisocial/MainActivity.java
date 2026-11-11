package com.rk.ohlisocial;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.media.AudioAttributes;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends Activity {
    private static final String APP_ASSET_HOST = "appassets.androidplatform.net";
    private static final String APP_ASSET_ORIGIN = "https://" + APP_ASSET_HOST;
    private static final String APP_START_URL = APP_ASSET_ORIGIN + "/assets/index.html";
    private static final int REQ_MEDIA = 2101;
    private static final int REQ_GEO = 2102;
    private static final int REQ_FILE = 2103;
    private static final int REQ_NOTIFICATIONS = 2104;
    private static final String NOTIFICATION_CHANNEL = "ohli_chat_v1";
    private static final String SERVER_PREFS = "ohli_server_config";
    private static final String SESSION_PREFS = "ohli_native_session";
    private static final String PUSH_PREFS = "ohli_fcm";
    private static final int NOTIFICATION_ID_CHAT = 4101;
    private static final int NOTIFICATION_ID_SOCIAL = 4102;
    private String currentServerUrl = BuildConfig.SERVER_FALLBACK_URL;
    private boolean clientStarted = false;
    private String pendingNotificationPeer = "";
    private boolean pendingNotificationCalls = false;
    private FrameLayout root;
    private FrameLayout webContainer;
    private WebView webView;
    private ProgressBar progress;
    private View splashView;
    private View offlineView;
    private boolean isForeground = true;
    private boolean firstPageLoaded = false;
    private boolean cacheFallbackAttempted = false;
    private boolean notificationPermissionAsked = false;
    private int lastChatUnread = 0;
    private int lastSocialUnread = 0;
    private PermissionRequest pendingWebPermission;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private ValueCallback<Uri[]> fileChooserCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int previousSystemUiVisibility;
    private WebViewAssetLoader assetLoader;
    private final Handler handler = new Handler();

    private final Runnable splashTimeout = new Runnable() {
        @Override public void run() {
            if (!firstPageLoaded && splashView != null) splashView.setVisibility(View.GONE);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(8, 12, 24));
        getWindow().setNavigationBarColor(Color.rgb(8, 12, 24));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        createNotificationChannels();

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 12, 24));
        webContainer = new FrameLayout(this);
        root.addView(webContainer, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(174, 124, 255)));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP);
        root.addView(progress, pp);
        splashView = buildSplash();
        root.addView(splashView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        lastChatUnread = getPreferences(MODE_PRIVATE).getInt("last_chat_unread", 0);
        lastSocialUnread = getPreferences(MODE_PRIVATE).getInt("last_social_unread", 0);
        currentServerUrl = getSharedPreferences(SERVER_PREFS, MODE_PRIVATE)
                .getString("server_url", BuildConfig.SERVER_FALLBACK_URL);
        handler.postDelayed(splashTimeout, 15000L);
        // Fetch the small no-cache Netlify config once per cold launch. A 2.2 s hard cap
        // keeps startup responsive; the last working URL remains the offline fallback.
        new Thread(() -> {
            String latest = fetchServerConfigOnce();
            if (latest != null) {
                currentServerUrl = latest;
                getSharedPreferences(SERVER_PREFS, MODE_PRIVATE).edit().putString("server_url", latest).apply();
            }
            runOnUiThread(() -> startClientWebView());
        }, "ohli-server-config").start();
    }


    private void startClientWebView() {
        if (clientStarted || isFinishing()) return;
        clientStarted = true;
        createWebView();
        handleNotificationIntent(getIntent());
        registerFcmTokenWithServer();
    }

    private String fetchServerConfigOnce() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(BuildConfig.SERVER_CONFIG_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("Pragma", "no-cache");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && body.length() < 8192) body.append(line);
            reader.close();
            String candidate = new JSONObject(body.toString()).optString("server_url", "").trim();
            Uri parsed = Uri.parse(candidate);
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null || parsed.getUserInfo() != null) return null;
            if (candidate.length() > 500) return null;
            return candidate.endsWith("/") ? candidate : candidate + "/";
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null) return;
        String peer = intent.getStringExtra("ohli_peer");
        boolean calls = intent.getBooleanExtra("ohli_open_calls", false);
        if (peer != null && peer.matches("[0-9]{8,15}")) pendingNotificationPeer = peer;
        if (calls) pendingNotificationCalls = true;
        intent.removeExtra("ohli_peer");
        intent.removeExtra("ohli_open_calls");
        dispatchPendingNotificationIntent();
    }

    private void dispatchPendingNotificationIntent() {
        if (webView == null || !firstPageLoaded) return;
        final String peer = pendingNotificationPeer;
        final boolean calls = pendingNotificationCalls;
        if ((peer == null || peer.length() == 0) && !calls) return;
        pendingNotificationPeer = "";
        pendingNotificationCalls = false;
        webView.postDelayed(() -> {
            if (webView == null) return;
            if (peer != null && peer.matches("[0-9]{8,15}")) {
                webView.evaluateJavascript("window.ohliRuntime&&window.ohliRuntime.openChatWith(" + JSONObject.quote(peer) + ")", null);
            } else if (calls) {
                webView.evaluateJavascript("window.ohliRuntime&&window.ohliRuntime.openCalls&&window.ohliRuntime.openCalls()", null);
            }
        }, 350L);
    }

    private void registerFcmTokenWithServer() {
        final android.content.SharedPreferences session = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE);
        final String authToken = session.getString("auth_token", "");
        final String phone = session.getString("phone", "");
        if (authToken.length() == 0 || phone.length() == 0) return;
        try {
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(fcmToken -> {
                if (fcmToken == null || fcmToken.length() < 50) return;
                getSharedPreferences(PUSH_PREFS, MODE_PRIVATE).edit().putString("fcm_token", fcmToken).apply();
                new Thread(() -> postPushRegistration(authToken, fcmToken, true), "ohli-push-register").start();
            });
        } catch (Exception ignored) { /* Firebase is inactive until google-services.json is supplied. */ }
    }

    private void postPushRegistration(String authToken, String fcmToken, boolean register) {
        HttpURLConnection connection = null;
        try {
            String endpoint = currentServerUrl.replaceAll("/$", "") + (register ? "/api/push/register" : "/api/push/unregister");
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(3500);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-RKM-Token", authToken);
            JSONObject body = new JSONObject();
            body.put("token", authToken);
            body.put("fcmToken", fcmToken);
            body.put("deviceId", android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));
            byte[] bytes = body.toString().getBytes("UTF-8");
            OutputStream out = connection.getOutputStream(); out.write(bytes); out.flush(); out.close();
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally { if (connection != null) connection.disconnect(); }
    }

    private View buildSplash() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(Color.rgb(8, 12, 24));
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.rk.ohlisocial.R.drawable.ohli_icon);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(116), dp(116));
        panel.addView(logo, lp);
        TextView title = new TextView(this);
        title.setText("OHLI Social");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(18);
        panel.addView(title, tp);
        TextView subtitle = new TextView(this);
        subtitle.setText("Chat · Social · Video · LIVE");
        subtitle.setTextColor(Color.rgb(174, 182, 209));
        subtitle.setTextSize(12);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(7);
        panel.addView(subtitle, sp);
        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(30), dp(30));
        bp.topMargin = dp(24);
        panel.addView(spinner, bp);
        return panel;
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void createWebView() {
        if (webView != null) {
            webContainer.removeView(webView);
            webView.destroy();
        }
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(7, 10, 20));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setCacheMode(hasUsableNetwork() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(true);
        settings.setDefaultTextEncodingName("UTF-8");
        String ua = settings.getUserAgentString();
        if (ua != null) settings.setUserAgentString(ua.replace("; wv", "").replace("Version/4.0 ", ""));

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new OhliAndroidBridge(), "OHLIAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(12);
            }

            @Override public void onPageFinished(WebView view, String url) {
                progress.setProgress(100);
                progress.setVisibility(View.GONE);
                if (!firstPageLoaded) {
                    firstPageLoaded = true;
                    handler.removeCallbacks(splashTimeout);
                    if (splashView != null) splashView.animate().alpha(0f).setDuration(180L).withEndAction(() -> splashView.setVisibility(View.GONE)).start();
                    maybeAskNotificationPermission();
                }
                dispatchPendingNotificationIntent();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    if (!cacheFallbackAttempted) {
                        cacheFallbackAttempted = true;
                        view.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                        view.postDelayed(() -> view.loadUrl(APP_START_URL), 120L);
                    } else if (!firstPageLoaded) {
                        showOfflinePage();
                    }
                }
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 500 && !firstPageLoaded) {
                    showOfflinePage();
                }
            }

            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (fileChooserCallback != null) { fileChooserCallback.onReceiveValue(null); fileChooserCallback = null; }
                if (pendingWebPermission != null) { pendingWebPermission.deny(); pendingWebPermission = null; }
                Toast.makeText(MainActivity.this, "Halaman OHLI dimuat ulang setelah proses WebView berhenti.", Toast.LENGTH_LONG).show();
                handler.postDelayed(() -> {
                    if (!isFinishing()) {
                        try { createWebView(); webView.loadUrl(APP_START_URL); } catch (Exception ignored) { }
                    }
                }, 250L);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override public void onReceivedTitle(WebView view, String title) {
                maybeNotifyUnreadChat(title);
            }

            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                runOnUiThread(() -> handleGeolocationPrompt(origin, callback));
            }

            @Override public boolean onShowFileChooser(WebView web, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "Pemilih file tidak tersedia pada perangkat ini.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                previousSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                webContainer.addView(customView, new FrameLayout.LayoutParams(-1, -1));
            }

            @Override public void onHideCustomView() { hideCustomView(); }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimeType);
                    request.addRequestHeader("User-Agent", userAgent);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) request.addRequestHeader("Cookie", cookie);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setTitle(downloadName(contentDisposition, mimeType));
                    request.setDescription("Mengunduh dari OHLI Social");
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, downloadName(contentDisposition, mimeType));
                    ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                    Toast.makeText(MainActivity.this, "Unduhan dimulai. Periksa folder Download.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Unduhan tidak dapat dimulai.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        webContainer.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        webView.loadUrl(APP_START_URL);
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if ("https".equals(scheme) || "http".equals(scheme)) return false; // Keep web links inside OHLI.
        if ("tel".equals(scheme) || "mailto".equals(scheme) || "sms".equals(scheme)) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
            return true;
        }
        return true;
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        if (request == null) return;
        Uri origin = Uri.parse(request.getOrigin().toString());
        if (!APP_ASSET_HOST.equalsIgnoreCase(origin.getHost()) || !"https".equalsIgnoreCase(origin.getScheme())) {
            request.deny();
            return;
        }
        List<String> missing = new ArrayList<>();
        String[] resources = request.getResources();
        for (String resource : resources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA);
            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
            else if (!PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && !PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                request.deny();
                return;
            }
        }
        if (missing.isEmpty()) request.grant(resources);
        else {
            pendingWebPermission = request;
            String[] ask = missing.toArray(new String[0]);
            requestPermissions(ask, REQ_MEDIA);
        }
    }

    private void handleGeolocationPrompt(String origin, GeolocationPermissions.Callback callback) {
        Uri uri = Uri.parse(origin);
        if (!APP_ASSET_HOST.equalsIgnoreCase(uri.getHost()) || !"https".equalsIgnoreCase(uri.getScheme())) {
            callback.invoke(origin, false, false);
            return;
        }
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) callback.invoke(origin, true, false);
        else {
            pendingGeoCallback = callback;
            pendingGeoOrigin = origin;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MEDIA) {
            boolean granted = true;
            for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) granted = false;
            PermissionRequest request = pendingWebPermission;
            pendingWebPermission = null;
            if (request != null) {
                if (granted) request.grant(request.getResources()); else request.deny();
            }
        } else if (requestCode == REQ_GEO) {
            boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null; pendingGeoOrigin = null;
        } else if (requestCode == REQ_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) Toast.makeText(this, "Notifikasi OHLI diizinkan pada perangkat ini.", Toast.LENGTH_SHORT).show();
        }
    }

    private void maybeAskNotificationPermission() {
        if (notificationPermissionAsked || Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        notificationPermissionAsked = true;
        new AlertDialog.Builder(this)
                .setTitle("Izinkan notifikasi OHLI")
                .setMessage("Izin ini diperlukan agar OHLI dapat menampilkan pesan, panggilan, undangan LIVE, dan aktivitas sosial. Pastikan notifikasi OHLI juga diaktifkan di pengaturan Android.")
                .setNegativeButton("Nanti", null)
                .setPositiveButton("Izinkan", (d, w) -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS))
                .show();
    }

    private void maybeNotifyUnreadChat(String title) {
        if (title == null) return;
        Matcher matcher = Pattern.compile("^\\((\\d+)\\)").matcher(title.trim());
        if (!matcher.find()) return;
        int count;
        try { count = Integer.parseInt(matcher.group(1)); } catch (Exception e) { return; }
        if (count > lastChatUnread && !isForeground) {
            postLocalNotification(NOTIFICATION_ID_CHAT, "Pesan OHLI belum dibaca", "Ada " + count + " pesan yang belum dibaca.");
        }
        lastChatUnread = count;
        getPreferences(MODE_PRIVATE).edit().putInt("last_chat_unread", count).apply();
    }

    private void onSocialUnreadCount(int count) {
        if (count > lastSocialUnread && !isForeground) {
            postLocalNotification(NOTIFICATION_ID_SOCIAL, "Aktivitas OHLI baru", "Ada " + count + " notifikasi sosial yang belum dibaca.");
        }
        lastSocialUnread = count;
        getPreferences(MODE_PRIVATE).edit().putInt("last_social_unread", count).apply();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        AudioAttributes audio = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        createChannel(manager, "ohli_chat_v1", "Pesan Chat", "Pesan baru dari percakapan OHLI", R.raw.ohli_message, NotificationManager.IMPORTANCE_HIGH, audio);
        createChannel(manager, "ohli_social_v1", "Aktivitas Sosial", "Pengikut, suka, komentar, Story dan aktivitas OHLI", R.raw.ohli_social, NotificationManager.IMPORTANCE_DEFAULT, audio);
        createChannel(manager, "ohli_voice_call_v1", "Panggilan Suara", "Peringatan panggilan suara masuk", R.raw.ohli_call_audio, NotificationManager.IMPORTANCE_HIGH, audio);
        createChannel(manager, "ohli_video_call_v1", "Panggilan Video", "Peringatan video call masuk", R.raw.ohli_call_video, NotificationManager.IMPORTANCE_HIGH, audio);
        createChannel(manager, "ohli_live_v1", "Undangan LIVE", "Undangan untuk bergabung ke OHLI LIVE", R.raw.ohli_live, NotificationManager.IMPORTANCE_HIGH, audio);
    }

    private void createChannel(NotificationManager manager, String id, String name, String description,
                               int soundResource, int importance, AudioAttributes audio) {
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.setDescription(description);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 180, 90, 180});
        Uri sound = Uri.parse("android.resource://" + getPackageName() + "/" + soundResource);
        channel.setSound(sound, audio);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }

    private void postLocalNotification(int id, String title, String text) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, NOTIFICATION_CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ohli_notification_icon)
                .setContentTitle(title).setContentText(text).setContentIntent(pending)
                .setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)
                .setColor(Color.rgb(155, 117, 255));
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(id, builder.build());
    }

    private void showOfflinePage() {
        if (webView == null) return;
        String html = "<!doctype html><html lang='id'><meta name='viewport' content='width=device-width,initial-scale=1'><meta charset='utf-8'><style>body{margin:0;background:#080c18;color:#f4f4ff;font:16px system-ui;display:grid;min-height:100vh;place-items:center;text-align:center}.card{max-width:340px;padding:28px}.mark{width:76px;height:76px;border-radius:22px;background:#151b34;margin:0 auto 18px;display:grid;place-items:center;font-size:30px;color:#b88bff}h2{margin:0 0 9px}p{color:#a7afca;line-height:1.6}button{border:0;border-radius:14px;background:linear-gradient(110deg,#a86cff,#70dff7);padding:13px 22px;font-weight:800;color:#080c18}</style><div class='card'><div class='mark'>O</div><h2>OHLI Social belum tersambung</h2><p>Aplikasi tetap siap dibuka, tetapi server OHLI belum dapat dijangkau. Periksa internet dan pastikan Cloudflare Tunnel serta server host masih aktif.</p><button onclick='OHLIAndroid.retry()'>Coba lagi</button></div></html>";
        webView.loadDataWithBaseURL(APP_ASSET_ORIGIN + "/", html, "text/html", "UTF-8", null);
    }

    private boolean hasUsableNetwork() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network network = manager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) { return true; }
    }

    private String readAssetText(String name) throws Exception {
        InputStream in = getAssets().open(name);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) out.append(line).append('\n');
        reader.close();
        return out.toString();
    }

    private String downloadName(String disposition, String mimeType) {
        String name = "OHLI-download";
        if (disposition != null) {
            Matcher m = Pattern.compile("filename\\*?=(?:UTF-8''|\")?([^;\"]+)", Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (m.find()) name = Uri.decode(m.group(1).replace("\"", "").trim());
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!name.contains(".")) {
            if (mimeType != null && mimeType.contains("image")) name += ".jpg";
            else if (mimeType != null && mimeType.contains("video")) name += ".mp4";
            else if (mimeType != null && mimeType.contains("audio")) name += ".audio";
            else name += ".bin";
        }
        return name;
    }

    private void hideCustomView() {
        if (customView == null) return;
        webContainer.removeView(customView);
        customView = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(previousSystemUiVisibility);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private class OhliAndroidBridge {
        @android.webkit.JavascriptInterface public String getServerUrl() { return currentServerUrl; }
        @android.webkit.JavascriptInterface public void saveSession(String authToken, String phone, String displayName) {
            if (authToken == null || authToken.length() < 16 || phone == null || !phone.matches("[0-9]{8,15}")) return;
            getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit().putString("auth_token", authToken).putString("phone", phone).putString("display_name", displayName == null ? "Pengguna OHLI" : displayName).apply();
            registerFcmTokenWithServer();
        }
        @android.webkit.JavascriptInterface public void clearSession() {
            android.content.SharedPreferences session = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE);
            String oldToken = session.getString("auth_token", "");
            String oldFcm = getSharedPreferences(PUSH_PREFS, MODE_PRIVATE).getString("fcm_token", "");
            if (oldToken.length() > 0 && oldFcm.length() > 0) new Thread(() -> postPushRegistration(oldToken, oldFcm, false), "ohli-push-unregister").start();
            session.edit().clear().apply();
        }
        @android.webkit.JavascriptInterface public void retry() {
            runOnUiThread(() -> {
                cacheFallbackAttempted = false;
                if (webView != null) {
                    webView.getSettings().setCacheMode(hasUsableNetwork() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
                    webView.loadUrl(APP_START_URL);
                }
            });
        }
        @android.webkit.JavascriptInterface public void onSocialUnreadCount(int count) {
            runOnUiThread(() -> onSocialUnreadCount(Math.max(0, count)));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        isForeground = true;
        getSharedPreferences("ohli_app_state", MODE_PRIVATE).edit().putBoolean("foreground", true).apply();
        if (webView != null) webView.onResume();
        registerFcmTokenWithServer();
    }

    @Override protected void onPause() {
        isForeground = false;
        getSharedPreferences("ohli_app_state", MODE_PRIVATE).edit().putBoolean("foreground", false).apply();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileChooserCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (customView != null) { hideCustomView(); return; }
        if (webView != null && webView.canGoBack()) { webView.goBack(); return; }
        new AlertDialog.Builder(this)
                .setTitle("Keluar dari OHLI Social?")
                .setMessage("Kamu bisa membuka aplikasi lagi kapan saja.")
                .setNegativeButton("Tetap di sini", null)
                .setPositiveButton("Keluar", (d, w) -> finish())
                .show();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (pendingWebPermission != null) { pendingWebPermission.deny(); pendingWebPermission = null; }
        if (fileChooserCallback != null) { fileChooserCallback.onReceiveValue(null); fileChooserCallback = null; }
        if (webView != null) {
            webContainer.removeView(webView);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
