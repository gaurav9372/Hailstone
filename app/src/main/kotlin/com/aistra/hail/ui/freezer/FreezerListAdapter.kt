package com.aistra.hail.ui.freezer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.FreezerList
import com.aistra.hail.databinding.ItemFreezerListBinding

class FreezerListAdapter(
    private val onClick: (FreezerList) -> Unit,
    private val onLongClick: (FreezerList) -> Unit
) : ListAdapter<FreezerList, FreezerListAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemFreezerListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemFreezerListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(list: FreezerList) = binding.run {
            listName.text = list.name
            listCount.text = root.resources.getQuantityString(
                R.plurals.freezer_app_count, list.packages.size, list.packages.size
            )
            root.setOnClickListener { onClick(list) }
            root.setOnLongClickListener {
                onLongClick(list)
                true
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<FreezerList>() {
        override fun areItemsTheSame(oldItem: FreezerList, newItem: FreezerList) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: FreezerList, newItem: FreezerList) = oldItem == newItem
    }
}
