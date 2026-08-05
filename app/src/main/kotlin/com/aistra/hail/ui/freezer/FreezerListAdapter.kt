package com.aistra.hail.ui.freezer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.FreezerList
import com.aistra.hail.databinding.ItemFreezerListBinding

data class FreezerListStatus(
    val list: FreezerList,
    val frozenCount: Int,
    val unfrozenCount: Int
)

class FreezerListAdapter(
    private val onClick: (FreezerList) -> Unit,
    private val onLongClick: (FreezerList) -> Unit
) : ListAdapter<FreezerListStatus, FreezerListAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemFreezerListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemFreezerListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(status: FreezerListStatus) = binding.run {
            val list = status.list
            listName.text = list.name
            val total = status.frozenCount + status.unfrozenCount
            listCount.text = root.context.getString(
                R.string.list_app_state_counts, status.frozenCount, total
            )
            val frozenPercent = if (total == 0) 0 else (status.frozenCount * 100f / total).toInt()
            frozenProgress.setProgressCompat(frozenPercent, false)
            frozenPercentageText.text = root.context.getString(
                R.string.percentage_value, frozenPercent
            )
            frozenProgress.contentDescription = root.context.getString(
                R.string.frozen_percentage, frozenPercent
            )
            root.setOnClickListener { onClick(list) }
            root.setOnLongClickListener {
                onLongClick(list)
                true
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<FreezerListStatus>() {
        override fun areItemsTheSame(oldItem: FreezerListStatus, newItem: FreezerListStatus) =
            oldItem.list.id == newItem.list.id

        override fun areContentsTheSame(oldItem: FreezerListStatus, newItem: FreezerListStatus) =
            oldItem == newItem
    }
}
