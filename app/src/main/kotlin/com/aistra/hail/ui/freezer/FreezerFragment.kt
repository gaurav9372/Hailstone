package com.aistra.hail.ui.freezer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.aistra.hail.R
import com.aistra.hail.app.FreezerData
import com.aistra.hail.app.FreezerList
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.databinding.FragmentFreezerBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.main.MainFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FreezerFragment : MainFragment() {
    private var _binding: FragmentFreezerBinding? = null
    private val binding get() = _binding!!
    private lateinit var listAdapter: FreezerListAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFreezerBinding.inflate(inflater, container, false)
        listAdapter = FreezerListAdapter(
            onClick = { list ->
                findNavController().navigate(
                    R.id.action_nav_freezer_to_freezerListFragment,
                    bundleOf("listId" to list.id, "listName" to list.name)
                )
            },
            onLongClick = ::showListActions
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = listAdapter
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
        }
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        activity.fab.apply {
            setIconResource(R.drawable.ic_outline_add)
            text = getString(R.string.action_new_list)
            setOnClickListener { showNameDialog() }
            setOnLongClickListener(null)
        }
        updateLists()
    }

    private fun updateLists() {
        val lists = FreezerData.lists.map { it.copy(packages = it.packages.toMutableList()) }
        listAdapter.submitList(lists)
        binding.empty.isVisible = lists.isEmpty()
    }

    private fun showNameDialog(existing: FreezerList? = null) {
        val input = DialogInputBinding.inflate(layoutInflater)
        input.inputLayout.setHint(R.string.list_name)
        input.editText.setText(existing?.name.orEmpty())
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.action_new_list else R.string.action_rename)
            .setView(input.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.editText.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                if (existing == null) FreezerData.createList(name)
                else FreezerData.findList(existing.id)?.let {
                    it.name = name
                    FreezerData.save()
                }
                updateLists()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showListActions(list: FreezerList) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(list.name)
            .setItems(arrayOf(getString(R.string.action_rename), getString(R.string.action_delete))) { _, which ->
                if (which == 0) showNameDialog(list)
                else MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.action_delete_list)
                    .setMessage(getString(R.string.msg_delete_list, list.name))
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        FreezerData.deleteList(list.id)
                        updateLists()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
