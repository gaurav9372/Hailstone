package com.aistra.hail.ui.freezer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ItemHomeListBinding
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import com.google.android.material.color.MaterialColors

class FreezerAppsAdapter(
    private val selectedPackages: Set<String>,
    private val onItemClick: (AppInfo) -> Unit,
    private val onItemLongClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, FreezerAppsAdapter.ViewHolder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemHomeListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemHomeListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(info: AppInfo) {
            binding.run {
                val frozen = info.state == AppInfo.State.FROZEN
                appName.text = buildString {
                    if (!HailData.grayscaleIcon && frozen) append("\u2744\uFE0F")
                    append(info.name)
                }
                appName.isEnabled = !HailData.grayscaleIcon || !frozen
                if (info.packageName in selectedPackages) {
                    appName.setTextColor(
                        MaterialColors.getColor(appName, androidx.appcompat.R.attr.colorPrimary)
                    )
                } else {
                    appName.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                }
                info.applicationInfo?.let {
                    AppIconCache.loadIconBitmapAsync(
                        root.context,
                        it,
                        myUserId,
                        appIcon,
                        HailData.grayscaleIcon && frozen
                    )
                } ?: appIcon.apply {
                    setImageDrawable(root.context.packageManager.defaultActivityIcon)
                    colorFilter = null
                }
                root.setOnLongClickListener {
                    onItemLongClick(info)
                    true
                }
                root.setOnClickListener { onItemClick(info) }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) = oldItem.packageName == newItem.packageName
        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem.name == newItem.name && oldItem.state == newItem.state
    }
}
