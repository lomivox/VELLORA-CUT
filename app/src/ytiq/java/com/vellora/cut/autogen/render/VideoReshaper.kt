package com.vellora.cut.autogen.render

import android.content.Context
import java.io.File

/**
 * YTIQ version (no FFmpeg): Short/Long reshaping and re-encoding are not available here, so both
 * return null and the upload code falls back to sending the original video as it is.
 * (VELLORA CUT keeps the real FFmpeg version in src/cut.)
 */
object VideoReshaper {

    enum class Target { SHORT, LONG }

    @Suppress("UNUSED_PARAMETER")
    suspend fun normalize(context: Context, sourceFile: File): File? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun reshapeIfNeeded(context: Context, sourceFile: File, target: Target): File? = null
}
