package com.aistra.hail.ui.modes

import android.content.pm.ApplicationInfo
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aistra.hail.BuildConfig
import com.aistra.hail.R
import com.aistra.hail.app.AppMode
import com.aistra.hail.app.ModeData
import com.aistra.hail.databinding.DialogFreezerAppPickerBinding
import com.aistra.hail.ui.freezer.FreezerAppPickerAdapter
import com.aistra.hail.utils.HPackages
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ModeAppPicker {
    fun show(fragment: Fragment, mode: AppMode, onSaved: () -> Unit) {
        val activity = fragment.requireActivity()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val (apps, labels) = withContext(Dispatchers.IO) {
                val labels = mutableMapOf<String, String>()
                val apps = HPackages.getModeEligibleApplications()
                    .filter { it.packageName != BuildConfig.APPLICATION_ID }
                apps.forEach { labels[it.packageName] = it.loadLabel(activity.packageManager).toString() }
                apps.sortedBy { labels[it.packageName]?.lowercase() } to labels
            }
            if (!fragment.isAdded) return@launch
            showLoaded(fragment, mode, apps, labels, onSaved)
        }
    }

    private fun showLoaded(
        fragment: Fragment,
        mode: AppMode,
        apps: List<ApplicationInfo>,
        labels: Map<String, String>,
        onSaved: () -> Unit
    ) {
        val activity = fragment.requireActivity()
        val selectedPackages = mode.excludedPackages.toMutableSet()
        val picker = DialogFreezerAppPickerBinding.inflate(fragment.layoutInflater)
        lateinit var pickerAdapter: FreezerAppPickerAdapter
        var visibleApps: List<ApplicationInfo> = emptyList()

        fun refresh() {
            val query = picker.search.text?.toString()?.trim().orEmpty()
            visibleApps = apps.filter { app ->
                query.isEmpty() || app.packageName.contains(query, ignoreCase = true) ||
                    labels[app.packageName].orEmpty().contains(query, ignoreCase = true)
            }
            pickerAdapter.submitList(visibleApps)
        }

        // A checkbox change does not alter list membership or ordering. Re-submitting the
        // list here made DiffUtil relocate selected rows and reset RecyclerView position.
        pickerAdapter = FreezerAppPickerAdapter(selectedPackages) { }
        picker.filterButton.isVisible = false
        picker.recyclerView.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = pickerAdapter
        }
        picker.search.doAfterTextChanged { refresh() }
        picker.selectAllButton.setOnClickListener {
            selectedPackages.addAll(visibleApps.map { it.packageName })
            pickerAdapter.notifyItemRangeChanged(0, pickerAdapter.itemCount)
        }
        picker.unselectAllButton.setOnClickListener {
            selectedPackages.removeAll(visibleApps.map { it.packageName }.toSet())
            pickerAdapter.notifyItemRangeChanged(0, pickerAdapter.itemCount)
        }
        refresh()

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_choose_excluded_apps)
            .setMessage(R.string.msg_excluded_apps_picker)
            .setView(picker.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            var saveInProgress = false
            saveButton.setOnClickListener {
                if (saveInProgress) return@setOnClickListener
                saveInProgress = true
                saveButton.isEnabled = false
                val chosenPackages = apps.asSequence()
                    .map { it.packageName }
                    .filter { it in selectedPackages }
                    .toList()
                if (ModeData.replaceExcludedPackages(mode.id, chosenPackages)) {
                    dialog.dismiss()
                    onSaved()
                } else {
                    saveInProgress = false
                    saveButton.isEnabled = true
                }
            }
        }
        dialog.show()
        val density = fragment.resources.displayMetrics.density
        val screenHeight = fragment.resources.displayMetrics.heightPixels
        picker.recyclerView.updateLayoutParams {
            height = (screenHeight * 0.36f).toInt()
                .coerceIn((200 * density).toInt(), (300 * density).toInt())
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isVisible = true
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isVisible = true
    }
}
