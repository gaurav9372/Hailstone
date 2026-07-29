package com.aistra.hail.ui.freezer

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.FreezerData
import com.aistra.hail.app.FreezerList
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogFreezerAppPickerBinding
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.databinding.FragmentFreezerListBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HShizuku
import com.aistra.hail.utils.HUI
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FreezerListFragment : MainFragment(), MenuProvider {
    private var _binding: FragmentFreezerListBinding? = null
    private val binding get() = _binding!!
    private val listId get() = requireArguments().getString("listId").orEmpty()
    private val freezerList: FreezerList? get() = FreezerData.findList(listId)
    private val appsAdapter = FreezerAppsAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        (requireActivity() as MenuHost).addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentFreezerListBinding.inflate(inflater, container, false)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = appsAdapter
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
        }
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        updateApps()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        activity.fab.apply {
            setIconResource(R.drawable.ic_round_frozen)
            text = getString(R.string.action_freeze)
            setOnClickListener { freezeList() }
            setOnLongClickListener(null)
        }
        updateFabVisibility()
    }

    private fun updateApps() {
        val apps = freezerList?.packages.orEmpty().map { AppInfo(it) }.sortedBy { it.name.toString().lowercase() }
        appsAdapter.submitList(apps)
        binding.empty.isVisible = apps.isEmpty()
        updateFabVisibility()
    }

    private fun updateFabVisibility() {
        if (freezerList?.packages.isNullOrEmpty()) activity.fab.hide() else activity.fab.show()
    }

    private fun freezeList() {
        val apps = freezerList?.packages.orEmpty().map { AppInfo(it) }
        if (apps.isEmpty()) return
        if (HailData.workingMode == HailData.MODE_DEFAULT) {
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.msg_guide)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        } else if (HailData.workingMode == HailData.MODE_SHIZUKU_HIDE) {
            runCatching { HShizuku.isRoot }.onSuccess { isRoot ->
                if (!isRoot) {
                    MaterialAlertDialogBuilder(activity)
                        .setMessage(R.string.shizuku_hide_adb)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return
                }
            }
        }
        val unfrozenApps = apps.filterNot { AppManager.isAppFrozen(it.packageName) }
        when (val result = AppManager.setListFrozen(true, *unfrozenApps.toTypedArray())) {
            null -> HUI.showToast(R.string.permission_denied)
            else -> {
                updateApps()
                HUI.showToast(R.string.msg_freeze, result)
            }
        }
    }

    private fun showAppPicker() {
        val list = freezerList ?: return
        val apps = HPackages.getInstalledApplications()
            .filter { it.flags and ApplicationInfo.FLAG_INSTALLED == ApplicationInfo.FLAG_INSTALLED }
            .sortedBy { it.loadLabel(activity.packageManager).toString().lowercase() }
        val selectedPackages = list.packages.toMutableSet()
        val picker = DialogFreezerAppPickerBinding.inflate(layoutInflater)
        var query = ""
        var showUserApps = HailData.filterUserApps
        var showSystemApps = HailData.filterSystemApps
        var showFrozenApps = HailData.filterFrozenApps
        var showUnfrozenApps = HailData.filterUnfrozenApps
        if (!showUserApps && !showSystemApps) showUserApps = true
        lateinit var pickerAdapter: FreezerAppPickerAdapter

        fun refreshPicker() {
            val visibleApps = apps.filter { app ->
                val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM == ApplicationInfo.FLAG_SYSTEM
                val isFrozen = AppManager.isAppFrozen(app.packageName)
                val matchesType = (showUserApps && !isSystem) || (showSystemApps && isSystem)
                val matchesState = (showFrozenApps && isFrozen) || (showUnfrozenApps && !isFrozen)
                val matchesQuery = query.isEmpty() ||
                    app.packageName.contains(query, ignoreCase = true) ||
                    app.loadLabel(activity.packageManager).contains(query, ignoreCase = true)
                matchesType && matchesState && matchesQuery
            }.sortedWith(
                compareBy<ApplicationInfo> { if (it.packageName in selectedPackages) 0 else 1 }
                    .thenBy { it.loadLabel(activity.packageManager).toString().lowercase() }
            )
            pickerAdapter.submitList(visibleApps)
        }

        pickerAdapter = FreezerAppPickerAdapter(selectedPackages, ::refreshPicker)
        picker.recyclerView.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = pickerAdapter
        }
        refreshPicker()
        picker.search.doAfterTextChanged { editable ->
            query = editable?.toString()?.trim().orEmpty()
            refreshPicker()
        }
        picker.filterButton.setOnClickListener {
            PopupMenu(activity, picker.filterButton).apply {
                menuInflater.inflate(R.menu.menu_freezer_app_filter, menu)

                fun updateChecks() {
                    menu.findItem(R.id.picker_filter_user_apps).isChecked = showUserApps
                    menu.findItem(R.id.picker_filter_system_apps).isChecked = showSystemApps
                    menu.findItem(R.id.picker_filter_frozen_apps).isChecked = showFrozenApps
                    menu.findItem(R.id.picker_filter_unfrozen_apps).isChecked = showUnfrozenApps
                }

                updateChecks()
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.picker_filter_user_apps -> {
                            showUserApps = true
                            showSystemApps = false
                        }
                        R.id.picker_filter_system_apps -> {
                            showUserApps = false
                            showSystemApps = true
                        }
                        R.id.picker_filter_frozen_apps -> showFrozenApps = !showFrozenApps
                        R.id.picker_filter_unfrozen_apps -> showUnfrozenApps = !showUnfrozenApps
                        else -> return@setOnMenuItemClickListener false
                    }
                    updateChecks()
                    refreshPicker()
                    true
                }
                show()
            }
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_manage_apps)
            .setView(picker.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                list.packages.clear()
                list.packages.addAll(apps.map { it.packageName }.filter { it in selectedPackages })
                FreezerData.save()
                updateApps()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditDialog() {
        val list = freezerList ?: return
        val input = DialogInputBinding.inflate(layoutInflater)
        input.inputLayout.setHint(R.string.list_name)
        input.editText.setText(list.name)
        input.editText.setSelection(list.name.length)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_edit)
            .setView(input.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                list.name = name
                FreezerData.save()
                requireArguments().putString("listName", name)
                activity.supportActionBar?.title = name
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog() {
        val list = freezerList ?: return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_delete_list)
            .setMessage(getString(R.string.msg_delete_list, list.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                FreezerData.deleteList(list.id)
                findNavController().navigateUp()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_freezer_list, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_manage_apps -> {
            showAppPicker()
            true
        }
        R.id.action_edit_list -> {
            showEditDialog()
            true
        }
        R.id.action_delete_list -> {
            showDeleteDialog()
            true
        }
        else -> false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
