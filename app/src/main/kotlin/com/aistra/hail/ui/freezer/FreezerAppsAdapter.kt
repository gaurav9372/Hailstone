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

class FreezerAppsAdapter : ListAdapter<AppInfo, FreezerAppsAdapter.ViewHolder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemHomeListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemHomeListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(info: AppInfo) {
            binding.run {
                appName.text = info.name
                info.applicationInfo?.let {
                    AppIconCache.loadIconBitmapAsync(
                        root.context,
                        it,
                        myUserId,
                        appIcon,
                        HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                    )
                } ?: appIcon.setImageDrawable(root.context.packageManager.defaultActivityIcon)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) = oldItem.packageName == newItem.packageName
        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem.name == newItem.name && oldItem.state == newItem.state
    }
}
