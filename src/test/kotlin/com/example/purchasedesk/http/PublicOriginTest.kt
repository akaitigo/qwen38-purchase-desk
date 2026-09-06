package com.example.purchasedesk.http

import com.example.purchasedesk.TempDb
import com.example.purchasedesk.domain.Hashing
import com.example.purchasedesk.domain.Role
import org.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the `publicOrigin` configuration:
 *  - cookies carry the `Secure` flag when publicOrigin is set
 *  - cookies do NOT carry `Secure` in the loopback-HTTP default
 *  - the same-origin check accepts the fixed public origin
 *  - wrong scheme / port / host / malformed origins are rejected
 *  - X-Forwarded-* headers are ignored
 *  - cookie clearing preserves `Secure`
 *
 * These tests drive a plain-HTTP loopback socket with a Host header of
 * `localhost:18443`, mirroring the production deployment where a trusted
 * reverse proxy terminates TLS and forwards HTTP to the app. The app itself
 * does not perform TLS.
 */
class PublicOriginTest {

    private lateinit var t: TempDb
    private lateinit var server: ApiServer
    private val publicOrigin = "https://localhost:18443"
    private val hostHeader = "localhost:18443"

    @BeforeTest
    fun setUp() {
        t = TempDb()
        t.db.createUser("alice", Role.EMPLOYEE, Hashing.hashPassword("alice-pw"))
        t.db.createUser("boss", Role.APPROVER, Hashing.hashPassword("boss-pw"))
        server = ApiServer(t.db, t.service, "127.0.0.1", 0, publicOrigin)
    }

    @AfterTest
    fun tearDown() {
        try { server.close() } catch (_: Exception) {}
        t.close()
    }

    // ---------- raw socket helper ----------

    private data class RawResp(
        val status: Int,
        val rawHeaders: String,
        val body: String,
        val setCookies: List<String>,
        val json: JSONObject
    ) {
        fun setCookie(name: String): String? =
            setCookies.firstOrNull { it.startsWith("$name=") }
                ?.substringAfter("=")?.substringBefore(";")
        fun header(name: String): String? =
            rawHeaders.lineSequence()
                .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
    }

    private fun raw(
        method: String,
        path: String,
        host: String,
        cookie: String? = null,
        origin: String? = null,
        csrf: String? = null,
        body: String? = null,
        contentType: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): RawResp {
        val sock = java.net.Socket("127.0.0.1", server.port).apply { soTimeout = 5000 }
        try {
            val out = sock.getOutputStream()
            val sb = StringBuilder()
            sb.append("$method $path HTTP/1.1\r\n")
            sb.append("Host: $host\r\n")
            if (cookie != null) sb.append("Cookie: $cookie\r\n")
            if (origin != null) sb.append("Origin: $origin\r\n")
            if (csrf != null) sb.append("X-CSRF-Token: $csrf\r\n")
            if (contentType != null) sb.append("Content-Type: $contentType\r\n")
            for ((k, v) in extraHeaders) sb.append("$k: $v\r\n")
            val bodyBytes = body?.toByteArray() ?: ByteArray(0)
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
            sb.append("Connection: close\r\n\r\n")
            val headerBytes = sb.toString().toByteArray()
            out.write(headerBytes)
            out.write(bodyBytes)
            out.flush()
            val rawText = sock.getInputStream().bufferedReader().readText()
            val hdrEnd = rawText.indexOf("\r\n\r\n")
            val hdrSection = rawText.substring(0, hdrEnd)
            val bodySection = rawText.substring(hdrEnd + 4)
            val status = hdrSection.lineSequence().first().split(" ")[1].toInt()
            val setCookies = hdrSection.lineSequence()
                .filter { it.startsWith("Set-Cookie:", ignoreCase = true) }
                .map { it.substringAfter(":").trim() }
                .toList()
            val json = if (bodySection.isNotBlank()) JSONObject(bodySection) else JSONObject()
            return RawResp(status, hdrSection, bodySection, setCookies, json)
        } finally {
            sock.close()
        }
    }

    /** Mints an anonymous session and returns (anonCookie, csrf). */
    private fun anon(): Pair<String, String> {
        val s = raw("GET", "/api/session", host = hostHeader)
        assertEquals(200, s.status, "anon session: ${s.body}")
        val cookie = s.setCookie("pd_anon")
        assertNotNull(cookie, "anon cookie must be set")
        return cookie to s.json.getString("csrfToken")
    }

    /** Logs in and returns (sessionCookie, csrf). */
    private fun login(user: String = "alice", pw: String = "alice-pw"): Pair<String, String> {
        val (anonCookie, csrf) = anon()
        val body = """{"username":"$user","password":"$pw"}"""
        val r = raw(
            "POST", "/api/login", host = hostHeader,
            cookie = "pd_anon=$anonCookie",
            origin = publicOrigin, csrf = csrf,
            body = body, contentType = "application/json"
        )
        assertEquals(200, r.status, "login: ${r.body}")
        val sessionCookie = r.setCookie("pd_session")
        assertNotNull(sessionCookie, "login must set pd_session")
        return sessionCookie to r.json.getString("csrfToken")
    }

