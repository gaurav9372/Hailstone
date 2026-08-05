package com.aistra.hail.ui.freezer

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupWindow
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
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
import com.aistra.hail.databinding.PopupFreezerAppFilterBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.FuzzySearch
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HShizuku
import com.aistra.hail.utils.HUI
import com.aistra.hail.utils.NineKeySearch
import com.aistra.hail.utils.PinyinSearch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FreezerListFragment : MainFragment(), MenuProvider {
    private var _binding: FragmentFreezerListBinding? = null
    private val binding get() = _binding!!
    private val listId get() = requireArguments().getString("listId").orEmpty()
    private val isAllFrozenList get() = listId == FreezerData.ID_ALL_FROZEN
    private val isNoCustomList get() = listId == FreezerData.ID_NO_CUSTOM_LIST
    private val isFreezerHome get() = isAllFrozenList
    private val freezerList: FreezerList? get() = FreezerData.findList(listId)
    private val selectedPackages = mutableSetOf<String>()
    private val appsAdapter = FreezerAppsAdapter(selectedPackages, ::onAppClick, ::onAppLongClick)
    private var selectionMode = false
    private var query = ""
    private var showUserApps = true
    private var showSystemApps = true
    private var showFrozenApps = true
    private var showUnfrozenApps = true
    private var syncingQuickFilters = false
    private var updateAppsJob: Job? = null
    private val contextualToolbar: MaterialToolbar
        get() = activity.findViewById(R.id.contextual_toolbar)
    private val pageToolbar: MaterialToolbar
        get() = activity.findViewById(R.id.toolbar)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        (requireActivity() as MenuHost).addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentFreezerListBinding.inflate(inflater, container, false)
        binding.recyclerView.apply {
            adapter = appsAdapter
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
        }
        setupQuickFilters()
        applyAppLayout(HailData.freezerListView)
        if (findNavController().currentDestination?.id == R.id.freezerListFragment) {
            applyNonEdgeToEdgeBottomInset()
        }
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        updateApps()
        return binding.root
    }

    private fun setupQuickFilters() {
        syncQuickFilterChips()
        binding.frozenChip.setOnCheckedChangeListener { _, checked ->
            if (syncingQuickFilters) return@setOnCheckedChangeListener
            showFrozenApps = checked
            updateApps()
        }
        binding.unfrozenChip.setOnCheckedChangeListener { _, checked ->
            if (syncingQuickFilters) return@setOnCheckedChangeListener
            showUnfrozenApps = checked
            updateApps()
        }
        binding.systemChip.setOnCheckedChangeListener { _, checked ->
            if (syncingQuickFilters) return@setOnCheckedChangeListener
            showSystemApps = checked
            updateApps()
        }
        binding.userChip.setOnCheckedChangeListener { _, checked ->
            if (syncingQuickFilters) return@setOnCheckedChangeListener
            showUserApps = checked
            updateApps()
        }
    }

    private fun syncQuickFilterChips() {
        syncingQuickFilters = true
        binding.frozenChip.isChecked = showFrozenApps
        binding.unfrozenChip.isChecked = showUnfrozenApps
        binding.systemChip.isChecked = showSystemApps
        binding.userChip.isChecked = showUserApps
        syncingQuickFilters = false
    }

    private fun applyAppLayout(listView: Boolean) {
        appsAdapter.listView = listView
        binding.recyclerView.layoutManager = if (listView) {
            LinearLayoutManager(activity)
        } else {
            GridLayoutManager(
                activity,
                resources.getInteger(
                    if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
                )
            )
        }
    }

    private fun setAppLayout(listView: Boolean) {
        HailData.freezerListView = listView
        applyAppLayout(listView)
        pageToolbar.menu.findItem(R.id.action_view_grid)?.isChecked = !listView
        pageToolbar.menu.findItem(R.id.action_view_list)?.isChecked = listView
    }

    private fun applyNonEdgeToEdgeBottomInset() {
        if (isLandscape) return
        val fabBaseMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
        val listBasePadding = binding.recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            activity.fab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = fabBaseMargin + navigationBarBottom
            }
            activity.addFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = fabBaseMargin + navigationBarBottom
            }
            activity.freezeFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = fabBaseMargin + navigationBarBottom
            }
            binding.recyclerView.updatePadding(bottom = listBasePadding + navigationBarBottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onResume() {
        super.onResume()
        activity.addFab.setOnClickListener {
            if (isFreezerHome) {
                showAppPicker(FreezerData.noCustomList, addOnly = true)
            } else {
                showAppPicker()
            }
        }
        activity.freezeFab.setOnClickListener { setListFrozen(true) }
        updateApps()
    }

    private fun updateApps() {
        val currentQuery = query
        val includeUserApps = showUserApps
        val includeSystemApps = showSystemApps
        val includeFrozenApps = showFrozenApps
        val includeUnfrozenApps = showUnfrozenApps
        updateAppsJob?.cancel()
        updateAppsJob = lifecycleScope.launch {
            val (packages, apps) = withContext(Dispatchers.IO) {
                val packages = FreezerData.packageSnapshot(listId)
                packages to packages.map { AppInfo(it) }.filter {
                    val isSystem = it.applicationInfo?.let { app ->
                        app.flags and ApplicationInfo.FLAG_SYSTEM == ApplicationInfo.FLAG_SYSTEM
                    } ?: false
                    val matchesType = (includeUserApps && !isSystem) || (includeSystemApps && isSystem)
                    val isFrozen = it.state == AppInfo.State.FROZEN
                    val matchesState = (includeFrozenApps && isFrozen) ||
                        (includeUnfrozenApps && !isFrozen)
                    val matchesQuery = currentQuery.isEmpty() ||
                        (HailData.nineKeySearch && NineKeySearch.search(
                            currentQuery, it.packageName, it.name.toString()
                        )) ||
                        FuzzySearch.search(it.packageName, currentQuery) ||
                        FuzzySearch.search(it.name.toString(), currentQuery) ||
                        PinyinSearch.searchPinyinAll(it.name.toString(), currentQuery)
                    matchesType && matchesState && matchesQuery
                }.sortedBy { it.name.toString().lowercase() }
            }
            if (_binding == null) return@launch
            appsAdapter.submitList(apps) {
                appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount)
            }
            binding.empty.isVisible = apps.isEmpty()
            updateFabVisibility(packages.isEmpty())
        }
    }

    private fun updateFabVisibility(isEmpty: Boolean) {
        if (isEmpty) activity.freezeFab.hide() else activity.freezeFab.show()
    }

    private fun setListFrozen(frozen: Boolean, removeAfter: Boolean = false) {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                FreezerData.packageSnapshot(listId).map { AppInfo(it) }
            }
            setAppsFrozen(apps, frozen, removeAfter)
        }
    }

    private fun setAppsFrozen(
        apps: List<AppInfo>,
        frozen: Boolean,
        removeAfter: Boolean = false
    ) {
        if (apps.isEmpty()) return
        val shouldRemove = removeAfter && !isAllFrozenList
        if (!frozen && HailData.workingMode.endsWith(HailData.STOP)) {
            HUI.showToast(R.string.msg_stop_mode_unfreeze)
            return
        }
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
        lifecycleScope.launch {
            val appsToChange = withContext(Dispatchers.IO) {
                apps.filter { AppManager.isAppFrozen(it.packageName) != frozen }
            }
            if (appsToChange.isEmpty()) {
                if (shouldRemove) removeUnfrozenApps(apps)
                else HUI.showToast(if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, "0")
                return@launch
            }
            when (val result = withContext(Dispatchers.IO) {
                AppManager.setListFrozen(frozen, *appsToChange.toTypedArray())
            }) {
                null -> HUI.showToast(R.string.permission_denied)
                else -> {
                    if (shouldRemove) removeUnfrozenApps(apps) else updateApps()
                    HUI.showToast(if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, result)
                }
            }
        }
    }

    private fun removeUnfrozenApps(apps: List<AppInfo>) {
        lifecycleScope.launch {
            val targetPackages = withContext(Dispatchers.IO) {
                apps.filterNot { AppManager.isAppFrozen(it.packageName) }
                    .mapTo(mutableSetOf()) { it.packageName }
            }
            freezerList?.packages?.removeAll { it in targetPackages }
            FreezerData.save()
            updateApps()
        }
    }

    private fun showAppActions(info: AppInfo) {
        val isFrozen = AppManager.isAppFrozen(info.packageName)
        val actions = if (isAllFrozenList) {
            arrayOf(
                getString(R.string.action_freeze),
                getString(R.string.action_unfreeze),
                getString(R.string.action_multi_select)
            )
        } else {
            arrayOf(
                getString(R.string.action_freeze),
                getString(R.string.action_unfreeze),
                getString(
                    if (isFrozen) R.string.action_unfreeze_remove_list_app
                    else R.string.action_remove_list_app
                ),
                getString(R.string.action_multi_select)
            )
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(info.name)
            .setItems(actions) { _, which ->
                if (isAllFrozenList) when (which) {
                    0 -> setAppsFrozen(listOf(info), true)
                    1 -> setAppsFrozen(listOf(info), false)
                    2 -> startMultiSelect(info)
                } else when (which) {
                    0 -> setAppsFrozen(listOf(info), true)
                    1 -> setAppsFrozen(listOf(info), false)
                    2 -> if (isFrozen) {
                        setAppsFrozen(listOf(info), false, removeAfter = true)
                    } else {
                        removeUnfrozenApps(listOf(info))
                    }
                    3 -> startMultiSelect(info)
                }
            }
            .setNeutralButton(R.string.action_details) { _, _ ->
                HUI.startActivity(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    HPackages.packageUri(info.packageName)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onAppClick(info: AppInfo) {
        if (selectionMode) toggleSelection(info)
    }

    private fun onAppLongClick(info: AppInfo) {
        if (selectionMode) toggleSelection(info) else showAppActions(info)
    }

    private fun startMultiSelect(info: AppInfo) {
        selectedPackages.add(info.packageName)
        if (!selectionMode) showSelectionBar()
        refreshSelection()
    }

    private fun toggleSelection(info: AppInfo) {
        if (!selectedPackages.add(info.packageName)) selectedPackages.remove(info.packageName)
        if (selectedPackages.isEmpty()) closeSelectionBar() else refreshSelection()
    }

    private fun refreshSelection() {
        contextualToolbar.title = getString(R.string.msg_selected, selectedPackages.size.toString())
        appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount)
    }

    private fun showSelectionBar() {
        selectionMode = true
        pageToolbar.isVisible = false
        contextualToolbar.apply {
            menu.clear()
            inflateMenu(R.menu.menu_freezer_selection)
            menu.findItem(R.id.action_selection_unfreeze_remove).isVisible = !isAllFrozenList
            setNavigationIcon(R.drawable.ic_outline_close)
            setNavigationOnClickListener { closeSelectionBar() }
            setOnMenuItemClickListener { item ->
            val selectedApps = selectedPackages.map { AppInfo(it) }
            when (item.itemId) {
                R.id.action_selection_freeze -> setAppsFrozen(selectedApps, true)
                R.id.action_selection_unfreeze -> setAppsFrozen(selectedApps, false)
                R.id.action_selection_unfreeze_remove ->
                    setAppsFrozen(selectedApps, false, removeAfter = true)
                else -> return@setOnMenuItemClickListener false
            }
                closeSelectionBar()
                true
            }
            isVisible = true
        }
    }

    private fun closeSelectionBar() {
        if (!selectionMode) return
        selectionMode = false
        contextualToolbar.apply {
            isVisible = false
            menu.clear()
            setOnMenuItemClickListener(null)
        }
        pageToolbar.isVisible = true
        selectedPackages.clear()
        appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount)
    }

    private fun showAppPicker(targetList: FreezerList? = null, addOnly: Boolean = false) {
        if (isAllFrozenList && targetList == null) return
        val list = targetList ?: freezerList ?: return
        val apps = HPackages.getInstalledApplications()
            .filter { it.flags and ApplicationInfo.FLAG_INSTALLED == ApplicationInfo.FLAG_INSTALLED }
            .sortedBy { it.loadLabel(activity.packageManager).toString().lowercase() }
        val selectedPackages = if (addOnly) mutableSetOf() else list.packages.toMutableSet()
        val picker = DialogFreezerAppPickerBinding.inflate(layoutInflater)
        var query = ""
        var showUserApps = HailData.filterUserApps
        var showSystemApps = HailData.filterSystemApps
        var showFrozenApps = true
        var showUnfrozenApps = true
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
            val filter = PopupFreezerAppFilterBinding.inflate(layoutInflater)
            filter.userApps.isChecked = showUserApps
            filter.systemApps.isChecked = showSystemApps
            filter.frozenApps.isChecked = showFrozenApps
            filter.unfrozenApps.isChecked = showUnfrozenApps
            filter.appTypeGroup.setOnCheckedChangeListener { _, checkedId ->
                showUserApps = checkedId == R.id.user_apps
                showSystemApps = checkedId == R.id.system_apps
                refreshPicker()
            }
            filter.frozenApps.setOnCheckedChangeListener { _, checked ->
                showFrozenApps = checked
                refreshPicker()
            }
            filter.unfrozenApps.setOnCheckedChangeListener { _, checked ->
                showUnfrozenApps = checked
                refreshPicker()
            }
            PopupWindow(
                filter.root,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = true
                elevation = 8f * resources.displayMetrics.density
                showAsDropDown(picker.filterButton, 0, 0, Gravity.END)
            }
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_manage_apps)
            .setView(picker.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosenPackages = apps.map { it.packageName }.filter { it in selectedPackages }
                if (addOnly) {
                    val customPackages = FreezerData.lists
                        .filter { it.id != FreezerData.ID_NO_CUSTOM_LIST }
                        .flatMapTo(mutableSetOf()) { it.packages }
                    list.packages.addAll(
                        chosenPackages.filterNot { it in list.packages || it in customPackages }
                    )
                    FreezerData.save()
                    updateApps()
                } else {
                    FreezerData.replaceListPackages(list.id, chosenPackages)
                    updateApps()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditDialog() {
        if (isAllFrozenList || isNoCustomList) return
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
        if (isAllFrozenList || isNoCustomList) return
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
        menu.findItem(R.id.action_view_grid).isChecked = !HailData.freezerListView
        menu.findItem(R.id.action_view_list).isChecked = HailData.freezerListView
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        if (HailData.nineKeySearch) {
            searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text).inputType =
                InputType.TYPE_CLASS_PHONE
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                query = newText
                updateApps()
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean = true
        })
    }

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.action_view_grid).isChecked = !HailData.freezerListView
        menu.findItem(R.id.action_view_list).isChecked = HailData.freezerListView
        if (isAllFrozenList) {
            menu.findItem(R.id.action_edit_list).isVisible = false
            menu.findItem(R.id.action_unfreeze_remove_list).isVisible = false
            menu.findItem(R.id.action_delete_list).isVisible = false
        } else if (isNoCustomList) {
            menu.findItem(R.id.action_edit_list).isVisible = false
            menu.findItem(R.id.action_delete_list).isVisible = false
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_view_grid -> {
            setAppLayout(false)
            true
        }
        R.id.action_view_list -> {
            setAppLayout(true)
            true
        }
        R.id.action_edit_list -> {
            showEditDialog()
            true
        }
        R.id.action_unfreeze_list -> {
            setListFrozen(false)
            true
        }
        R.id.action_unfreeze_remove_list -> {
            setListFrozen(false, removeAfter = true)
            true
        }
        R.id.action_delete_list -> {
            showDeleteDialog()
            true
        }
        else -> false
    }

    override fun onDestroyView() {
        updateAppsJob?.cancel()
        closeSelectionBar()
        if (!isLandscape) {
            activity.fab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
            }
            activity.addFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
            }
            activity.freezeFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
            }
        }
        super.onDestroyView()
        _binding = null
    }
}
