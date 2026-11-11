# Client/server boundary

The v3.6.27 uploaded project is a server-host APK: its `MainActivity` is an admin dashboard, `ServerService` owns the HTTP API and JSON files, and `CloudflareTunnelService` publishes that local server. This new project is a separate client APK.

The client uses the v3.6.27 web front end locally and points `/api/...` requests and `/api/...` media elements to the remote HTTPS server. That avoids downloading HTML/CSS/JS on every launch and makes the user-facing interface available from the APK itself.

The current server source has CORS headers for the app-assets origin and custom `X-RKM-Token` header. Confirm the live host is running a compatible server build. A temporary Cloudflare hostname by itself is not the backend; the host process and its data storage must continue running behind it.

This client caches JSON read responses locally, not mutations. A safe offline-send queue needs a server-supported idempotency key so a retry cannot create duplicate messages after ambiguous network failures. A native closed-app push feature needs server registration and a push provider; Android permission alone does not create push events.
