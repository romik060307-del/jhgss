# OHLI Social Client v1.2.0 — Dynamic server + native push foundation

This is the user-facing Android client. It bundles the OHLI Social v3.6.27 web interface and does not bundle the server or Cloudflare tunnel engine.

## Dynamic server address (Netlify)

The app reads `https://ohlisocial.netlify.app/server.json` once on each cold launch (1.5-second connection/read limits). If Netlify is unavailable, it uses the last successfully saved server URL, then the bundled fallback URL `https://bonds-kick-graduates-include.trycloudflare.com/`. It never polls the config continuously. The server config file is in the separate `OHLI-Netlify-Control` ZIP; edit only `server.json` and redeploy that folder to the existing Netlify site to rotate the Cloudflare Quick Tunnel hostname. No APK rebuild is required for hostname changes.

## Native notification implementation

This client includes Firebase Cloud Messaging receive support, five Android notification channels, original OHLI WAV sounds, heads-up-capable high-importance chat/call/LIVE channels, notification taps, and inline text reply to a chat message. The reply uses the same authenticated `/api/messages` endpoint.

**FCM cannot work until the Firebase project is configured and the server host has the matching push patch.** This ZIP intentionally does not contain fabricated Firebase credentials. Before building a production client APK:

1. In Firebase Console, create a project and add an Android app with package name `com.rk.ohlisocial`.
2. Download the real `google-services.json` and place it at `app/google-services.json` (do not rename it). The project compiles without this file, but FCM is inactive until it is present.
3. Deploy the Netlify Control repository package that includes `/.netlify/functions/ohli-push` and `server.json`.
4. In Netlify site environment variables, set `FIREBASE_SERVICE_ACCOUNT_JSON` to the service-account JSON string from Firebase/Google Cloud, and `OHLI_PUSH_WEBHOOK_SECRET` to a long random secret. Do not put either secret in the static `server.json`.
5. In the private server-host GitHub repository, add Actions secret `OHLI_PUSH_SECRET` with the exact same value as `OHLI_PUSH_WEBHOOK_SECRET`. Build and install the updated server-host APK from the separate server patch ZIP. Keep the server-host APK private; the webhook secret is compiled into that operator-only APK.
6. Build the client APK with the real `google-services.json`, install it, sign in, and allow Android notifications. The client registers its FCM token with `/api/push/register` after login and refreshes registration when Firebase rotates the device token.

The current backend is a single-host Android server. Native background push is delivered only while that host/server is online and reachable through its current tunnel. If the host phone is shut down or its server process is stopped, Netlify config does not keep the backend alive; it only tells clients the latest address.

## Notification behavior

- Chat: high-importance channel, original OHLI message tone, inline `Balas` action.
- Social: separate softer tone.
- Voice call and video call: separate high-importance alert channels and distinct tones.
- LIVE invite: separate tone and channel.
- Tapping call notifications opens OHLI; the existing WebRTC UI must still load and the user accepts inside the app. This build does not claim a complete Telecom/lock-screen calling UI or continuous ringtone service.
- Android 13+ requires POST_NOTIFICATIONS permission. Channel sound/importance can be changed by the user in Android app notification settings. Android keeps channel choices, so reinstall/clear app data may be needed to re-create them after a sound change.

## Build

1. Add the real `app/google-services.json` before a release build.
2. Upload the contents of this folder to the GitHub repository root.
3. Run **Actions → Build OHLI Social Client APK**.
4. Download artifact `OHLI-Social-Client-APK-v1.2.0`.

## Validation status

Static source checks only. This workspace cannot build Gradle/Android SDK or test on physical Android hardware. Test client login, token registration, message push while app closed, inline reply, call alert, permission denial, and Android battery restrictions on real devices before sharing publicly.
