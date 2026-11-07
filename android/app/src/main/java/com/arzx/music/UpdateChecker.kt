package com.arzx.music

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * In-app updates (0.6.3, WS-E): the app updates itself from its
 * OWN GitHub Releases (lulitaxu/music-player — a public repo).
 *
 * The contract every release follows:
 *  - tag "v<versionName>" (e.g. v0.6.3),
 *  - the release body is the what's-new text shown to the user,
 *  - an attached `version.json` asset: {"versionCode": N,
 *    "versionName": "x.y.z"} — THE comparison source. An update
 *    exists iff its versionCode is greater than the installed
 *    one; downgrades are never offered. (A tag-name comparison
 *    is kept only as a fallback for a release missing the JSON,
 *    comparing dotted numbers component-wise.)
 *  - an attached .apk asset: the installable build.
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
    private const val LATEST_URL =
        "https://api.github.com/repos/lulitaxu/music-player/releases/latest"

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val pageUrl: String
    )

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

    private fun getText(url: String, accept: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "arzxsh (Android)")
            setRequestProperty("Accept", accept)
        }
        if (conn.responseCode !in 200..299) {
            throw RuntimeException("HTTP ${conn.responseCode}")
        }
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
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

    /**
     * The latest release, or null when the fetch/parse fails OR
     * the release lacks a usable APK asset. Callers compare
     * [Release.versionCode] against the installed code
     * themselves; this returns the release either way.
     * Blocking: call off the main thread.
     */
    fun fetchLatest(ctx: Context): Release? {
        val root = JSONObject(getText(LATEST_URL, "application/vnd.github+json"))
        val tag = root.optString("tag_name")
        val pageUrl = root.optString("html_url")
        val notes = root.optString("body")
        val assets = root.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var apkName: String? = null
        var versionJsonUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (!url.startsWith("https://")) continue
            when {
                name == "version.json" -> versionJsonUrl = url
                name.endsWith(".apk") && apkUrl == null -> {
                    apkUrl = url
                    apkName = name
                }
            }
        }
        val apk = apkUrl ?: return null
        var code = -1
        var vname = tag.removePrefix("v")
        if (versionJsonUrl != null) {
            try {
                val vj = JSONObject(getText(versionJsonUrl, "application/octet-stream"))
                code = vj.optInt("versionCode", -1)
                vj.optString("versionName").takeIf { it.isNotEmpty() }?.let { vname = it }
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
        return Release(code, vname, notes, apk, apkName ?: "arzxsh.apk", pageUrl)
    }

    private fun apkFile(ctx: Context, rel: Release): File =
        File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "arzxsh-${rel.versionName}.apk")

    /** The already-downloaded APK for [rel], if present: after
     *  the unknown-sources grant detour, tapping Update again
     *  goes straight to the installer. */
    fun downloadedApk(ctx: Context, rel: Release): File? =
        apkFile(ctx, rel).takeIf { it.exists() && it.length() > 0 }

    /**
     * Downloads the release APK into the app's cache dir.
     * Callbacks fire on the IO thread; the caller hops to the UI
     * thread. onDone receives the file, or null on failure (the
     * partial is deleted — a corrupt update is never offered to
     * the installer).
     */
    fun downloadApk(
        ctx: Context,
        rel: Release,
        onProgress: (Int) -> Unit,
        onDone: (File?) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        ioExecutor.execute {
            val dest = apkFile(appCtx, rel)
            if (dest.exists() && dest.length() > 0) {
                onDone(dest)
                return@execute
            }
            val tmp = File(dest.parentFile, dest.name + ".part")
            try {
                val conn = (URL(rel.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "arzxsh (Android)")
                }
                if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
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
                if (tmp.length() == 0L) throw RuntimeException("empty download")
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                onProgress(100)
                onDone(dest)
            } catch (t: Throwable) {
                tmp.delete()
                onDone(null)
            }
        }
    }
}
