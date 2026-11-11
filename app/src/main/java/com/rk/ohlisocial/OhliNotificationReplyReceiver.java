package com.rk.ohlisocial;

import android.content.BroadcastReceiver;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import android.net.Uri;

public class OhliNotificationReplyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        CharSequence reply = RemoteInput.getResultsFromIntent(intent) == null ? null : RemoteInput.getResultsFromIntent(intent).getCharSequence(OhliFirebaseMessagingService.KEY_REPLY);
        String peer = intent.getStringExtra("peer");
        String text = reply == null ? "" : reply.toString().trim();
        int notificationId = intent.getIntExtra("notification_id", 0);
        if (TextUtils.isEmpty(text) || text.length() > 2000 || peer == null || !peer.matches("[0-9]{8,15}")) return;
        android.content.SharedPreferences session = context.getSharedPreferences("ohli_native_session", Context.MODE_PRIVATE);
        String authToken = session.getString("auth_token", "");
        final String savedServer = context.getSharedPreferences("ohli_server_config", Context.MODE_PRIVATE)
                .getString("server_url", BuildConfig.SERVER_FALLBACK_URL);
        if (TextUtils.isEmpty(authToken)) return;
        final BroadcastReceiver.PendingResult pending = goAsync();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                // Refresh the dynamic tunnel address before sending a notification reply.
                String latest = fetchLatestServer(context);
                String server = TextUtils.isEmpty(latest) ? savedServer : latest;
                connection = (HttpURLConnection) new URL(server.replaceAll("/$", "") + "/api/messages").openConnection();
                connection.setRequestMethod("POST"); connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("X-RKM-Token", authToken);
                JSONObject body = new JSONObject(); body.put("token", authToken); body.put("recipient", peer); body.put("text", text);
                OutputStream out = connection.getOutputStream(); out.write(body.toString().getBytes("UTF-8")); out.flush(); out.close();
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && notificationId != 0) nm.cancel(notificationId);
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
                pending.finish();
            }
        }, "ohli-notification-reply").start();
    }
    private String fetchLatestServer(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BuildConfig.SERVER_CONFIG_URL).openConnection();
            connection.setConnectTimeout(1200); connection.setReadTimeout(1200); connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder text = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null && text.length() < 4096) text.append(line);
            reader.close();
            String candidate = new JSONObject(text.toString()).optString("server_url", "").trim();
            Uri uri = Uri.parse(candidate);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || candidate.length() > 500) return null;
            String result = candidate.endsWith("/") ? candidate : candidate + "/";
            context.getSharedPreferences("ohli_server_config", Context.MODE_PRIVATE).edit().putString("server_url", result).apply();
            return result;
        } catch (Exception ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }

}
