package com.aistra.hail.utils

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.provider.Telephony
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import org.lsposed.hiddenapibypass.HiddenApiBypass

object HPackages {
    val myUserId get() = android.os.Process.myUserHandle().hashCode()

    fun packageUri(packageName: String) = "package:$packageName"

    @RequiresApi(Build.VERSION_CODES.N)
    fun packageUid(packageName: String) = if (HTarget.T) app.packageManager.getPackageUid(
        packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
    ) else app.packageManager.getPackageUid(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)

    fun getInstalledApplications(flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192): List<ApplicationInfo> =
        if (HTarget.T) app.packageManager.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getInstalledApplications(flags)

    /**
     * Apps Modes is allowed to manage.
     *
     * This is intentionally stricter than "apps with launcher icons": preinstalled
     * and updated-system packages can expose normal launcher activities while still
     * being required by the OS. Modes only operates on non-system applications and
     * additionally protects the current home app and known critical packages.
     */
    fun getModeEligibleApplications(): List<ApplicationInfo> {
        val homePackage = runCatching {
            app.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }.getOrNull()
        val protectedPackages = modeProtectedPackages(homePackage)

        return getInstalledApplications().filter { application ->
            val installed = application.flags and ApplicationInfo.FLAG_INSTALLED != 0
            val regularUserApp = application.flags and ApplicationInfo.FLAG_SYSTEM == 0
            val protected = application.packageName in protectedPackages ||
                application.packageName.startsWith("com.google.android.gms.") ||
                application.flags and ApplicationInfo.FLAG_PERSISTENT != 0
            installed && regularUserApp && !protected
        }
    }

    private fun modeProtectedPackages(homePackage: String?): Set<String> = buildSet {
        addAll(MODE_PROTECTED_PACKAGES)
        add(BuildConfig.APPLICATION_ID)
        homePackage?.let(::add)
        runCatching {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.takeIf(String::isNotBlank)?.let(::add)
        }
        runCatching {
            Settings.Secure.getString(app.contentResolver, "always_on_vpn_app")
                ?.takeIf(String::isNotBlank)?.let(::add)
        }
        listOf(
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "enabled_notification_listeners"
        ).forEach { setting ->
            runCatching {
                Settings.Secure.getString(app.contentResolver, setting)
                    .orEmpty().split(':').mapNotNullTo(this) { flattened ->
                        flattened.substringBefore('/').takeIf(String::isNotBlank)
                    }
            }
        }
        runCatching { app.getSystemService<TelecomManager>()?.defaultDialerPackage?.let(::add) }
        runCatching { Telephony.Sms.getDefaultSmsPackage(app)?.let(::add) }
        runCatching {
            app.getSystemService<DevicePolicyManager>()?.activeAdmins.orEmpty()
                .mapTo(this) { it.packageName }
        }
        listOf(
            "android.view.InputMethod",
            "android.net.VpnService"
        ).forEach { action ->
            runCatching {
                app.packageManager.queryIntentServices(
                    Intent(action),
                    PackageManager.MATCH_DISABLED_COMPONENTS
                ).mapTo(this) { it.serviceInfo.packageName }
            }
        }
    }

    private val MODE_PROTECTED_PACKAGES = setOf(
        "com.google.android.gms",
        "com.aistra.hail",
        "com.google.android.gsf",
        "com.android.vending",
        "com.android.systemui",
        "com.android.settings",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.samsung.android.ipsgeofence",
        "com.samsung.android.scloud",
        "com.samsung.android.samsungpass",
        "com.samsung.android.mdx",
        "com.samsung.android.lool",
        "com.sec.android.app.launcher",
        "moe.shizuku.privileged.api",
        "rikka.shizuku"
    )

    fun getUnhiddenPackageInfoOrNull(
        packageName: String, flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192
    ) = runCatching {
        if (HTarget.T) app.packageManager.getPackageInfo(
            packageName, PackageManager.PackageInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getPackageInfo(packageName, flags)
    }.getOrNull()

    fun getApplicationInfoOrNull(
        packageName: String, flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192
    ) = runCatching {
        if (HTarget.T) app.packageManager.getApplicationInfo(
            packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getApplicationInfo(packageName, flags)
    }.getOrNull()

    fun isAppDisabled(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.enabled?.not() ?: false

    fun isAppHidden(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 1 == 1
    } ?: false

    fun isAppStopped(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_STOPPED == ApplicationInfo.FLAG_STOPPED }
            ?: false

    fun isAppSuspended(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        when {
//            This method will cause NameNotFoundException with uninstalled packages
//            HTarget.Q -> app.packageManager.isPackageSuspended(packageName)
            HTarget.N -> it.flags and ApplicationInfo.FLAG_SUSPENDED == ApplicationInfo.FLAG_SUSPENDED
            else -> false
        }
    } ?: false

    fun isAppUninstalled(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_INSTALLED != ApplicationInfo.FLAG_INSTALLED }
            ?: true

    fun isPrivilegedApp(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 8 == 8
    } ?: false

    fun canUninstallNormally(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.sourceDir?.startsWith("/data") ?: false

    fun forceStopApp(packageName: String): Boolean = runCatching {
        app.getSystemService<ActivityManager>()!!.let {
            if (HTarget.P) HiddenApiBypass.invoke(it::class.java, it, "forceStopPackage", packageName)
            else it::class.java.getMethod("forceStopPackage", String::class.java).invoke(it, packageName)
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    fun setAppDisabled(packageName: String, disabled: Boolean): Boolean {
        getApplicationInfoOrNull(packageName) ?: return false
        if (disabled) forceStopApp(packageName)
        runCatching {
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            app.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }.onFailure {
            HLog.e(it)
        }
        return isAppDisabled(packageName) == disabled
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean = runCatching {
        app.getSystemService<AppOpsManager>()!!.let {
            HiddenApiBypass.invoke(
                it::class.java,
                it,
                "setMode",
                "android:run_any_in_background",
                packageUid(packageName),
                packageName,
                if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
            )
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }
}
