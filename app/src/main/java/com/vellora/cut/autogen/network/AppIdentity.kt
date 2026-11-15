package com.vellora.cut.autogen.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * What Google sees when this app asks to sign in: the package name and the SHA-1 of the certificate
 * that signed the installed APK. Cloud Console must have an Android OAuth client with exactly this pair.
 */
object AppIdentity {

    fun sha1(context: Context): String? {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val first = signatures?.firstOrNull() ?: return null
            MessageDigest.getInstance("SHA-1").digest(first.toByteArray())
                .joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun summary(context: Context): String =
        "Package: ${context.packageName}\nSHA-1: ${sha1(context) ?: "nahi mila"}"
}
