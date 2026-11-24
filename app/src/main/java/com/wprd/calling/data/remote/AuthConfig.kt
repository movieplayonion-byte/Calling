package com.wprd.calling.data.remote

/**
 * WPRD server (push sender + auth API — one Node process, see
 * server/index.js + server/auth.js) ka base URL.
 *
 * IMPORTANT: ye URL phone se reachable hona chahiye. VM par chalta
 * server (port 3000) seedha phone se reachable NAHI hai — device par
 * test karne se pehle isey server ki public deployment URL se badlein
 * (jaise Replit deployment ki URL). Emulator ke liye 10.0.2.2 host
 * machine ko point karta hai.
 */
object AuthConfig {
    const val SERVER_BASE_URL = "http://10.0.2.2:3000"
}
