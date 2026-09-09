package com.vellora.cut.autogen.playback

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Caches decoded thumbnails for local AutoGen image files, keyed by file
 * path — so scrolling the Timeline/Preview doesn't re-decode the same PNG
 * from disk on every recomposition. Sized off this device's actual
 * available app heap (via ActivityManager), same approach as
 * VELLORA-ENGINE's BitmapLoader — a low-RAM device automatically gets a
 * smaller cache instead of one fixed number risking an OOM everywhere.
 *
 * Deliberately much simpler than BitmapLoader: AutoGen images are plain
 * local files that never change once generated (no video frames, no
 * trim/seek, no MediaMetadataRetriever) — a straightforward
 * path-to-Bitmap LruCache is all this needs.
 */
class ImageBitmapCache(context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val maxCacheBytes = (((activityManager?.memoryClass ?: 64) * 1024L * 1024L) / 8L)
        .coerceAtLeast(4L * 1024L * 1024L)
        .toInt()

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Decodes (downsampled, inSampleSize=2) and caches [path]; returns the
     * cached Bitmap on every call after the first. Null if decoding fails. */
    fun load(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        return try {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(path, options)?.also { cache.put(path, it) }
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        cache.evictAll()
    }
}
