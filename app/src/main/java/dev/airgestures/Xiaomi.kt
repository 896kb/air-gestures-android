package dev.airgestures

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * HyperOS (and MIUI before it) adds its own "Open new windows while running in
 * the background" permission on top of Android's rules. Without it, starting an
 * app from a gesture is dropped silently, even from the accessibility service.
 */
object Xiaomi {
    /** MIUI's app-op for starting activities from the background. */
    private const val OP_BACKGROUND_START_ACTIVITY = 10021

    val isXiaomi: Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    /** Whether the permission is granted; null when the system won't say. */
    fun canOpenFromBackground(context: Context): Boolean? = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val check = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
        )
        check.invoke(ops, OP_BACKGROUND_START_ACTIVITY, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    /** The app's "Other permissions" page, or its App info if that page isn't there. */
    fun openPermissions(context: Context) {
        val editor = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName)
        runCatching { context.startActivity(editor) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
}
