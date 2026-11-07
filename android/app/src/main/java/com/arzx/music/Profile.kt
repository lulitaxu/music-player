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
 * Perf discipline: the stored photo decodes to ONE cached
 * bitmap (≤512×512, ~1 MB), decoded once per change and reused
 * by every header — never decoded per draw, never routed
 * through the remote-artwork pipeline.
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

    /** The stored photo, decoded once and cached by file
     *  stamp; null when no photo is set. Safe to call on the
     *  UI thread: the decode is a single ≤512px JPEG. */
    fun photoBitmap(ctx: Context): Bitmap? {
        val f = photoFile(ctx)
        if (!f.exists() || f.length() == 0L) return null
        val stamp = f.lastModified() xor f.length()
        synchronized(cacheLock) {
            if (cached != null && cachedStamp == stamp) return cached
        }
        val bmp = try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Exception) {
            null
        }
        synchronized(cacheLock) {
            cached = bmp
            cachedStamp = stamp
        }
        return bmp
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
