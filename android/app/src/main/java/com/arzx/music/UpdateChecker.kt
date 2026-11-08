package com.arzx.music

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * In-app updates (0.6.3, WS-E; transport rebuilt 0.6.4, WS-1):
 * the app updates itself from its OWN GitHub Releases
 * (lulitaxu/music-player — a public repo).
 *
 * The contract every release follows:
 *  - tag "v<versionName>" (e.g. v0.6.4),
 *  - the release body is the what's-new text shown to the user,
 *  - an attached `version.json` asset: {"versionCode": N,
 *    "versionName": "x.y.z"} — THE comparison source. An update
 *    exists iff its versionCode is greater than the installed
 *    one; downgrades are never offered. (A tag-name comparison
 *    is kept only as a fallback for a release missing the JSON,
 *    comparing dotted numbers component-wise.)
 *  - an attached .apk asset named "arzxsh-<versionName>.apk".
 *
 * Transport (0.6.4): the PRIMARY path never touches the REST
 * API. `releases/latest/download/version.json` is served by
 * github.com itself and resolves to the newest release's
 * version.json; the APK URL is then constructed from the
 * naming contract above. The REST API
 * (api.github.com/repos/.../releases/latest) is demoted to a
 * fallback + a best-effort enrichment for the release notes
 * when an update exists. Why: 0.6.3's check was gated on that
 * one API call, and unauthenticated api.github.com is limited
 * to 60 requests/hour PER IP — on carrier NAT (mobile data)
 * that shared budget is routinely exhausted by strangers, so
 * the phone got HTTP 403 and the whole check died before the
 * (working) asset URLs were ever reached. The 0.6.3 VM-side
 * verification passed because the VM's datacenter IP had a
 * fresh budget.
 *
 * Every failure throws [UpdateCheckException] carrying a
 * human-readable detail (HTTP status + GitHub's own message,
 * or the exception class) — callers show it as small print,
 * never a bare "check your connection".
 *
 * All network/disk work runs on this object's own
 * process-lifetime executor — the same survival rule as the
 * download manager (WS-D Bug D): nothing here rides an activity
 * executor that a teardown can interrupt mid-transfer.
 *
 * Installs are NEVER silent (Android does not allow that for
 * sideloaded apps): we download with progress, then hand the
 * APK to the system installer; the one system confirm tap —
 * and the one-time "allow installs from arzxsh" grant — stay
 * the user's, by platform design.
 */
object UpdateChecker {
    private const val REPO = "lulitaxu/music-player"
    private const val LATEST_VERSION_JSON_URL =
        "https://github.com/$REPO/releases/latest/download/version.json"
    private const val API_LATEST_URL =
        "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO/releases"

