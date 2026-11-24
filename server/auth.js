/**
 * WPRD auth: MSG91 SMS OTP (signup / forgot-password) + password login.
 *
 * Design: see ~/workspace/goals/wprd-android-calling-app/hidden_files/
 * v120-auth-design.md. In short:
 *   - OTP send/verify is proxied through MSG91's OTP *Widget* APIs
 *     (default widget config => MSG91's own DLT sender/template, no DLT
 *     registration needed on our side). The server authkey never
 *     leaves this server.
 *   - After MSG91 verifies an OTP, this server mints its own one-time
 *     "grant" token (10 min, purpose-bound) that the app exchanges for
 *     signup / password reset.
 *   - Passwords are scrypt-hashed with per-user salts. Session and
 *     grant tokens are random; only their SHA-256 is stored in RTDB.
 *
 * Config (env vars):
 *   MSG91_WIDGET_ID   widget id (default: the WPRD widget created
 *                     2026-11-24, "SecureOTPWidget9B31")
 *   MSG91_AUTHKEY     the widget's server-side authkey (dashboard: OTP ->
 *                     Widgets -> SecureOTPWidget9B31 -> Server Side
 *                     Integration; ours is named "WPRDServer"). Sent as
 *                     the `authkey` header on every widget API call —
 *                     MSG91 accepts it for sendOtp / verifyOtp /
 *                     verifyAccessToken (verified live 2026-11-24:
 *                     bogus values then fail with record/token errors,
 *                     not AuthenticationFailure). This is the primary
 *                     server credential.
 *   MSG91_TOKEN_AUTH  the widget's client tokenAuth (Tokens tab).
 *                     REQUIRED for sendOtp / verifyOtp: those APIs
 *                     validate the body tokenAuth, not the account
 *                     authkey — verified live 2026-11-24 by probing:
 *                     no tokenAuth in body => "Invalid request";
 *                     bogus tokenAuth => AuthenticationFailure 401.
 *                     The authkey header alone only authenticates
 *                     verifyAccessToken and getWidgetProcess. Without
 *                     MSG91_TOKEN_AUTH set, live OTP sends fail with
 *                     otp_send_failed even when the authkey is set.
 *   MSG91_BASE_URL    override for testing (default control.msg91.com).
 */
'use strict';

const crypto = require('crypto');

const MSG91_BASE = process.env.MSG91_BASE_URL || 'https://control.msg91.com/api/v5/widget';
const WIDGET_ID = process.env.MSG91_WIDGET_ID || '366b78697958303636353130';
const TOKEN_AUTH = process.env.MSG91_TOKEN_AUTH || '';
const AUTHKEY = process.env.MSG91_AUTHKEY || '';

const SESSION_TTL_MS = 30 * 24 * 3600 * 1000; // 30 days
const GRANT_TTL_MS = 10 * 60 * 1000; // 10 minutes
const OTP_TTL_MS = 15 * 60 * 1000; // matches the widget's expiryTime (15 min, per getWidgetProcess)
const SEND_COOLDOWN_MS = { signup: 60 * 1000, reset: 5 * 60 * 1000 };
const DAILY_SEND_CAP = { signup: 5, reset: 3 };
const MAX_VERIFY_ATTEMPTS = 5;
const LOGIN_MAX_FAILS = 10;
const LOGIN_WINDOW_MS = 60 * 60 * 1000;
const LOGIN_LOCK_MS = 15 * 60 * 1000;
const MIN_PASSWORD_LEN = 6;

// ---------------------------------------------------------------- utils

/** Same normalisation as the app's ProfileSyncManager.normalizePhoneKey. */
function normalizePhoneKey(phone) {
  const digits = String(phone || '').replace(/\D/g, '');
  if (!digits) return '';
  if (digits.length === 10) return '91' + digits;
  if (digits.length === 11 && digits.startsWith('0')) return '91' + digits.slice(1);
  return digits;
}

function sha256Hex(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex');
}

function randomToken() {
  return crypto.randomBytes(32).toString('hex');
}

/** Calendar day in Asia/Kolkata as YYYY-MM-DD (rate-limit windows). */
function istDayKey(ts = Date.now()) {
  return new Date(ts + 5.5 * 3600 * 1000).toISOString().slice(0, 10);
}

