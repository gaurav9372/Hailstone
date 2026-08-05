package com.aistra.hail.ui.freezer

import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ItemFreezerAppPickerBinding
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import kotlinx.coroutines.Job

class FreezerAppPickerAdapter(
    private val selectedPackages: MutableSet<String>,
    private val onSelectionChanged: () -> Unit
) : ListAdapter<ApplicationInfo, FreezerAppPickerAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemFreezerAppPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemFreezerAppPickerBinding) : RecyclerView.ViewHolder(binding.root) {
        private var iconJob: Job? = null

        fun bind(info: ApplicationInfo) = binding.run {
            iconJob?.cancel()
            appIcon.setImageDrawable(root.context.packageManager.defaultActivityIcon)
            appIcon.colorFilter = null
            val packageName = info.packageName
            appName.text = info.loadLabel(root.context.packageManager)
            appPackage.text = packageName
            appCheck.setOnCheckedChangeListener(null)
            appCheck.isChecked = packageName in selectedPackages
            iconJob = AppIconCache.loadIconBitmapAsync(
                root.context,
                info,
                myUserId,
                appIcon,
                HailData.grayscaleIcon && AppManager.isAppFrozen(packageName)
            )
            appCheck.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedPackages.add(packageName) else selectedPackages.remove(packageName)
                onSelectionChanged()
            }
            root.setOnClickListener { appCheck.toggle() }
        }

        fun recycle() {
            iconJob?.cancel()
            iconJob = null
        }
    }

    private object Diff : DiffUtil.ItemCallback<ApplicationInfo>() {
        override fun areItemsTheSame(oldItem: ApplicationInfo, newItem: ApplicationInfo) =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: ApplicationInfo, newItem: ApplicationInfo) =
            oldItem.packageName == newItem.packageName
    }
}
