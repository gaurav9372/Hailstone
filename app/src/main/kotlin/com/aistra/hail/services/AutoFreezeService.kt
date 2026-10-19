package com.aistra.hail.services

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.aistra.hail.R
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.receiver.ScreenOffReceiver

class AutoFreezeService : NotificationListenerService() {
    private val channelID = javaClass.simpleName
    private val lockReceiver by lazy { ScreenOffReceiver() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val freezeAuto = PendingIntent.getActivity(
            applicationContext,
            0,
            HailApi.authorizeInternal(Intent(HailApi.ACTION_FREEZE_AUTO)),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelID)
            .setContentTitle(getString(R.string.auto_freeze_notification_title))
            .setSmallIcon(R.drawable.ic_round_frozen)
            .addAction(R.drawable.ic_round_frozen, getString(R.string.auto_freeze), freezeAuto)
        if (HailData.managedApps().any { it.whitelisted }) {
            val freezeNonWhitelisted = PendingIntent.getActivity(
                applicationContext,
                0,
                HailApi.authorizeInternal(Intent(HailApi.ACTION_FREEZE_NON_WHITELISTED)),
                PendingIntent.FLAG_IMMUTABLE
            )
            notification.addAction(
                R.drawable.ic_round_frozen,
                getString(R.string.action_freeze_non_whitelisted),
                freezeNonWhitelisted
            )
        }
        startForeground(100, notification.build())
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val name = getString(R.string.auto_freeze)
        val importance = NotificationManagerCompat.IMPORTANCE_LOW
        val channel = NotificationChannelCompat.Builder(channelID, importance).setName(name).build()
        // Register the channel with the system
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    override fun onCreate() {
        super.onCreate()
        registerScreenReceiver()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    private fun registerScreenReceiver() {
        registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
        unregisterReceiver(lockReceiver)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        @Volatile
        private var instance: AutoFreezeService? = null

        /** Null means notification state is unavailable, not that no app is notifying. */
        fun notifyingPackages(): Set<String>? = instance?.let { listener ->
            runCatching { listener.activeNotifications?.mapTo(mutableSetOf()) { it.packageName } }
                .getOrNull()
        }
    }
}