    // ---------- cookie attributes ----------

    @Test
    fun `session cookie has Secure flag when publicOrigin is set`() {
        val (anonCookie, csrf) = anon()
        val body = """{"username":"alice","password":"alice-pw"}"""
        val r = raw(
            "POST", "/api/login", host = hostHeader,
            cookie = "pd_anon=$anonCookie",
            origin = publicOrigin, csrf = csrf,
            body = body, contentType = "application/json"
        )
        assertEquals(200, r.status, r.body)
        val sc = r.setCookies.firstOrNull { it.startsWith("pd_session=") }
        assertNotNull(sc, "session cookie must be set")
        assertTrue(sc.contains("Secure"), "session cookie must carry Secure: $sc")
        assertTrue(sc.contains("HttpOnly"), "session cookie must carry HttpOnly: $sc")
        assertTrue(sc.contains("SameSite="), "session cookie must carry SameSite: $sc")
    }

    @Test
    fun `anonymous cookie has Secure flag when publicOrigin is set`() {
        val s = raw("GET", "/api/session", host = hostHeader)
        val sc = s.setCookies.firstOrNull { it.startsWith("pd_anon=") }
        assertNotNull(sc, "anonymous cookie must be set")
        assertTrue(sc.contains("Secure"), "anonymous cookie must carry Secure: $sc")
    }

    @Test
    fun `loopback-HTTP default does NOT set Secure on cookies`() {
        // Boot a second server without publicOrigin.
        val t2 = TempDb()
        try {
            t2.db.createUser("alice", Role.EMPLOYEE, Hashing.hashPassword("alice-pw"))
            val s2 = ApiServer(t2.db, t2.service, "127.0.0.1", 0)
            try {
                val sock = java.net.Socket("127.0.0.1", s2.port).apply { soTimeout = 5000 }
                try {
                    val out = sock.getOutputStream()
                    val reqBytes = "GET /api/session HTTP/1.1\r\nHost: 127.0.0.1:${s2.port}\r\nConnection: close\r\n\r\n".toByteArray()
                    out.write(reqBytes)
                    out.flush()
                    val rawText = sock.getInputStream().bufferedReader().readText()
                    val sc = rawText.lineSequence()
                        .filter { it.startsWith("Set-Cookie:", ignoreCase = true) }
                        .map { it.substringAfter(":").trim() }
                        .firstOrNull { it.startsWith("pd_anon=") }
                    assertNotNull(sc, "anon cookie must be set")
                    assertTrue(!sc.contains("Secure"), "loopback-HTTP default must NOT carry Secure: $sc")
                } finally {
                    sock.close()
                }
            } finally {
                s2.close()
            }
        } finally {
            t2.close()
        }
    }

    @Test
    fun `cookie clearing on logout preserves Secure flag`() {
        val (cookie, csrf) = login()
        val r = raw(
            "POST", "/api/logout", host = hostHeader,
            cookie = "pd_session=$cookie",
            origin = publicOrigin, csrf = csrf,
            body = "{}", contentType = "application/json"
        )
        assertEquals(200, r.status, r.body)
        val sc = r.setCookies.firstOrNull { it.startsWith("pd_session=") }
        assertNotNull(sc, "logout must clear pd_session")
        assertTrue(sc.contains("Secure"), "cleared session cookie must carry Secure: $sc")
        assertTrue(sc.contains("Max-Age=0"), "cleared session cookie must have Max-Age=0: $sc")
    }

    // ---------- origin validation under publicOrigin ----------

    @Test
    fun `mutating request with correct public origin succeeds`() {
        val (cookie, csrf) = login()
        val body = """{"itemName":"ok","quantity":1,"unitPriceYen":100,"reason":"r"}"""
        val r = raw(
            "POST", "/api/requests", host = hostHeader,
            cookie = "pd_session=$cookie",
            origin = publicOrigin, csrf = csrf,
            body = body, contentType = "application/json"
        )
        assertEquals(201, r.status, "correct-origin create must succeed: ${r.body}")
    }

    @Test
    fun `mutating request with http scheme is rejected`() {
        val (cookie, csrf) = login()
        val body = """{"itemName":"bad","quantity":1,"unitPriceYen":100,"reason":"r"}"""
        // http://localhost (default port 80) — wrong scheme and port.
        val r = raw(
            "POST", "/api/requests", host = hostHeader,
            cookie = "pd_session=$cookie",
            origin = "http://localhost", csrf = csrf,
            body = body, contentType = "application/json"
        )
        assertEquals(403, r.status, "http://localhost must be rejected: ${r.body}")
    }

