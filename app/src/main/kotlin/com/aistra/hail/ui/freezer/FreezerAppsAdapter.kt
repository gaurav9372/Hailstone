package com.aistra.hail.ui.freezer

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job

class FreezerAppsAdapter(
    private val selectedPackages: Set<String>,
    private val onItemClick: (AppInfo) -> Unit,
    private val onItemLongClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, FreezerAppsAdapter.ViewHolder>(Diff) {
    var listView: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int) = if (listView) VIEW_TYPE_LIST else VIEW_TYPE_GRID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(
            if (viewType == VIEW_TYPE_LIST) R.layout.item_home_list else R.layout.item_home,
            parent,
            false
        )
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val appIcon = view.findViewById<ImageView>(R.id.app_icon)
        private val appName = view.findViewById<TextView>(R.id.app_name)
        private var iconJob: Job? = null

        fun bind(info: AppInfo) {
            iconJob?.cancel()
            appIcon.setImageDrawable(itemView.context.packageManager.defaultActivityIcon)
            appIcon.colorFilter = null
            val frozen = info.state == AppInfo.State.FROZEN
            appName.apply {
                text = buildString {
                    if (!HailData.grayscaleIcon && frozen) append("\u2744\uFE0F")
                    append(info.name)
                }
                isEnabled = !HailData.grayscaleIcon || !frozen
                setTextAppearance(
                    if (listView) com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
                    else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, HailData.homeFontSize)
                if (info.packageName in selectedPackages) {
                    setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                }
            }
            info.applicationInfo?.let {
                iconJob = AppIconCache.loadIconBitmapAsync(
                    itemView.context,
                    it,
                    myUserId,
                    appIcon,
                    HailData.grayscaleIcon && frozen
                )
            } ?: appIcon.apply {
                setImageDrawable(itemView.context.packageManager.defaultActivityIcon)
                colorFilter = null
            }
            itemView.setOnLongClickListener {
                onItemLongClick(info)
                true
            }
            itemView.setOnClickListener { onItemClick(info) }
        }

        fun recycle() {
            iconJob?.cancel()
            iconJob = null
        }
    }

    private object Diff : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) = oldItem.packageName == newItem.packageName
        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem.name == newItem.name && oldItem.state == newItem.state
    }

    private companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }
}
