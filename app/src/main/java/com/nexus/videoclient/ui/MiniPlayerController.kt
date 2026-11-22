package com.nexus.videoclient.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.nexus.videoclient.R
import com.nexus.videoclient.playback.PlaybackService
import com.nexus.videoclient.playback.PlaybackSession

class MiniPlayerController(private val activity: Activity) {

    private val root: View? = activity.findViewById(R.id.mini_player)
    private val playerView: PlayerView? = activity.findViewById(R.id.mini_view)
    private val titleView: TextView? = activity.findViewById(R.id.mini_title)
    private val subtitleView: TextView? = activity.findViewById(R.id.mini_subtitle)
    private val playPauseBtn: TextView? = activity.findViewById(R.id.mini_play_pause)
    private val closeBtn: View? = activity.findViewById(R.id.mini_close)

    private val stateListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playPauseBtn?.text = if (isPlaying) "❚❚" else "▶"
        }
    }

    init {
        root?.setOnClickListener { openFullPlayer() }
        playPauseBtn?.setOnClickListener { togglePlayPause() }
        closeBtn?.setOnClickListener { closePlayer() }
    }

    fun onStart() {
        val container = root ?: return
        val active = PlaybackSession.player
        // Only hide when PiP is showing the video. The main activity is not
        // visible while the full-screen player is on top anyway.
        val show = PlaybackSession.isActive &&
            active != null &&
            !PlaybackSession.isInPip

        if (show && active != null) {
            titleView?.text = PlaybackSession.title.ifBlank { "Now Playing" }
            subtitleView?.text = PlaybackSession.channel.ifBlank { "Tap to expand" }
            if (playerView?.player !== active) playerView?.player = active
            active.removeListener(stateListener)
            active.addListener(stateListener)
            playPauseBtn?.text = if (active.isPlaying) "❚❚" else "▶"
            if (container.visibility != View.VISIBLE) {
                container.visibility = View.VISIBLE
                container.alpha = 0f
                container.translationY = 20f * activity.resources.displayMetrics.density
                container.animate().alpha(1f).translationY(0f).setDuration(180).start()
            }
        } else {
            active?.removeListener(stateListener)
            playerView?.player = null
            container.visibility = View.GONE
        }
    }

    fun onPause() {
        PlaybackSession.player?.removeListener(stateListener)
        playerView?.player = null
    }

    private fun togglePlayPause() {
        val p = PlaybackSession.player ?: return
        playPauseBtn?.animate()?.cancel()
        playPauseBtn?.animate()?.scaleX(0.84f)?.scaleY(0.84f)?.setDuration(65)?.withEndAction {
            playPauseBtn.animate().scaleX(1f).scaleY(1f).setDuration(150)
                .setInterpolator(OvershootInterpolator(2.5f)).start()
        }?.start()
        if (p.isPlaying) {
            p.pause()
            playPauseBtn?.text = "▶"
        } else {
            p.play()
            playPauseBtn?.text = "❚❚"
        }
    }

    private fun openFullPlayer() {
        val id = PlaybackSession.videoId
        if (id.isBlank()) return
        PlaybackSession.player?.removeListener(stateListener)
        playerView?.player = null
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, id)
                .putExtra(PlayerActivity.EXTRA_TITLE, PlaybackSession.title)
        )
    }

    private fun closePlayer() {
        PlaybackSession.player?.removeListener(stateListener)
        playerView?.player = null
        PlaybackSession.stop()
        PlaybackService.stop(activity)
        val container = root ?: return
        container.animate()
            .alpha(0f)
            .translationY(18f * activity.resources.displayMetrics.density)
            .setDuration(140)
            .withEndAction {
                container.visibility = View.GONE
                container.alpha = 1f
                container.translationY = 0f
            }
            .start()
    }
}
