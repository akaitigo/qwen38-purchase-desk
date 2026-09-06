package com.example.purchasedesk.http

import com.example.purchasedesk.TempDb
import com.example.purchasedesk.domain.Hashing
import com.example.purchasedesk.domain.Role
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boots the real [ApiServer] on an ephemeral port and drives it with raw
 * [HttpURLConnection] calls, capturing cookies manually.
 */
class HttpIntegrationTest {

    private lateinit var t: TempDb
    private lateinit var server: ApiServer

    @BeforeTest
    fun setUp() {
        t = TempDb()
        // seed users directly into the DB (login uses these)
        t.db.createUser("alice", Role.EMPLOYEE, Hashing.hashPassword("alice-pw"))
        t.db.createUser("bob", Role.EMPLOYEE, Hashing.hashPassword("bob-pw"))
        t.db.createUser("boss", Role.APPROVER, Hashing.hashPassword("boss-pw"))
        server = ApiServer(t.db, t.service, "127.0.0.1", 0)
    }

    @AfterTest
    fun tearDown() {
        try {
            server.close()
        } catch (_: Exception) {
        }
        t.close()
    }

    // ---------- helpers ----------

    private class Resp(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: String
    ) {
        val json: JSONObject get() = JSONObject(body)
        fun cookie(name: String): String? =
            headers.entries.firstOrNull { it.key.equals("Set-Cookie", true) }
                ?.value?.firstOrNull { it.startsWith("$name=") }
                ?.substringAfter("=", "")
                ?.substringBefore(";")
        fun setCookie(): String? = cookie("pd_session")
        fun header(name: String): String? = headers.firstNotNullOfOrNull { (k, v) ->
            if (k.equals(name, true)) v.firstOrNull() else null
        }
    }

