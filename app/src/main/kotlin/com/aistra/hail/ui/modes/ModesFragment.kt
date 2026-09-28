package com.aistra.hail.ui.modes

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.aistra.hail.BuildConfig
import com.aistra.hail.R
import com.aistra.hail.app.AppMode
import com.aistra.hail.app.HailData
import com.aistra.hail.app.ModeController
import com.aistra.hail.app.ModeData
import com.aistra.hail.app.ModeProgress
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.databinding.DialogModeProgressBinding
import com.aistra.hail.databinding.FragmentModesBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HUI
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModesFragment : MainFragment() {
    private var _binding: FragmentModesBinding? = null
    private val binding get() = _binding!!
    private var operationRunning = false
    private var progressDialog: AlertDialog? = null
    private var progressBinding: DialogModeProgressBinding? = null
    private val modesAdapter = ModesAdapter(
        onClick = ::openModeDetail,
        onLongClick = ::showModeActions,
        onToggle = ::onModeToggle
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModesBinding.inflate(inflater, container, false)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = modesAdapter
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
        }
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        updateModes()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        activity.fab.apply {
            setIconResource(R.drawable.ic_outline_add)
            text = getString(R.string.action_new_mode)
            extend()
            isEnabled = !operationRunning
            setOnClickListener { showNameDialog() }
            setOnLongClickListener(null)
        }
        updateModes()
    }

    private fun updateModes() {
        val installedUserApps = installedUserApps()
        val items = ModeData.modesSnapshot().map { mode ->
            ModeItem(
                mode = mode.copy(
                    excludedPackages = mode.excludedPackages.toMutableList(),
                    frozenByMode = mode.frozenByMode.toMutableList()
                ),
                enabled = ModeData.isActive(mode.id),
                affectedAppCount = installedUserApps.count { it.packageName !in mode.excludedPackages }
            )
        }
        modesAdapter.submitList(items) {
            modesAdapter.notifyItemRangeChanged(0, modesAdapter.itemCount)
        }
        binding.empty.isVisible = items.isEmpty()
    }

    private fun installedUserApps(): List<ApplicationInfo> =
        HPackages.getModeEligibleApplications()
            .filter { it.packageName != BuildConfig.APPLICATION_ID }

    private fun showNameDialog(existing: AppMode? = null) {
        val input = DialogInputBinding.inflate(layoutInflater)
        input.inputLayout.setHint(R.string.mode_name)
        input.editText.setText(existing?.name.orEmpty())
        input.editText.setSelection(input.editText.text?.length ?: 0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.action_new_mode else R.string.action_rename)
            .setView(input.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                if (existing == null) {
                    val mode = ModeData.createMode(name)
                    updateModes()
                    showAppPicker(mode)
                } else {
                    ModeData.renameMode(existing.id, name)
                    updateModes()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showModeActions(mode: AppMode) {
        val active = ModeData.isActive(mode.id)
        val actions = if (active) {
            arrayOf(getString(R.string.action_rename))
        } else {
            arrayOf(getString(R.string.action_rename), getString(R.string.action_delete))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(mode.name)
            .setItems(actions) { _, which ->
                if (which == 0) showNameDialog(mode) else showDeleteDialog(mode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(mode: AppMode) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_delete_mode)
            .setMessage(getString(R.string.msg_delete_mode, mode.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                ModeData.deleteMode(mode.id)
                updateModes()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAppPicker(mode: AppMode) {
        if (ModeData.isActive(mode.id)) {
            HUI.showToast(R.string.msg_disable_mode_to_edit)
            return
        }
        ModeAppPicker.show(this, mode, ::updateModes)
    }

    private fun openModeDetail(mode: AppMode) {
        findNavController().navigate(
            R.id.action_nav_modes_to_modeDetailFragment,
            bundleOf("modeId" to mode.id, "modeName" to mode.name)
        )
    }

    private fun onModeToggle(mode: AppMode, enabled: Boolean) {
        if (operationRunning) return
        if (enabled) {
            if (HailData.workingMode == HailData.MODE_DEFAULT) {
                MaterialAlertDialogBuilder(activity)
                    .setMessage(R.string.msg_guide)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                updateModes()
                return
            }
            val affectedCount = installedUserApps().count { it.packageName !in mode.excludedPackages }
            MaterialAlertDialogBuilder(activity)
                .setTitle(getString(R.string.action_enable_mode, mode.name))
                .setMessage(getString(R.string.msg_enable_mode, affectedCount))
                .setPositiveButton(R.string.action_enable) { _, _ -> setModeEnabled(mode, true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> updateModes() }
                .setOnCancelListener { updateModes() }
                .show()
        } else {
            setModeEnabled(mode, false)
        }
    }

    private fun setModeEnabled(mode: AppMode, enabled: Boolean) {
        operationRunning = true
        modesAdapter.controlsEnabled = false
        activity.fab.isEnabled = false
        showModeProgress(mode, enabled)
        lifecycleScope.launch {
            try {
                val result = if (enabled) {
                    ModeController.enable(mode.id, ::updateModeProgress)
                } else {
                    ModeController.disable(mode.id, ::updateModeProgress)
                }
                when {
                    result.stoppedAppsNeedLaunch -> HUI.showToast(R.string.msg_stop_mode_disable)
                    result.success && result.failedCount > 0 -> HUI.showToast(
                        getString(
                            R.string.msg_mode_partially_enabled,
                            result.changedCount,
                            result.failedCount
                        )
                    )
                    result.success -> HUI.showToast(
                        if (enabled) R.string.msg_mode_enabled else R.string.msg_mode_disabled,
                        mode.name
                    )
                    else -> HUI.showToast(
                        R.string.msg_mode_operation_failed,
                        result.failedCount.toString()
                    )
                }
            } finally {
                operationRunning = false
                dismissModeProgress()
                if (_binding != null) {
                    modesAdapter.controlsEnabled = true
                    activity.fab.isEnabled = true
                    updateModes()
                }
            }
        }
    }

    private fun showModeProgress(mode: AppMode, enabling: Boolean) {
        dismissModeProgress()
        val progress = DialogModeProgressBinding.inflate(layoutInflater)
        progress.progressTitle.text = getString(
            if (enabling) R.string.msg_enabling_mode else R.string.msg_disabling_mode,
            mode.name
        )
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(progress.root)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        progressBinding = progress
        progressDialog = dialog
        dialog.show()
    }

    private suspend fun updateModeProgress(progress: ModeProgress) = withContext(Dispatchers.Main.immediate) {
        val progressView = progressBinding ?: return@withContext
        if (progressDialog?.isShowing != true || _binding == null) return@withContext

        val percentage = if (progress.total == 0) 100
        else (progress.completed * 100 / progress.total).coerceIn(0, 100)
        progressView.progressRing.isIndeterminate = false
        progressView.progressRing.setProgressCompat(percentage, progress.completed > 0)
        progressView.progressPercentage.text = getString(R.string.percentage_value, percentage)
        progressView.progressTitle.setText(
            if (progress.freezing) R.string.msg_freezing_apps else R.string.msg_unfreezing_apps
        )

        val appName = progress.packageName?.let { packageName ->
            HPackages.getApplicationInfoOrNull(packageName)
                ?.loadLabel(requireContext().packageManager)
                ?.toString()
                ?: packageName
        }
        progressView.progressDetail.text = if (appName == null) {
            getString(R.string.msg_mode_progress_count, progress.completed, progress.total)
        } else {
            getString(
                R.string.msg_mode_progress_app,
                appName,
                progress.completed,
                progress.total
            )
        }
        progressView.progressScope.text = if (progress.freezing) {
            getString(
                R.string.msg_mode_progress_freeze_scope,
                progress.total,
                progress.alreadyCompleted
            )
        } else {
            getString(R.string.msg_mode_progress_unfreeze_scope, progress.total)
        }
        progressView.progressRing.contentDescription = listOf(
            progressView.progressTitle.text,
            progressView.progressPercentage.text,
            progressView.progressDetail.text,
            progressView.progressScope.text
        ).joinToString(", ")
    }

    private fun dismissModeProgress() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBinding = null
    }

    override fun onDestroyView() {
        dismissModeProgress()
        super.onDestroyView()
        _binding = null
    }
}
