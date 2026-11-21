package org.imdtv.iptvtv.data.management

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.imdtv.iptvtv.data.update.Update
import java.io.File
import java.security.MessageDigest

/** Hash detects corruption; the installed app's signer is the trust anchor, not a key downloaded from the panel. */
internal fun verifyManagedApk(context: Context, file: File, update: Update) {
    require(update.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true) { "Missing APK digest" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    require(actual.equals(update.sha256, ignoreCase = true)) { "APK checksum does not match the published release" }
    @Suppress("DEPRECATION")
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        ?: throw IllegalArgumentException("Invalid APK")
    @Suppress("DEPRECATION")
    val installed = context.packageManager.getPackageInfo(context.packageName, flags)
    require(archive.packageName == context.packageName) { "APK belongs to a different application" }
    require(versionOf(archive) == update.versionCode && versionOf(archive) > versionOf(installed)) { "APK version does not match, or is not newer" }
    require(archive.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true) { "APK requires a newer Android version" }
    val currentSigners = signers(installed)
    require(currentSigners.isNotEmpty() && signers(archive) == currentSigners) { "APK must be signed with the same key as this installation" }
}

@Suppress("DEPRECATION")
private fun versionOf(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

@Suppress("DEPRECATION")
private fun signers(info: PackageInfo): Set<String> =
    (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
        .orEmpty().map { it.toCharsString() }.toSet()