function scryptAsync(password, salt, keylen) {
  return new Promise((resolve, reject) => {
    crypto.scrypt(password, salt, keylen, { N: 16384, r: 8, p: 1 }, (err, key) =>
      err ? reject(err) : resolve(key)
    );
  });
}

async function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const key = await scryptAsync(password, salt, 64);
  return { salt: salt.toString('base64'), passwordHash: key.toString('base64') };
}

async function verifyPassword(password, saltB64, expectedB64) {
  const salt = Buffer.from(saltB64, 'base64');
  const expected = Buffer.from(expectedB64, 'base64');
  const actual = await scryptAsync(password, salt, expected.length);
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 64 * 1024) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (chunks.length === 0) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(new Error('invalid json'));
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(body);
}

// ------------------------------------------------------------- MSG91 API

async function msg91Post(path, body, extraHeaders = {}) {
  const resp = await fetch(`${MSG91_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...extraHeaders },
    body: JSON.stringify(body),
  });
  let data = null;
  try {
    data = await resp.json();
  } catch {
    /* MSG91 sometimes returns plain text on hard failures */
  }
  return { httpStatus: resp.status, data };
}

function msg91Ok(result) {
  return result && result.data && result.data.type === 'success';
}

/** Widget API auth: the server authkey travels in the `authkey` header;
 *  the client tokenAuth (if configured) rides in the body as well. */
function widgetAuthHeaders() {
  return AUTHKEY ? { authkey: AUTHKEY } : {};
}

function widgetBody(fields) {
  return {
    widgetId: WIDGET_ID,
    ...(TOKEN_AUTH ? { tokenAuth: TOKEN_AUTH } : {}),
    ...fields,
  };
}

// ------------------------------------------------------------ the handler

function createAuthHandler(db) {
  if (!TOKEN_AUTH && !AUTHKEY) {
    console.warn(
      '[wprd-auth] Neither MSG91_AUTHKEY nor MSG91_TOKEN_AUTH is set: OTP ' +
        'endpoints will return 503 otp_not_configured. Get the authkey from ' +
        'MSG91 dashboard -> OTP -> Widgets -> SecureOTPWidget9B31 -> Server ' +
        'Side Integration.'
    );
  } else if (AUTHKEY) {
    console.log(
      '[wprd-auth] MSG91 authkey configured: widget APIs use authkey-header ' +
        'auth; verifyAccessToken phone-binding check is ON.'
    );
  }

  const authRef = (key) => db.ref(`auth/${key}`);
  const otpRef = (key) => db.ref(`authOtp/${key}`);

  async function issueSession(phoneKey) {
    const token = randomToken();
    const now = Date.now();
    const expiresAt = now + SESSION_TTL_MS;
    await db.ref(`authSessions/${sha256Hex(token)}`).set({ phoneKey, createdAt: now, expiresAt });
    await authRef(phoneKey).child('sessionHashes').child(sha256Hex(token)).set(true);
    return { sessionToken: token, expiresAt };
  }

  async function revokeAllSessions(phoneKey, record) {
    const hashes = record && record.sessionHashes ? Object.keys(record.sessionHashes) : [];
    const updates = {};
    for (const h of hashes) updates[`authSessions/${h}`] = null;
    updates[`auth/${phoneKey}/sessionHashes`] = null;
    if (Object.keys(updates).length > 1 || hashes.length) {
      await db.ref().update(updates);
    }
  }

  // ------------------------------------------------------------ OTP send
  async function handleOtpSend(req, res, body) {
    const phoneKey = normalizePhoneKey(body.phone);
    const purpose = body.purpose === 'reset' ? 'reset' : 'signup';
    if (!phoneKey || phoneKey.length < 10) {
      return sendJson(res, 400, { ok: false, error: 'invalid_phone' });
    }
    if (!TOKEN_AUTH && !AUTHKEY) {
      return sendJson(res, 503, { ok: false, error: 'otp_not_configured' });
    }

    const accountSnap = await authRef(phoneKey).once('value');
    const accountExists = accountSnap.exists();
    if (purpose === 'signup' && accountExists) {
      return sendJson(res, 409, { ok: false, error: 'account_exists' });
    }
    if (purpose === 'reset' && !accountExists) {
      // No account enumeration: pretend success, send nothing.
      console.log(`[wprd-auth] reset OTP requested for unknown phone ${phoneKey}; ignored`);
      return sendJson(res, 200, { ok: true });
    }

    const now = Date.now();
    const otpSnap = await otpRef(phoneKey).once('value');
    const otpState = otpSnap.val() || {};
    if (otpState.lastSentAt && now - otpState.lastSentAt < SEND_COOLDOWN_MS[purpose]) {
      const retryAfterSec = Math.ceil((SEND_COOLDOWN_MS[purpose] - (now - otpState.lastSentAt)) / 1000);
      return sendJson(res, 429, { ok: false, error: 'cooldown', retryAfterSec });
    }
    const today = istDayKey(now);
    const countField = purpose === 'signup' ? 'signupCount' : 'resetCount';
    const sentToday = otpState.dayKey === today ? otpState[countField] || 0 : 0;
    if (sentToday >= DAILY_SEND_CAP[purpose]) {
      return sendJson(res, 429, { ok: false, error: 'daily_limit' });
    }

    let result;
    try {
      result = await msg91Post('/sendOtp', widgetBody({ identifier: phoneKey }), widgetAuthHeaders());
    } catch (err) {
      console.error('[wprd-auth] MSG91 sendOtp failed:', err.message);
      return sendJson(res, 502, { ok: false, error: 'otp_send_failed' });
    }
    if (!msg91Ok(result)) {
      console.error('[wprd-auth] MSG91 sendOtp error:', JSON.stringify(result.data));
      return sendJson(res, 502, { ok: false, error: 'otp_send_failed' });
    }

    const reqId = result.data.message;
    const counters = {
      dayKey: today,
      signupCount: otpState.dayKey === today ? otpState.signupCount || 0 : 0,
      resetCount: otpState.dayKey === today ? otpState.resetCount || 0 : 0,
    };
    counters[countField] = sentToday + 1;
    await otpRef(phoneKey).set({
      reqId,
      purpose,
      sentAt: now,
      expiresAt: now + OTP_TTL_MS,
      verifyAttempts: 0,
      lastSentAt: now,
      ...counters,
    });
    console.log(`[wprd-auth] OTP sent (${purpose}) to ${phoneKey}`);
    return sendJson(res, 200, { ok: true });
  }

  // ---------------------------------------------------------- OTP verify
  async function handleOtpVerify(req, res, body) {
    const phoneKey = normalizePhoneKey(body.phone);
    const otp = String(body.otp || '').trim();
    if (!phoneKey || !/^\d{4,8}$/.test(otp)) {
      return sendJson(res, 400, { ok: false, error: 'invalid_request' });
    }
    if (!TOKEN_AUTH && !AUTHKEY) {
      return sendJson(res, 503, { ok: false, error: 'otp_not_configured' });
    }

    const otpSnap = await otpRef(phoneKey).once('value');
    const otpState = otpSnap.val();
    if (!otpState || !otpState.reqId) {
      return sendJson(res, 400, { ok: false, error: 'no_otp_requested' });
    }
    if (Date.now() > (otpState.expiresAt || 0)) {
      return sendJson(res, 400, { ok: false, error: 'otp_expired' });
    }
    if ((otpState.verifyAttempts || 0) >= MAX_VERIFY_ATTEMPTS) {
      return sendJson(res, 429, { ok: false, error: 'too_many_attempts' });
    }
    await otpRef(phoneKey).child('verifyAttempts').set((otpState.verifyAttempts || 0) + 1);

    let result;
    try {
      result = await msg91Post(
        '/verifyOtp',
        widgetBody({ reqId: otpState.reqId, otp }),
        widgetAuthHeaders()
      );
    } catch (err) {
      console.error('[wprd-auth] MSG91 verifyOtp failed:', err.message);
      return sendJson(res, 502, { ok: false, error: 'otp_verify_failed' });
    }
    if (!msg91Ok(result)) {
      return sendJson(res, 400, { ok: false, error: 'invalid_otp' });
    }

    // Defense-in-depth: bind the JWT to this phone via verifyAccessToken.
    // The access token is single-use, so this is the one place it is spent.
    const accessToken = result.data.message;
    if (AUTHKEY && accessToken) {
      try {
        const check = await msg91Post('/verifyAccessToken', { 'access-token': accessToken }, { authkey: AUTHKEY });
        if (!msg91Ok(check)) {
          console.error('[wprd-auth] verifyAccessToken rejected:', JSON.stringify(check.data));
          return sendJson(res, 400, { ok: false, error: 'invalid_otp' });
        }
        const verifiedMobile =
          check.data && check.data.data && (check.data.data.mobile || check.data.data.identifier);
        if (verifiedMobile && normalizePhoneKey(verifiedMobile) !== phoneKey) {
          console.error('[wprd-auth] verifyAccessToken phone mismatch');
          return sendJson(res, 400, { ok: false, error: 'invalid_otp' });
        }
      } catch (err) {
        console.error('[wprd-auth] verifyAccessToken call failed:', err.message);
        return sendJson(res, 502, { ok: false, error: 'otp_verify_failed' });
      }
    }

    // OTP proven. Clear the reqId (keep rate-limit counters) and mint a
    // one-time grant the app exchanges for signup / reset.
    await otpRef(phoneKey).update({ reqId: null, verifyAttempts: 0 });
    const grantToken = randomToken();
    const now = Date.now();
    const purpose = otpState.purpose === 'reset' ? 'reset' : 'signup';
    await db.ref(`authGrants/${sha256Hex(grantToken)}`).set({
      phoneKey,
      purpose,
      createdAt: now,
      expiresAt: now + GRANT_TTL_MS,
      used: false,
    });
    console.log(`[wprd-auth] OTP verified (${purpose}) for ${phoneKey}; grant issued`);
    return sendJson(res, 200, { ok: true, grantToken, purpose, expiresAt: now + GRANT_TTL_MS });
  }

  /** Load + validate + consume a grant. Returns the grant record or null. */
  async function consumeGrant(grantToken, phoneKey, purpose) {
    if (!grantToken) return null;
    const ref = db.ref(`authGrants/${sha256Hex(grantToken)}`);
    const snap = await ref.once('value');
    const grant = snap.val();
    if (!grant || grant.used || grant.phoneKey !== phoneKey || grant.purpose !== purpose) return null;
    if (Date.now() > (grant.expiresAt || 0)) return null;
    await ref.child('used').set(true);
    return grant;
  }

  // -------------------------------------------------------------- signup
  async function handleSignup(req, res, body) {
    const phoneKey = normalizePhoneKey(body.phone);
    const name = String(body.name || '').trim();
    const password = String(body.password || '');
    if (!phoneKey || phoneKey.length < 10 || name.length < 1) {
      return sendJson(res, 400, { ok: false, error: 'invalid_request' });
    }
    if (password.length < MIN_PASSWORD_LEN) {
      return sendJson(res, 400, { ok: false, error: 'weak_password' });
    }
    const existing = await authRef(phoneKey).once('value');
    if (existing.exists()) {
      return sendJson(res, 409, { ok: false, error: 'account_exists' });
    }
    const grant = await consumeGrant(body.grantToken, phoneKey, 'signup');
    if (!grant) {
      return sendJson(res, 403, { ok: false, error: 'otp_required' });
    }
    const { salt, passwordHash } = await hashPassword(password);
    const now = Date.now();
    await authRef(phoneKey).set({
      passwordHash,
      salt,
      algo: 'scrypt',
      name,
      createdAt: now,
      updatedAt: now,
    });
    const session = await issueSession(phoneKey);
    console.log(`[wprd-auth] signup complete for ${phoneKey}`);
    return sendJson(res, 200, { ok: true, phone: '+' + phoneKey, ...session });
  }

  // --------------------------------------------------------------- login
  async function handleLogin(req, res, body) {
    const phoneKey = normalizePhoneKey(body.phone);
    const password = String(body.password || '');
    if (!phoneKey || !password) {
      return sendJson(res, 400, { ok: false, error: 'invalid_request' });
    }
    const snap = await authRef(phoneKey).once('value');
    const record = snap.val();
    if (!record || !record.passwordHash) {
      return sendJson(res, 401, { ok: false, error: 'invalid_credentials' });
    }
    const now = Date.now();
    const meta = record.loginMeta || {};
    if (meta.lockedUntil && now < meta.lockedUntil) {
      return sendJson(res, 429, { ok: false, error: 'locked', retryAfterSec: Math.ceil((meta.lockedUntil - now) / 1000) });
    }

    const ok = await verifyPassword(password, record.salt, record.passwordHash);
    if (!ok) {
      const inWindow = meta.windowStart && now - meta.windowStart < LOGIN_WINDOW_MS;
      const fails = (inWindow ? meta.fails || 0 : 0) + 1;
      const newMeta = {
        fails,
        windowStart: inWindow ? meta.windowStart : now,
        lockedUntil: fails >= LOGIN_MAX_FAILS ? now + LOGIN_LOCK_MS : 0,
      };
      await authRef(phoneKey).child('loginMeta').set(newMeta);
      return sendJson(res, 401, { ok: false, error: 'invalid_credentials' });
    }

    await authRef(phoneKey).child('loginMeta').set({ fails: 0, windowStart: 0, lockedUntil: 0 });
    const session = await issueSession(phoneKey);
    console.log(`[wprd-auth] login ok for ${phoneKey}`);
    return sendJson(res, 200, { ok: true, phone: '+' + phoneKey, name: record.name || '', ...session });
  }

  // --------------------------------------------------------------- reset
  async function handleReset(req, res, body) {
    const phoneKey = normalizePhoneKey(body.phone);
    const newPassword = String(body.newPassword || '');
    if (!phoneKey || newPassword.length < MIN_PASSWORD_LEN) {
      return sendJson(res, 400, { ok: false, error: 'weak_password' });
    }
    const snap = await authRef(phoneKey).once('value');
    const record = snap.val();
    if (!record) {
      return sendJson(res, 404, { ok: false, error: 'no_account' });
    }
    const grant = await consumeGrant(body.grantToken, phoneKey, 'reset');
    if (!grant) {
      return sendJson(res, 403, { ok: false, error: 'otp_required' });
    }
    const { salt, passwordHash } = await hashPassword(newPassword);
    await authRef(phoneKey).update({
      passwordHash,
      salt,
      algo: 'scrypt',
      updatedAt: Date.now(),
      loginMeta: { fails: 0, windowStart: 0, lockedUntil: 0 },
    });
    await revokeAllSessions(phoneKey, record);
    console.log(`[wprd-auth] password reset for ${phoneKey}; sessions revoked`);
    return sendJson(res, 200, { ok: true });
  }

  // ------------------------------------------------------- logout/session
  async function handleLogout(req, res, body) {
    const token = String(body.sessionToken || '');
    if (token) {
      const hash = sha256Hex(token);
      const snap = await db.ref(`authSessions/${hash}`).once('value');
      const sess = snap.val();
      if (sess && sess.phoneKey) {
        await authRef(sess.phoneKey).child('sessionHashes').child(hash).remove();
      }
      await db.ref(`authSessions/${hash}`).remove();
    }
    return sendJson(res, 200, { ok: true });
  }

  async function handleSession(req, res, url) {
    const token = url.searchParams.get('token') || '';
    if (!token) return sendJson(res, 400, { ok: false, error: 'invalid_request' });
    const snap = await db.ref(`authSessions/${sha256Hex(token)}`).once('value');
    const sess = snap.val();
    if (!sess || Date.now() > (sess.expiresAt || 0)) {
      return sendJson(res, 200, { ok: true, valid: false });
    }
    return sendJson(res, 200, { ok: true, valid: true, phone: '+' + sess.phoneKey });
  }

  // -------------------------------------------------------------- routing
  return async function handleAuth(req, res) {
    const url = new URL(req.url, 'http://localhost');
    const pathName = url.pathname;
    try {
      if (req.method === 'GET' && pathName === '/auth/session') {
        return await handleSession(req, res, url);
      }
      if (req.method !== 'POST') {
        return sendJson(res, 405, { ok: false, error: 'method_not_allowed' });
      }
      const body = await readJsonBody(req);
      switch (pathName) {
        case '/auth/otp/send':
          return await handleOtpSend(req, res, body);
        case '/auth/otp/verify':
          return await handleOtpVerify(req, res, body);
        case '/auth/signup':
          return await handleSignup(req, res, body);
        case '/auth/login':
          return await handleLogin(req, res, body);
        case '/auth/reset':
          return await handleReset(req, res, body);
        case '/auth/logout':
          return await handleLogout(req, res, body);
        default:
          return sendJson(res, 404, { ok: false, error: 'not_found' });
      }
    } catch (err) {
      console.error('[wprd-auth] handler error:', err.message);
      return sendJson(res, 500, { ok: false, error: 'server_error' });
    }
  };
}

module.exports = { createAuthHandler, normalizePhoneKey };
