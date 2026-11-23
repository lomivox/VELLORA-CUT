package com.vellora.cut.autogen.memory

import java.io.File
import java.security.MessageDigest

/**
 * Remembers YouTube search/list answers on the phone, so the same question is not asked again (and does not use up
 * the daily YouTube quota). Only successful answers are saved.
 */
object SearchCache {

    private const val MAX_FILES = 400

    private fun file(rawKey: String): File? {
        val ctx = ChannelMemory.context() ?: return null
        val dir = File(ctx.cacheDir, "yt_answers").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(rawKey.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.json")
    }

    fun get(rawKey: String, ttlMs: Long): String? {
        val f = file(rawKey) ?: return null
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > ttlMs) return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    fun put(rawKey: String, body: String) {
        val f = file(rawKey) ?: return
        try {
            f.writeText(body)
            val all = f.parentFile?.listFiles()
            if (all != null && all.size > MAX_FILES) {
                all.sortedBy { it.lastModified() }.take(all.size - MAX_FILES + 50).forEach { it.delete() }
            }
        } catch (e: Exception) {
            // cache is best-effort
        }
    }
}
