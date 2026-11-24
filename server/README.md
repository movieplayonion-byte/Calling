# WPRD Push Server (incoming-call notifications)

A tiny Node.js service that makes WPRD ring like WhatsApp when the app is
closed or in the background:

1. It watches the Firebase Realtime Database path `/calls`.
2. When a new call appears with `status = RINGING`, it reads the callee's
   FCM token from `users/<calleeUid>/fcmToken`.
3. It sends a **high-priority, data-only** FCM message:

   ```json
   {
     "type": "incoming_call",
     "callId": "...",
     "callerUid": "91...",
     "callerName": "...",
     "callerPhone": "+91...",
     "callType": "VOICE | VIDEO"
   }
   ```

4. The Android app (`WprdMessagingService`) receives it and posts the
   full-screen ringing notification itself.

## What you need first: a service-account key

The key does **not** exist yet and is **never** stored in this repo
(it is git-ignored — a leaked key gives full access to the Firebase project).

1. Open [Firebase Console](https://console.firebase.google.com) → project
   **wprd-call-f7b87**.
2. ⚙️ **Project settings** → **Service accounts** tab.
3. **Generate new private key** → a JSON file downloads. Rename it to
   `serviceAccountKey.json`.

## Deploy on Replit (exact steps)

1. Create a new **Node.js** Repl (e.g. name it `wprd-push`).
2. Upload these two files from this `server/` folder into the Repl root:
   - `index.js`
   - `package.json`
3. Upload `serviceAccountKey.json` into the Repl root as well
   (Files panel → ⋮ → *Upload file*). It stays inside your private Repl —
   do not paste its contents anywhere public.
   - Alternative: in the Repl's **Secrets** tab you cannot store a file, so
     if you prefer, set the secret `SERVICE_ACCOUNT_PATH` to wherever you
     placed the file. By default the code reads `./serviceAccountKey.json`.
4. (Optional) Add a Secret `DATABASE_URL` =
   `https://wprd-call-f7b87-default-rtdb.firebaseio.com`
   — this is already the built-in default, so you can skip it.
5. In the Repl **Shell**, run:

   ```bash
   npm install
   npm start
   ```

   You should see:

   ```text
   [wprd-push] Listening for new RINGING calls on /calls ...
   ```

6. Press **Run** so `npm start` is the run command, and (on a paid Replit
   plan) enable **Always On** for the Repl — a sleeping Repl cannot ring
   phones. Without Always On, the service only works while the Repl is awake.

## Test it

1. Install the WPRD app (v1.1.1+) on two phones, log in on both.
2. Close the app completely on phone B.
3. Call phone B from phone A.
4. Phone B should ring full-screen, and the Replit console should log:
   `[wprd-push] call <id>: push sent to <calleeUid> (...)`.

## Notes

- Only calls created **after** the server starts (and less than 60 seconds
  old) trigger a push, so restarting the server never re-rings old calls.
- The FCM message has a 60-second TTL and Android `priority: high`.
- Blocked callers are filtered on the phone itself (the app checks its
  blocked list before ringing), so no server change is needed for blocking.

## Auth API (v1.2.0+, in auth.js)

The same process also serves the app's login system under `/auth/*`
(every other path still returns the health text):

- `POST /auth/otp/send` `{phone, purpose: "signup"|"reset"}` — sends an
  SMS OTP through MSG91's OTP Widget APIs (default widget config, so no
  DLT registration is needed). Rate limited (cooldown + daily caps).
- `POST /auth/otp/verify` `{phone, otp}` — verifies via MSG91 and
  returns a one-time 10-minute `grantToken`.
- `POST /auth/signup` `{phone, grantToken, name, password}` — creates
  the account (scrypt-hashed password) and returns a session token.
- `POST /auth/login` `{phone, password}` — daily login, NO OTP.
- `POST /auth/reset` `{phone, grantToken, newPassword}` — forgot
  password; revokes all existing sessions.
- `POST /auth/logout` `{sessionToken}`, `GET /auth/session?token=...`

Extra env vars for the auth API:

- `MSG91_TOKEN_AUTH` — **required** for OTP: the value of the widget
  token named "WPRD" (MSG91 dashboard → OTP → Widgets →
  SecureOTPWidget9B31 → Tokens). Without it the OTP endpoints return
  `503 otp_not_configured`. Keep it in the host's secret env, never in
  git.
- `MSG91_AUTHKEY` — optional; enables the verifyAccessToken
  double-check that binds a verified OTP to its phone number.
- `MSG91_WIDGET_ID` — defaults to the WPRD widget id.
