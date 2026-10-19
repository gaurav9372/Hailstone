package com.aistra.hail.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.aistra.hail.utils.AppIconCache

abstract class MainFragment : Fragment() {
    protected val activity: MainActivity get() = requireActivity() as MainActivity

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Package updates briefly remove the old APK; wait for replacement.
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) return
            AppIconCache.clear()
            if (view != null) onPackagesChanged()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(requireContext(), packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        requireContext().unregisterReceiver(packageReceiver)
        super.onStop()
    }

    protected open fun onPackagesChanged() = Unit
}
