package com.aistra.hail.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistra.hail.app.ModeController
import com.aistra.hail.work.HWork

class ModePackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED &&
            intent.action != Intent.ACTION_PACKAGE_REPLACED &&
            intent.action != Intent.ACTION_PACKAGE_CHANGED
        ) return
        if (intent.action == Intent.ACTION_PACKAGE_CHANGED) {
            intent.data?.schemeSpecificPart?.let(ModeController::onPackageChanged)
        }
        HWork.reconcileMode()
    }
}
