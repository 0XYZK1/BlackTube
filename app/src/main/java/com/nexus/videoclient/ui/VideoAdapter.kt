package com.nexus.videoclient.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.nexus.videoclient.R
import com.nexus.videoclient.data.VideoItem

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit,
    private val onChannelClick: ((VideoItem) -> Unit)? = null,
    private val onMoreClick: ((VideoItem, Int) -> Unit)? = null
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    private val items = ArrayList<VideoItem>()

    fun submit(newItems: List<VideoItem>, append: Boolean) {
        if (!append) items.clear()
        val known = items.mapTo(HashSet()) { it.id }
        newItems.forEach { item ->
            if (item.id.isNotBlank() && known.add(item.id)) items.add(item)
        }
        notifyDataSetChanged()
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], onClick, onChannelClick, onMoreClick)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.item_thumb)
        private val avatar: ImageView = view.findViewById(R.id.item_avatar)
        private val duration: TextView = view.findViewById(R.id.item_duration)
        private val badge: TextView = view.findViewById(R.id.item_badge)
        private val title: TextView = view.findViewById(R.id.item_title)
        private val meta: TextView = view.findViewById(R.id.item_meta)
        private val moreBtn: View = view.findViewById(R.id.item_more)
        private val watchBar: android.widget.ProgressBar =
            view.findViewById(R.id.item_progress)

        init {
            avatar.makeCircular()
        }

        fun bind(
            item: VideoItem,
            onClick: (VideoItem) -> Unit,
            onChannelClick: ((VideoItem) -> Unit)?,
            onMoreClick: ((VideoItem, Int) -> Unit)?
        ) {
            if (item.thumbnail.isNotBlank()) {
                thumb.load(item.thumbnail) { crossfade(150) }
            }
            if (item.avatar.isNotBlank()) {
                avatar.visibility = View.VISIBLE
                avatar.load(item.avatar) {
                    placeholder(R.drawable.bg_avatar_circle)
                    error(R.drawable.bg_avatar_circle)
                    crossfade(100)
                    transformations(CircleCropTransformation())
                }
                avatar.setOnClickListener { onChannelClick?.invoke(item) }
            } else {
                avatar.setImageDrawable(null)
                avatar.setBackgroundResource(R.drawable.bg_avatar_circle)
                avatar.visibility = if (item.isPlaylist) View.GONE else View.VISIBLE
                avatar.setOnClickListener { onChannelClick?.invoke(item) }
            }

            title.text = item.title
            duration.text = if (item.isLive) "LIVE" else item.duration
            duration.visibility =
                if (item.duration.isBlank() && !item.isLive) View.GONE else View.VISIBLE
            badge.visibility = if (item.isPlaylist) View.VISIBLE else View.GONE

            if (item.percentWatched > 0) {
                watchBar.visibility = View.VISIBLE
                watchBar.progress = item.percentWatched
            } else {
                watchBar.visibility = View.GONE
            }

            val metaText = listOf(item.channel, item.views, item.published)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            meta.text = metaText
            meta.visibility = if (metaText.isBlank()) View.GONE else View.VISIBLE

            moreBtn.visibility = if (onMoreClick != null) View.VISIBLE else View.GONE
            moreBtn.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onMoreClick?.invoke(item, pos)
                }
            }

            // Dezente Press-Scale-Animation
            itemView.setOnClickListener { v ->
                v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(70).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                    onClick(item)
                }.start()
            }
        }
    }
}
