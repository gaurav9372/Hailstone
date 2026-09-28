package com.aistra.hail.ui.modes

import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppMode
import com.aistra.hail.app.HailData
import com.aistra.hail.app.ModeData
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.databinding.FragmentModeDetailBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.freezer.FreezerAppsAdapter
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HUI
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModeDetailFragment : MainFragment(), MenuProvider {
    private var _binding: FragmentModeDetailBinding? = null
    private val binding get() = _binding!!
    private val modeId get() = requireArguments().getString("modeId").orEmpty()
    private val mode: AppMode? get() = ModeData.findMode(modeId)
    private var query = ""
    private var updateJob: Job? = null
    private val appsAdapter = FreezerAppsAdapter(emptySet(), ::showAppDetails, ::showAppDetails)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        (requireActivity() as MenuHost).addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentModeDetailBinding.inflate(inflater, container, false)
        binding.recyclerView.apply {
            adapter = appsAdapter
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
        }
        applyAppLayout(HailData.freezerListView)
        applyBottomInset()
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        updateApps()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        activity.addFab.apply {
            contentDescription = getString(R.string.action_manage_excluded_apps)
            setImageResource(R.drawable.ic_outline_add)
            setOnClickListener { showAppPicker() }
        }
        updateApps()
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
        activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.apply {
                findItem(R.id.action_view_grid)?.isChecked = !listView
                findItem(R.id.action_view_list)?.isChecked = listView
            }
    }

    private fun updateApps() {
        val currentMode = mode
        if (currentMode == null) {
            findNavController().navigateUp()
            return
        }
        val currentQuery = query
        updateJob?.cancel()
        updateJob = lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                currentMode.excludedPackages.map { AppInfo(it) }
                    .filter {
                        currentQuery.isEmpty() ||
                            it.packageName.contains(currentQuery, ignoreCase = true) ||
                            it.name.toString().contains(currentQuery, ignoreCase = true)
                    }
                    .sortedBy { it.name.toString().lowercase() }
            }
            if (_binding == null) return@launch
            appsAdapter.submitList(apps)
            binding.empty.isVisible = apps.isEmpty()
        }
    }

    private fun showAppPicker() {
        val currentMode = mode ?: return
        if (ModeData.isActive(modeId)) {
            HUI.showToast(R.string.msg_disable_mode_to_edit)
            return
        }
        ModeAppPicker.show(this, currentMode, ::updateApps)
    }

    private fun showAppDetails(info: AppInfo) {
        HUI.startActivity(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            HPackages.packageUri(info.packageName)
        )
    }

    private fun showRenameDialog() {
        val currentMode = mode ?: return
        val input = DialogInputBinding.inflate(layoutInflater)
        input.inputLayout.setHint(R.string.mode_name)
        input.editText.setText(currentMode.name)
        input.editText.setSelection(currentMode.name.length)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_rename)
            .setView(input.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                if (ModeData.renameMode(modeId, name)) {
                    requireArguments().putString("modeName", name)
                    activity.supportActionBar?.title = name
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeleteDialog() {
        val currentMode = mode ?: return
        if (ModeData.isActive(modeId)) {
            HUI.showToast(R.string.msg_disable_mode_to_delete)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_delete_mode)
            .setMessage(getString(R.string.msg_delete_mode, currentMode.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                if (ModeData.deleteMode(modeId)) findNavController().navigateUp()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun applyBottomInset() {
        if (isLandscape) return
        val fabBaseMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
        val listBasePadding = binding.recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            activity.addFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = fabBaseMargin + bottom
            }
            binding.recyclerView.updatePadding(bottom = listBasePadding + bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_mode_detail, menu)
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

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_view_grid -> {
            setAppLayout(false)
            true
        }
        R.id.action_view_list -> {
            setAppLayout(true)
            true
        }
        R.id.action_edit_mode -> {
            showRenameDialog()
            true
        }
        R.id.action_delete_mode -> {
            showDeleteDialog()
            true
        }
        else -> false
    }

    override fun onDestroyView() {
        updateJob?.cancel()
        if (!isLandscape) {
            activity.addFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
            }
        }
        super.onDestroyView()
        _binding = null
    }
}