    /** A failed update check/download, with the real reason. */
    class UpdateCheckException(val detail: String) : Exception(detail)

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val pageUrl: String,
        /** SHA-256 (hex) of the release APK, from version.json
         *  (0.6.13 audit fix): the downloaded APK is verified
         *  against this before the installer ever sees it. Null
         *  for a release whose version.json carries no hash —
         *  such a download is REFUSED, never installed blind. */
        val sha256: String? = null
    )

    /** A versionName from a remote document is data, not truth:
     *  it is interpolated into download URLs and file names, so
     *  only the strict dotted form is ever accepted (0.6.13). */
    private val VERSION_NAME_RE = Regex("""^\d+\.\d+\.\d+$""")
    private val SHA256_RE = Regex("""^[0-9a-fA-F]{64}$""")

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun installedVersionCode(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    } catch (_: Exception) {
        0
    }

    fun installedVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    // ------------------------------------------------------------ HTTP

    private fun openGet(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "arzxsh (Android)")
            setRequestProperty("Accept", accept)
        }

    /** GitHub's own error wording (the "message" field of an
     *  error body), trimmed for display; "" when unavailable. */
    private fun serverMessage(body: String?): String {
        if (body.isNullOrEmpty()) return ""
        return try {
            JSONObject(body).optString("message").take(110)
        } catch (_: Exception) {
            ""
        }
    }

    private fun httpDetail(code: Int, body: String?): String {
        val msg = serverMessage(body)
        return if (msg.isNotEmpty()) "HTTP $code · $msg" else "HTTP $code"
    }

    private fun ioDetail(t: Throwable): String {
        val msg = t.message?.take(100)
        return if (msg.isNullOrEmpty()) t.javaClass.simpleName
        else "${t.javaClass.simpleName} · $msg"
    }

    /** GET [url] as text, or throw [UpdateCheckException] with
     *  the real reason (status + server message, or the IO
     *  exception's class/message). */
    private fun getText(url: String, accept: String): String {
        val conn = openGet(url, accept)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try {
                    conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: Exception) {
                    null
                }
                throw UpdateCheckException(httpDetail(code, errBody))
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: UpdateCheckException) {
            throw e
        } catch (e: IOException) {
            throw UpdateCheckException(ioDetail(e))
        } finally {
            conn.disconnect()
        }
    }

    /** Dotted-version compare: is [candidate] strictly newer
     *  than [installed]? ("0.6.10" > "0.6.3" — numeric per
     *  component, never lexicographic.) */
    private fun nameIsNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String): List<Int> =
            v.trim().removePrefix("v").split(".").map { it.trim().toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun apkUrlFor(versionName: String): String =
        "https://github.com/$REPO/releases/download/v$versionName/arzxsh-$versionName.apk"

    private fun tagPageUrl(versionName: String): String =
        "https://github.com/$REPO/releases/tag/v$versionName"

    // ------------------------------------------------------------ fetch paths

    /**
     * PRIMARY (API-free): the newest release's version.json via
     * github.com's latest/download shortcut. Notes are not
     * available on this path ("" — enrichment may fill them).
     */
    private fun fetchViaVersionJson(): Release {
        val body = getText(LATEST_VERSION_JSON_URL, "application/json")
        val vj = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw UpdateCheckException("version.json unreadable (${ioDetail(e)})")
        }
        val code = vj.optInt("versionCode", -1)
        val vname = vj.optString("versionName").removePrefix("v")
        if (code < 0 || vname.isEmpty()) {
            throw UpdateCheckException("version.json lacks versionCode/versionName")
        }
        if (!VERSION_NAME_RE.matches(vname)) {
            throw UpdateCheckException("version.json has a malformed versionName")
        }
        val sha = vj.optString("sha256").takeIf { SHA256_RE.matches(it) }?.lowercase()
        return Release(code, vname, "", apkUrlFor(vname), "arzxsh-$vname.apk", tagPageUrl(vname), sha)
    }

    /**
     * FALLBACK / ENRICHMENT: the REST API. Carries the release
     * notes and the canonical asset URLs, but is rate-limited
     * per IP (60/h unauthenticated) — never the gate again.
     */
    private fun fetchViaApi(ctx: Context): Release {
        val root = JSONObject(getText(API_LATEST_URL, "application/vnd.github+json"))
        val tag = root.optString("tag_name")
        val pageUrl = root.optString("html_url")
        val notes = root.optString("body")
        val assets = root.optJSONArray("assets")
            ?: throw UpdateCheckException("release JSON has no asset list")
        var versionJsonUrl: String? = null
        // 0.6.13 audit fix: an asset URL is only ever trusted
        // when it is https AND hosted by GitHub itself — the
        // asset list is remote data, and "first .apk wins" let
        // any asset (or a tampered listing) choose the binary.
        fun trustedAssetUrl(url: String): Boolean {
            if (!url.startsWith("https://")) return false
            val host = try {
                URL(url).host
            } catch (_: Exception) {
                null
            } ?: return false
            return host == "github.com" || host.endsWith(".githubusercontent.com")
        }
        val apkAssets = ArrayList<Pair<String, String>>()
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (!trustedAssetUrl(url)) continue
            when {
                name == "version.json" -> versionJsonUrl = url
                name.endsWith(".apk") -> apkAssets += name to url
            }
        }
        var code = -1
        var vname = tag.removePrefix("v")
        var sha: String? = null
        if (versionJsonUrl != null) {
            try {
                val vj = JSONObject(getText(versionJsonUrl, "application/octet-stream"))
                code = vj.optInt("versionCode", -1)
                // A versionName out of remote JSON must pass the
                // same strict shape check as the primary path;
                // a malformed one is ignored, never interpolated.
                vj.optString("versionName").removePrefix("v")
                    .takeIf { VERSION_NAME_RE.matches(it) }?.let { vname = it }
                sha = vj.optString("sha256").takeIf { SHA256_RE.matches(it) }?.lowercase()
            } catch (_: Exception) {
                // Fall through to the tag fallback below.
            }
        }
        if (code < 0) {
            // Tag fallback (documented contract breach, handled
            // conservatively): encode newer-than-installed as
            // installed+1, anything else as installed, so the
            // caller's single comparison still behaves.
            val installedName = installedVersionName(ctx)
            val installedCode = installedVersionCode(ctx)
            code = if (nameIsNewer(vname, installedName)) installedCode + 1 else installedCode
        }
        // The APK asset must be THE contract asset for this
        // version — never "some .apk attached to the release".
        val expectedName = "arzxsh-$vname.apk"
        val apk = apkAssets.firstOrNull { it.first == expectedName }
            ?: throw UpdateCheckException("latest release has no $expectedName asset")
        return Release(code, vname, notes, apk.second, apk.first, pageUrl, sha)
    }

    /** One-byte range probe proving a constructed APK URL
     *  really resolves to a downloadable asset (used when the
     *  API could not confirm the asset list). */
    private fun verifyApkReachable(apkUrl: String) {
        val conn = openGet(apkUrl, "*/*")
        try {
            conn.setRequestProperty("Range", "bytes=0-0")
            val code = conn.responseCode
            if (code != 200 && code != 206) {
                val errBody = try {
                    conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: Exception) {
                    null
                }
                throw UpdateCheckException("APK asset check failed: ${httpDetail(code, errBody)}")
            }
        } catch (e: UpdateCheckException) {
            throw e
        } catch (e: IOException) {
            throw UpdateCheckException("APK asset check failed: ${ioDetail(e)}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The latest release. Blocking: call off the main thread.
     * Throws [UpdateCheckException] (detail = the real reason)
     * when every path fails.
     *
     * Order: API-free version.json first; the REST API only as
     * fallback, or — when an update actually exists — as a
     * best-effort enrichment for the notes (its failure can no
     * longer fail the check; the constructed APK URL is then
     * proven reachable instead).
     */
    fun fetchLatest(ctx: Context): Release {
        var direct: Release? = null
        var directError: UpdateCheckException? = null
        try {
            direct = fetchViaVersionJson()
        } catch (e: UpdateCheckException) {
            directError = e
        }
        val rel = direct ?: run {
            try {
                return fetchViaApi(ctx)
            } catch (e: UpdateCheckException) {
                throw UpdateCheckException(
                    "direct check: ${directError?.detail ?: "failed"} · API check: ${e.detail}"
                )
            }
        }
        if (rel.versionCode > installedVersionCode(ctx)) {
            val enriched = try {
                fetchViaApi(ctx)
            } catch (_: Exception) {
                null
            }
            if (enriched != null && enriched.versionName == rel.versionName) return enriched
            verifyApkReachable(rel.apkUrl)
        }
        return rel
    }

    // ------------------------------------------------------------ download

    private fun apkFile(ctx: Context, rel: Release): File =
        File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "arzxsh-${rel.versionName}.apk")

    /** The already-downloaded APK for [rel], if present AND
     *  verified: after the unknown-sources grant detour, tapping
     *  Update again goes straight to the installer. 0.6.13 audit
     *  fix: an existing file is no longer trusted for merely
     *  existing — without the release's SHA-256, or with a digest
     *  that does not match, the file is deleted and the caller
     *  falls through to a fresh (verified) download. */
    fun downloadedApk(ctx: Context, rel: Release): File? {
        val f = apkFile(ctx, rel)
        if (!f.exists() || f.length() == 0L) return null
        val expected = rel.sha256
        val ok = expected != null && try {
            sha256Of(f) == expected
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            f.delete()
            return null
        }
        return f
    }

    /**
     * Downloads the release APK into the app's cache dir.
     * Callbacks fire on the IO thread; the caller hops to the UI
     * thread. onDone receives the file (error null), or null
     * plus the failure detail (the partial is deleted — a
     * corrupt update is never offered to the installer).
     */
    fun downloadApk(
        ctx: Context,
        rel: Release,
        onProgress: (Int) -> Unit,
        onDone: (File?, String?) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        ioExecutor.execute {
            val dest = apkFile(appCtx, rel)
            // 0.6.13 audit fix: an update is installed ONLY when
            // it can be verified against the release's SHA-256.
            // A release with no hash on record is refused up
            // front — an unverifiable binary is never handed to
            // the system installer.
            val expected = rel.sha256
            if (expected == null) {
                onDone(null, "this release carries no checksum to verify the download against")
                return@execute
            }
            downloadedApk(appCtx, rel)?.let { verified ->
                onDone(verified, null)
                return@execute
            }
            val tmp = File(dest.parentFile, dest.name + ".part")
            try {
                val conn = (URL(rel.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "arzxsh (Android)")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errBody = try {
                        conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                    } catch (_: Exception) {
                        null
                    }
                    throw UpdateCheckException(httpDetail(code, errBody))
                }
                val total = conn.contentLengthLong
                var read = 0L
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt().coerceIn(0, 99)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                // A stream that ends early (or overruns) is a
                // corrupt update: it is deleted by the catch
                // below, never renamed into place (0.6.13).
                if (total > 0 && read != total) {
                    throw UpdateCheckException("truncated download ($read of $total bytes)")
                }
                if (tmp.length() == 0L) throw UpdateCheckException("empty download")
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                // The final gate: the bytes on disk must BE the
                // release the user was offered. A mismatch means
                // the file is deleted and the error surfaces —
                // the installer is never launched on it.
                val actual = sha256Of(dest)
                if (actual != expected) {
                    dest.delete()
                    throw UpdateCheckException("checksum mismatch — download discarded")
                }
                onProgress(100)
                onDone(dest, null)
            } catch (t: Throwable) {
                tmp.delete()
                val detail = if (t is UpdateCheckException) t.detail else ioDetail(t)
                onDone(null, detail)
            }
        }
    }

    // ------------------------------------------------------------ pending-update reminders (0.6.13)

    /**
     * A found-but-not-installed update is REMEMBERED (Arz's
     * rule): one notification when it is first seen, ONE more
     * after 48h if it is still not installed, then silence; and
     * the in-app prompt may reappear at most once per 24h. The
     * record dies the moment the installed version catches up.
     * All state is four small preference values; the activity
     * owns the dialog and the notification, this object owns
     * the bookkeeping.
     */
    private const val REMINDER_PREFS = "arzxsh_update_reminders"
    private const val KEY_CODE = "pending_code"
    private const val KEY_NAME = "pending_name"
    private const val KEY_SHA = "pending_sha256"
    private const val KEY_FIRST_SEEN = "pending_first_seen"
    private const val KEY_LAST_REMINDED = "pending_last_reminded"
    private const val KEY_NOTIF_COUNT = "pending_notif_count"
    private const val KEY_NOTIF_LAST = "pending_notif_last"

    const val REMIND_AFTER_MS = 24L * 3600L * 1000L
    const val SECOND_NOTIF_AFTER_MS = 48L * 3600L * 1000L

    private fun reminderPrefs(ctx: Context) =
        ctx.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)

    /** Records [rel] as the pending update (called whenever a
     *  check finds it newer than the installed build). A NEWER
     *  version than the recorded one resets the reminder clock;
     *  re-seeing the same version changes nothing. A release
     *  that is not newer clears any stale record instead. */
    fun recordPending(ctx: Context, rel: Release) {
        if (rel.versionCode <= installedVersionCode(ctx)) {
            clearPending(ctx)
            return
        }
        val p = reminderPrefs(ctx)
        if (p.getInt(KEY_CODE, -1) == rel.versionCode) return
        p.edit()
            .putInt(KEY_CODE, rel.versionCode)
            .putString(KEY_NAME, rel.versionName)
            .putString(KEY_SHA, rel.sha256)
            .putLong(KEY_FIRST_SEEN, System.currentTimeMillis())
            .putLong(KEY_LAST_REMINDED, 0L)
            .putInt(KEY_NOTIF_COUNT, 0)
            .putLong(KEY_NOTIF_LAST, 0L)
            .apply()
    }

    /** The pending update as a [Release], reconstructed from
     *  the naming contract (no network), or null when there is
     *  none — including when the installed build has caught up,
     *  which clears the record on the spot. */
    fun pendingRelease(ctx: Context): Release? {
        val p = reminderPrefs(ctx)
        val code = p.getInt(KEY_CODE, -1)
        if (code < 0) return null
        if (installedVersionCode(ctx) >= code) {
            clearPending(ctx)
            return null
        }
        val name = p.getString(KEY_NAME, null)?.takeIf { VERSION_NAME_RE.matches(it) }
            ?: run {
                clearPending(ctx)
                return null
            }
        val sha = p.getString(KEY_SHA, null)?.takeIf { SHA256_RE.matches(it) }
        return Release(code, name, "", apkUrlFor(name), "arzxsh-$name.apk", tagPageUrl(name), sha)
    }

    fun pendingLastReminded(ctx: Context): Long =
        reminderPrefs(ctx).getLong(KEY_LAST_REMINDED, 0L)

    /** Stamps the in-app prompt as shown just now, so it can
     *  reappear at most once per [REMIND_AFTER_MS]. */
    fun markReminded(ctx: Context) {
        val p = reminderPrefs(ctx)
        if (p.getInt(KEY_CODE, -1) < 0) return
        p.edit().putLong(KEY_LAST_REMINDED, System.currentTimeMillis()).apply()
    }

    /** True when a notification is owed for the pending
     *  version: the first one, or the single 48h follow-up.
     *  Never a third. */
    fun notificationDue(ctx: Context): Boolean {
        val p = reminderPrefs(ctx)
        val code = p.getInt(KEY_CODE, -1)
        if (code < 0 || installedVersionCode(ctx) >= code) return false
        return when (p.getInt(KEY_NOTIF_COUNT, 0)) {
            0 -> true
            1 -> System.currentTimeMillis() - p.getLong(KEY_NOTIF_LAST, 0L) >=
                SECOND_NOTIF_AFTER_MS
            else -> false
        }
    }

    fun markNotified(ctx: Context) {
        val p = reminderPrefs(ctx)
        if (p.getInt(KEY_CODE, -1) < 0) return
        p.edit()
            .putInt(KEY_NOTIF_COUNT, p.getInt(KEY_NOTIF_COUNT, 0) + 1)
            .putLong(KEY_NOTIF_LAST, System.currentTimeMillis())
            .apply()
    }

    fun clearPending(ctx: Context) {
        reminderPrefs(ctx).edit().clear().apply()
    }
}
