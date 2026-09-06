package com.example.purchasedesk.http

import com.example.purchasedesk.data.Database
import com.example.purchasedesk.domain.ApiException
import com.example.purchasedesk.domain.Role
import com.example.purchasedesk.domain.State
import com.example.purchasedesk.domain.Tokens
import com.example.purchasedesk.domain.User
import com.example.purchasedesk.domain.Validation
import com.example.purchasedesk.service.Service
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Wires the JSON API + HTML pages to the [Service] layer, adding:
 *  - cookie-based session management
 *  - CSRF (header on JSON, hidden field on HTML)
 *  - HTML escaping for any user-provided value (in [Html])
 */
class ApiServer(
    private val db: Database,
    private val service: Service,
    host: String,
    port: Int,
    /**
     * Optional externally-trusted public origin for TLS-terminated deployments
     * (e.g. `https://localhost:18443` behind a trusted loopback reverse proxy).
     * When set, all session/anonymous cookies are marked `Secure` and the
     * same-origin check accepts this origin (and the local HTTP Host used by
     * the proxy itself). When null, cookies are non-`Secure` and the check
     * matches the literal Host header — the loopback-HTTP default.
     */
    private val publicOrigin: String? = null
) : AutoCloseable {

    private val server = HttpServer(host, port) { req -> route(req) }

    val port: Int get() = server.actualPort()

    /** `true` when cookies should carry the `Secure` flag. */
    private val cookiesSecure: Boolean get() = publicOrigin != null

    /** Parsed form of [publicOrigin], or null. Never trusted for anything else. */
    private val expectedOriginParts: Triple<String, String, Int>? = publicOrigin?.let {
        try {
            val u = java.net.URI(it.trim())
            val s = u.scheme?.lowercase() ?: return@let null
            val h = u.host?.lowercase() ?: return@let null
            val p = u.port.takeIf { pr -> pr in 1..65535 } ?: (if (s == "https") 443 else 80)
            Triple(s, h, p)
        } catch (_: Exception) { null }
    }

    private fun route(req: HttpRequest): HttpResponse {
        return try {
            when {
                req.path == "/api/session" -> handleSession(req)
                req.path == "/api/login" -> handleLogin(req)
                req.path == "/api/logout" -> handleLogout(req)
                req.path == "/api/requests" -> handleRequests(req)
                req.path.startsWith("/api/requests/") -> handleApiRequestByPath(req)
                req.path == "/" -> redirect(302, "/requests")
                req.path == "/login" -> handleLoginPage(req)
                req.path == "/logout" -> handleLogoutPage(req)
                req.path == "/requests" -> handleListPage(req)
                req.path == "/requests/new" -> handleNewPage(req)
                req.path.startsWith("/requests/") -> handleDetailPage(req)
                req.path == "/healthz" -> jsonResponse(200, "{\"ok\":true}")
                else -> jsonResponse(404, Json.errorJson("ページが見つかりません。"))
            }
        } catch (e: ApiException) {
            apiError(e.status, e.message, req)
        } catch (e: org.json.JSONException) {
            apiError(400, "JSONの形式が正しくありません。", req)
        } catch (e: Exception) {
            apiError(500, "サーバー内部エラーです。", req)
        }
    }

    // ---------------- session & auth ----------------

    /**
     * The anonymous identity (cookie token + bound CSRF token) for this
     * request, or null. The token is only accepted when it is backed by the
     * cookie that minted it — a forged or unbound token is rejected.
     */
    private fun anonymousContext(req: HttpRequest): Pair<String, String>? {
        val cookieToken = anonCookie(req) ?: return null
        val anon = db.anonymousSession(cookieToken) ?: return null
        val provided = csrfProvided(req) ?: return null
        if (provided.isBlank() || provided != anon.second) return null
        return anon.first to anon.second
    }

    private fun handleSession(req: HttpRequest): HttpResponse {
        val s = currentSession(req)
        if (s != null) {
            return jsonResponse(200, Json.sessionJson(s.user.username to s.user.role.name, s.csrf))
        }
        val cookieToken = anonCookie(req)
        if (cookieToken != null && db.anonymousSession(cookieToken) != null) {
            val csrf = db.anonymousSession(cookieToken)!!.second
            return jsonResponse(200, Json.sessionJson(null, csrf))
        }
        // No valid session yet: mint a new anonymous identity.
        val token = Tokens.randomToken()
        val csrf = Tokens.randomToken()
        db.createAnonymousSession(token, csrf, nowUtc())
        return jsonResponse(200, Json.sessionJson(null, csrf), cookieValue(ANON_COOKIE, token, secure = cookiesSecure))
    }

    private fun handleLogin(req: HttpRequest): HttpResponse {
        requireMethod(req, "POST")
        if (!sameOrigin(req)) return apiError(403, "Originが一致しません。", req)
        val body = parseJson(req)
        val username = body.optString("username", "").trim()
        val password = body.optString("password", "")

        // Validate CSRF BEFORE creating any session, so a forged/unbound token
        // can never mint an orphan authenticated session. Wrong credentials
        // still surface as 401 (verified first), correct credentials + a bad
        // token surface as 403.
        val s = currentSession(req)
        if (s != null) {
            // An authenticated caller must present its own session token.
            if (csrfProvided(req) != s.csrf) throw ApiException(403, "CSRFトークンが不正です。")
            val (user, token) = try {
                service.login(username, password)
            } catch (e: ApiException) {
                throw e
            }
            return issueAuthenticatedLogin(user, token)
        }
        val anon = anonymousContext(req)
        if (anon == null) throw ApiException(403, "CSRFトークンが不正です。")
        val (user, token) = try {
            service.login(username, password)
        } catch (e: ApiException) {
            // 401 for bad credentials: the anonymous session is left intact.
            throw e
        }
        // Consume the anonymous session so its token cannot be replayed.
        db.consumeAnonymousSession(anon.first)
        // Rotate: the new session gets a fresh token + CSRF; the anonymous
        // cookie is cleared so its token stays bound to nothing.
        return issueAuthenticatedLogin(user, token, clearAnonCookie = true)
    }

    /** Issues the authenticated login response (fresh session + CSRF). */
    private fun issueAuthenticatedLogin(user: User, token: String, clearAnonCookie: Boolean = false): HttpResponse {
        val csrf = service.csrfFor(token)!!
        val res = jsonResponse(200, Json.sessionJson(user.username to user.role.name, csrf), cookieValue(SESSION_COOKIE, token, maxAge = 86400, secure = cookiesSecure))
        if (clearAnonCookie) res.header("Set-Cookie", cookieValue(ANON_COOKIE, "", maxAge = 0, secure = cookiesSecure))
        return res
    }

    private fun handleLogout(req: HttpRequest): HttpResponse {
        requireMethod(req, "POST")
        if (!sameOrigin(req)) return apiError(403, "Originが一致しません。", req)
        val s = currentSession(req)
        if (s != null) {
            if (csrfProvided(req) != s.csrf) return apiError(403, "CSRFトークンが不正です。", req)
            service.logout(s.token)
        } else {
            if (anonymousContext(req) == null) return apiError(403, "CSRFトークンが不正です。", req)
        }
        val token = Tokens.randomToken()
        val csrf = Tokens.randomToken()
        db.createAnonymousSession(token, csrf, nowUtc())
        val clear = cookieValue(SESSION_COOKIE, "", maxAge = 0, secure = cookiesSecure)
        val res = jsonResponse(200, Json.sessionJson(null, csrf), clear)
        res.header("Set-Cookie", cookieValue(ANON_COOKIE, token, secure = cookiesSecure))
        return res
    }

    // ---------------- JSON API: requests ----------------

    private fun handleRequests(req: HttpRequest): HttpResponse {
        val user = requireAuth(req)
        return when (req.method) {
            "GET" -> jsonResponse(200, Json.listWrapper(service.list(user)))
            "POST" -> {
                if (!mutatingValid(req)) return apiError(403, "CSRFトークンが不正です。", req)
                val input = Validation.parseRequestInput(parseInput(req))
                val created = service.create(user, input)
                jsonResponse(201, Json.requestWrapper(created))
            }
            else -> apiError(405, "メソッドがサポートされていません。", req)
        }
    }

    private fun handleApiRequestByPath(req: HttpRequest): HttpResponse {
        val user = requireAuth(req)
        val rest = req.path.removePrefix("/api/requests/")
        val seg = rest.split('/')
        val id = URLDecoder.decode(seg[0], "UTF-8")
        return when {
            seg.size == 1 && req.method == "GET" ->
                jsonResponse(200, Json.requestWrapper(service.get(user, id)))
            seg.size == 1 && req.method == "PATCH" -> {
                if (!mutatingValid(req)) return apiError(403, "CSRFトークンが不正です。", req)
                val input = Validation.parseRequestInput(parseInput(req), id)
                val updated = service.update(user, id, input)
                jsonResponse(200, Json.requestWrapper(updated))
            }
            seg.size == 2 && seg[1] == "submit" && req.method == "POST" -> {
                if (!mutatingValid(req)) return apiError(403, "CSRFトークンが不正です。", req)
                val updated = service.submit(user, id)
                jsonResponse(200, Json.requestWrapper(updated))
            }
            seg.size == 2 && seg[1] == "return" && req.method == "POST" -> {
                if (!mutatingValid(req)) return apiError(403, "CSRFトークンが不正です。", req)
                val comment = Validation.parseReturnComment(parseJson(req))
                val updated = service.`return`(user, id, comment)
                jsonResponse(200, Json.requestWrapper(updated))
            }
            seg.size == 2 && seg[1] == "approve" && req.method == "POST" -> {
                if (!mutatingValid(req)) return apiError(403, "CSRFトークンが不正です。", req)
                val updated = service.approve(user, id)
                jsonResponse(200, Json.requestWrapper(updated))
            }
            else -> apiError(405, "メソッドがサポートされていません。", req)
        }
    }

    // ---------------- HTML pages ----------------

    private fun handleLoginPage(req: HttpRequest): HttpResponse {
        if (req.method == "GET") {
            if (currentSession(req) != null) return redirect(302, "/requests")
            // The form must carry the token bound to this visitor's cookie.
            val cookieToken = anonCookie(req)
            val anon = cookieToken?.let { db.anonymousSession(it) }
            if (anon != null) {
                return htmlResponse(200, Html.loginPage(anonCsrf = anon.second))
            }
            // No valid anonymous identity yet: mint one and set the cookie.
            val token = Tokens.randomToken()
            val csrf = Tokens.randomToken()
            db.createAnonymousSession(token, csrf, nowUtc())
            return htmlResponse(200, Html.loginPage(anonCsrf = csrf), cookieValue(ANON_COOKIE, token, secure = cookiesSecure))
        }
        requireMethod(req, "POST")
        if (!sameOrigin(req)) return apiError(403, "Originが一致しません。", req)
        if (anonymousContext(req) == null) return apiError(403, "CSRFトークンが不正です。", req)
        val form = parseForm(req)
        val username = form["username"]?.trim().orEmpty()
        val password = form["password"].orEmpty()
        return try {
            val (user, token) = service.login(username, password)
            db.consumeAnonymousSession(anonCookie(req)!!)
            val cookie = cookieValue(SESSION_COOKIE, token, maxAge = 86400, secure = cookiesSecure)
            val clear = cookieValue(ANON_COOKIE, "", maxAge = 0, secure = cookiesSecure)
            val res = redirect(302, "/requests")
            res.header("Set-Cookie", cookie)
            res.header("Set-Cookie", clear)
            res
        } catch (e: ApiException) {
            htmlResponse(401, Html.loginPage(username, e.message, anonCsrf = anonymousContext(req)?.second))
        }
    }

    /** HTML logout: POST /logout invalidates the session and redirects to /login. */
    private fun handleLogoutPage(req: HttpRequest): HttpResponse {
        requireMethod(req, "POST")
        if (!sameOrigin(req)) return htmlResponse(403, Html.errorPage("Originが一致しません。"))
        val s = currentSession(req)
        if (s != null) {
            if (csrfProvided(req) != s.csrf) return htmlResponse(403, Html.errorPage("CSRFトークンが不正です。"))
            service.logout(s.token)
        }
        val res = redirect(302, "/login")
        res.header("Set-Cookie", cookieValue(SESSION_COOKIE, "", maxAge = 0, secure = cookiesSecure))
        return res
    }

    private fun handleListPage(req: HttpRequest): HttpResponse {
        val s = currentSession(req) ?: return redirect(302, "/login")
        val reqs = service.list(s.user)
        return htmlResponse(200, Html.listPage(s.user, reqs, s.csrf))
    }

    private fun handleNewPage(req: HttpRequest): HttpResponse {
        val s = currentSession(req) ?: return redirect(302, "/login")
        val user = s.user
        val csrf = s.csrf
        if (user.role != Role.EMPLOYEE) {
            return htmlResponse(403, Html.errorPage("このページは社員のみ利用できます。"))
        }
        if (req.method == "GET") return htmlResponse(200, Html.newRequestPage(user, csrf))
        requireMethod(req, "POST")
        if (!mutatingValid(req)) return htmlResponse(403, Html.newRequestPage(user, csrf, error = "CSRFトークンが不正です。"))
        val form = parseForm(req)
        return try {
            val input = Validation.parseRequestInput(formToInput(form))
            val created = service.create(user, input)
            redirect(302, "/requests/${URLEncoder.encode(created.id, "UTF-8")}")
        } catch (e: ApiException) {
            htmlResponse(400, Html.newRequestPage(user, csrf, form = form, error = e.message))
        }
    }

    private fun handleDetailPage(req: HttpRequest): HttpResponse {
        val s = currentSession(req) ?: return redirect(302, "/login")
        val user = s.user
        val csrf = s.csrf
        val id = URLDecoder.decode(req.path.removePrefix("/requests/"), "UTF-8")
        val idOnly = id.substringBefore('/')
        val tail = id.substringAfter('/', "")

        // /requests/{id}/edit  → edit form (GET)
        if (tail == "edit") {
            val purchase = try {
                service.get(user, idOnly)
            } catch (e: ApiException) {
                return htmlResponse(e.status, Html.errorPage(e.message))
            }
            return handleEditPage(user, purchase, csrf, req)
        }

        val purchase = try {
            service.get(user, idOnly)
        } catch (e: ApiException) {
            return htmlResponse(e.status, Html.errorPage(e.message))
        }

        if (req.method == "GET") {
            return htmlResponse(200, Html.detailPage(user, purchase, csrf))
        }

        // POST actions
        requireMethod(req, "POST")
        if (!mutatingValid(req)) return apiError(403, "CSRFトークンが不正です。", req)
        val form = parseForm(req)
        val action = form["action"]
        return when (action) {
            "submit" -> try {
                val updated = service.submit(user, idOnly)
                redirect(302, "/requests/${URLEncoder.encode(updated.id, "UTF-8")}")
            } catch (e: ApiException) {
                htmlResponse(e.status, Html.detailPage(user, purchase, csrf, form, e.message))
            }
            "update" -> try {
                val input = Validation.parseRequestInput(formToInput(form), idOnly)
                val updated = service.update(user, idOnly, input)
                redirect(302, "/requests/${URLEncoder.encode(updated.id, "UTF-8")}")
            } catch (e: ApiException) {
                htmlResponse(e.status, Html.editPage(user, purchase, csrf, form, e.message))
            }
            "return" -> try {
                val comment = Validation.parseReturnComment(JSONObject(form.filterKeys { it == "comment" }))
                val updated = service.`return`(user, idOnly, comment)
                redirect(302, "/requests/${URLEncoder.encode(updated.id, "UTF-8")}")
            } catch (e: ApiException) {
                htmlResponse(e.status, Html.detailPage(user, purchase, csrf, form, e.message))
            }
            "approve" -> try {
                val updated = service.approve(user, idOnly)
                redirect(302, "/requests/${URLEncoder.encode(updated.id, "UTF-8")}")
            } catch (e: ApiException) {
                htmlResponse(e.status, Html.detailPage(user, purchase, csrf, form, e.message))
            }
            else -> apiError(400, "不明な操作です。", req)
        }
    }

    private fun handleEditPage(
        user: User,
        purchase: com.example.purchasedesk.domain.PurchaseRequest,
        csrf: String,
        req: HttpRequest
    ): HttpResponse {
        val isOwner = purchase.ownerUsername == user.username
        val canEdit = user.role == Role.EMPLOYEE && isOwner &&
                (purchase.state == State.DRAFT || purchase.state == State.RETURNED)
        if (!canEdit) {
            return htmlResponse(403, Html.errorPage("この申請を編集する権限がありません。"))
        }
        if (req.method == "GET") {
            return htmlResponse(200, Html.editPage(user, purchase, csrf))
        }
        requireMethod(req, "POST")
        if (!mutatingValid(req)) return apiError(403, "CSRFトークンが不正です。", req)
        val form = parseForm(req)
        return try {
            val input = Validation.parseRequestInput(formToInput(form), purchase.id)
            val updated = service.update(user, purchase.id, input)
            redirect(302, "/requests/${URLEncoder.encode(updated.id, "UTF-8")}")
        } catch (e: ApiException) {
            htmlResponse(e.status, Html.editPage(user, purchase, csrf, form, e.message))
        }
    }

    // ---------------- helpers ----------------

    private fun requireMethod(req: HttpRequest, expected: String) {
        if (req.method != expected) throw ApiException(405, "メソッドがサポートされていません。")
    }

    private fun sessionToken(req: HttpRequest): String? =
        parseCookies(req.header("cookie") ?: "")[SESSION_COOKIE]?.takeIf { it.isNotBlank() }

    private fun anonCookie(req: HttpRequest): String? =
        parseCookies(req.header("cookie") ?: "")[ANON_COOKIE]?.takeIf { it.isNotBlank() }

    private data class Session(val user: User, val csrf: String, val token: String)

    /** The authenticated session for this request, or null when anonymous. */
    private fun currentSession(req: HttpRequest): Session? {
        val token = sessionToken(req) ?: return null
        val (user, csrf) = service.sessionUser(token) ?: return null
        return Session(user, csrf, token)
    }

    private fun requireAuth(req: HttpRequest): User =
        currentSession(req)?.user ?: throw ApiException(401, "ログインが必要です。")

    private fun csrfProvided(req: HttpRequest): String? {
        val header = req.header("x-csrf-token")
        val form = if (req.method == "POST") parseForm(req) else emptyMap()
        return header ?: form["csrfToken"]
    }

    /**
     * Same-origin check for mutating requests. A request is accepted only when
     * the (scheme, hostname, effective-port) tuple of its `Origin` (or,
     * fallback, `Referer`) header matches the expected origin for this request.
     *
     * Without [publicOrigin] the expected origin is derived from the literal
     * `Host` header of this connection (`http://<host[:port]>`), so the
     * loopback-HTTP default stays usable. When [publicOrigin] is configured the
     * expected origin is that fixed value; the literal Host header is never
     * trusted to widen the accept list. Arbitrary `X-Forwarded-Proto` /
     * `X-Forwarded-Host` / `X-Forwarded-Port` headers are ignored entirely —
     * they must be stripped by the trusted proxy, not honored here.
     */
    private fun sameOrigin(req: HttpRequest): Boolean {
        val headerValue = req.header("origin") ?: req.header("referer") ?: return true
        val expected = expectedOriginParts ?: parseHostToOrigin(req.header("host") ?: return true) ?: return false
        val candidate = parseOrigin(headerValue) ?: return false
        return candidate == expected
    }

    /** Splits a Host header into `(scheme="http", host, effective port)`. */
    private fun parseHostToOrigin(hostHeader: String): Triple<String, String, Int>? {
        val s = hostHeader.trim()
        if (s.isEmpty()) return null
        // Support both "host:port" and bracketed IPv6 "[::1]:port".
        val (host, portStr) = when {
            s.startsWith("[") -> {
                val close = s.indexOf(']')
                if (close < 0) return null
                s.substring(1, close) to s.substring(close + 1).substringAfter(":", "").takeIf { it.isNotEmpty() }
            }
            s.lastIndexOf(':') > s.lastIndexOf('.') -> {
                // IPv4 or hostname with an explicit port
                val idx = s.lastIndexOf(':')
                s.substring(0, idx) to s.substring(idx + 1)
            }
            else -> s to null
        }
        if (host.isBlank()) return null
        val port = portStr?.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: 80
        return Triple("http", host.lowercase(), port)
    }

    /**
     * Parses an Origin (or Referer) header into a normalized
     * `(scheme, host, effective-port)` triple, or null when malformed / not a
     * valid absolute URI with http(s) scheme.
     */
    private fun parseOrigin(v: String): Triple<String, String, Int>? {
        val s = v.trim()
        if (s.isEmpty()) return null
        val u = try {
            java.net.URI(s)
        } catch (_: Exception) {
            return null
        }
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase() ?: return null
        if (host.isEmpty()) return null
        val port = u.port.takeIf { p -> p in 1..65535 } ?: (if (scheme == "https") 443 else 80)
        return Triple(scheme, host, port)
    }

    /**
     * Mutating-route check: same-origin + CSRF token matching the caller's
     * current session (authenticated or anonymous).
     */
    private fun mutatingValid(req: HttpRequest): Boolean {
        if (!sameOrigin(req)) return false
        val s = currentSession(req)
        if (s != null) return csrfProvided(req) == s.csrf
        return anonymousContext(req) != null
    }

    private fun parseJson(req: HttpRequest): JSONObject {
        val body = req.bodyString
        if (body.isBlank()) return JSONObject()
        return JSONObject(body)
    }

    /**
     * Parses the request body into a JSONObject regardless of whether it is
     * JSON or form-urlencoded (so HTML form submissions work on API paths).
     */
    private fun parseInput(req: HttpRequest): JSONObject {
        val ct = req.header("content-type").orEmpty()
        if (ct.contains("application/x-www-form-urlencoded")) {
            return formToInput(parseForm(req))
        }
        return parseJson(req)
    }

    private fun parseForm(req: HttpRequest): Map<String, String> {
        val ct = req.header("content-type").orEmpty()
        val out = mutableMapOf<String, String>()
        val body = req.bodyString
        if (body.isBlank()) return out
        if (ct.contains("application/json")) {
            try {
                val o = JSONObject(body)
                for (k in o.keys()) out[k] = o.optString(k)
            } catch (_: Exception) {
                // fall through
            }
            return out
        }
        for (pair in body.split('&')) {
            val idx = pair.indexOf('=')
            if (idx < 0) continue
            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            out[k] = v
        }
        return out
    }

    private fun formToInput(form: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in form) if (k != "csrfToken" && k != "action") o.put(k, v)
        return o
    }

    /**
     * API paths always return JSON. HTML paths return an HTML error page so a
     * browser sees something readable.
     */
    private fun apiError(status: Int, message: String, req: HttpRequest): HttpResponse {
        return if (req.path.startsWith("/api/")) {
            jsonResponse(status, Json.errorJson(message))
        } else {
            htmlResponse(status, Html.errorPage(message))
        }
    }

    private fun parseCookies(header: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (pair in header.split(';')) {
            val idx = pair.indexOf('=')
            if (idx < 0) continue
            out[pair.substring(0, idx).trim()] = pair.substring(idx + 1).trim()
        }
        return out
    }

    private fun nowUtc(): String =
        java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    override fun close() = server.close()

    companion object {
        const val SESSION_COOKIE = "pd_session"
        const val ANON_COOKIE = "pd_anon"
    }
}
