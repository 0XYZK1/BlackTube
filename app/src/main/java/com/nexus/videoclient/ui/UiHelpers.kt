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

    if (!item.isPlaylist && !item.isChannel) {
        addRow("Save to Playlist") {
            showSaveToPlaylistSheet(context, scope, item)
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

fun formatTimestamp(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}

fun showSaveToPlaylistSheet(
    context: Context,
    scope: CoroutineScope,
    item: VideoItem
) {
    val dialog = BottomSheetDialog(context, R.style.Theme_Nexus_BottomSheet)
    val d = context.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * d).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_bottom_sheet)
        setPadding(dp(18), dp(14), dp(18), dp(26))
    }

    root.addView(View(context).apply {
        setBackgroundResource(R.drawable.bg_account_pill)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        }
    })

    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    header.addView(TextView(context).apply {
        text = "SAVE TO PLAYLIST"
        letterSpacing = 0.14f
        setTextColor(context.getColor(R.color.nexus_white))
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })

    if (Session.isLoggedIn) {
        header.addView(TextView(context).apply {
            text = "+ New"
            setTextColor(context.getColor(R.color.nexus_black))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_primary_button)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener {
                dialog.dismiss()
                showCreatePlaylistDialog(context, scope, item)
            }
        })
    }
    root.addView(header)

    val listBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, 0)
    }

    fun addPlaylistRow(id: String, name: String, subtitle: String, isWatchLater: Boolean = false) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(context).apply {
            text = name
            setTextColor(context.getColor(R.color.nexus_white))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(context).apply {
            text = subtitle
            setTextColor(context.getColor(R.color.nexus_text_dim))
            textSize = 11.5f
        })
        val actionLabel = TextView(context).apply {
            text = "+ ADD"
            letterSpacing = 0.1f
            setTextColor(context.getColor(R.color.nexus_white))
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(col)
        row.addView(actionLabel)
        row.setOnClickListener {
            if (isWatchLater) LocalStore.addWatchLater(item)
            if (!Session.isLoggedIn) {
                Toast.makeText(context, "Saved locally to $name", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                return@setOnClickListener
            }
            actionLabel.text = "…"
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    InnerTube.addVideoToPlaylist(id, item.id)
                }
                if (ok) {
                    actionLabel.text = "✓ SAVED"
                    Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    actionLabel.text = "RETRY"
                    Toast.makeText(context, "Could not add to $name", Toast.LENGTH_SHORT).show()
                }
            }
        }
        listBox.addView(row)
    }

    addPlaylistRow("WL", "Watch Later", "Quick queue", isWatchLater = true)

    val statusTv = TextView(context).apply {
        text = if (Session.isLoggedIn) "Loading your playlists…" else "Sign in to sync YouTube playlists."
        setTextColor(context.getColor(R.color.nexus_text_muted))
        textSize = 12.5f
        setPadding(dp(4), dp(12), dp(4), dp(4))
    }
    listBox.addView(statusTv)

    root.addView(android.widget.ScrollView(context).apply { addView(listBox) })
    dialog.setContentView(root)
    dialog.show()

    if (!Session.isLoggedIn) return
    scope.launch {
        val playlists = withContext(Dispatchers.IO) {
            runCatching { InnerTube.userPlaylists() }.getOrDefault(emptyList())
        }
        listBox.removeView(statusTv)
        val filtered = playlists.filter {
            it.id != "WL" && it.id != "VLWL" && it.id != "LL" && it.id != "VLLL"
        }
        if (filtered.isEmpty()) {
            listBox.addView(TextView(context).apply {
                text = "No custom playlists yet – tap + New above."
                setTextColor(context.getColor(R.color.nexus_text_muted))
                textSize = 12.5f
                setPadding(dp(4), dp(12), dp(4), dp(4))
            })
        } else {
            filtered.forEach { pl ->
                addPlaylistRow(pl.id, pl.title, pl.views.ifBlank { "Playlist" })
            }
        }
    }
}

private fun showCreatePlaylistDialog(
    context: Context,
    scope: CoroutineScope,
    initialItem: VideoItem
) {
    val input = android.widget.EditText(context).apply {
        hint = "Playlist title"
        setTextColor(context.getColor(R.color.nexus_white))
        setHintTextColor(context.getColor(R.color.nexus_text_dim))
        val p = (16 * context.resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
    }
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("New Private Playlist")
        .setView(input)
        .setPositiveButton("Create & Add") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) return@setPositiveButton
            scope.launch {
                val createdId = withContext(Dispatchers.IO) {
                    InnerTube.createPlaylist(name, "PRIVATE", initialItem.id)
                }
                Toast.makeText(
                    context,
                    if (createdId != null) "Created \"$name\" & added video" else "Failed to create playlist",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}
