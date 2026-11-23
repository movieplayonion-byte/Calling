/**
 * WPRD incoming-call push sender.
 *
 * Watches RTDB /calls for new RINGING calls, looks up the callee's FCM
 * token at users/<calleeUid>/fcmToken and sends a high-priority, data-only
 * FCM message. The Android app (WprdMessagingService) turns that into a
 * WhatsApp-style full-screen ringing notification.
 *
 * Config (env vars):
 *   FIREBASE_SERVICE_ACCOUNT_JSON  the full service-account JSON as a string
 *                         (use this on Replit: store it as a Secret — NEVER
 *                         commit the key to git)
 *   SERVICE_ACCOUNT_PATH  path to the Firebase service-account JSON file
 *                         (default: ./serviceAccountKey.json)
 *   DATABASE_URL          RTDB URL (default: the WPRD project's RTDB)
 *   PORT                  health-check HTTP port (default 3000; Replit sets
 *                         this automatically — the HTTP endpoint lets an
 *                         uptime pinger keep the repl awake)
 */
const admin = require('firebase-admin');
const fs = require('fs');
const http = require('http');
const path = require('path');

const databaseURL =
  process.env.DATABASE_URL || 'https://wprd-call-f7b87-default-rtdb.firebaseio.com';

let serviceAccount;
if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON) {
  try {
    serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
  } catch (err) {
    console.error('[wprd-push] FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON:', err.message);
    process.exit(1);
  }
} else {
  const serviceAccountPath =
    process.env.SERVICE_ACCOUNT_PATH || path.join(__dirname, 'serviceAccountKey.json');
  if (!fs.existsSync(serviceAccountPath)) {
    console.error(
      `[wprd-push] Service account file not found: ${serviceAccountPath}\n` +
        'Download it from Firebase Console -> Project settings -> Service accounts ' +
        '-> Generate new private key, and save it as serviceAccountKey.json ' +
        '(see README.md), or set FIREBASE_SERVICE_ACCOUNT_JSON.'
    );
    process.exit(1);
  }
  serviceAccount = require(serviceAccountPath);
}

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL,
});

const db = admin.database();

// Calls that existed before this process started also arrive via
// child_added; only ring for fresh calls so a restart never re-rings
// stale calls.
const MAX_CALL_AGE_MS = 60_000;
const notified = new Set();

async function notifyCallee(callId, call) {
  if (!call || call.status !== 'RINGING') return;
  if (notified.has(callId)) return;

  const createdAt = typeof call.createdAt === 'number' ? call.createdAt : Date.now();
  if (Date.now() - createdAt > MAX_CALL_AGE_MS) return;

  const calleeUid = call.calleeUid || call.receiverUid;
  if (!calleeUid) {
    console.warn(`[wprd-push] call ${callId}: no calleeUid/receiverUid, skipping`);
    return;
  }

  const tokenSnap = await db.ref(`users/${calleeUid}/fcmToken`).once('value');
  const token = tokenSnap.val();
  if (!token) {
    console.log(`[wprd-push] call ${callId}: no FCM token for ${calleeUid}, skipping`);
    return;
  }

  notified.add(callId);
  const message = {
    token,
    data: {
      type: 'incoming_call',
      callId: String(callId),
      callerUid: String(call.callerUid || ''),
      callerName: String(call.callerName || 'Unknown'),
      callerPhone: String(call.callerPhone || ''),
      callType: String(call.callType || 'VOICE'),
    },
    android: {
      priority: 'high',
      ttl: 60 * 1000, // a call that is a minute old should not ring
    },
    // No notification payload on purpose: the app builds the full-screen
    // ringing UI itself from the data payload.
  };

  try {
    const id = await admin.messaging().send(message);
    console.log(`[wprd-push] call ${callId}: push sent to ${calleeUid} (${id})`);
  } catch (err) {
    notified.delete(callId);
    console.error(`[wprd-push] call ${callId}: send failed:`, err.message);
  }
}

const callsRef = db.ref('calls');
callsRef.on('child_added', (snap) => {
  notifyCallee(snap.key, snap.val()).catch((err) =>
    console.error('[wprd-push] notify error:', err.message)
  );
});

console.log('[wprd-push] Listening for new RINGING calls on /calls ...');

// Health endpoint: keeps hosts like Replit awake via an uptime pinger and
// gives a quick way to confirm the sender is alive.
const port = process.env.PORT || 3000;
http
  .createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('wprd-push is alive\n');
  })
  .listen(port, () => console.log(`[wprd-push] Health endpoint on port ${port}`));
