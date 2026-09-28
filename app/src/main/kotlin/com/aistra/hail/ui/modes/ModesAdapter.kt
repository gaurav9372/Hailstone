package com.aistra.hail.ui.modes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppMode
import com.aistra.hail.databinding.ItemModeBinding

data class ModeItem(
    val mode: AppMode,
    val enabled: Boolean,
    val affectedAppCount: Int
)

class ModesAdapter(
    private val onClick: (AppMode) -> Unit,
    private val onLongClick: (AppMode) -> Unit,
    private val onToggle: (AppMode, Boolean) -> Unit
) : ListAdapter<ModeItem, ModesAdapter.ViewHolder>(Diff) {
    var controlsEnabled: Boolean = true
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemModeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemModeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ModeItem) = binding.run {
            modeName.text = item.mode.name
            modeSummary.text = root.resources.getQuantityString(
                R.plurals.mode_excluded_summary,
                item.mode.excludedPackages.size,
                item.mode.excludedPackages.size,
                item.affectedAppCount
            )
            modeSwitch.setOnCheckedChangeListener(null)
            modeSwitch.isChecked = item.enabled
            modeSwitch.isEnabled = controlsEnabled
            modeSwitch.contentDescription = root.context.getString(
                if (item.enabled) R.string.action_disable_mode else R.string.action_enable_mode,
                item.mode.name
            )
            modeSwitch.setOnCheckedChangeListener { _, checked -> onToggle(item.mode, checked) }
            root.setOnClickListener { if (controlsEnabled) onClick(item.mode) }
            root.setOnLongClickListener {
                if (controlsEnabled) onLongClick(item.mode)
                controlsEnabled
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ModeItem>() {
        override fun areItemsTheSame(oldItem: ModeItem, newItem: ModeItem) =
            oldItem.mode.id == newItem.mode.id

        override fun areContentsTheSame(oldItem: ModeItem, newItem: ModeItem) = oldItem == newItem
    }
}
