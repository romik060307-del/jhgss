package com.rk.ohlisocial;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.graphics.Color;
import android.text.TextUtils;


import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class OhliFirebaseMessagingService extends FirebaseMessagingService {
    public static final String KEY_REPLY = "ohli_reply_text";

    @Override public void onNewToken(String token) {
        super.onNewToken(token);
        getSharedPreferences("ohli_fcm", MODE_PRIVATE).edit().putString("fcm_token", token).apply();
        // Refresh device registration even when MainActivity is not currently open.
        android.content.SharedPreferences session = getSharedPreferences("ohli_native_session", MODE_PRIVATE);
        String auth = session.getString("auth_token", "");
        if (!TextUtils.isEmpty(auth) && !TextUtils.isEmpty(token)) {
            new Thread(() -> registerToken(auth, token), "ohli-fcm-token-refresh").start();
        }
    }

    private void registerToken(String auth, String fcmToken) {
        String server = getSharedPreferences("ohli_server_config", MODE_PRIVATE)
                .getString("server_url", BuildConfig.SERVER_FALLBACK_URL);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(server.replaceAll("/$", "") + "/api/push/register").openConnection();
            connection.setRequestMethod("POST"); connection.setConnectTimeout(3500); connection.setReadTimeout(3500); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-RKM-Token", auth);
            JSONObject body = new JSONObject(); body.put("token", auth); body.put("fcmToken", fcmToken);
            body.put("deviceId", android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));
            OutputStream out = connection.getOutputStream(); out.write(body.toString().getBytes("UTF-8")); out.flush(); out.close();
            connection.getResponseCode();
        } catch (Exception ignored) { } finally { if (connection != null) connection.disconnect(); }
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        Map<String, String> data = message.getData();
        if (data == null || data.isEmpty()) return;
        if (getSharedPreferences("ohli_app_state", MODE_PRIVATE).getBoolean("foreground", false)) return;
        String type = value(data, "type", "social");
        String title = value(data, "title", "OHLI Social");
        String body = value(data, "body", "Ada aktivitas baru.");
        String peer = value(data, "peer", "");
        showNativeNotification(type, title, body, peer, data);
    }

    private String value(Map<String, String> data, String key, String fallback) {
        String v = data.get(key); return v == null || v.trim().length() == 0 ? fallback : v;
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        AudioAttributes audio = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        createChannel(nm, "ohli_chat_v1", "Pesan Chat", R.raw.ohli_message, NotificationManager.IMPORTANCE_HIGH, audio);
        createChannel(nm, "ohli_social_v1", "Aktivitas Sosial", R.raw.ohli_social, NotificationManager.IMPORTANCE_DEFAULT, audio);
        createChannel(nm, "ohli_voice_call_v1", "Panggilan Suara", R.raw.ohli_call_audio, NotificationManager.IMPORTANCE_HIGH, audio);
        createChannel(nm, "ohli_video_call_v1", "Panggilan Video", R.raw.ohli_call_video, NotificationManager.IMPORTANCE_HIGH, audio);
        createChannel(nm, "ohli_live_v1", "Undangan LIVE", R.raw.ohli_live, NotificationManager.IMPORTANCE_HIGH, audio);
    }

    private void createChannel(NotificationManager nm, String id, String name, int soundId, int importance, AudioAttributes audio) {
        NotificationChannel ch = new NotificationChannel(id, name, importance);
        ch.enableVibration(true); ch.setVibrationPattern(new long[]{0, 180, 90, 180});
        ch.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + soundId), audio);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(ch);
    }

    private void showNativeNotification(String type, String title, String body, String peer, Map<String, String> data) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        ensureChannels();
        String channel = "ohli_social_v1";
        if ("chat".equals(type)) channel = "ohli_chat_v1";
        else if ("call_audio".equals(type)) channel = "ohli_voice_call_v1";
        else if ("call_video".equals(type)) channel = "ohli_video_call_v1";
        else if ("live_invite".equals(type)) channel = "ohli_live_v1";

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!TextUtils.isEmpty(peer)) open.putExtra("ohli_peer", peer);
        if (type.startsWith("call_") || "live_invite".equals(type)) open.putExtra("ohli_open_calls", true);
        int id = (type + peer + value(data, "callId", "")).hashCode() & 0x7fffffff;
        PendingIntent openPending = PendingIntent.getActivity(this, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder builder = new Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ohli_notification_icon)
                .setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openPending).setAutoCancel(true)
                .setColor(Color.rgb(155, 117, 255)).setShowWhen(true);

        if ("chat".equals(type)) {
            builder.setCategory(Notification.CATEGORY_MESSAGE);
            addReplyAction(builder, peer, id);
        } else if (type.startsWith("call_")) {
            builder.setCategory(Notification.CATEGORY_CALL).setOnlyAlertOnce(false);
            builder.setPriority(Notification.PRIORITY_MAX);
        } else if ("live_invite".equals(type)) {
            builder.setCategory(Notification.CATEGORY_EVENT).setPriority(Notification.PRIORITY_HIGH);
        } else {
            builder.setCategory(Notification.CATEGORY_SOCIAL);
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, builder.build());
    }

    private void addReplyAction(Notification.Builder builder, String peer, int notificationId) {
        if (TextUtils.isEmpty(peer) || !peer.matches("[0-9]{8,15}")) return;
        Intent reply = new Intent(this, OhliNotificationReplyReceiver.class);
        reply.setAction("com.rk.ohlisocial.REPLY");
        reply.putExtra("peer", peer); reply.putExtra("notification_id", notificationId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(this, notificationId, reply, flags);
        RemoteInput input = new RemoteInput.Builder(KEY_REPLY).setLabel("Balas pesan…").build();
        Notification.Action action = new Notification.Action.Builder(R.drawable.ohli_notification_icon, "Balas", pending)
                .addRemoteInput(input).setAllowGeneratedReplies(true).build();
        builder.addAction(action);
    }
}