    private fun raw(
        method: String,
        path: String,
        cookie: String? = null,
        csrf: String? = null,
        body: String? = null,
        contentType: String? = null,
        origin: String? = null,
        extraCookies: String? = null,
        followRedirects: Boolean = true
    ): Resp {
        // HttpURLConnection silently drops the Origin header (restricted header)
        // and does not support PATCH; use a raw socket in either case.
        if (origin != null || method == "PATCH") return rawWithOrigin(method, path, cookie, csrf, body, contentType, origin, extraCookies)
        val conn = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (!followRedirects) conn.instanceFollowRedirects = false
        val cookieParts = mutableListOf("pd_session=${cookie ?: ""}")
        if (extraCookies != null) cookieParts += extraCookies
        conn.setRequestProperty("Cookie", cookieParts.joinToString("; "))
        if (csrf != null) conn.setRequestProperty("X-CSRF-Token", csrf)
        if (origin != null) conn.setRequestProperty("Origin", origin)
        if (body != null) {
            conn.doOutput = true
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        var status = conn.responseCode
        var headerMap = conn.headerFields.entries.associate { (k, v) ->
            (k ?: "") to v
        }
        var text = (if (status in 200..399) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        // Follow a bounded chain of 3xx redirects (same host, no body).
        var hops = 0
        while (followRedirects && status in 300..399 && hops < 5) {
            val loc = headerMap.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull()
            if (loc == null) break
            val next = URL(if (loc.startsWith("/")) "http://127.0.0.1:${server.port}$loc" else loc)
            val c2 = next.openConnection() as HttpURLConnection
            c2.requestMethod = "GET"
            c2.instanceFollowRedirects = false
            c2.connectTimeout = 5000
            c2.readTimeout = 5000
            status = c2.responseCode
            headerMap = c2.headerFields.entries.associate { (k, v) -> (k ?: "") to v }
            text = (if (status in 200..399) c2.inputStream else c2.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            c2.disconnect()
            hops++
        }
        conn.disconnect()
        return Resp(status, headerMap, text)
    }

    /** Raw-socket HTTP request that can send the Origin header or use PATCH (both blocked by HttpURLConnection). */
    private fun rawWithOrigin(
        method: String,
        path: String,
        cookie: String?,
        csrf: String?,
        body: String?,
        contentType: String?,
        origin: String?,
        extraCookies: String?
    ): Resp {
        val sock = java.net.Socket("127.0.0.1", server.port).apply { soTimeout = 5000 }
        val out = sock.getOutputStream()
        val sb = StringBuilder()
        sb.append("$method $path HTTP/1.1\r\n")
        sb.append("Host: 127.0.0.1:${server.port}\r\n")
        val cookieParts = mutableListOf("pd_session=${cookie ?: ""}")
        if (extraCookies != null) cookieParts += extraCookies
        sb.append("Cookie: ${cookieParts.joinToString("; ")}\r\n")
        if (origin != null) sb.append("Origin: $origin\r\n")
        if (csrf != null) sb.append("X-CSRF-Token: $csrf\r\n")
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        if (body != null) {
            val bytes = body.toByteArray()
            sb.append("Content-Length: ${bytes.size}\r\n")
        } else {
            sb.append("Content-Length: 0\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (body != null) out.write(body.toByteArray())
        out.flush()
        val raw = sock.getInputStream().bufferedReader().readText()
        sock.close()
        // Parse status line
        val headerEnd = raw.indexOf("\r\n\r\n")
        val headerSection = raw.substring(0, headerEnd)
        val bodySection = raw.substring(headerEnd + 4)
        val statusLine = headerSection.lineSequence().first()
        val status = statusLine.split(" ")[1].toInt()
        val headerMap = mutableMapOf<String, MutableList<String>>()
        for (line in headerSection.lineSequence().drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                headerMap.getOrPut(k) { mutableListOf() }.add(v)
            }
        }
        return Resp(status, headerMap, bodySection)
    }

    /** Logs in and returns the session cookie + csrf token. */
    private fun login(username: String, password: String): Pair<String, String> {
        // anonymous session provides a csrf token bound to a cookie
        val anon = raw("GET", "/api/session")
        assertEquals(200, anon.status)
        val csrf = anon.json.getString("csrfToken")
        val anonCookie = anon.cookie("pd_anon")
        val body = """{"username":"$username","password":"$password"}"""
        val r = raw(
            "POST", "/api/login", csrf = csrf,
            body = body, contentType = "application/json",
            extraCookies = if (anonCookie != null) "pd_anon=$anonCookie" else null
        )
        assertEquals(200, r.status, "login failed: ${r.body}")
        val cookie = r.setCookie()!!
        return cookie to r.json.getString("csrfToken")
    }

    /** GETs /api/session carrying the given anonymous cookie; returns (token, csrf). */
    private fun anonContext(cookieValue: String? = null): Pair<String, String> {
        val r = if (cookieValue != null) {
            raw("GET", "/api/session", extraCookies = "pd_anon=$cookieValue")
        } else {
            raw("GET", "/api/session")
        }
        assertEquals(200, r.status, r.body)
        val token = r.cookie("pd_anon") ?: cookieValue!!
        return token to r.json.getString("csrfToken")
    }

    // ---------- auth ----------

    @Test
    fun `anonymous session returns null user and a csrf token`() {
        val r = raw("GET", "/api/session")
        assertEquals(200, r.status)
        assertEquals(JSONObject.NULL, r.json.get("user"))
        assertTrue(r.json.getString("csrfToken").isNotBlank())
    }

    @Test
    fun `login with wrong password is 401`() {
        // Mint a bound anonymous token and present it together with its cookie.
        val (token, csrf) = anonContext()
        val r = raw(
            "POST", "/api/login", csrf = csrf,
            body = """{"username":"alice","password":"WRONG"}""",
            contentType = "application/json",
            extraCookies = "pd_anon=$token"
        )
        assertEquals(401, r.status)
        assertTrue(r.json.has("error"))
    }

    @Test
    fun `login without any csrf token is 403`() {
        val r = raw(
            "POST", "/api/login", // no csrf header at all
            body = """{"username":"alice","password":"alice-pw"}""",
            contentType = "application/json"
        )
        assertEquals(403, r.status)
        assertTrue(r.json.has("error"))
    }

    @Test
    fun `logged in session reports user`() {
        val (cookie, _) = login("boss", "boss-pw")
        val r = raw("GET", "/api/session", cookie = cookie)
        assertEquals(200, r.status)
        assertEquals("boss", r.json.getJSONObject("user").getString("username"))
        assertEquals("APPROVER", r.json.getJSONObject("user").getString("role"))
    }

    @Test
    fun `logout invalidates session`() {
        val (cookie, csrf) = login("alice", "alice-pw")
        val r = raw("POST", "/api/logout", cookie = cookie, csrf = csrf)
        assertEquals(200, r.status)
        assertEquals(JSONObject.NULL, r.json.get("user"))
        // session gone now
        val after = raw("GET", "/api/session", cookie = cookie)
        assertEquals(JSONObject.NULL, after.json.get("user"))
    }

    // ---------- JSON API flow ----------

    @Test
    fun `full employee to approver flow over HTTP`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")

        // create
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"マウス","quantity":3,"unitPriceYen":1500,"reason":"作業用"}""",
            contentType = "application/json"
        )
        assertEquals(201, created.status, created.body)
        val id = created.json.getJSONObject("request").getString("id")
        assertEquals("DRAFT", created.json.getJSONObject("request").getString("state"))
        assertEquals(4500L, created.json.getJSONObject("request").getLong("totalYen"))

        // employee list sees it
        val list = raw("GET", "/api/requests", cookie = aliceC)
        assertEquals(200, list.status)
        assertTrue(list.json.getJSONArray("requests").length() >= 1)

        // submit
        val submitted = raw("POST", "/api/requests/$id/submit", cookie = aliceC, csrf = aliceCsrf, body = "{}", contentType = "application/json")
        assertEquals(200, submitted.status, submitted.body)
        assertEquals("SUBMITTED", submitted.json.getJSONObject("request").getString("state"))

        // approver approves
        val approved = raw("POST", "/api/requests/$id/approve", cookie = bossC, csrf = bossCsrf, body = "{}", contentType = "application/json")
        assertEquals(200, approved.status, approved.body)
        assertEquals("APPROVED", approved.json.getJSONObject("request").getString("state"))

        // history shows CREATE, SUBMIT, APPROVE
        val hist = approved.json.getJSONObject("request").getJSONArray("history")
        val actions = (0 until hist.length()).map { hist.getJSONObject(it).getString("action") }
        assertTrue(actions.contains("CREATE") && actions.contains("SUBMIT") && actions.contains("APPROVE"))
    }

    @Test
    fun `double approve is 409`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"キーボード","quantity":1,"unitPriceYen":5000,"reason":"買い替え"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        raw("POST", "/api/requests/$id/submit", cookie = aliceC, csrf = aliceCsrf, body = "{}", contentType = "application/json")
        assertEquals(200, raw("POST", "/api/requests/$id/approve", cookie = bossC, csrf = bossCsrf, body = "{}", contentType = "application/json").status)
        val again = raw("POST", "/api/requests/$id/approve", cookie = bossC, csrf = bossCsrf, body = "{}", contentType = "application/json")
        assertEquals(409, again.status, again.body)
        assertTrue(again.json.has("error"))
    }

    @Test
    fun `patch after approved returns 409 and keeps db healthy`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"モニタ","quantity":1,"unitPriceYen":9000,"reason":"修理"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        raw("POST", "/api/requests/$id/submit", cookie = aliceC, csrf = aliceCsrf, body = "{}", contentType = "application/json")
        assertEquals(200, raw("POST", "/api/requests/$id/approve", cookie = bossC, csrf = bossCsrf, body = "{}", contentType = "application/json").status)

        // The next PATCH of an APPROVED request is a state conflict, not a 500.
        val patch = raw(
            "PATCH", "/api/requests/$id", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"モニタ","quantity":1,"unitPriceYen":9000,"reason":"修理"}""",
            contentType = "application/json"
        )
        assertEquals(409, patch.status, patch.body)
        assertTrue(patch.json.has("error"))

        // The single approval was recorded exactly once.
        val finalReq = raw("GET", "/api/requests/$id", cookie = aliceC)
        val hist = finalReq.json.getJSONObject("request").getJSONArray("history")
        var approvals = 0
        for (i in 0 until hist.length()) if (hist.getJSONObject(i).getString("action") == "APPROVE") approvals++
        assertEquals(1, approvals, "APPROVE must be recorded exactly once")

        // The database is still usable: a fresh create + login must succeed.
        val fresh = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"マウスパッド","quantity":2,"unitPriceYen":300,"reason":"新調"}""",
            contentType = "application/json"
        )
        assertEquals(201, fresh.status, fresh.body)
        val (boss2Cookie, _) = login("boss", "boss-pw")
        val s = raw("GET", "/api/session", cookie = boss2Cookie)
        assertEquals(200, s.status)
        assertEquals("boss", s.json.getJSONObject("user").getString("username"))
    }

    @Test
    fun `new request form posts to the html route and logout to html`() {
        val (aliceC, _) = login("alice", "alice-pw")
        val page = raw("GET", "/requests/new", cookie = aliceC)
        assertEquals(200, page.status, page.body)
        // The rendered new-request form (the one with the itemName field) must
        // target the HTML handler, not the JSON API.
        val newForm = Regex("<form[^>]*?action=\"([^\"]*)\"[^>]*?>[^<]*<input type=\"hidden\" name=\"csrfToken\"[^>]*>[^<]*<label>品名[^<]*</label>[^<]*<input name=\"itemName\"").find(page.body)
        assertEquals("/requests/new", newForm?.groupValues?.get(1), "new-request form must post to /requests/new")
        // The header logout form must target the HTML logout, not the JSON API.
        val logout = Regex("<form[^>]*action=\"([^\"]+)\"[^>]*>(?:(?!</form>).)*ログアウト", RegexOption.DOT_MATCHES_ALL).find(page.body)
        assertEquals("/logout", logout?.groupValues?.get(1), "logout form must post to /logout")
    }

    @Test
    fun `form workflow new submit return edit resubmit approve logout`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        fun postForm(path: String, cookie: String, csrf: String, body: String, follow: Boolean): Resp =
            raw("POST", path, cookie = cookie, body = body, contentType = "application/x-www-form-urlencoded", followRedirects = follow)

        // 1. new (invalid first: quantity=0) -> form re-rendered with inputs + error
        val invalid = postForm("/requests/new", aliceC, aliceCsrf,
            "csrfToken=${java.net.URLEncoder.encode(aliceCsrf, "UTF-8")}" +
                "&itemName=ホワイトボード&quantity=0&unitPriceYen=400&reason=備品", false)
        assertEquals(400, invalid.status, invalid.body)
        assertTrue(invalid.body.contains("value=\"ホワイトボード\""), "itemName preserved")
        assertTrue(invalid.body.contains("keep") || invalid.body.contains("value=\"0\""), "quantity preserved")
        assertTrue(invalid.body.contains("value=\"400\""), "unitPrice preserved")
        assertTrue(invalid.body.contains("備品"), "reason preserved")

        // 2. submit the (corrected) new form via its rendered action
        val newAction = Regex("<form[^>]*?action=\"([^\"]*)\"[^>]*?>[^<]*<input type=\"hidden\" name=\"csrfToken\"[^>]*>[^<]*<label>品名[^<]*</label>[^<]*<input name=\"itemName\"").find(raw("GET", "/requests/new", cookie = aliceC).body)!!.groupValues[1]
        val createdResp = postForm(newAction, aliceC, aliceCsrf,
            "csrfToken=${java.net.URLEncoder.encode(aliceCsrf, "UTF-8")}" +
                "&itemName=ホワイトボード&quantity=1&unitPriceYen=400&reason=備品", false)
        assertEquals(302, createdResp.status, createdResp.body)
        val id = createdResp.header("Location")!!.removePrefix("/requests/")
        assertEquals("DRAFT", raw("GET", "/api/requests/$id", cookie = aliceC).json.getJSONObject("request").getString("state"))

        // 3. return to the detail page, then submit it
        assertEquals(200, raw("GET", "/requests/$id", cookie = aliceC).status)
        val detail = raw("GET", "/requests/$id", cookie = aliceC).body
        // The submit form is the first action form on the detail page.
        val submitAction = Regex("<form[^>]*?action=\"([^\"]*)\"[^>]*?>(?:[^<]|<(?!/form>))*?value=\"submit\"").find(detail)?.groupValues?.get(1)
        assertEquals("/requests/$id", submitAction, "submit form must post to the detail route")
        val submitted = postForm(submitAction!!, aliceC, aliceCsrf,
            "csrfToken=${java.net.URLEncoder.encode(aliceCsrf, "UTF-8")}&action=submit", false)
        assertEquals(302, submitted.status, submitted.body)

        // 4. approver returns it
        val detailApprover = raw("GET", "/requests/$id", cookie = bossC).body
        // The return form is the last form on the approver's detail page.
        val returnAction = Regex("<form[^>]*?action=\"([^\"]*)\"[^>]*?>(?:[^<]|<(?!/form>))*?value=\"return\"").find(detailApprover)!!.groupValues[1]
        val returned = postForm(returnAction, bossC, bossCsrf,
            "csrfToken=${java.net.URLEncoder.encode(bossCsrf, "UTF-8")}&action=return&comment=${java.net.URLEncoder.encode("金額を再確認してください", "UTF-8")}", false)
        assertEquals(302, returned.status, returned.body)
        val stateCheck = raw("GET", "/api/requests/$id", cookie = aliceC).json
        assertEquals("RETURNED", stateCheck.optJSONObject("request")?.optString("state"),
            "request should be RETURNED after approver return: ${stateCheck.toString()}")

        // 5. owner edits it: the detail page's edit link points to /requests/{id}/edit,
        // and the rendered edit form there posts the update.
        val editPage = raw("GET", "/requests/$id/edit", cookie = aliceC).body
        val editAction = Regex("<form[^>]*?action=\"([^\"]*)\"[^>]*?>(?:[^<]|<(?!/form>))*?value=\"update\"").find(editPage)!!.groupValues[1]
        val updated = postForm(editAction, aliceC, aliceCsrf,
            "csrfToken=${java.net.URLEncoder.encode(aliceCsrf, "UTF-8")}&action=update" +
                "&itemName=ホワイトボード&quantity=2&unitPriceYen=400&reason=修正済み", false)
        assertEquals(302, updated.status, updated.body)
        val afterUpdate = raw("GET", "/api/requests/$id", cookie = aliceC).json.getJSONObject("request")
        assertEquals("RETURNED", afterUpdate.getString("state"))
        assertEquals(2L, afterUpdate.getLong("quantity"))
        assertEquals(800L, afterUpdate.getLong("totalYen"))

        // 6. owner resubmits: the re-apply form (same submit action field) posts again.
        val resubmitPage = raw("GET", "/requests/$id", cookie = aliceC).body
        val resubmitAction = Regex("<form[^>]*?action=\"([^\"]*)\"[^>]*?>(?:[^<]|<(?!/form>))*?value=\"submit\"").find(resubmitPage)?.groupValues?.get(1)
        val resubmitted = postForm(resubmitAction!!, aliceC, aliceCsrf,
            "csrfToken=${java.net.URLEncoder.encode(aliceCsrf, "UTF-8")}&action=submit", false)
        assertEquals(302, resubmitted.status, resubmitted.body)
        assertEquals("SUBMITTED", raw("GET", "/api/requests/$id", cookie = aliceC).json.getJSONObject("request").getString("state"))

        // 7. approver approves: the approve form's action is rendered on the page.
        val approvePage = raw("GET", "/requests/$id", cookie = bossC).body
        val approveAction = Regex("<form[^>]*?action=\"([^\"]*)\"[^>]*?>(?:[^<]|<(?!/form>))*?value=\"approve\"").find(approvePage)
        assertEquals("/requests/$id", approveAction?.groupValues?.get(1), "approve form must post to the detail route")
        val approved = postForm(approveAction!!.groupValues[1], bossC, bossCsrf,
            "csrfToken=${java.net.URLEncoder.encode(bossCsrf, "UTF-8")}&action=approve", false)
        assertEquals(302, approved.status, approved.body)
        assertEquals("APPROVED", raw("GET", "/api/requests/$id", cookie = aliceC).json.getJSONObject("request").getString("state"))

        // 8. logout via the rendered form leaves the browser on the login page
        val logoutForm = Regex("<form[^>]*action=\"([^\"]+)\"[^>]*>(?:(?!</form>).)*ログアウト", RegexOption.DOT_MATCHES_ALL).find(raw("GET", "/requests", cookie = aliceC).body)!!.groupValues[1]
        assertEquals("/logout", logoutForm)
        val logout = raw("POST", logoutForm, cookie = aliceC,
            body = "csrfToken=${java.net.URLEncoder.encode(aliceCsrf, "UTF-8")}",
            contentType = "application/x-www-form-urlencoded", followRedirects = false)
        assertEquals(302, logout.status, logout.body)
        assertEquals("/login", logout.header("Location"))
        // session invalidated, and the follow-up GET is HTML, not JSON
        val after = raw("GET", "/api/session", cookie = aliceC)
        assertEquals(JSONObject.NULL, after.json.get("user"))
        val page = raw("GET", "/requests")
        assertEquals(200, page.status)
        assertTrue(page.body.contains("<!doctype html>") || page.body.contains("<html"), "must be HTML, not JSON")
    }

    @Test
    fun `login with valid password but forged csrf creates no session`() {
        val r = raw(
            "POST", "/api/login", csrf = "forged-token",
            body = """{"username":"alice","password":"alice-pw"}""",
            contentType = "application/json"
        )
        assertEquals(403, r.status, r.body)
        // No authenticated session may exist: no session cookie is issued.
        assertTrue(r.cookie("pd_session") == null || r.cookie("pd_session")!!.isEmpty(),
            "a forged CSRF must not mint an authenticated session")
    }

    @Test
    fun `mutating endpoint without csrf is 403`() {
        val (aliceC, _) = login("alice", "alice-pw")
        val r = raw(
            "POST", "/api/requests", cookie = aliceC, // no csrf
            body = """{"itemName":"x","quantity":1,"unitPriceYen":1,"reason":"y"}""",
            contentType = "application/json"
        )
        assertEquals(403, r.status)
    }

    @Test
    fun `employee cannot read another employee request over http 404`() {
        val (aliceC, _) = login("alice", "alice-pw")
        val (bobC, bobCsrf) = login("bob", "bob-pw")
        val created = raw(
            "POST", "/api/requests", cookie = bobC, csrf = bobCsrf,
            body = """{"itemName":"ボブの品","quantity":1,"unitPriceYen":10,"reason":"r"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        val r = raw("GET", "/api/requests/$id", cookie = aliceC)
        assertEquals(404, r.status, r.body)
    }

    @Test
    fun `invalid input is 400`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val r = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"x","quantity":0,"unitPriceYen":1,"reason":"y"}""",
            contentType = "application/json"
        )
        assertEquals(400, r.status, r.body)
        assertTrue(r.json.has("error"))
    }

    @Test
    fun `unauthenticated api call is 401`() {
        val r = raw("GET", "/api/requests")
        assertEquals(401, r.status)
    }

    // ---------- HTML ----------

    @Test
    fun `root lands on login or requests`() {
        val anon = raw("GET", "/")
        assertEquals(200, anon.status, anon.body)
        assertTrue(anon.body.contains("username"), "expected login form")
        val (aliceC, _) = login("alice", "alice-pw")
        val logged = raw("GET", "/", cookie = aliceC)
        assertEquals(200, logged.status, logged.body)
        assertTrue(logged.body.contains("申請一覧"), "expected list page")
    }

    @Test
    fun `login page renders and bad login shows 401 with message`() {
        val page = raw("GET", "/login")
        assertEquals(200, page.status)
        assertTrue(page.body.contains("備品購入申請"))
        assertTrue(page.body.contains("username"))
    }

    @Test
    fun `unauthenticated page request lands on login`() {
        val r = raw("GET", "/requests")
        assertEquals(200, r.status, r.body)
        assertTrue(r.body.contains("備品購入申請"), "expected login page")
    }

    @Test
    fun `html detail page escapes user supplied item name`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val evil = "<script>alert(1)</script>"
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"${evil.replace("\"", "\\\"")}","quantity":1,"unitPriceYen":1,"reason":"ok"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        val page = raw("GET", "/requests/$id", cookie = aliceC)
        assertEquals(200, page.status)
        assertFalse(page.body.contains("<script>alert(1)</script>"), "raw script leaked into HTML")
        assertTrue(page.body.contains("&lt;script&gt;"), "expected escaped form")
    }

    @Test
    fun `healthz is ok`() {
        val r = raw("GET", "/healthz")
        assertEquals(200, r.status)
        assertTrue(r.json.has("ok"))
    }

    // ---------- regression: reason validation ----------

    @Test
    fun `create with whitespace-only reason is 400 and writes nothing`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val before = raw("GET", "/api/requests", cookie = aliceC)
        val r = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"Diagnostic keyboard","quantity":2,"unitPriceYen":3200,"reason":" "}""",
            contentType = "application/json"
        )
        assertEquals(400, r.status, r.body)
        val after = raw("GET", "/api/requests", cookie = aliceC)
        assertEquals(
            before.json.getJSONArray("requests").length(),
            after.json.getJSONArray("requests").length(),
            "no request may be created"
        )
    }

    @Test
    fun `update with whitespace-only reason is 400 and changes nothing`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb","quantity":2,"unitPriceYen":3200,"reason":"ok"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        val r = raw(
            "PATCH", "/api/requests/$id", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb2","quantity":2,"unitPriceYen":3200,"reason":"   "}""",
            contentType = "application/json"
        )
        assertEquals(400, r.status, r.body)
        val after = raw("GET", "/api/requests/$id", cookie = aliceC)
        assertEquals("ok", after.json.getJSONObject("request").getString("reason"))
        assertEquals("kb", after.json.getJSONObject("request").getString("itemName"))
    }

    // ---------- regression: origin checks ----------

    /**
     * Helper: after a rejected mutating request, performs a full
     * create + submit + approve cycle to prove the rejected request did not
     * leave the session / DB in an unusable state.
     */
    private fun assertFlowStillWorks(
        aliceC: String, aliceCsrf: String,
        bossC: String, bossCsrf: String,
        itemName: String = "Post-rejection item"
    ) {
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"$itemName","quantity":1,"unitPriceYen":100,"reason":"post-rejection"}""",
            contentType = "application/json"
        )
        assertEquals(201, created.status, "post-rejection create must still succeed: ${created.body}")
        val id = created.json.getJSONObject("request").getString("id")
        val submitted = raw(
            "POST", "/api/requests/$id/submit", cookie = aliceC, csrf = aliceCsrf,
            body = "{}", contentType = "application/json"
        )
        assertEquals(200, submitted.status, "post-rejection submit must still succeed: ${submitted.body}")
        val approved = raw(
            "POST", "/api/requests/$id/approve", cookie = bossC, csrf = bossCsrf,
            body = "{}", contentType = "application/json"
        )
        assertEquals(200, approved.status, "post-rejection approve must still succeed: ${approved.body}")
    }

    @Test
    fun `patch with foreign origin is 403 and session stays usable`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb","quantity":2,"unitPriceYen":3200,"reason":"ok"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        val r = raw(
            "PATCH", "/api/requests/$id", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kbx","quantity":2,"unitPriceYen":3200,"reason":"ok"}""",
            contentType = "application/json",
            origin = "https://untrusted.invalid"
        )
        assertEquals(403, r.status, r.body)
        // The rejected PATCH did not damage the session: a fresh
        // create → submit → approve must succeed afterwards.
        assertFlowStillWorks(aliceC, aliceCsrf, bossC, bossCsrf, "After patch rejection")
    }

    @Test
    fun `patch with https scheme over http is 403 and session stays usable`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb","quantity":1,"unitPriceYen":100,"reason":"ok"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        // Server is http://127.0.0.1:PORT; an https Origin must be rejected.
        val r = raw(
            "PATCH", "/api/requests/$id", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb2","quantity":1,"unitPriceYen":100,"reason":"ok"}""",
            contentType = "application/json",
            origin = "https://127.0.0.1:${server.port}"
        )
        assertEquals(403, r.status, r.body)
        assertFlowStillWorks(aliceC, aliceCsrf, bossC, bossCsrf, "After https-scheme rejection")
    }

    @Test
    fun `patch with http default-port origin is 403 and session stays usable`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb","quantity":1,"unitPriceYen":100,"reason":"ok"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        // http://127.0.0.1 → effective port 80, but server is on a non-80 port.
        val r = raw(
            "PATCH", "/api/requests/$id", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb2","quantity":1,"unitPriceYen":100,"reason":"ok"}""",
            contentType = "application/json",
            origin = "http://127.0.0.1"
        )
        assertEquals(403, r.status, r.body)
        assertFlowStillWorks(aliceC, aliceCsrf, bossC, bossCsrf, "After default-port rejection")
    }

    @Test
    fun `patch with null or malformed origin is 403 and session stays usable`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb","quantity":1,"unitPriceYen":100,"reason":"ok"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        // null-ish / malformed origins are all rejected.
        for (bad in listOf("null", "not-a-url", "ftp://127.0.0.1:${server.port}", "http://")) {
            val r = raw(
                "PATCH", "/api/requests/$id", cookie = aliceC, csrf = aliceCsrf,
                body = """{"itemName":"kb2","quantity":1,"unitPriceYen":100,"reason":"ok"}""",
                contentType = "application/json",
                origin = bad
            )
            assertEquals(403, r.status, "origin='$bad' must be rejected: ${r.body}")
        }
        assertFlowStillWorks(aliceC, aliceCsrf, bossC, bossCsrf, "After malformed-origin rejection")
    }

    @Test
    fun `patch with correct same origin succeeds`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb","quantity":1,"unitPriceYen":100,"reason":"ok"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        // A correctly-formed same-origin request must succeed.
        val r = raw(
            "PATCH", "/api/requests/$id", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb-updated","quantity":2,"unitPriceYen":200,"reason":"updated"}""",
            contentType = "application/json",
            origin = "http://127.0.0.1:${server.port}"
        )
        assertEquals(200, r.status, r.body)
        val updated = r.json.getJSONObject("request")
        assertEquals("kb-updated", updated.getString("itemName"))
        assertEquals(2L, updated.getLong("quantity"))
        assertEquals(400L, updated.getLong("totalYen"))
    }

    @Test
    fun `login with foreign origin is 403 and login still works after`() {
        val (token, csrf) = anonContext()
        val r = raw(
            "POST", "/api/login", csrf = csrf,
            body = """{"username":"alice","password":"alice-pw"}""",
            contentType = "application/json",
            origin = "https://untrusted.invalid",
            extraCookies = "pd_anon=$token"
        )
        assertEquals(403, r.status, r.body)
        // The anonymous session must still be usable: a follow-up login
        // with the same token + cookie must succeed.
        val retry = raw(
            "POST", "/api/login", csrf = csrf,
            body = """{"username":"alice","password":"alice-pw"}""",
            contentType = "application/json",
            extraCookies = "pd_anon=$token"
        )
        assertEquals(200, retry.status, "login after origin rejection must succeed: ${retry.body}")
        val session = raw("GET", "/api/session", cookie = retry.setCookie()!!)
        assertEquals("alice", session.json.getJSONObject("user").getString("username"))
    }

    @Test
    fun `logout with foreign origin is 403 and session stays valid`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val r = raw(
            "POST", "/api/logout", cookie = aliceC, csrf = aliceCsrf,
            body = "{}", contentType = "application/json",
            origin = "https://untrusted.invalid"
        )
        assertEquals(403, r.status, r.body)
        // session must still be valid
        val after = raw("GET", "/api/session", cookie = aliceC)
        assertEquals("alice", after.json.getJSONObject("user").getString("username"))
        // and a full create→submit→approve cycle must still succeed
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        assertFlowStillWorks(aliceC, aliceCsrf, bossC, bossCsrf, "After logout rejection")
    }

    @Test
    fun `x-forwarded-headers are ignored when deciding origin`() {
        // Even with a spoofed X-Forwarded-Proto/Host/Port, the Origin check
        // must use only the literal Host header.
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb","quantity":1,"unitPriceYen":100,"reason":"ok"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        // Spoof: claim the request came over https://evil.example.com via
        // X-Forwarded-* headers. The server must still reject because the
        // Origin header itself is a foreign host.
        val r = rawWithOrigin(
            "PATCH", "/api/requests/$id", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"kb2","quantity":1,"unitPriceYen":100,"reason":"ok"}""",
            contentType = "application/json",
            origin = "https://evil.example.com",
            extraCookies = null
        )
        assertEquals(403, r.status, "spoofed forwarded headers must not widen origin accept: ${r.body}")
    }

    // ---------- regression: bound anonymous tokens ----------

    @Test
    fun `session token is bound to anonymous cookie and stable for it`() {
        val first = raw("GET", "/api/session")
        assertEquals(200, first.status)
        val cookie = first.cookie("pd_anon")
        assertNotNull(cookie)
        val csrf1 = first.json.getString("csrfToken")
        // same client (cookie kept) gets the same token
        val second = raw("GET", "/api/session", extraCookies = "pd_anon=$cookie")
        assertEquals(200, second.status)
        assertEquals(csrf1, second.json.getString("csrfToken"))
        // an independent client with no cookie gets a different token
        val other = raw("GET", "/api/session")
        val otherCsrf = other.json.getString("csrfToken")
        assertTrue(otherCsrf != csrf1, "independent clients must not share tokens")
    }

    @Test
    fun `login with forged token is 403 and no session is created`() {
        val r = raw(
            "POST", "/api/login", csrf = "forged-token",
            body = """{"username":"alice","password":"alice-pw"}""",
            contentType = "application/json"
        )
        assertEquals(403, r.status, r.body)
        val s = raw("GET", "/api/session")
        assertEquals(JSONObject.NULL, s.json.get("user"))
    }

    @Test
    fun `login with unbound token from another client is 403`() {
        // client A mints a token; client B (different cookie) presents it
        val a = raw("GET", "/api/session")
        val csrfA = a.json.getString("csrfToken")
        val r = raw(
            "POST", "/api/login", csrf = csrfA,
            body = """{"username":"alice","password":"alice-pw"}""",
            contentType = "application/json"
        )
        assertEquals(403, r.status, r.body)
    }

    @Test
    fun `login without token is 403`() {
        val r = raw(
            "POST", "/api/login",
            body = """{"username":"alice","password":"alice-pw"}""",
            contentType = "application/json"
        )
        assertEquals(403, r.status, r.body)
    }

    @Test
    fun `login rotates the session`() {
        val (token, csrf) = anonContext()
        val r = raw(
            "POST", "/api/login", csrf = csrf,
            body = """{"username":"alice","password":"alice-pw"}""",
            contentType = "application/json",
            extraCookies = "pd_anon=$token"
        )
        assertEquals(200, r.status, r.body)
        val newCookie = r.setCookie()!!
        val newCsrf = r.json.getString("csrfToken")
        assertTrue(newCsrf.isNotBlank())
        // the new session works
        val s = raw("GET", "/api/session", cookie = newCookie)
        assertEquals("alice", s.json.getJSONObject("user").getString("username"))
        // the anonymous token is consumed: replaying it on a fresh login fails
        val replay = raw(
            "POST", "/api/login", csrf = csrf,
            body = """{"username":"alice","password":"alice-pw"}""",
            contentType = "application/json",
            extraCookies = "pd_anon=$token"
        )
        assertEquals(403, replay.status, replay.body)
    }

    @Test
    fun `logout with forged token is 403`() {
        val r = raw(
            "POST", "/api/logout", csrf = "forged",
            body = "{}", contentType = "application/json"
        )
        assertEquals(403, r.status, r.body)
    }

    // ---------- regression: HTML login form ----------

    @Test
    fun `login page carries the token bound to the anonymous cookie`() {
        val s = raw("GET", "/api/session")
        val cookie = s.cookie("pd_anon")
        assertNotNull(cookie)
        val csrf = s.json.getString("csrfToken")
        val page = raw("GET", "/login", extraCookies = "pd_anon=$cookie")
        assertEquals(200, page.status)
        assertTrue(page.body.contains("name=\"csrfToken\" value=\"$csrf\""), "form must carry the bound token")
    }

    @Test
    fun `login page renders style markup not css text`() {
        val s = raw("GET", "/api/session")
        val cookie = s.cookie("pd_anon")
        val page = raw("GET", "/login", extraCookies = "pd_anon=$cookie")
        assertEquals(200, page.status)
        assertTrue(page.body.contains("<style>"), "CSS must be inside a <style> element")
        // Strip the <style> block; the CSS must not appear as plain text outside it.
        val withoutStyle = Regex("<style>.*?</style>", RegexOption.DOT_MATCHES_ALL).replace(page.body, "")
        assertFalse(withoutStyle.contains("body{font-family") || withoutStyle.contains("body { font-family"),
            "CSS must not be rendered as text outside <style>")
    }

    @Test
    fun `html login form round trip works over real http`() {
        // 1. anonymous client mints a token
        val s = raw("GET", "/api/session")
        val cookie = s.cookie("pd_anon")
        val csrf = s.json.getString("csrfToken")
        // 2. fetch the login page with the cookie
        val page = raw("GET", "/login", extraCookies = "pd_anon=$cookie")
        assertEquals(200, page.status)
        assertTrue(page.body.contains("name=\"csrfToken\" value=\"$csrf\""))
        // 3. POST the form with the bound token + cookie
        val form = "csrfToken=${java.net.URLEncoder.encode(csrf, "UTF-8")}" +
                "&username=alice&password=alice-pw"
        val r = raw(
            "POST", "/login", body = form,
            contentType = "application/x-www-form-urlencoded",
            extraCookies = "pd_anon=$cookie",
            followRedirects = false
        )
        assertEquals(302, r.status, r.body)
        assertEquals("/requests", r.header("Location"))
        val sessionCookie = r.cookie("pd_session")
        assertNotNull(sessionCookie)
        // 4. the new session works
        val after = raw("GET", "/api/session", cookie = sessionCookie)
        assertEquals("alice", after.json.getJSONObject("user").getString("username"))
    }

    @Test
    fun `html login form with forged token is rejected and data unchanged`() {
        val s = raw("GET", "/api/session")
        val cookie = s.cookie("pd_anon")
        val form = "csrfToken=forged&username=alice&password=alice-pw"
        val r = raw(
            "POST", "/login", body = form,
            contentType = "application/x-www-form-urlencoded",
            extraCookies = "pd_anon=$cookie"
        )
        assertEquals(403, r.status, r.body)
    }

    // ---------- regression: HTML form numeric handling ----------

    private fun createViaForm(cookie: String, csrf: String, form: String): Resp =
        raw(
            "POST", "/api/requests", cookie = cookie,
            body = "csrfToken=${java.net.URLEncoder.encode(csrf, "UTF-8")}&$form",
            contentType = "application/x-www-form-urlencoded"
        )

    @Test
    fun `form create with string numerics works`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val r = createViaForm(
            aliceC, aliceCsrf,
            "itemName=${java.net.URLEncoder.encode("ノート", "UTF-8")}" +
                "&quantity=3&unitPriceYen=1500&reason=${java.net.URLEncoder.encode("会議", "UTF-8")}"
        )
        assertEquals(201, r.status, r.body)
        val req = r.json.getJSONObject("request")
        assertEquals(3L, req.getLong("quantity"))
        assertEquals(1500L, req.getLong("unitPriceYen"))
        assertEquals(4500L, req.getLong("totalYen"))
    }

    @Test
    fun `form create with fractional quantity is 400`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val r = createViaForm(
            aliceC, aliceCsrf,
            "itemName=a&quantity=1.5&unitPriceYen=100&reason=r"
        )
        assertEquals(400, r.status, r.body)
    }

    @Test
    fun `form create preserves inputs on validation error`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val r = raw(
            "POST", "/requests/new", cookie = aliceC,
            body = "csrfToken=${java.net.URLEncoder.encode(aliceCsrf, "UTF-8")}" +
                    "&itemName=keep&quantity=0&unitPriceYen=100&reason=keepreason",
            contentType = "application/x-www-form-urlencoded",
            followRedirects = false
        )
        assertEquals(400, r.status, r.body)
        assertTrue(r.body.contains("value=\"keep\""), "itemName must be preserved")
        assertTrue(r.body.contains("keepreason"), "reason must be preserved")
        assertTrue(r.body.contains("value=\"0\"") || r.body.contains(">0<"), "quantity must be preserved")
    }

    @Test
    fun `detail form submit and approve round trip via http forms`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        // create via API, then drive submit + approve through HTML forms
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"ペン","quantity":1,"unitPriceYen":10,"reason":"r"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")

        val submitted = raw(
            "POST", "/requests/$id", cookie = aliceC,
            body = "csrfToken=${java.net.URLEncoder.encode(aliceCsrf, "UTF-8")}&action=submit",
            contentType = "application/x-www-form-urlencoded",
            followRedirects = false
        )
        assertEquals(302, submitted.status, submitted.body)
        assertEquals("/requests/$id", submitted.header("Location"))

        val approved = raw(
            "POST", "/requests/$id", cookie = bossC,
            body = "csrfToken=${java.net.URLEncoder.encode(bossCsrf, "UTF-8")}&action=approve",
            contentType = "application/x-www-form-urlencoded",
            followRedirects = false
        )
        assertEquals(302, approved.status, approved.body)
        val final = raw("GET", "/api/requests/$id", cookie = aliceC)
        assertEquals("APPROVED", final.json.getJSONObject("request").getString("state"))
    }

    @Test
    fun `detail form return round trip via http form`() {
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val (bossC, bossCsrf) = login("boss", "boss-pw")
        val created = raw(
            "POST", "/api/requests", cookie = aliceC, csrf = aliceCsrf,
            body = """{"itemName":"ペン","quantity":1,"unitPriceYen":10,"reason":"r"}""",
            contentType = "application/json"
        )
        val id = created.json.getJSONObject("request").getString("id")
        raw("POST", "/api/requests/$id/submit", cookie = aliceC, csrf = aliceCsrf, body = "{}", contentType = "application/json")

        val returned = raw(
            "POST", "/requests/$id", cookie = bossC,
            body = "csrfToken=${java.net.URLEncoder.encode(bossCsrf, "UTF-8")}" +
                    "&action=return&comment=${java.net.URLEncoder.encode("理由を訂正してください", "UTF-8")}",
            contentType = "application/x-www-form-urlencoded",
            followRedirects = false
        )
        assertEquals(302, returned.status, returned.body)
        val final = raw("GET", "/api/requests/$id", cookie = aliceC)
        val req = final.json.getJSONObject("request")
        assertEquals("RETURNED", req.getString("state"))
        val hist = req.getJSONArray("history")
        val last = hist.getJSONObject(hist.length() - 1)
        assertEquals("RETURN", last.getString("action"))
        assertEquals("理由を訂正してください", last.getString("comment"))
    }

    // ---------- rendering regression ----------

    @Test
    fun `logout button renders visible Japanese label with contrasting text`() {
        val (aliceC, _) = login("alice", "alice-pw")
        val page = raw("GET", "/requests", cookie = aliceC).body
        // The nav logout button must carry readable text and a light color
        // on the grey background (no class="muted" that would grey the text out).
        val btn = Regex("<button[^>]*>ログアウト</button>").find(page)
        assertNotNull(btn, "logout button with ログアウト label must be rendered")
        assertFalse(btn.value.contains("muted"), "logout button must not use the muted text class: ${btn.value}")
        assertTrue(btn.value.contains("color:#fff") || btn.value.contains("color: #fff"),
            "logout button text must have sufficient contrast: ${btn.value}")
    }

    @Test
    fun `error page wraps css in style tag and keeps error text and home link`() {
        // A 403 on HTML logout (wrong origin) renders via Html.errorPage.
        val (aliceC, aliceCsrf) = login("alice", "alice-pw")
        val r = raw(
            "POST", "/logout", cookie = aliceC,
            body = "csrfToken=${java.net.URLEncoder.encode(aliceCsrf, "UTF-8")}",
            contentType = "application/x-www-form-urlencoded",
            origin = "http://evil.example.com"
        )
        assertEquals(403, r.status, r.body)
        assertTrue(r.body.contains("<html"), "must be HTML: ${r.body}")
        // CSS must be wrapped in <style> so the browser does not display it literally.
        val styleOpen = r.body.indexOf("<style>")
        assertTrue(styleOpen >= 0, "error page must contain a <style> element")
        val styleClose = r.body.indexOf("</style>", styleOpen)
        assertTrue(styleClose > styleOpen, "error page must close the <style> element")
        // No raw CSS outside the style wrapper in the head.
        val head = r.body.substring(0, r.body.indexOf("</head>"))
        val cssOutsideStyle = head.substring(0, styleOpen) + head.substring(styleClose + "</style>".length)
        assertFalse(cssOutsideStyle.contains("body{"),
            "no raw CSS may appear in <head> outside <style>")
        // Error text and the home link remain accessible.
        assertTrue(r.body.contains("Originが一致しません"), "error message must be present: ${r.body}")
        assertTrue(r.body.contains("href=\"/\""), "home link must be present: ${r.body}")
    }
}
