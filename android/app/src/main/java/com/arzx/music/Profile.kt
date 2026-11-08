package com.arzx.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Local profile (0.6.4, WS-2): a display name + optional photo
 * behind the header-corner avatar (Home / Library / Search).
 *
 * Deliberately LOCAL and account-free: the name lives in
 * app-private SharedPreferences, the photo as a single
 * center-cropped ≤512px JPEG in app-private internal storage.
 * Nothing is uploaded, nothing needs a sign-in, and the
 * allowBackup=false stance is unchanged — the profile stays on
 * this device, like the rest of the app's state.
 *
 * Photo picking goes through the system photo picker
 * (ACTION_PICK_IMAGES on API 33+, ACTION_GET_CONTENT below) —
 * no storage permission is requested; the picker's transient
 * read grant is consumed immediately by copying the image in
 * [savePhotoFromUri].
 *
 * Perf discipline (the 0.6.4 optimization gate): the stored
 * file is capped at 512px, and DISPLAY decodes are sampled to
 * the requesting view's size — the ~34dp header corner decodes
 * at ~128px class, only the 96dp sheet preview approaches the
 * 512px ceiling. The largest decode per photo is cached and
 * reused by smaller targets: one bounded decode per (photo
 * change × size class), never a decode per draw, and nothing
 * is routed through the remote-artwork pipeline.
 *
 * V7 NOTE: when Google sign-in ships, the Google account name
 * and photo take precedence over this local profile wherever
 * both exist; this object remains the signed-out fallback.
 * (Not built here — 0.6.4 is local-only by design.)
 */
object Profile {
    private const val PREFS = "profile"
    private const val KEY_NAME = "display_name"
    private const val PHOTO_NAME = "profile_photo.jpg"
    private const val MAX_PX = 512
    private const val MAX_NAME_CHARS = 40

    fun displayName(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, "") ?: ""

    fun setDisplayName(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, name.trim().take(MAX_NAME_CHARS)).apply()
    }

    /** Avatar fallback initial: the display name's first
     *  letter, or "A" while no name is set (the pre-0.6.4
     *  corner's letter). */
    fun initial(ctx: Context): String {
        val n = displayName(ctx).trim()
        return if (n.isEmpty()) "A" else n.first().uppercaseChar().toString()
    }

    private fun photoFile(ctx: Context): File = File(ctx.filesDir, PHOTO_NAME)

    fun hasPhoto(ctx: Context): Boolean =
        photoFile(ctx).let { it.exists() && it.length() > 0 }

    fun removePhoto(ctx: Context) {
        photoFile(ctx).delete()
        synchronized(cacheLock) {
            cached = null
            cachedStamp = -1L
        }
    }

    // ------------------------------------------------------------ photo bitmap cache

    private val cacheLock = Any()

    @Volatile
    private var cached: Bitmap? = null

    @Volatile
    private var cachedStamp: Long = -1L

    /** The stored photo decoded for a view of [targetPx]
     *  pixels, cached by file stamp; null when no photo is
     *  set. The decode is sampled (largest power-of-two sample
     *  still yielding ≥ [targetPx] wide), so the header corner
     *  never pays for the full 512px bitmap. The largest decode
     *  for a stamp is kept and reused by smaller targets. */
    fun photoBitmap(ctx: Context, targetPx: Int): Bitmap? {
        val f = photoFile(ctx)
        if (!f.exists() || f.length() == 0L) return null
        val stamp = f.lastModified() xor f.length()
        synchronized(cacheLock) {
            val c = cached
            if (c != null && cachedStamp == stamp && c.width >= targetPx) return c
        }
        val bmp = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
                BitmapFactory.decodeFile(
                    f.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        } catch (_: Exception) {
            null
        }
        if (bmp != null) {
            synchronized(cacheLock) {
                val c = cached
                if (cachedStamp != stamp || c == null || bmp.width > c.width) {
                    cached = bmp
                    cachedStamp = stamp
                }
            }
            return bmp
        }
        return synchronized(cacheLock) { if (cachedStamp == stamp) cached else null }
    }

    /**
     * Imports a picked image: center-crop to a square,
     * downscale to at most [MAX_PX]px, store as JPEG in
     * app-private storage (via a temp file + rename, so a
     * failed import never leaves a torn photo behind).
     * Two-pass decode with inSampleSize: a full-resolution
     * camera photo never lands in memory whole (Helio G95
     * budget). Blocking — call off the main thread.
     * Returns success.
     */
    fun savePhotoFromUri(ctx: Context, uri: Uri): Boolean {
        return try {
            val resolver = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            val largest = max(bounds.outWidth, bounds.outHeight)
            while (largest / (sample * 2) >= MAX_PX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            val side = min(src.width, src.height)
            val cropped = Bitmap.createBitmap(
                src, (src.width - side) / 2, (src.height - side) / 2, side, side
            )
            val scaled = if (cropped.width > MAX_PX) {
                Bitmap.createScaledBitmap(cropped, MAX_PX, MAX_PX, true)
            } else {
                cropped
            }
            val dest = photoFile(ctx)
            val tmp = File(ctx.filesDir, "$PHOTO_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                    tmp.delete()
                    return false
                }
            }
            // 0.6.13 audit fix: the intermediate bitmaps are
            // dead once compressed — recycle each DISTINCT
            // instance (createBitmap can return the source
            // itself when the crop is the whole image, so
            // identity, not role, decides).
            listOf(src, cropped, scaled).distinct().forEach { it.recycle() }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            synchronized(cacheLock) {
                cached = null
                cachedStamp = -1L
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
