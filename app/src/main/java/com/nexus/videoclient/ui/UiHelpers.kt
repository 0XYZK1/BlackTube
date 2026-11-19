package com.nexus.videoclient.ui

import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nexus.videoclient.R
import com.nexus.videoclient.data.InnerTube
import com.nexus.videoclient.data.LocalStore
import com.nexus.videoclient.data.Session
import com.nexus.videoclient.data.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun View.makeCircular() {
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val size = minOf(view.width, view.height)
            val left = (view.width - size) / 2
            val top = (view.height - size) / 2
            outline.setOval(left, top, left + size, top + size)
        }
    }
}

fun showVideoOptionsSheet(
    context: Context,
    scope: CoroutineScope,
    item: VideoItem,
    onQueueChanged: (() -> Unit)? = null,
    onRemoved: (() -> Unit)? = null
) {
    val dialog = BottomSheetDialog(context, R.style.Theme_Nexus_BottomSheet)
    val density = context.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_bottom_sheet)
        setPadding(dp(18), dp(14), dp(18), dp(24))
    }

    val handle = View(context).apply {
        setBackgroundResource(R.drawable.bg_account_pill)
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        }
    }
    root.addView(handle)

    val titleView = TextView(context).apply {
        text = item.title
        setTextColor(context.getColor(R.color.nexus_white))
        textSize = 14.5f
        maxLines = 2
        setPadding(dp(4), 0, dp(4), dp(12))
    }
    root.addView(titleView)

    fun addRow(label: String, onClick: () -> Unit) {
        val row = TextView(context).apply {
            text = label
            setTextColor(context.getColor(R.color.nexus_white))
            textSize = 14.5f
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                dialog.dismiss()
                onClick()
            }
        }
        root.addView(row)
    }

    if (!item.isPlaylist && !item.isChannel) {
        addRow("Add to Queue") {
            com.nexus.videoclient.playback.PlaybackSession.addToQueue(item)
            Toast.makeText(
                context,
                "Added to queue (${com.nexus.videoclient.playback.PlaybackSession.getQueue().size} queued)",
                Toast.LENGTH_SHORT
            ).show()
            onQueueChanged?.invoke()
        }
    }

    addRow("Save to Watch Later") {
        LocalStore.addWatchLater(item)
        if (Session.isLoggedIn) {
            scope.launch {
                withContext(Dispatchers.IO) { InnerTube.addToRemoteWatchLater(item.id) }
            }
        }
        Toast.makeText(context, "Saved to Watch Later", Toast.LENGTH_SHORT).show()
    }

    val isFav = LocalStore.isFavorite(item.id)
    addRow(if (isFav) "Remove from Favorites" else "Add to Favorites") {
        val added = LocalStore.toggleFavorite(item)
        Toast.makeText(
            context,
            if (added) "Added to Favorites" else "Removed from Favorites",
            Toast.LENGTH_SHORT
        ).show()
    }

    if (item.channelId.isNotBlank() || item.channel.isNotBlank()) {
        addRow("Go to Channel (${item.channel.ifBlank { "Channel" }})") {
            if (item.channelId.isNotBlank()) {
                context.startActivity(
                    Intent(context, ListActivity::class.java)
                        .putExtra(ListActivity.EXTRA_MODE, ListActivity.MODE_CHANNEL)
                        .putExtra(ListActivity.EXTRA_BROWSE_ID, item.channelId)
                        .putExtra(ListActivity.EXTRA_TITLE, item.channel)
                )
            } else {
                context.startActivity(
                    Intent(context, ListActivity::class.java)
                        .putExtra(ListActivity.EXTRA_MODE, ListActivity.MODE_SEARCH)
                        .putExtra(ListActivity.EXTRA_QUERY, item.channel)
                        .putExtra(ListActivity.EXTRA_TITLE, item.channel)
                )
            }
        }
    }

    addRow("Share Link") {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, item.watchUrl)
        runCatching { context.startActivity(Intent.createChooser(send, "Share")) }
    }

    addRow("Not Interested") {
        LocalStore.hideVideo(item.id)
        onRemoved?.invoke()
        Toast.makeText(context, "Video hidden", Toast.LENGTH_SHORT).show()
    }

    dialog.setContentView(root)
    dialog.show()
}