    @Test
    fun `mutating request with wrong https port is rejected`() {
        val (cookie, csrf) = login()
        val body = """{"itemName":"bad","quantity":1,"unitPriceYen":100,"reason":"r"}"""
        val r = raw(
            "POST", "/api/requests", host = hostHeader,
            cookie = "pd_session=$cookie",
            origin = "https://localhost:9999", csrf = csrf,
            body = body, contentType = "application/json"
        )
        assertEquals(403, r.status, "wrong https port must be rejected: ${r.body}")
    }

    @Test
    fun `mutating request with foreign host is rejected`() {
        val (cookie, csrf) = login()
        val body = """{"itemName":"bad","quantity":1,"unitPriceYen":100,"reason":"r"}"""
        val r = raw(
            "POST", "/api/requests", host = hostHeader,
            cookie = "pd_session=$cookie",
            origin = "https://evil.example.com:18443", csrf = csrf,
            body = body, contentType = "application/json"
        )
        assertEquals(403, r.status, "foreign host must be rejected: ${r.body}")
    }

    @Test
    fun `mutating request with malformed origin is rejected`() {
        val (cookie, csrf) = login()
        val body = """{"itemName":"bad","quantity":1,"unitPriceYen":100,"reason":"r"}"""
        for (bad in listOf("not-a-url", "ftp://localhost:18443", "http://", "null")) {
            val r = raw(
                "POST", "/api/requests", host = hostHeader,
                cookie = "pd_session=$cookie",
                origin = bad, csrf = csrf,
                body = body, contentType = "application/json"
            )
            assertEquals(403, r.status, "origin='$bad' must be rejected: ${r.body}")
        }
    }

    @Test
    fun `mutating request with spoofed x-forwarded headers is rejected`() {
        val (cookie, csrf) = login()
        val body = """{"itemName":"bad","quantity":1,"unitPriceYen":100,"reason":"r"}"""
        val r = raw(
            "POST", "/api/requests", host = hostHeader,
            cookie = "pd_session=$cookie",
            origin = "https://evil.example.com", csrf = csrf,
            body = body, contentType = "application/json",
            extraHeaders = mapOf(
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "localhost:18443",
                "X-Forwarded-Port" to "18443"
            )
        )
        assertEquals(403, r.status, "spoofed X-Forwarded-* must not bypass origin check: ${r.body}")
    }

    @Test
    fun `login with wrong origin is 403 and no session cookie issued`() {
        val (anonCookie, csrf) = anon()
        val body = """{"username":"alice","password":"alice-pw"}"""
        val r = raw(
            "POST", "/api/login", host = hostHeader,
            cookie = "pd_anon=$anonCookie",
            origin = "https://evil.example.com", csrf = csrf,
            body = body, contentType = "application/json"
        )
        assertEquals(403, r.status, "login with wrong origin must be 403: ${r.body}")
        // No authenticated session cookie should be issued (no Max-Age=0 clear either,
        // because the anonymous path does not clear the session cookie).
        val nonClearSession = r.setCookies.filter {
            it.startsWith("pd_session=") && !it.contains("Max-Age=0")
        }
        assertTrue(nonClearSession.isEmpty(),
            "no authenticated session cookie should be set on rejected login: ${r.setCookies}")
    }

    @Test
    fun `logout with wrong origin is 403 and session stays valid`() {
        val (cookie, csrf) = login()
        val r = raw(
            "POST", "/api/logout", host = hostHeader,
            cookie = "pd_session=$cookie",
            origin = "https://evil.example.com", csrf = csrf,
            body = "{}", contentType = "application/json"
        )
        assertEquals(403, r.status, "logout with wrong origin must be 403: ${r.body}")
        // Session must still be valid.
        val s = raw("GET", "/api/session", host = hostHeader, cookie = "pd_session=$cookie")
        assertEquals(200, s.status)
        assertEquals("alice", s.json.getJSONObject("user").getString("username"))
    }

    @Test
    fun `full create-submit-approve flow works under publicOrigin`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        // create
        val created = raw(
            "POST", "/api/requests", host = hostHeader,
            cookie = "pd_session=$aliceC",
            origin = publicOrigin, csrf = aliceCsrf,
            body = """{"itemName":"mouse","quantity":1,"unitPriceYen":1500,"reason":"work"}""",
            contentType = "application/json"
        )
        assertEquals(201, created.status, "create: ${created.body}")
        val id = created.json.getJSONObject("request").getString("id")
        // submit
        val submitted = raw(
            "POST", "/api/requests/$id/submit", host = hostHeader,
            cookie = "pd_session=$aliceC",
            origin = publicOrigin, csrf = aliceCsrf,
            body = "{}", contentType = "application/json"
        )
        assertEquals(200, submitted.status, "submit: ${submitted.body}")
        // approve
        val approved = raw(
            "POST", "/api/requests/$id/approve", host = hostHeader,
            cookie = "pd_session=$bossC",
            origin = publicOrigin, csrf = bossCsrf,
            body = "{}", contentType = "application/json"
        )
        assertEquals(200, approved.status, "approve: ${approved.body}")
        assertEquals("APPROVED", approved.json.getJSONObject("request").getString("state"))
    }
}
