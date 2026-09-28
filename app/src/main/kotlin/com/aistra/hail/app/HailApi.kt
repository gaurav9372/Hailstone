package com.aistra.hail.app

import android.content.Intent
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HFiles
import java.security.MessageDigest
import java.util.UUID

object HailApi {
    private const val KEY_INTERNAL_CAPABILITY = "internal_capability"
    private val internalCapability: String by lazy {
        val path = "${app.noBackupFilesDir.path}/api-capability"
        HFiles.read(path)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
            HFiles.writeAtomic(path, it)
        }
    }
    /** @since 0.5.0 */
    const val ACTION_LAUNCH = "${BuildConfig.APPLICATION_ID}.action.LAUNCH"

    /** @since 0.5.0 */
    const val ACTION_FREEZE = "${BuildConfig.APPLICATION_ID}.action.FREEZE"

    /** @since 0.5.0 */
    const val ACTION_UNFREEZE = "${BuildConfig.APPLICATION_ID}.action.UNFREEZE"

    /** @since 1.1.0 */
    const val ACTION_FREEZE_TAG = "${BuildConfig.APPLICATION_ID}.action.FREEZE_TAG"

    /** @since 1.1.0 */
    const val ACTION_UNFREEZE_TAG = "${BuildConfig.APPLICATION_ID}.action.UNFREEZE_TAG"

    /** @since 0.5.0 */
    const val ACTION_FREEZE_ALL = "${BuildConfig.APPLICATION_ID}.action.FREEZE_ALL"

    /** @since 0.5.0 */
    const val ACTION_UNFREEZE_ALL = "${BuildConfig.APPLICATION_ID}.action.UNFREEZE_ALL"

    /** @since 1.0.0 */
    const val ACTION_FREEZE_NON_WHITELISTED =
        "${BuildConfig.APPLICATION_ID}.action.FREEZE_NON_WHITELISTED"

    /** @since 1.3.0 */
    const val ACTION_FREEZE_AUTO = "${BuildConfig.APPLICATION_ID}.action.FREEZE_AUTO"

    /** @since 0.6.0 */
    const val ACTION_LOCK = "${BuildConfig.APPLICATION_ID}.action.LOCK"

    /** @since 0.6.0 */
    const val ACTION_LOCK_FREEZE = "${BuildConfig.APPLICATION_ID}.action.LOCK_FREEZE"

    fun getIntentForPackage(action: String, packageName: String) =
        authorizeInternal(Intent(action).putExtra(HailData.KEY_PACKAGE, packageName))

    fun Intent.addTag(tag: String) = putExtra(HailData.KEY_TAG, tag)

    fun getIntentForTag(action: String, tag: String) = authorizeInternal(Intent(action).addTag(tag))

    fun authorizeInternal(intent: Intent): Intent =
        intent.putExtra(KEY_INTERNAL_CAPABILITY, signatureFor(intent))

    fun isAuthorizedInternal(intent: Intent): Boolean {
        val supplied = intent.getStringExtra(KEY_INTERNAL_CAPABILITY) ?: return false
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            signatureFor(intent).toByteArray(Charsets.UTF_8)
        )
    }

    private fun signatureFor(intent: Intent): String {
        val payload = listOf(
            internalCapability,
            intent.action.orEmpty(),
            intent.getStringExtra(HailData.KEY_PACKAGE).orEmpty(),
            intent.getStringExtra(HailData.KEY_TAG).orEmpty()
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
