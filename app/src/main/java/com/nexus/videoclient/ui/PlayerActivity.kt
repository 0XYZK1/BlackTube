package com.nexus.videoclient.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Rational
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nexus.videoclient.R
import com.nexus.videoclient.data.Comment
import com.nexus.videoclient.data.Dislikes
import com.nexus.videoclient.data.InnerTube
import com.nexus.videoclient.data.LocalStore
import com.nexus.videoclient.data.MediaDownloader
import com.nexus.videoclient.data.Resolved
import com.nexus.videoclient.data.Session
import com.nexus.videoclient.data.StreamChoice
import com.nexus.videoclient.data.VideoItem
import com.nexus.videoclient.data.WatchPage
import com.nexus.videoclient.data.YtExtractor
import com.nexus.videoclient.playback.PlaybackService
import com.nexus.videoclient.playback.PlaybackSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_TITLE = "title"

        private const val GESTURE_NONE = 0
        private const val GESTURE_VERTICAL_HUD = 1
        private const val GESTURE_SWIPE_MINIMIZE = 2
        private const val GESTURE_HORIZONTAL = 3

        private val H264_ITAGS = setOf(
            5, 6, 17, 18, 22, 34, 35, 36, 37, 38, 43, 44, 45, 46,
            133, 134, 135, 136, 137, 138, 160, 264, 266, 298, 299
        )

        private val AUDIO_ITAG_KBPS = mapOf(
            139 to 48, 140 to 128, 141 to 256,
            249 to 50, 250 to 70, 251 to 160
        )

        private val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }

    private lateinit var playerRoot: View
    private lateinit var videoContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var uiPlayer: androidx.media3.common.ForwardingPlayer
    private lateinit var titleView: TextView
    private lateinit var channelRow: View
    private lateinit var channelAvatarView: ImageView
    private lateinit var channelView: TextView
    private lateinit var channelSubsView: TextView
    private lateinit var metaView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var qualityButton: TextView
    private lateinit var likeCountView: TextView
    private lateinit var dislikeCountView: TextView
    private lateinit var descView: TextView
    private lateinit var descToggle: TextView
    private lateinit var commentsCountView: TextView
    private lateinit var relatedContainer: LinearLayout
    private lateinit var btnFullscreen: ImageButton
    private lateinit var audioTrackButton: TextView
    private lateinit var likeIconView: ImageView
    private lateinit var dislikeIconView: ImageView
    private lateinit var commentsPreviewCard: View
    private lateinit var commentsPreviewAvatar: ImageView
    private lateinit var commentsPreviewText: TextView

    private lateinit var skipLeftOverlay: View
    private lateinit var skipLeftText: TextView
    private lateinit var skipRightOverlay: View
    private lateinit var skipRightText: TextView
    private var accumulatedSkipSec = 0
    private var lastSkipSide = 0 // -1 links, +1 rechts

    private lateinit var hudBrightnessLeft: View
    private lateinit var hudBrightnessBar: ProgressBar
    private lateinit var hudBrightnessText: TextView
    private lateinit var hudVolumeRight: View
    private lateinit var hudVolumeBar: ProgressBar
    private lateinit var hudVolumeText: TextView

    private var streams: Resolved? = null
    private var videoId: String = ""
    private var selectedHeight: Int? = null
    private var selectedAudioKey: String? = null
    private var playbackSpeed: Float = 1.0f

    private var descriptionExpanded = false
    private var commentToken: String? = null
    private var initialCommentToken: String? = null
    private var createCommentParams: String? = null
    private var commentsLoading = false
    private var postingComment = false
    private val seenComments = HashSet<String>()
    private val loadedComments = ArrayList<Comment>()
    private var videoLiked = false
    private var videoDisliked = false
    private var sponsorSegments: List<com.nexus.videoclient.data.SponsorSegment> = emptyList()
    private val skippedSegments = HashSet<Long>()
    private var sleepStopAtMs: Long = 0L
    private var pipReceiverRegistered = false

    private val pipActionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!this@PlayerActivity::player.isInitialized) return
            when (intent?.action) {
                "com.nexus.blacktube.PIP_TOGGLE" -> {
                    if (player.isPlaying) player.pause() else player.play()
                    updatePipParams()
                }
                "com.nexus.blacktube.PIP_REWIND" -> {
                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                }
                "com.nexus.blacktube.PIP_FORWARD" -> {
                    val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 10_000L).coerceAtMost(dur))
                }
            }
        }
    }

    private var resolveTimeMs = 0L
    private var fallbackTried = false
    private var usedAdaptive = false
    private var commentsCollapsed = false
    private var fullscreen = false
    private var autoRetried = false
    private var watchChannelId = ""
    private var watchChannelName = ""
    private var watchChannelSubscribed = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private var castContext: com.google.android.gms.cast.framework.CastContext? = null
    private var mediaRouter: androidx.mediarouter.media.MediaRouter? = null
    private var lastRelated: List<VideoItem> = emptyList()

    private val castSessionListener = object :
        com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
        override fun onSessionStarted(
            session: com.google.android.gms.cast.framework.CastSession,
            sessionId: String
        ) {
            castCurrentVideoTo(session)
        }

        override fun onSessionResumed(
            session: com.google.android.gms.cast.framework.CastSession,
            wasSuspended: Boolean
        ) {
            castCurrentVideoTo(session)
        }

        override fun onSessionEnded(
            session: com.google.android.gms.cast.framework.CastSession,
            error: Int
        ) {
            toast("Disconnected from Cast device")
        }

        override fun onSessionStarting(
            session: com.google.android.gms.cast.framework.CastSession
        ) {
        }

        override fun onSessionStartFailed(
            session: com.google.android.gms.cast.framework.CastSession,
            error: Int
        ) {
            toast("Could not connect to Cast device")
        }

        override fun onSessionEnding(
            session: com.google.android.gms.cast.framework.CastSession
        ) {
        }

        override fun onSessionResuming(
            session: com.google.android.gms.cast.framework.CastSession,
            sessionId: String
        ) {
        }

        override fun onSessionResumeFailed(
            session: com.google.android.gms.cast.framework.CastSession,
            error: Int
        ) {
        }

        override fun onSessionSuspended(
            session: com.google.android.gms.cast.framework.CastSession,
            reason: Int
        ) {
        }
    }

    private val watchTimeRunnable = object : Runnable {
        private var tick = 0
        override fun run() {
            if (this@PlayerActivity::player.isInitialized && player.isPlaying) {
                val pos = player.currentPosition
                val dur = player.duration

                // SponsorBlock auto-skip (checked once per second)
                if (LocalStore.isSponsorBlockEnabled() && sponsorSegments.isNotEmpty()) {
                    val seg = sponsorSegments.firstOrNull {
                        pos in it.startMs until (it.endMs - 600L) && skippedSegments.add(it.startMs)
                    }
                    if (seg != null) {
                        player.seekTo(seg.endMs)
                        toast("Skipped ${seg.category}")
                    }
                }

                // Sleep timer
                if (sleepStopAtMs > 0L && System.currentTimeMillis() >= sleepStopAtMs) {
                    sleepStopAtMs = 0L
                    player.pause()
                    toast("Sleep timer paused playback")
                }

                // Every 5 seconds persist watchtime & resume position
                tick++
                if (tick % 5 == 0) {
                    LocalStore.addWatchSeconds(5L)
                    if (dur > 0L) LocalStore.savePlaybackPosition(videoId, pos, dur)
                }
            }
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAt = 0L
    private var lastMoveY = 0f
    private var gestureMode = GESTURE_NONE
    private var lastTapAt = 0L
    private var brightnessLevel = 0.5f
    private var volumeAccumulator = 0f
    private var touchSlop = 24

    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
            if (!isPlaying && this@PlayerActivity::player.isInitialized && player.duration > 0L) {
                LocalStore.savePlaybackPosition(videoId, player.currentPosition, player.duration)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                progress.visibility = View.GONE
                statusView.setOnClickListener(null)
                val timing = if (resolveTimeMs > 0) " · %.1fs".format(resolveTimeMs / 1000.0) else ""
                val speedLabel = if (playbackSpeed != 1.0f) " · ${playbackSpeed}x" else ""
                val audioLabel = currentAudioLabel()
                statusView.text =
                    "${selectedHeight?.let { "${it}p" } ?: "Auto"} · $audioLabel$speedLabel$timing"
                audioTrackButton.text = audioLabel
                updatePipParams()
            } else if (state == Player.STATE_BUFFERING) {
                progress.visibility = View.VISIBLE
            } else if (state == Player.STATE_ENDED) {
                if (player.duration > 0L) {
                    LocalStore.savePlaybackPosition(videoId, player.duration, player.duration)
                }
                val next = PlaybackSession.popNextInQueue()
                if (next != null) {
                    toast("Up next: ${next.title}")
                    openVideo(next.id, next.title)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            progress.visibility = View.GONE
            val currentPos = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0L) {
                LocalStore.savePlaybackPosition(videoId, currentPos, player.duration)
            }

            if (!fallbackTried && usedAdaptive) {
                fallbackTried = true
                val fallback = streams?.bestProgressive(720)
                if (fallback != null) {
                    playProgressive(fallback, currentPos)
                    return
                }
            }
            if (!autoRetried) {
                autoRetried = true
                statusView.text = "Refreshing stream…"
                uiHandler.postDelayed({ autoReload(currentPos) }, 900)
                return
            }
            statusView.text = "Stream interrupted – tap to retry"
            statusView.setOnClickListener { retryPlayback(currentPos) }
        }
    }

    private fun currentAudioLabel(): String {
        val active = streams?.bestOriginalAudio(selectedAudioKey) ?: return "Original"
        return when {
            active.isOriginalAudio -> "Original"
            active.isDubbedAudio -> "${active.audioLocale.uppercase().ifBlank { "Dub" }} (Dub)"
            else -> active.audioTrackName.ifBlank { "Original" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()

        playerRoot = findViewById(R.id.player_root)
        videoContainer = findViewById(R.id.video_container)
        titleView = findViewById(R.id.player_title)
        channelRow = findViewById(R.id.channel_row)
        channelAvatarView = findViewById(R.id.player_channel_avatar)
        channelAvatarView.makeCircular()
        channelView = findViewById(R.id.player_channel)
        channelSubsView = findViewById(R.id.player_channel_subs)
        metaView = findViewById(R.id.player_meta)
        statusView = findViewById(R.id.player_status)
        progress = findViewById(R.id.progress)
        qualityButton = findViewById(R.id.btn_quality)
        playerView = findViewById(R.id.player_view)
        likeCountView = findViewById(R.id.like_count)
        dislikeCountView = findViewById(R.id.dislike_count)
        descView = findViewById(R.id.desc_text)
        descToggle = findViewById(R.id.desc_toggle)
        commentsCountView = findViewById(R.id.comments_count)
        relatedContainer = findViewById(R.id.related_container)
        audioTrackButton = findViewById(R.id.btn_audio_track)
        likeIconView = findViewById(R.id.icon_like)
        dislikeIconView = findViewById(R.id.icon_dislike)
        commentsPreviewCard = findViewById(R.id.comments_preview_card)
        commentsPreviewAvatar = findViewById(R.id.comments_preview_avatar)
        commentsPreviewAvatar.makeCircular()
        commentsPreviewText = findViewById(R.id.comments_preview_text)

        skipLeftOverlay = findViewById(R.id.skip_left_overlay)
        skipLeftText = findViewById(R.id.skip_left_text)
        skipRightOverlay = findViewById(R.id.skip_right_overlay)
        skipRightText = findViewById(R.id.skip_right_text)

        hudBrightnessLeft = findViewById(R.id.hud_brightness_left)
        hudBrightnessBar = findViewById(R.id.hud_brightness_bar)
        hudBrightnessText = findViewById(R.id.hud_brightness_text)
        hudVolumeRight = findViewById(R.id.hud_volume_right)
        hudVolumeBar = findViewById(R.id.hud_volume_bar)
        hudVolumeText = findViewById(R.id.hud_volume_text)

        setPortrait16by9Height()

        titleView.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val shared = PlaybackSession.player
        // der puffer ist absichtlich fett, sonst bleibt 1080p auf mobilfunk stehen
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player = shared ?: ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 25_000,
                        /* maxBufferMs = */ 90_000,
                        /* bufferForPlaybackMs = */ 1_200,
                        /* bufferForPlaybackAfterRebufferMs = */ 3_500
                    )
                    .setTargetBufferBytes(64 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
            .also { PlaybackSession.attach(it) }

        val resuming = shared != null &&
            PlaybackSession.isActive &&
            PlaybackSession.videoId == videoId &&
            player.mediaItemCount > 0

        player.setHandleAudioBecomingNoisy(true)
        player.addListener(playbackListener)
        uiPlayer = createUiPlayer(player)
        playerView.player = uiPlayer
        setupPictureInPicture()
        setupGestures()
        initGoogleCast()
        uiHandler.postDelayed(watchTimeRunnable, 1_000L)

        installCustomControls()

        qualityButton.setOnClickListener { showSettingsSheet() }
        audioTrackButton.setOnClickListener { showAudioTrackSheet() }

        findViewById<View>(R.id.action_save_playlist).setOnClickListener {
            showSaveToPlaylistSheet(
                this,
                lifecycleScope,
                VideoItem(
                    id = videoId,
                    title = titleView.text.toString(),
                    channel = watchChannelName,
                    channelId = watchChannelId,
                    thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                )
            )
        }
        commentsPreviewCard.setOnClickListener { showCommentsBottomSheet() }

        // Real video like / dislike via InnerTube
        findViewById<View>(R.id.action_like).setOnClickListener {
            handleVideoLikeClick(like = true)
        }
        findViewById<View>(R.id.action_dislike).setOnClickListener {
            handleVideoLikeClick(like = false)
        }
        videoLiked = LocalStore.isLiked(videoId)
        videoDisliked = LocalStore.isDisliked(videoId)
        updateLikeButtonsUi()

        val initialTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        titleView.text = initialTitle
        if (videoId.isNotBlank()) PlaybackSession.activate(videoId, initialTitle)

        // PiP actions (Android 13/14 safe)
        val pipFilter = android.content.IntentFilter().apply {
            addAction("com.nexus.blacktube.PIP_TOGGLE")
            addAction("com.nexus.blacktube.PIP_REWIND")
            addAction("com.nexus.blacktube.PIP_FORWARD")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, pipActionReceiver, pipFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        pipReceiverRegistered = true

        descToggle.setOnClickListener { toggleDescription() }
        findViewById<Button>(R.id.btn_mp4).setOnClickListener { downloadVideo() }
        findViewById<Button>(R.id.btn_audiofile).setOnClickListener { downloadAudio() }
        findViewById<View>(R.id.action_share).setOnClickListener { share() }
        channelRow.setOnClickListener { openChannel() }
        findViewById<View>(R.id.btn_player_subscribe).setOnClickListener {
            if (!Session.isLoggedIn) {
                toast("Sign in to subscribe")
                return@setOnClickListener
            }
            if (watchChannelId.isBlank()) {
                toast("Loading channel…")
                return@setOnClickListener
            }
            val target = !watchChannelSubscribed
            watchChannelSubscribed = target
            updatePlayerSubscribeUi()
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    InnerTube.setSubscribed(watchChannelId, target)
                }
                if (ok) {
                    toast(if (target) "Subscribed" else "Unsubscribed")
                } else {
                    watchChannelSubscribed = !target
                    updatePlayerSubscribeUi()
                    toast("Subscription failed")
                }
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (fullscreen) {
                setFullscreenState(false)
            } else {
                animateAndMinimize()
            }
        }

        if (videoId.isBlank()) {
            statusView.text = "No video specified."
            return
        }

        if (resuming) {
            statusView.text = "Playing · Original audio"
            lifecycleScope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    runCatching { YtExtractor.resolve(videoId) }.getOrNull()
                }
                if (resolved != null) {
                    streams = resolved
                    applyResolvedMetadata(resolved)
                }
                uiHandler.postDelayed({ loadWatchInfo() }, 400)
            }
        } else {
            load()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!fullscreen && !isInPictureInPictureMode) {
            videoContainer.post { setPortrait16by9Height() }
        }
    }

    private fun createUiPlayer(base: ExoPlayer): androidx.media3.common.ForwardingPlayer {
        return object : androidx.media3.common.ForwardingPlayer(base) {
            override fun getAvailableCommands(): Player.Commands {
                val builder = super.getAvailableCommands().buildUpon()
                if (PlaybackSession.hasNext()) {
                    builder.add(Player.COMMAND_SEEK_TO_NEXT)
                    builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                } else {
                    builder.remove(Player.COMMAND_SEEK_TO_NEXT)
                    builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
                if (PlaybackSession.hasPrevious() ||
                    (this@PlayerActivity::player.isInitialized && currentPosition > 3_500L)
                ) {
                    builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
                return builder.build()
            }

            override fun isCommandAvailable(command: Int): Boolean = when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> PlaybackSession.hasNext()
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                    PlaybackSession.hasPrevious() ||
                        (this@PlayerActivity::player.isInitialized && currentPosition > 3_500L)
                else -> super.isCommandAvailable(command)
            }

            override fun hasNextMediaItem(): Boolean = PlaybackSession.hasNext()
            override fun hasPreviousMediaItem(): Boolean = PlaybackSession.hasPrevious()

            override fun seekToNext() {
                skipNext()
            }

            override fun seekToNextMediaItem() {
                skipNext()
            }

            override fun seekToPrevious() {
                skipPrevious()
            }

            override fun seekToPreviousMediaItem() {
                skipPrevious()
            }
        }
    }

    private fun updateSkipButtons() {
        runCatching {
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_next)
                ?.isEnabled = PlaybackSession.hasNext()
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_prev)
                ?.isEnabled = PlaybackSession.hasPrevious() ||
                (this::player.isInitialized && player.currentPosition > 3_500L)
        }
    }

    private fun setPortrait16by9Height() {
        val screenWidth = minOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        val targetHeight = (screenWidth * 9f / 16f).toInt()
        videoContainer.layoutParams = videoContainer.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = targetHeight
        }
        videoContainer.requestLayout()
    }

    private fun load(resumeFromMs: Long = -1L) {
        progress.visibility = View.VISIBLE
        statusView.text = "Resolving stream…"
        statusView.setOnClickListener(null)
        sponsorSegments = emptyList()
        skippedSegments.clear()

        val targetResumeMs = if (resumeFromMs >= 0L) {
            resumeFromMs
        } else {
            LocalStore.getPlaybackPosition(videoId)
        }

        val started = System.currentTimeMillis()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { YtExtractor.resolve(videoId) }
            }
            result.onSuccess { resolved ->
                resolveTimeMs = System.currentTimeMillis() - started
                streams = resolved
                applyResolvedMetadata(resolved)
                PlaybackSession.updateMetadata(resolved.title, resolved.uploader)
                play(targetResumeMs)
                if (targetResumeMs >= 5_000L) {
                    toast("Resumed at ${formatTimestamp(targetResumeMs)}")
                }
            }.onFailure { error ->
                progress.visibility = View.GONE
                statusView.text = "Could not load: ${error.message} – tap to retry"
                statusView.setOnClickListener { retryPlayback(targetResumeMs.coerceAtLeast(0L)) }
            }
            uiHandler.postDelayed({ loadWatchInfo() }, 500)
            if (LocalStore.isSponsorBlockEnabled()) {
                val segs = withContext(Dispatchers.IO) {
                    com.nexus.videoclient.data.SponsorBlock.segments(videoId)
                }
                sponsorSegments = segs
            }
        }
    }

    private fun applyResolvedMetadata(resolved: Resolved) {
        if (resolved.title.isNotBlank()) titleView.text = resolved.title
        if (resolved.uploader.isNotBlank()) {
            watchChannelName = resolved.uploader
            channelView.text = resolved.uploader
        }
        if (resolved.uploaderAvatar.isNotBlank()) {
            channelAvatarView.load(resolved.uploaderAvatar) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        }
        if (watchChannelId.isBlank() && resolved.uploaderUrl.contains("/channel/")) {
            watchChannelId = resolved.uploaderUrl.substringAfter("/channel/").substringBefore("/")
        }
    }

    private fun retryPlayback(resumePos: Long = 0L) {
        YtExtractor.invalidate(videoId)
        streams = null
        fallbackTried = false
        autoRetried = false
        statusView.setOnClickListener(null)
        load(resumePos)
    }

    private fun autoReload(resumePos: Long) {
        YtExtractor.invalidate(videoId)
        streams = null
        fallbackTried = false
        load(resumePos)
    }

    private fun loadWatchInfo() {
        lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching { InnerTube.watch(videoId) }.getOrNull()
            }
            if (page != null) applyWatchInfo(page)
            if (page == null) likeCountView.text = "–"

            val dislikes = withContext(Dispatchers.IO) {
                runCatching { Dislikes.count(videoId) }.getOrNull()
            }
            dislikeCountView.text = dislikes?.takeIf { it.isNotBlank() } ?: "–"
        }
    }

    private fun applyWatchInfo(page: WatchPage) {
        val info = page.info
        if (info.title.isNotBlank()) titleView.text = info.title
        if (info.channel.isNotBlank()) {
            watchChannelName = info.channel
            channelView.text = info.channel
        }
        PlaybackSession.updateMetadata(titleView.text.toString(), watchChannelName)
        channelSubsView.text = info.subscribers.ifBlank { "Channel" }

        if (info.channelAvatar.isNotBlank()) {
            channelAvatarView.load(info.channelAvatar) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        }
        if (info.channelId.isNotBlank()) watchChannelId = info.channelId

        watchChannelSubscribed = info.isSubscribed
        updatePlayerSubscribeUi()

        if (Session.isLoggedIn) {
            videoLiked = info.isLiked
            videoDisliked = info.isDisliked
            LocalStore.setVideoLikeState(videoId, videoLiked, videoDisliked)
            updateLikeButtonsUi()
        }

        val meta = listOf(info.views, info.published).filter { it.isNotBlank() }
        metaView.text = meta.joinToString(" · ")
        likeCountView.text = info.likes.ifBlank { "Like" }

        if (info.description.isNotBlank()) {
            descView.text = info.description
            descView.movementMethod = LinkMovementMethod.getInstance()
            descView.linksClickable = true
            Linkify.addLinks(descView, Linkify.WEB_URLS)
            descToggle.visibility = View.VISIBLE
        } else {
            descView.text = "No description."
            descToggle.visibility = View.GONE
        }

        val countClean = page.commentCount
            .replace(Regex("(?i)\\s*comments?\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        commentsCountView.text = countClean

        createCommentParams = page.createCommentParams
        commentToken = page.commentToken
        initialCommentToken = page.commentToken
        lastRelated = page.related
        renderRelated(page.related)

        if (commentToken.isNullOrBlank()) {
            commentsPreviewText.text = "Comments are disabled for this video."
            commentsPreviewAvatar.visibility = View.GONE
        } else {
            commentsPreviewText.text = "Tap to read & post comments…"
            loadComments()
        }
    }

    private fun toggleDescription() {
        descriptionExpanded = !descriptionExpanded
        descView.maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2
        descView.ellipsize = if (descriptionExpanded) null else TextUtils.TruncateAt.END
        descToggle.text = if (descriptionExpanded) "Show less" else "Show more"
    }

    private fun renderRelated(items: List<VideoItem>) {
        relatedContainer.removeAllViews()
        if (items.isEmpty()) {
            relatedContainer.visibility = View.GONE
            return
        }
        relatedContainer.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        items.forEach { item ->
            val view = inflater.inflate(R.layout.item_video, relatedContainer, false)
            view.findViewById<TextView>(R.id.item_title).text = item.title
            view.findViewById<TextView>(R.id.item_meta).text =
                listOf(item.channel, item.views, item.published)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            val duration = view.findViewById<TextView>(R.id.item_duration)
            duration.text = item.duration
            duration.visibility = if (item.duration.isBlank()) View.GONE else View.VISIBLE
            view.findViewById<TextView>(R.id.item_badge).visibility = View.GONE
            if (item.thumbnail.isNotBlank()) {
                view.findViewById<ImageView>(R.id.item_thumb).load(item.thumbnail) { crossfade(150) }
            }
            val avatar = view.findViewById<ImageView>(R.id.item_avatar)
            avatar.makeCircular()
            if (item.avatar.isNotBlank()) {
                avatar.visibility = View.VISIBLE
                avatar.load(item.avatar) {
                    crossfade(150)
                    transformations(CircleCropTransformation())
                }
            } else {
                avatar.visibility = View.GONE
            }
            val more = view.findViewById<View>(R.id.item_more)
            more.visibility = View.VISIBLE
            more.setOnClickListener {
                showVideoOptionsSheet(
                    this,
                    lifecycleScope,
                    item,
                    onQueueChanged = { renderInlineQueue() },
                    onRemoved = { relatedContainer.removeView(view) }
                )
            }
            view.setOnClickListener { openVideo(item.id, item.title) }
            relatedContainer.addView(view)
        }
    }

    private fun openVideo(id: String, title: String) {
        if (id == videoId) return
        if (this::player.isInitialized && player.duration > 0L) {
            LocalStore.savePlaybackPosition(videoId, player.currentPosition, player.duration)
        }
        videoId = id
        selectedHeight = null
        selectedAudioKey = null
        resolveTimeMs = 0
        fallbackTried = false
        autoRetried = false
        watchChannelId = ""
        watchChannelName = ""
        watchChannelSubscribed = false
        streams = null
        commentToken = null
        initialCommentToken = null
        createCommentParams = null
        seenComments.clear()
        loadedComments.clear()
        sponsorSegments = emptyList()
        skippedSegments.clear()
        relatedContainer.removeAllViews()
        commentsCountView.text = ""
        commentsPreviewText.text = "Loading comments…"
        commentsPreviewAvatar.setImageDrawable(null)
        commentsPreviewAvatar.visibility = View.GONE
        descView.text = ""
        descView.maxLines = 2
        descriptionExpanded = false
        descToggle.text = "Show more"
        likeCountView.text = "…"
        dislikeCountView.text = "…"
        metaView.text = ""
        titleView.text = title
        channelView.text = ""
        channelSubsView.text = ""
        channelAvatarView.setImageDrawable(null)
        audioTrackButton.text = "Audio"
        videoLiked = LocalStore.isLiked(id)
        videoDisliked = LocalStore.isDisliked(id)
        updatePlayerSubscribeUi()
        updateLikeButtonsUi()
        findViewById<View>(R.id.player_scroll).scrollTo(0, 0)
        PlaybackSession.activate(id, title)
        LocalStore.recordVideoWatched(VideoItem(id = id, title = title))
        renderInlineQueue()
        load()
    }

    private fun animateAndMinimize() {
        if (fullscreen) {
            setFullscreenState(false)
            return
        }
        val targetY = resources.displayMetrics.heightPixels - dp(120).toFloat()
        playerRoot.animate()
            .translationY(targetY)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .withEndAction { minimizeImmediate() }
            .start()
    }

    private fun minimizeImmediate() {
        PlaybackSession.isPlayerForeground = false
        if (this::playerView.isInitialized) {
            // Release the TextureView surface before MainActivity attaches the mini player.
            // Otherwise both views briefly fight over the same player -> stutter/frozen frame.
            runCatching {
                playerView.player = null
                playerView.onPause()
            }
        }
        if (this::player.isInitialized && player.mediaItemCount > 0) {
            PlaybackSession.activate(
                videoId,
                titleView.text.toString(),
                watchChannelName
            )
        } else {
            PlaybackService.stop(this)
            PlaybackSession.stop()
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0) // kein schwarzes aufblitzen
    }

    private fun enterPipManual() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ok = runCatching {
                enterPictureInPictureMode(pipParams(autoEnter = false))
            }.getOrDefault(false)
            if (!ok) {
                toast("Picture-in-Picture is disabled on this device")
            }
        } else {
            animateAndMinimize()
        }
    }

    private fun initGoogleCast() {
        runCatching {
            castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(this)
            mediaRouter = androidx.mediarouter.media.MediaRouter.getInstance(this)
            castContext?.sessionManager?.addSessionManagerListener(
                castSessionListener,
                com.google.android.gms.cast.framework.CastSession::class.java
            )
        }
    }

    private fun showCastSheet() {
        val ctx = castContext
        val router = mediaRouter
        if (ctx == null || router == null) {
            toast("Google Cast unavailable on this device")
            return
        }
        val selector = ctx.mergedSelector ?: run {
            toast("Cast selector unavailable")
            return
        }

        val dialog = BottomSheetDialog(this, R.style.Theme_Nexus_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(18), dp(14), dp(18), dp(26))
        }
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_account_pill)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        })
        root.addView(TextView(this).apply {
            text = "Cast to device"
            setTextColor(getColor(R.color.nexus_white))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(10))
        })

        val devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesBox)

        fun refreshDevices() {
            devicesBox.removeAllViews()
            val routes = router.routes.filter {
                !it.isDefault && it.isEnabled && it.matchesSelector(selector)
            }
            if (routes.isEmpty()) {
                devicesBox.addView(TextView(this).apply {
                    text = "Searching for nearby Cast devices on Wi-Fi…"
                    setTextColor(getColor(R.color.nexus_text_muted))
                    textSize = 13f
                    setPadding(dp(4), dp(12), dp(4), dp(16))
                })
                return
            }
            routes.forEach { route ->
                val connected = route.isSelected
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(
                        if (connected) R.drawable.bg_primary_button else R.drawable.bg_card
                    )
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                    setOnClickListener {
                        if (connected) {
                            router.unselect(
                                androidx.mediarouter.media.MediaRouter.UNSELECT_REASON_STOPPED
                            )
                            toast("Disconnected from ${route.name}")
                        } else {
                            route.select()
                            toast("Connecting to ${route.name}…")
                        }
                        dialog.dismiss()
                    }
                }
                card.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_cast)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        getColor(if (connected) R.color.nexus_black else R.color.nexus_white)
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                        marginEnd = dp(12)
                    }
                })
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                col.addView(TextView(this).apply {
                    text = route.name
                    setTextColor(
                        getColor(if (connected) R.color.nexus_black else R.color.nexus_white)
                    )
                    textSize = 14.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                col.addView(TextView(this).apply {
                    text = if (connected) {
                        "Connected · Tap to stop"
                    } else {
                        route.description ?: "Ready to cast"
                    }
                    setTextColor(
                        getColor(if (connected) R.color.nexus_black else R.color.nexus_text_dim)
                    )
                    textSize = 11.5f
                })
                card.addView(col)
                devicesBox.addView(card)
            }
        }

        val scanCallback = object : androidx.mediarouter.media.MediaRouter.Callback() {
            override fun onRouteAdded(
                r: androidx.mediarouter.media.MediaRouter,
                info: androidx.mediarouter.media.MediaRouter.RouteInfo
            ) = refreshDevices()

            override fun onRouteRemoved(
                r: androidx.mediarouter.media.MediaRouter,
                info: androidx.mediarouter.media.MediaRouter.RouteInfo
            ) = refreshDevices()

            override fun onRouteChanged(
                r: androidx.mediarouter.media.MediaRouter,
                info: androidx.mediarouter.media.MediaRouter.RouteInfo
            ) = refreshDevices()
        }

        router.addCallback(
            selector,
            scanCallback,
            androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
        )
        dialog.setOnDismissListener { router.removeCallback(scanCallback) }

        refreshDevices()
        dialog.setContentView(root)
        dialog.show()
        expandBottomSheetFully(dialog)
    }

    private fun castCurrentVideoTo(
        session: com.google.android.gms.cast.framework.CastSession
    ) {
        val resolved = streams ?: run {
            toast("Stream still loading…")
            return
        }
        val progressiveUrl = resolved.bestProgressive(720)?.url
            ?: resolved.bestAdaptive(1080)?.url
        val streamUrl: String
        val contentType: String
        val streamType: Int
        if (progressiveUrl != null) {
            streamUrl = progressiveUrl
            contentType = "video/mp4"
            streamType = com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED
        } else if (resolved.hlsUrl.isNotBlank()) {
            streamUrl = resolved.hlsUrl
            contentType = "application/x-mpegURL"
            streamType = com.google.android.gms.cast.MediaInfo.STREAM_TYPE_LIVE
        } else {
            toast("No castable stream available")
            return
        }
        val metadata = com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE
        ).apply {
            putString(
                com.google.android.gms.cast.MediaMetadata.KEY_TITLE,
                titleView.text.toString()
            )
            putString(
                com.google.android.gms.cast.MediaMetadata.KEY_SUBTITLE,
                watchChannelName
            )
            addImage(
                com.google.android.gms.common.images.WebImage(
                    Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                )
            )
        }
        val mediaInfo = com.google.android.gms.cast.MediaInfo.Builder(streamUrl)
            .setStreamType(streamType)
            .setContentType(contentType)
            .setMetadata(metadata)
            .build()

        val pos = if (this::player.isInitialized) player.currentPosition.coerceAtLeast(0L) else 0L
        val req = com.google.android.gms.cast.MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(pos)
            .build()

        if (this::player.isInitialized) player.pause()
        session.remoteMediaClient?.load(req)
        toast("Casting to ${session.castDevice?.friendlyName ?: "device"}")
    }

    private fun toggleFullscreen() {
        setFullscreenState(!fullscreen)
    }

    private fun setFullscreenState(enable: Boolean) {
        fullscreen = enable
        val scroll = findViewById<View>(R.id.player_scroll)
        val controller = WindowInsetsControllerCompat(window, window.decorView)

        if (fullscreen) {
            scroll.visibility = View.GONE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())

            videoContainer.layoutParams = videoContainer.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            playerView.layoutParams = playerView.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            if (this::btnFullscreen.isInitialized) {
                btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit)
            }
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            controller.show(WindowInsetsCompat.Type.systemBars())
            scroll.visibility = View.VISIBLE
            setPortrait16by9Height()
            if (this::btnFullscreen.isInitialized) {
                btnFullscreen.setImageResource(R.drawable.ic_fullscreen)
            }
        }
        videoContainer.requestLayout()
        playerView.requestLayout()
    }

    private fun openChannel() {
        if (watchChannelId.isBlank() && watchChannelName.isBlank()) {
            toast("Loading channel…")
            return
        }
        if (this::player.isInitialized && player.mediaItemCount > 0) {
            PlaybackSession.activate(videoId, titleView.text.toString(), watchChannelName)
        }
        val intent = if (watchChannelId.isNotBlank()) {
            Intent(this, ListActivity::class.java)
                .putExtra(ListActivity.EXTRA_MODE, ListActivity.MODE_CHANNEL)
                .putExtra(ListActivity.EXTRA_BROWSE_ID, watchChannelId)
                .putExtra(ListActivity.EXTRA_TITLE, watchChannelName)
        } else {
            Intent(this, ListActivity::class.java)
                .putExtra(ListActivity.EXTRA_MODE, ListActivity.MODE_SEARCH)
                .putExtra(ListActivity.EXTRA_QUERY, watchChannelName)
                .putExtra(ListActivity.EXTRA_TITLE, watchChannelName)
        }
        startActivity(intent)
        finish()
    }

    private fun setupGestures() {
        playerView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    touchDownAt = System.currentTimeMillis()
                    lastMoveY = event.rawY
                    gestureMode = GESTURE_NONE
                    brightnessLevel = currentBrightness()
                    volumeAccumulator = 0f
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchDownX
                    val dy = event.rawY - touchDownY

                    if (gestureMode == GESTURE_NONE) {
                        if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.2f) {
                            gestureMode = if (!fullscreen && dy > 0) {
                                GESTURE_SWIPE_MINIMIZE
                            } else {
                                GESTURE_VERTICAL_HUD
                            }
                        } else if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            gestureMode = GESTURE_HORIZONTAL
                        }
                    }

                    when (gestureMode) {
                        GESTURE_SWIPE_MINIMIZE -> {
                            val drag = dy.coerceAtLeast(0f)
                            val progressFraction = (drag / (resources.displayMetrics.heightPixels * 0.55f))
                                .coerceIn(0f, 1f)
                            playerRoot.translationY = drag * 0.85f
                            val scale = 1f - progressFraction * 0.08f
                            playerRoot.scaleX = scale
                            playerRoot.scaleY = scale
                            playerRoot.alpha = 1f - progressFraction * 0.35f
                        }

                        GESTURE_VERTICAL_HUD -> {
                            val delta = lastMoveY - event.rawY
                            val height = view.height.coerceAtLeast(1)
                            if (touchDownX < view.width / 2f) {
                                applyBrightness(brightnessLevel + (delta / height) * 1.25f)
                                lastMoveY = event.rawY
                            } else {
                                adjustVolumeSmooth(delta / height)
                                lastMoveY = event.rawY
                            }
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - touchDownX
                    val dy = event.rawY - touchDownY
                    val elapsed = System.currentTimeMillis() - touchDownAt

                    when (gestureMode) {
                        GESTURE_SWIPE_MINIMIZE -> {
                            if (dy > dp(105) || (dy > dp(55) && elapsed < 260)) {
                                animateAndMinimize()
                            } else {
                                playerRoot.animate()
                                    .translationY(0f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .alpha(1f)
                                    .setDuration(180)
                                    .start()
                            }
                        }

                        GESTURE_VERTICAL_HUD -> {
                            hideSideHudsDelayed()
                        }

                        GESTURE_NONE -> {
                            if (elapsed < 260 && abs(dx) < touchSlop && abs(dy) < touchSlop) {
                                val localX = event.x
                                handleTap(localX, view.width)
                            }
                        }
                    }
                    gestureMode = GESTURE_NONE
                }
            }
            false
        }
    }

    private fun handleTap(x: Float, width: Int) {
        val now = System.currentTimeMillis()
        if (now - lastTapAt in 1..320) {
            lastTapAt = now
            when {
                x < width * 0.38f -> triggerYouTubeSkip(leftSide = true)
                x > width * 0.62f -> triggerYouTubeSkip(leftSide = false)
                else -> togglePlayPause()
            }
        } else {
            lastTapAt = now
        }
    }

    private fun installCustomControls() {
        playerView.post {
            runCatching {
                val basic = playerView.findViewById<LinearLayout>(
                    androidx.media3.ui.R.id.exo_basic_controls
                ) ?: return@post

                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                }
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                }

                basic.addView(
                    controlButton(R.drawable.ic_minimize, "Minimize video") {
                        animateAndMinimize()
                    }
                )
                basic.addView(
                    controlButton(R.drawable.ic_cast, "Cast to device") {
                        showCastSheet()
                    }
                )
                basic.addView(
                    controlButton(R.drawable.ic_popout, "Pop-Out player") {
                        enterPipManual()
                    }
                )
                basic.addView(
                    controlButton(R.drawable.ic_gear, "Settings") {
                        showSettingsSheet()
                    }
                )
                btnFullscreen = controlButton(R.drawable.ic_fullscreen, "Fullscreen") {
                    toggleFullscreen()
                }
                basic.addView(btnFullscreen)
            }
        }
    }

    private fun controlButton(
        iconRes: Int,
        description: String,
        onClick: () -> Unit
    ): ImageButton {
        val size = dp(40)
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)

        return ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            setBackgroundResource(tv.resourceId)
            imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.nexus_white)
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(9)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }
    }

    private fun triggerYouTubeSkip(leftSide: Boolean) {
        if (!this::player.isInitialized) return
        val side = if (leftSide) -1 else 1
        if (lastSkipSide == side) {
            accumulatedSkipSec += 10
        } else {
            accumulatedSkipSec = 10
            lastSkipSide = side
        }

        val deltaMs = side * 10_000L
        val duration = player.duration
        val target = if (duration > 0) {
            (player.currentPosition + deltaMs).coerceIn(0, duration)
        } else {
            (player.currentPosition + deltaMs).coerceAtLeast(0)
        }
        player.seekTo(target)

        val overlay = if (leftSide) skipLeftOverlay else skipRightOverlay
        val label = if (leftSide) skipLeftText else skipRightText
        val other = if (leftSide) skipRightOverlay else skipLeftOverlay
        other.visibility = View.GONE

        label.text = if (leftSide) "«\n−${accumulatedSkipSec}s" else "»\n+${accumulatedSkipSec}s"
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0.25f
        overlay.scaleX = 0.92f
        overlay.scaleY = 0.92f
        overlay.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140)
            .start()

        uiHandler.removeCallbacks(hideSkipRippleRunnable)
        uiHandler.postDelayed(hideSkipRippleRunnable, 650)
    }

    private val hideSkipRippleRunnable = Runnable {
        accumulatedSkipSec = 0
        lastSkipSide = 0
        if (this::skipLeftOverlay.isInitialized) {
            skipLeftOverlay.animate().alpha(0f).setDuration(180).withEndAction {
                skipLeftOverlay.visibility = View.GONE
            }.start()
        }
        if (this::skipRightOverlay.isInitialized) {
            skipRightOverlay.animate().alpha(0f).setDuration(180).withEndAction {
                skipRightOverlay.visibility = View.GONE
            }.start()
        }
    }

    private val hideSideHudsRunnable = Runnable {
        if (this::hudBrightnessLeft.isInitialized) {
            hudBrightnessLeft.animate().alpha(0f).setDuration(180).withEndAction {
                hudBrightnessLeft.visibility = View.GONE
            }.start()
        }
        if (this::hudVolumeRight.isInitialized) {
            hudVolumeRight.animate().alpha(0f).setDuration(180).withEndAction {
                hudVolumeRight.visibility = View.GONE
            }.start()
        }
    }

    private fun hideSideHudsDelayed() {
        uiHandler.removeCallbacks(hideSideHudsRunnable)
        uiHandler.postDelayed(hideSideHudsRunnable, 750)
    }

    private fun showLeftBrightnessHud(percent: Int) {
        uiHandler.removeCallbacks(hideSideHudsRunnable)
        hudVolumeRight.visibility = View.GONE
        hudBrightnessBar.progress = percent.coerceIn(0, 100)
        hudBrightnessText.text = "${percent.coerceIn(0, 100)}%"
        if (hudBrightnessLeft.visibility != View.VISIBLE) {
            hudBrightnessLeft.alpha = 0f
            hudBrightnessLeft.visibility = View.VISIBLE
            hudBrightnessLeft.animate().alpha(1f).setDuration(120).start()
        }
    }

    private fun showRightVolumeHud(percent: Int) {
        uiHandler.removeCallbacks(hideSideHudsRunnable)
        hudBrightnessLeft.visibility = View.GONE
        hudVolumeBar.progress = percent.coerceIn(0, 100)
        hudVolumeText.text = "${percent.coerceIn(0, 100)}%"
        runCatching {
            findViewById<ImageView>(R.id.hud_volume_icon).setImageResource(
                if (percent <= 0) R.drawable.ic_volume_mute else R.drawable.ic_volume
            )
        }
        if (hudVolumeRight.visibility != View.VISIBLE) {
            hudVolumeRight.alpha = 0f
            hudVolumeRight.visibility = View.VISIBLE
            hudVolumeRight.animate().alpha(1f).setDuration(120).start()
        }
    }

    private fun togglePlayPause() {
        if (!this::player.isInitialized) return
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun skipNext() {
        val next = PlaybackSession.popNextInQueue() ?: lastRelated.firstOrNull()
        if (next == null) {
            toast("Queue is empty")
            return
        }
        toast("Playing next: ${next.title}")
        openVideo(next.id, next.title)
    }

    private fun skipPrevious() {
        if (this::player.isInitialized && player.currentPosition > 3_500L) {
            player.seekTo(0L)
            return
        }
        val prev = PlaybackSession.popPrevious()
        if (prev == null) {
            if (this::player.isInitialized) player.seekTo(0L)
            return
        }
        openVideo(prev.id, prev.title)
    }

    private fun updatePlayerSubscribeUi() {
        val btn = findViewById<TextView>(R.id.btn_player_subscribe)
        btn.text = if (watchChannelSubscribed) "Subscribed" else "Subscribe"
        btn.setBackgroundResource(
            if (watchChannelSubscribed) R.drawable.bg_account_pill
            else R.drawable.bg_primary_button
        )
        btn.setTextColor(
            getColor(if (watchChannelSubscribed) R.color.nexus_white else R.color.nexus_black)
        )
    }

    private fun handleVideoLikeClick(like: Boolean) {
        val prevLiked = videoLiked
        val prevDisliked = videoDisliked

        if (like) {
            videoLiked = !videoLiked
            if (videoLiked) videoDisliked = false
        } else {
            videoDisliked = !videoDisliked
            if (videoDisliked) videoLiked = false
        }

        LocalStore.setVideoLikeState(videoId, videoLiked, videoDisliked)
        updateLikeButtonsUi()

        if (!Session.isLoggedIn) {
            toast(
                when {
                    videoLiked -> "Saved locally · Sign in to sync likes"
                    videoDisliked -> "Disliked locally"
                    else -> "Rating cleared"
                }
            )
            return
        }

        val targetLiked = videoLiked
        val targetDisliked = videoDisliked
        val targetId = videoId
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                when {
                    targetLiked -> InnerTube.likeVideo(targetId)
                    targetDisliked -> InnerTube.dislikeVideo(targetId)
                    else -> InnerTube.removeLike(targetId)
                }
            }
            if (ok) {
                InnerTube.clearCache()
                toast(
                    when {
                        targetLiked -> "Liked video"
                        targetDisliked -> "Disliked video"
                        else -> "Removed rating"
                    }
                )
            } else {
                videoLiked = prevLiked
                videoDisliked = prevDisliked
                LocalStore.setVideoLikeState(targetId, prevLiked, prevDisliked)
                updateLikeButtonsUi()
                toast("Could not update rating on YouTube")
            }
        }
    }

    private fun updateLikeButtonsUi() {
        val likePill = findViewById<View>(R.id.action_like)
        val dislikePill = findViewById<View>(R.id.action_dislike)

        likePill.setBackgroundResource(
            if (videoLiked) R.drawable.bg_primary_button else R.drawable.bg_account_pill
        )
        val likeFg = getColor(if (videoLiked) R.color.nexus_black else R.color.nexus_white)
        likeCountView.setTextColor(likeFg)
        likeIconView.imageTintList = android.content.res.ColorStateList.valueOf(likeFg)

        dislikePill.setBackgroundResource(
            if (videoDisliked) R.drawable.bg_primary_button else R.drawable.bg_account_pill
        )
        val dislikeFg = getColor(if (videoDisliked) R.color.nexus_black else R.color.nexus_white)
        dislikeCountView.setTextColor(dislikeFg)
        dislikeIconView.imageTintList = android.content.res.ColorStateList.valueOf(dislikeFg)
    }

    private fun currentBrightness(): Float {
        val attr = window.attributes.screenBrightness
        if (attr >= 0f) return attr
        return runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.05f, 1f)
    }

    private fun applyBrightness(value: Float) {
        brightnessLevel = value.coerceIn(0.05f, 1f)
        val attrs = window.attributes
        attrs.screenBrightness = brightnessLevel
        window.attributes = attrs
        showLeftBrightnessHud((brightnessLevel * 100).toInt())
    }

    private fun adjustVolumeSmooth(deltaFraction: Float) {
        val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeAccumulator += deltaFraction * max * 1.35f
        val step = volumeAccumulator.toInt()
        val target = (current + step).coerceIn(0, max)
        if (step != 0) {
            volumeAccumulator -= step
            runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        }
        val pct = ((target.toFloat() / max.toFloat()) * 100f).toInt()
        showRightVolumeHud(pct)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun loadComments(onLoaded: (() -> Unit)? = null) {
        val token = commentToken ?: run {
            onLoaded?.invoke()
            return
        }
        if (commentsLoading) return
        commentsLoading = true

        lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching { InnerTube.comments(token) }.getOrNull()
            }
            commentsLoading = false

            if (page != null) {
                page.items.forEach { c ->
                    val key = c.id.ifBlank { c.author + "|" + c.text }
                    if (seenComments.add(key)) loadedComments.add(c)
                }
                if (!page.createCommentParams.isNullOrBlank()) {
                    createCommentParams = page.createCommentParams
                }
                commentToken = page.continuation
            }
            updateCommentsPreviewCard()
            onLoaded?.invoke()
        }
    }

    private fun updateCommentsPreviewCard() {
        val top = loadedComments.firstOrNull()
        if (top != null) {
            commentsPreviewText.text = top.text
            if (top.avatar.isNotBlank()) {
                commentsPreviewAvatar.visibility = View.VISIBLE
                commentsPreviewAvatar.load(top.avatar) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
            } else {
                commentsPreviewAvatar.visibility = View.GONE
            }
        } else {
            commentsPreviewAvatar.visibility = View.GONE
            commentsPreviewText.text =
                if (commentToken.isNullOrBlank()) "No comments yet · Tap to add one"
                else "Tap to open comments"
        }
    }

    private fun showCommentsBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_Nexus_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(16), dp(12), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.78f).toInt()
            )
        }

        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_account_pill)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        header.addView(TextView(this).apply {
            val cnt = commentsCountView.text.toString()
            text = if (cnt.isNotBlank()) "COMMENTS · $cnt" else "COMMENTS"
            letterSpacing = 0.14f
            setTextColor(getColor(R.color.nexus_white))
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"
            setTextColor(getColor(R.color.nexus_text_muted))
            textSize = 16f
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        // Composer at the top -> immediately reachable without scrolling.
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val input = EditText(this).apply {
            background = null
            hint = if (Session.isLoggedIn) "Write a comment…" else "Sign in to comment"
            setTextColor(getColor(R.color.nexus_white))
            setHintTextColor(getColor(R.color.nexus_text_dim))
            textSize = 13.5f
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendBtn = TextView(this).apply {
            text = "POST"
            letterSpacing = 0.1f
            setTextColor(getColor(R.color.nexus_black))
            textSize = 11.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_primary_button)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        composer.addView(input)
        composer.addView(sendBtn)
        root.addView(composer)

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val statusTv = TextView(this).apply {
            setTextColor(getColor(R.color.nexus_text_dim))
            textSize = 12.5f
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        val loadMoreBtn = Button(this, null, 0, R.style.NexusButton).apply {
            text = "Load more comments"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { topMargin = dp(10) }
            visibility = View.GONE
        }
        val scrollInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(listBox)
            addView(statusTv)
            addView(loadMoreBtn)
        }
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(scrollInner)
        }
        root.addView(scroll)

        fun renderList() {
            listBox.removeAllViews()
            loadedComments.forEachIndexed { idx, comment ->
                listBox.addView(inflateInteractiveComment(comment) { updated ->
                    if (idx in loadedComments.indices) loadedComments[idx] = updated
                })
            }
            statusTv.text = when {
                commentsLoading -> "Loading comments…"
                loadedComments.isEmpty() -> "No comments yet."
                else -> ""
            }
            statusTv.visibility = if (statusTv.text.isBlank()) View.GONE else View.VISIBLE
            loadMoreBtn.visibility = if (commentToken.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        loadMoreBtn.setOnClickListener {
            loadMoreBtn.isEnabled = false
            loadComments {
                loadMoreBtn.isEnabled = true
                renderList()
            }
        }

        sendBtn.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            if (!Session.isLoggedIn) {
                toast("Sign in with Google to post comments")
                runCatching { startActivity(Intent(this, LoginActivity::class.java)) }
                return@setOnClickListener
            }
            val params = createCommentParams ?: InnerTube.commentParamsFor(videoId)
            if (params.isBlank() || postingComment) return@setOnClickListener

            postingComment = true
            sendBtn.isEnabled = false
            sendBtn.text = "…"
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching { InnerTube.postComment(params, text) }
                }
                postingComment = false
                sendBtn.isEnabled = true
                sendBtn.text = "POST"
                res.onSuccess {
                    input.setText("")
                    toast("Comment posted")
                    loadedComments.add(
                        0,
                        Comment(
                            author = Session.accountName ?: "You",
                            avatar = Session.accountAvatar.orEmpty(),
                            text = text,
                            published = "Just now",
                            likes = "0"
                        )
                    )
                    updateCommentsPreviewCard()
                    renderList()
                }.onFailure { err ->
                    toast("Failed to post: ${err.message}")
                }
            }
        }

        renderList()
        if (loadedComments.isEmpty() && !commentToken.isNullOrBlank()) {
            loadComments { renderList() }
        }

        dialog.setContentView(root)
        dialog.show()
        expandBottomSheetFully(dialog)
    }

    private fun inflateInteractiveComment(
        initial: Comment,
        onStateChanged: (Comment) -> Unit
    ): View {
        var current = initial
        val view = LayoutInflater.from(this).inflate(R.layout.item_comment, null, false)

        view.findViewById<TextView>(R.id.comment_author).text = current.author
        view.findViewById<TextView>(R.id.comment_published).text = current.published
        view.findViewById<TextView>(R.id.comment_text).text = current.text

        val avatar = view.findViewById<ImageView>(R.id.comment_avatar)
        avatar.makeCircular()
        if (current.avatar.isNotBlank()) {
            avatar.load(current.avatar) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        }

        val likeBtn = view.findViewById<View>(R.id.comment_like_btn)
        val likeIcon = view.findViewById<ImageView>(R.id.comment_like_icon)
        val likesTv = view.findViewById<TextView>(R.id.comment_likes)
        val dislikeBtn = view.findViewById<View>(R.id.comment_dislike_btn)
        val dislikeIcon = view.findViewById<ImageView>(R.id.comment_dislike_icon)

        fun renderReaction() {
            val activeTint = getColor(R.color.nexus_white)
            val mutedTint = getColor(R.color.nexus_text_dim)
            likeIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (current.isLiked) activeTint else mutedTint
            )
            likeIcon.alpha = if (current.isLiked) 1f else 0.65f
            likesTv.setTextColor(if (current.isLiked) activeTint else mutedTint)
            likesTv.text = when {
                current.isLiked && current.likesWhenLiked.isNotBlank() -> current.likesWhenLiked
                current.isLiked && current.likes.isBlank() -> "1"
                else -> current.likes
            }

            dislikeIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (current.isDisliked) activeTint else mutedTint
            )
            dislikeIcon.alpha = if (current.isDisliked) 1f else 0.65f
        }
        renderReaction()

        likeBtn.setOnClickListener {
            if (!Session.isLoggedIn) {
                toast("Sign in to like comments")
                return@setOnClickListener
            }
            val action = if (current.isLiked) current.unlikeAction else current.likeAction
            if (action.isBlank()) {
                toast("Comment action unavailable")
                return@setOnClickListener
            }
            val prev = current
            current = current.copy(
                isLiked = !current.isLiked,
                isDisliked = if (!current.isLiked) false else current.isDisliked
            )
            renderReaction()
            onStateChanged(current)

            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { InnerTube.performCommentAction(action) }
                if (!ok) {
                    current = prev
                    renderReaction()
                    onStateChanged(prev)
                    toast("Could not like comment")
                }
            }
        }

        dislikeBtn.setOnClickListener {
            if (!Session.isLoggedIn) {
                toast("Sign in to react to comments")
                return@setOnClickListener
            }
            val action = if (current.isDisliked) current.undislikeAction else current.dislikeAction
            if (action.isBlank()) return@setOnClickListener
            val prev = current
            current = current.copy(
                isDisliked = !current.isDisliked,
                isLiked = if (!current.isDisliked) false else current.isLiked
            )
            renderReaction()
            onStateChanged(current)

            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { InnerTube.performCommentAction(action) }
                if (!ok) {
                    current = prev
                    renderReaction()
                    onStateChanged(prev)
                }
            }
        }

        return view
    }

    private fun showAudioTrackSheet() {
        val resolved = streams ?: run {
            toast("Audio tracks are loading…")
            return
        }
        val tracks = resolved.distinctAudioTracks
        if (tracks.isEmpty()) {
            toast("Only one audio track available")
            return
        }

        val dialog = BottomSheetDialog(this, R.style.Theme_Nexus_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(18), dp(14), dp(18), dp(26))
        }
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_account_pill)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        })
        root.addView(TextView(this).apply {
            text = "AUDIO TRACK · ORIGINAL / DUB"
            letterSpacing = 0.14f
            setTextColor(getColor(R.color.nexus_white))
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), 0, dp(2), dp(10))
        })

        val currentTrack = resolved.bestOriginalAudio(selectedAudioKey)
        tracks.forEach { track ->
            val active = track.trackKey == currentTrack?.trackKey
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(if (active) R.drawable.bg_primary_button else R.drawable.bg_card)
                setPadding(dp(14), dp(13), dp(14), dp(13))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setOnClickListener {
                    selectedAudioKey = track.trackKey
                    dialog.dismiss()
                    play()
                    toast("Audio: ${track.displayAudioName}")
                }
            }
            val fg = getColor(if (active) R.color.nexus_black else R.color.nexus_white)
            row.addView(TextView(this).apply {
                text = track.displayAudioName
                setTextColor(fg)
                textSize = 13.5f
                setTypeface(
                    typeface,
                    if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (active) {
                row.addView(TextView(this).apply {
                    text = "ACTIVE"
                    letterSpacing = 0.1f
                    setTextColor(fg)
                    textSize = 10.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
            }
            root.addView(row)
        }

        dialog.setContentView(android.widget.ScrollView(this).apply { addView(root) })
        dialog.show()
        expandBottomSheetFully(dialog)
    }

    private fun play(startPositionMs: Long? = null) {
        val resolved = streams ?: return
        val position = startPositionMs
            ?: if (this::player.isInitialized) player.currentPosition.coerceAtLeast(0L) else 0L
        val maxHeight = selectedHeight ?: 720
        val built = buildSource(resolved, maxHeight) ?: run {
            statusView.text = "No playable stream found."
            return
        }

        usedAdaptive = built.adaptive
        fallbackTried = false
        player.stop()
        player.clearMediaItems()
        player.setMediaSource(built.source, position)
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        player.prepare()
        player.playWhenReady = true
        PlaybackService.start(this)

        statusView.text = "Loading… ${currentAudioLabel()}"
        qualityButton.text = buildString {
            append(selectedHeight?.let { "${it}p" } ?: "Auto")
            if (playbackSpeed != 1.0f) append(" · ${playbackSpeed}x")
        }
        audioTrackButton.text = currentAudioLabel()
    }

    private fun playProgressive(choice: StreamChoice, positionMs: Long = 0L) {
        usedAdaptive = false
        player.stop()
        player.clearMediaItems()
        player.setMediaSource(source(choice.url), positionMs)
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        player.prepare()
        player.playWhenReady = true
        PlaybackService.start(this)
        statusView.text = "Quality: ${choice.height}p · Stable direct stream"
    }

    private data class ChosenSource(val source: MediaSource, val adaptive: Boolean)

    private fun smartLoadErrorPolicy() = object : DefaultLoadErrorHandlingPolicy(2) {
        override fun getRetryDelayMsFor(
            loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
        ): Long {
            val err = loadErrorInfo.exception
            // On 403/410 abort immediately instead of retrying 8x; onPlayerError refreshes the URL.
            if (err is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException &&
                (err.responseCode == 403 || err.responseCode == 410 || err.responseCode == 404)
            ) {
                return C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private fun buildSource(resolved: Resolved, maxHeight: Int): ChosenSource? {
        if (resolved.hlsUrl.isNotBlank()) {
            val hlsSource = androidx.media3.exoplayer.hls.HlsMediaSource
                .Factory(dataSourceFactory(resolved.hlsUrl))
                .setLoadErrorHandlingPolicy(smartLoadErrorPolicy())
                .createMediaSource(MediaItem.fromUri(resolved.hlsUrl))
            return ChosenSource(hlsSource, false)
        }

        val adaptive = resolved.bestAdaptive(maxHeight)
        val audio = resolved.bestOriginalAudio(selectedAudioKey)
        val progressive = resolved.bestProgressive(maxHeight)

        return when {
            adaptive != null && audio != null ->
                ChosenSource(
                    MergingMediaSource(source(adaptive.url), source(audio.url)),
                    true
                )
            progressive != null -> ChosenSource(source(progressive.url), false)
            audio != null -> ChosenSource(source(audio.url), false)
            else -> null
        }
    }

    private fun source(url: String): MediaSource =
        ProgressiveMediaSource.Factory(
            com.nexus.videoclient.playback.ChunkedYouTubeDataSource.Factory(
                upstreamFactory = dataSourceFactory(url),
                chunkSize = 4L * 1024L * 1024L
            )
        )
            .setContinueLoadingCheckIntervalBytes(1024 * 1024)
            .setLoadErrorHandlingPolicy(smartLoadErrorPolicy())
            .createMediaSource(MediaItem.fromUri(url))

    private fun dataSourceFactory(url: String): DataSource.Factory {
        val (ua, headers) = YtExtractor.headersForStreamUrl(url)
        return OkHttpDataSource.Factory(httpClient)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun renderInlineQueue() {
        val card = findViewById<View>(R.id.inline_queue_card)
        val title = findViewById<TextView>(R.id.inline_queue_title)
        val clearBtn = findViewById<View>(R.id.btn_inline_queue_clear)
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(
            R.id.inline_queue_recycler
        )
        val items = PlaybackSession.getQueue().toMutableList()

        if (items.isEmpty()) {
            card.visibility = View.GONE
            updateSkipButtons()
            return
        }
        card.visibility = View.VISIBLE
        title.text = "Queue · ${items.size} up next"
        clearBtn.setOnClickListener {
            PlaybackSession.clearQueue()
            renderInlineQueue()
            toast("Queue cleared")
        }

        val queueAdapter =
            object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(
                    parent: ViewGroup,
                    viewType: Int
                ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                    val row = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(6), dp(8), dp(6), dp(8))
                        layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    val thumb = ImageView(parent.context).apply {
                        id = View.generateViewId()
                        tag = "thumb"
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(getColor(R.color.nexus_pill))
                        layoutParams = LinearLayout.LayoutParams(dp(76), dp(43)).apply {
                            marginEnd = dp(10)
                        }
                    }
                    val col = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    }
                    col.addView(TextView(parent.context).apply {
                        tag = "title"
                        setTextColor(getColor(R.color.nexus_white))
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    col.addView(TextView(parent.context).apply {
                        tag = "channel"
                        setTextColor(getColor(R.color.nexus_text_dim))
                        textSize = 11.5f
                        maxLines = 1
                    })
                    val del = TextView(parent.context).apply {
                        tag = "del"
                        text = "✕"
                        setTextColor(getColor(R.color.nexus_text_muted))
                        textSize = 14f
                        setPadding(dp(10), dp(6), dp(4), dp(6))
                    }
                    row.addView(thumb)
                    row.addView(col)
                    row.addView(del)
                    return object :
                        androidx.recyclerview.widget.RecyclerView.ViewHolder(row) {}
                }

                override fun getItemCount(): Int = items.size

                override fun onBindViewHolder(
                    holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    pos: Int
                ) {
                    val item = items[pos]
                    val row = holder.itemView
                    row.findViewWithTag<ImageView>("thumb")
                        .load(item.thumbnail) { crossfade(100) }
                    row.findViewWithTag<TextView>("title").text = item.title
                    row.findViewWithTag<TextView>("channel").text = item.channel
                    row.setOnClickListener {
                        PlaybackSession.removeFromQueue(item.id)
                        openVideo(item.id, item.title)
                    }
                    row.findViewWithTag<View>("del").setOnClickListener {
                        PlaybackSession.removeFromQueue(item.id)
                        renderInlineQueue()
                    }
                }
            }

        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rv.adapter = queueAdapter

        androidx.recyclerview.widget.ItemTouchHelper(object :
            androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                0,
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                    androidx.recyclerview.widget.ItemTouchHelper.RIGHT
            ) {
            override fun onMove(
                r: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                t: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                dir: Int
            ) {
                val idx = vh.bindingAdapterPosition
                if (idx in items.indices) {
                    PlaybackSession.removeFromQueue(items[idx].id)
                    renderInlineQueue()
                }
            }
        }).attachToRecyclerView(rv)

        updateSkipButtons()
    }

    private fun showQueueSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_Nexus_BottomSheet)
        val queue = PlaybackSession.getQueue()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_account_pill)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Up Next Queue (${queue.size})"
            setTextColor(getColor(R.color.nexus_white))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })
        if (queue.isNotEmpty()) {
            header.addView(TextView(this).apply {
                text = "Clear"
                setTextColor(getColor(R.color.nexus_text_muted))
                textSize = 13f
                setPadding(dp(10), dp(6), dp(4), dp(6))
                setOnClickListener {
                    PlaybackSession.clearQueue()
                    dialog.dismiss()
                    toast("Queue cleared")
                }
            })
        }
        root.addView(header)

        if (queue.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Your queue is empty. Tap ⋮ on any video and choose \"Add to Queue\"."
                setTextColor(getColor(R.color.nexus_text_muted))
                textSize = 13f
                setPadding(0, dp(14), 0, dp(12))
            })
        } else {
            queue.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                    setOnClickListener {
                        PlaybackSession.removeFromQueue(item.id)
                        dialog.dismiss()
                        openVideo(item.id, item.title)
                    }
                }
                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                textCol.addView(TextView(this).apply {
                    text = item.title
                    setTextColor(getColor(R.color.nexus_white))
                    textSize = 13.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                textCol.addView(TextView(this).apply {
                    text = item.channel
                    setTextColor(getColor(R.color.nexus_text_dim))
                    textSize = 11.5f
                })
                val removeBtn = TextView(this).apply {
                    text = "✕"
                    setTextColor(getColor(R.color.nexus_text_muted))
                    textSize = 14f
                    setPadding(dp(12), dp(6), dp(4), dp(6))
                    setOnClickListener {
                        PlaybackSession.removeFromQueue(item.id)
                        dialog.dismiss()
                        showQueueSheet()
                    }
                }
                row.addView(textCol)
                row.addView(removeBtn)
                root.addView(row)
            }
        }

        dialog.setContentView(android.widget.ScrollView(this).apply { addView(root) })
        dialog.show()
        expandBottomSheetFully(dialog)
    }

    private fun expandBottomSheetFully(dialog: BottomSheetDialog) {
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.skipCollapsed = true
            behavior.peekHeight = resources.displayMetrics.heightPixels
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun showSettingsSheet() {
        val resolved = streams
        if (resolved == null) {
            toast("Settings are available once the video has loaded.")
            return
        }

        val dialog = BottomSheetDialog(this, R.style.Theme_Nexus_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }

        val handle = View(this).apply {
            setBackgroundResource(R.drawable.bg_account_pill)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        }
        root.addView(handle)

        fun addSectionTitle(title: String) {
            root.addView(TextView(this).apply {
                text = title
                setTextColor(getColor(R.color.nexus_text_dim))
                textSize = 11.5f
                letterSpacing = 0.12f
                setPadding(dp(4), dp(12), dp(4), dp(8))
            })
        }

        addSectionTitle("VIDEO QUALITY")
        val qualityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val qualityScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(qualityRow)
        }
        val heights = listOf(null) + resolved.heights
        heights.forEach { h ->
            val active = selectedHeight == h
            val pill = TextView(this).apply {
                text = h?.let { "${it}p" } ?: "Auto (720p)"
                setTextColor(getColor(if (active) R.color.nexus_black else R.color.nexus_white))
                textSize = 13f
                setBackgroundResource(
                    if (active) R.drawable.bg_primary_button else R.drawable.bg_account_pill
                )
                setPadding(dp(16), dp(9), dp(16), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener {
                    selectedHeight = h
                    dialog.dismiss()
                    play()
                }
            }
            qualityRow.addView(pill)
        }
        root.addView(qualityScroll)

        addSectionTitle("PLAYBACK SPEED")
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val speedScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(speedRow)
        }
        SPEED_STEPS.forEach { spd ->
            val active = abs(playbackSpeed - spd) < 0.01f
            val pill = TextView(this).apply {
                text = if (spd == 1.0f) "Normal (1x)" else "${spd}x"
                setTextColor(getColor(if (active) R.color.nexus_black else R.color.nexus_white))
                textSize = 13f
                setBackgroundResource(
                    if (active) R.drawable.bg_primary_button else R.drawable.bg_account_pill
                )
                setPadding(dp(14), dp(9), dp(14), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener {
                    playbackSpeed = spd
                    player.playbackParameters = PlaybackParameters(spd)
                    qualityButton.text = buildString {
                        append(selectedHeight?.let { "${it}p" } ?: "Auto")
                        if (playbackSpeed != 1.0f) append(" · ${playbackSpeed}x")
                    }
                    dialog.dismiss()
                    toast("Speed: ${spd}x")
                }
            }
            speedRow.addView(pill)
        }
        root.addView(speedScroll)

        addSectionTitle("AUDIO TRACK (ORIGINAL DEFAULT)")
        val tracks = resolved.distinctAudioTracks
        val currentTrack = resolved.bestOriginalAudio(selectedAudioKey)
        tracks.forEach { track ->
            val active = track.trackKey == currentTrack?.trackKey
            val labelText = when {
                track.isOriginalAudio ->
                    "✓ ${track.displayAudioName}"
                track.isDubbedAudio ->
                    "${track.displayAudioName} (Dubbed)"
                else -> track.displayAudioName
            }
            val row = TextView(this).apply {
                text = labelText
                setTextColor(getColor(if (active) R.color.nexus_black else R.color.nexus_white))
                textSize = 13.5f
                setBackgroundResource(
                    if (active) R.drawable.bg_primary_button else R.drawable.bg_card
                )
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                setOnClickListener {
                    selectedAudioKey = track.trackKey
                    dialog.dismiss()
                    play()
                    toast("Audio: ${track.displayAudioName}")
                }
            }
            root.addView(row)
        }

        // Viewer tools: SponsorBlock + Sleep Timer
        addSectionTitle("VIEWER TOOLS")
        val sbEnabled = LocalStore.isSponsorBlockEnabled()
        root.addView(TextView(this).apply {
            text = if (sbEnabled) "SponsorBlock Auto-Skip · ON" else "SponsorBlock Auto-Skip · OFF"
            setTextColor(getColor(if (sbEnabled) R.color.nexus_black else R.color.nexus_white))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(if (sbEnabled) R.drawable.bg_primary_button else R.drawable.bg_card)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnClickListener {
                val next = !LocalStore.isSponsorBlockEnabled()
                LocalStore.setSponsorBlockEnabled(next)
                dialog.dismiss()
                toast(if (next) "SponsorBlock enabled" else "SponsorBlock disabled")
            }
        })

        val sleepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        listOf(0 to "Off", 15 to "15m", 30 to "30m", 60 to "60m").forEach { (mins, label) ->
            val isSet = (mins == 0 && sleepStopAtMs == 0L)
            sleepRow.addView(TextView(this).apply {
                text = if (mins == 0) "Sleep: Off" else label
                setTextColor(getColor(if (isSet) R.color.nexus_black else R.color.nexus_white))
                textSize = 12f
                setBackgroundResource(if (isSet) R.drawable.bg_primary_button else R.drawable.bg_account_pill)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener {
                    sleepStopAtMs = if (mins == 0) 0L else System.currentTimeMillis() + mins * 60_000L
                    dialog.dismiss()
                    toast(if (mins == 0) "Sleep timer off" else "Sleep timer set: $mins min")
                }
            })
        }
        root.addView(sleepRow)

        val scrollWrapper = android.widget.ScrollView(this).apply {
            addView(root)
        }
        dialog.setContentView(scrollWrapper)
        dialog.show()
        expandBottomSheetFully(dialog)
    }

    private fun downloadVideo() {
        val resolved = streams ?: run {
            toast("Streams are still loading.")
            return
        }

        val heights = LinkedHashSet<Int>()
        resolved.progressive
            .filter { it.suffix == "mp4" && it.height > 0 }
            .sortedByDescending { it.height }
            .forEach { heights.add(it.height) }
        resolved.videoOnly
            .filter { it.height > 0 }
            .sortedByDescending { it.height }
            .forEach { heights.add(it.height) }

        if (heights.isEmpty()) {
            toast("No video track is available for this video.")
            return
        }

        val list = heights.sortedDescending()
        val labels = list.mapIndexed { index, h ->
            val container = videoContainerLabel(resolved, h)
            val prefix = if (index == 0) "Highest · " else ""
            "$prefix${h}p · $container"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Download MP4 (video)")
            .setItems(labels) { _, which -> startVideoDownload(resolved, list[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun videoContainerLabel(resolved: Resolved, height: Int): String {
        val hasH264 = resolved.videoOnly.any {
            it.height == height && it.itag in H264_ITAGS
        }
        val hasMp4Progressive = resolved.progressive.any {
            it.height == height && it.suffix == "mp4"
        }
        return if (hasH264 || hasMp4Progressive) "MP4" else "WebM"
    }

    private fun startVideoDownload(resolved: Resolved, height: Int) {
        val progressive = resolved.progressive
            .filter { it.suffix == "mp4" && it.height == height }
            .maxByOrNull { it.bitrate }
        val anyProgressiveFallback = resolved.bestProgressive(720)
        val video = pickVideo(resolved, height)
        val audio = resolved.bestOriginalAudio(selectedAudioKey) ?: pickAudio(resolved, video)

        toast("Download started – progress is shown in the status bar!")
        progress.visibility = View.VISIBLE
        statusView.text = "MP4 download 0% (see status bar)"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    MediaDownloader.downloadVideoWithNotification(
                        context = this@PlayerActivity,
                        video = progressive ?: video,
                        audio = audio,
                        progressiveFallback = progressive ?: anyProgressiveFallback,
                        baseName = safeName("${resolved.title} [${height}p]")
                    ) { percent ->
                        runOnUiThread {
                            statusView.text = "MP4 download $percent% · Folder: Downloads/BlackTube"
                        }
                    }
                }
            }
            progress.visibility = View.GONE
            result.onSuccess { saved ->
                statusView.text = "Saved to: ${saved.folderPath}"
                toast("Saved to: ${saved.folderPath}")
            }.onFailure { error ->
                statusView.text = "Download failed: ${error.message}"
                toast("Download failed: ${error.message}")
            }
        }
    }

    private fun pickVideo(resolved: Resolved, height: Int): StreamChoice? {
        val candidates = resolved.videoOnly.filter { it.height == height }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareByDescending<StreamChoice> { it.itag in H264_ITAGS }
                .thenByDescending { it.mime.contains("mp4") }
                .thenByDescending { it.bitrate }
        ).first()
    }

    private fun pickAudio(resolved: Resolved, video: StreamChoice?): StreamChoice? {
        val wantsAac = video == null || video.mime.contains("mp4")
        val matching = resolved.audio.filter {
            !it.isDubbedAudio && (if (wantsAac) it.mime.contains("mp4") else it.mime.contains("webm"))
        }
        return (matching.ifEmpty { resolved.audio }).maxByOrNull { it.bitrate }
    }

    private fun downloadAudio() {
        val resolved = streams ?: run {
            toast("Streams are still loading.")
            return
        }
        val source = pickMp3Source(resolved)
        if (source == null) {
            toast("No audio track found.")
            return
        }

        val qualities = listOf(
            320 to "320 kbps · Highest quality (largest file)",
            256 to "256 kbps · High quality",
            192 to "192 kbps · Balanced",
            128 to "128 kbps · Small file"
        )

        AlertDialog.Builder(this)
            .setTitle("Download MP3 (audio)")
            .setItems(qualities.map { it.second }.toTypedArray()) { _, which ->
                startAudioDownload(resolved, source, qualities[which].first)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickMp3Source(resolved: Resolved): StreamChoice? {
        val pool = resolved.audio.filter { !it.isDubbedAudio }
        return pool.filter { it.mime.contains("mp4") }.maxByOrNull { it.bitrate }
            ?: pool.maxByOrNull { it.bitrate }
            ?: resolved.bestOriginalAudio()
    }

    private fun startAudioDownload(
        resolved: Resolved,
        source: StreamChoice,
        bitrateKbps: Int
    ) {
        val baseName = safeName("${resolved.title} [MP3 ${bitrateKbps}kbps]")
        toast("MP3 conversion runs on your device – this can take a moment.")
        progress.visibility = View.VISIBLE
        statusView.text = "MP3 0% · downloading audio"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    MediaDownloader.downloadAudioAsMp3WithNotification(
                        context = this@PlayerActivity,
                        audio = source,
                        bitrateKbps = bitrateKbps,
                        title = resolved.title,
                        artist = resolved.uploader,
                        baseName = baseName
                    ) { pct ->
                        runOnUiThread {
                            val stage = if (pct < 56) "downloading audio" else "converting to MP3"
                            statusView.text = "MP3 $pct% · $stage · Downloads/BlackTube"
                        }
                    }
                }
            }
            progress.visibility = View.GONE
            result.onSuccess { saved ->
                statusView.text = "Saved to: ${saved.folderPath}"
                toast("Saved to: ${saved.folderPath}")
            }.onFailure { error ->
                statusView.text = "MP3 conversion failed: ${error.message}"
                toast("MP3 conversion failed: ${error.message}")
            }
        }
    }

    private fun audioKbps(choice: StreamChoice): Int {
        val direct = if (choice.bitrate > 1000) choice.bitrate / 1000 else choice.bitrate
        if (direct > 0) return direct
        return AUDIO_ITAG_KBPS[choice.itag] ?: 0
    }

    private fun codecLabel(choice: StreamChoice): String = when {
        choice.mime.contains("mp4") || choice.suffix == "m4a" -> "AAC (.m4a)"
        choice.suffix == "webm" || choice.mime.contains("webm") -> "Opus (.webm)"
        choice.suffix.isNotBlank() -> ".${choice.suffix}"
        else -> choice.mime
    }

    private fun safeName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .trim()
            .take(90)
            .ifBlank { "video" }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$videoId")
        runCatching { startActivity(Intent.createChooser(send, "Share video")) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val nextId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        val nextTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (nextId.isNotBlank() && nextId != videoId) {
            openVideo(nextId, nextTitle)
        } else {
            rebindPlayerSurface()
        }
    }

    private fun rebindPlayerSurface() {
        if (!this::playerView.isInitialized || !this::uiPlayer.isInitialized) return
        // Force a fresh TextureView surface after PiP / background switch -> no more frozen frames.
        playerView.player = null
        playerView.player = uiPlayer
        playerView.onResume()
        if (!isInPictureInPictureMode && !fullscreen) {
            setPortrait16by9Height()
        }
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        val playing = this::player.isInitialized && player.isPlaying

        val rewPi = android.app.PendingIntent.getBroadcast(
            this, 101, Intent("com.nexus.blacktube.PIP_REWIND").setPackage(packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val togglePi = android.app.PendingIntent.getBroadcast(
            this, 102, Intent("com.nexus.blacktube.PIP_TOGGLE").setPackage(packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val fwdPi = android.app.PendingIntent.getBroadcast(
            this, 103, Intent("com.nexus.blacktube.PIP_FORWARD").setPackage(packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val actions = listOf(
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_skip_prev),
                "Back 10s", "Back 10s", rewPi
            ),
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(
                    this,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                ),
                if (playing) "Pause" else "Play",
                if (playing) "Pause" else "Play",
                togglePi
            ),
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_skip_next),
                "Forward 10s", "Forward 10s", fwdPi
            )
        )
        builder.setActions(actions)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter && playing)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun setupPictureInPicture() = updatePipParams()

    private fun updatePipParams() {
        runCatching {
            val canAuto = this::player.isInitialized &&
                player.playbackState != Player.STATE_IDLE &&
                player.mediaItemCount > 0 &&
                !isFinishing
            setPictureInPictureParams(pipParams(autoEnter = canAuto))
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Always try to enter PiP when the user leaves the app while a video is loaded.
        // Works for playing and buffering states; auto-enter is kept as an additional path.
        if (!isInPictureInPictureMode &&
            this::player.isInitialized &&
            player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE &&
            !isFinishing
        ) {
            val entered = runCatching {
                enterPictureInPictureMode(pipParams(autoEnter = false))
            }.getOrDefault(false)
            if (!entered) {
                // Some OEMs/gesture-nav setups only honour auto-enter.
                runCatching { setPictureInPictureParams(pipParams(autoEnter = true)) }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlaybackSession.isInPip = isInPictureInPictureMode
        val scroll = findViewById<View>(R.id.player_scroll)

        if (isInPictureInPictureMode) {
            scroll.visibility = View.GONE
            playerView.useController = false
            playerView.hideController()
            hudBrightnessLeft.visibility = View.GONE
            hudVolumeRight.visibility = View.GONE
            skipLeftOverlay.visibility = View.GONE
            skipRightOverlay.visibility = View.GONE
            videoContainer.layoutParams = videoContainer.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            videoContainer.requestLayout()
        } else {
            // User dismissed PiP with X / swipe (state CREATED) -> pause cleanly and finish.
            if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) {
                if (this::player.isInitialized && player.duration > 0L) {
                    LocalStore.savePlaybackPosition(videoId, player.currentPosition, player.duration)
                    player.pause()
                }
                finish()
                return
            }
            // User expanded PiP back into the app -> cleanly rebind the surface.
            scroll.visibility = if (fullscreen) View.GONE else View.VISIBLE
            playerView.useController = true
            videoContainer.post { rebindPlayerSurface() }
        }
    }

    override fun onStart() {
        super.onStart()
        PlaybackSession.isPlayerForeground = true
        if (this::player.isInitialized) {
            if (!this::uiPlayer.isInitialized) uiPlayer = createUiPlayer(player)
            rebindPlayerSurface()
            updateSkipButtons()
            renderInlineQueue()
            updatePipParams()
        }
    }

    override fun onPause() {
        PlaybackSession.isPlayerForeground = false
        if (this::player.isInitialized && player.duration > 0L) {
            LocalStore.savePlaybackPosition(videoId, player.currentPosition, player.duration)
        }
        super.onPause()
    }

    override fun onStop() {
        PlaybackSession.isPlayerForeground = false
        if (!isInPictureInPictureMode && !PlaybackSession.isInPip &&
            this::playerView.isInitialized
        ) {
            playerView.onPause()
            playerView.player = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        PlaybackSession.isPlayerForeground = false
        PlaybackSession.isInPip = false
        uiHandler.removeCallbacksAndMessages(null)
        if (pipReceiverRegistered) {
            runCatching { unregisterReceiver(pipActionReceiver) }
            pipReceiverRegistered = false
        }
        if (this::player.isInitialized && player.duration > 0L) {
            LocalStore.savePlaybackPosition(videoId, player.currentPosition, player.duration)
        }
        runCatching { if (this::player.isInitialized) player.removeListener(playbackListener) }
        runCatching {
            castContext?.sessionManager?.removeSessionManagerListener(
                castSessionListener,
                com.google.android.gms.cast.framework.CastSession::class.java
            )
        }
        if (this::playerView.isInitialized) playerView.player = null

        val keepPlaying = this::player.isInitialized &&
            PlaybackSession.isActive && PlaybackSession.player === player

        if (!keepPlaying) {
            PlaybackService.stop(this)
            if (this::player.isInitialized && PlaybackSession.player === player) {
                PlaybackSession.stop()
            } else if (this::player.isInitialized) {
                runCatching { player.release() }
            }
        }
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
