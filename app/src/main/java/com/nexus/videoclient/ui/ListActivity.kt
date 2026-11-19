package com.nexus.videoclient.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.nexus.videoclient.R
import com.nexus.videoclient.data.ChannelHeader
import com.nexus.videoclient.data.InnerTube
import com.nexus.videoclient.data.LocalStore
import com.nexus.videoclient.data.Page
import com.nexus.videoclient.data.Session
import com.nexus.videoclient.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_BROWSE = "browse"
        const val MODE_SEARCH = "search"
        const val MODE_CHANNEL = "channel"
        const val EXTRA_BROWSE_ID = "browse_id"
        const val EXTRA_QUERY = "query"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var adapter: VideoAdapter
    private lateinit var feed: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView

    private lateinit var miniPlayerController: MiniPlayerController
    private var channelSubscribed = false

    private var mode = MODE_BROWSE
    private var browseId = ""
    private var query = ""
    private var continuation: String? = null
    private var loading = false
    private var channelTab = "overview"
    private var searchFilterParams: String? = null
    private var loadedItems: List<VideoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BROWSE
        browseId = intent.getStringExtra(EXTRA_BROWSE_ID).orEmpty()
        query = intent.getStringExtra(EXTRA_QUERY).orEmpty()

        findViewById<TextView>(R.id.list_title).text =
            intent.getStringExtra(EXTRA_TITLE) ?: "BlackTube"
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        feed = findViewById(R.id.feed)
        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.empty_view)
        emptyText = findViewById(R.id.empty_text)

        miniPlayerController = MiniPlayerController(this)
        findViewById<ImageView>(R.id.channel_avatar).makeCircular()

        if (mode == MODE_CHANNEL) setupChannelTabs()
        if (mode == MODE_SEARCH) setupSearchFilters()

        adapter = VideoAdapter(
            onClick = { openItem(it) },
            onChannelClick = { item ->
                if (item.channelId.isNotBlank() && item.channelId != browseId) {
                    startActivity(
                        Intent(this, ListActivity::class.java)
                            .putExtra(EXTRA_MODE, MODE_CHANNEL)
                            .putExtra(EXTRA_BROWSE_ID, item.channelId)
                            .putExtra(EXTRA_TITLE, item.channel)
                    )
                }
            },
            onMoreClick = { item, pos ->
                showVideoOptionsSheet(this, lifecycleScope, item) {
                    adapter.removeAt(pos)
                }
            }
        )
        feed.layoutManager = LinearLayoutManager(this)
        feed.adapter = adapter
        feed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading) return
                val manager = rv.layoutManager as LinearLayoutManager
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - 4) load(false)
            }
        })

        load(true)
    }

    // Lifecycle for the persistent mini player
    override fun onStart() {
        super.onStart()
        miniPlayerController.onStart()
    }

    override fun onPause() {
        miniPlayerController.onPause()
        super.onPause()
    }

    private fun load(first: Boolean) {
        if (loading) return
        if (!first && continuation == null) return

        // Show local lists instantly when not signed in
        if (first && !Session.isLoggedIn && mode == MODE_BROWSE) {
            val localItems = when (browseId) {
                "VLWL" -> LocalStore.getWatchLater()
                "VLLL" -> LocalStore.getFavorites()
                "FEhistory" -> LocalStore.getHistory()
                else -> emptyList()
            }
            if (localItems.isNotEmpty()) {
                adapter.submit(localItems, false)
                bindPlaylistHeaderIfNeeded(localItems)
                emptyView.visibility = View.GONE
                return
            }
        }

        loading = true
        progress.visibility = if (first) View.VISIBLE else View.GONE

        val token = if (first) null else continuation
        lifecycleScope.launch {
            if (mode == MODE_CHANNEL) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { InnerTube.browseChannel(browseId, channelTab, token) }
                }
                loading = false
                progress.visibility = View.GONE
                result.onSuccess { chPage ->
                    continuation = chPage.continuation
                    if (first) bindChannelHeader(chPage.header)
                    adapter.submit(chPage.videos, !first)
                    if (first && chPage.videos.isEmpty()) {
                        emptyText.text = "No videos found on this channel."
                        emptyView.visibility = View.VISIBLE
                    }
                }.onFailure { error ->
                    if (first) {
                        emptyText.text = "Failed to load channel: ${error.message}"
                        emptyView.visibility = View.VISIBLE
                    }
                }
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        mode == MODE_SEARCH ->
                            InnerTube.search(query, token, searchFilterParams)
                        first && browseId == "FEplaylists" ->
                            Page(InnerTube.userPlaylists(), null)
                        else -> InnerTube.browse(browseId, token)
                    }
                }
            }
            loading = false
            progress.visibility = View.GONE

            result.onSuccess { page: Page ->
                continuation = page.continuation
                // Prepend local entries (Watch Later / Favorites / History)
                val combined = if (first) mergeWithLocal(page.items) else page.items
                adapter.submit(combined, !first)
                if (first) bindPlaylistHeaderIfNeeded(combined)
                if (first && combined.isEmpty()) {
                    emptyText.text = "Nothing found here."
                    emptyView.visibility = View.VISIBLE
                }
            }.onFailure { error ->
                if (first) {
                    val localFallback = mergeWithLocal(emptyList())
                    if (localFallback.isNotEmpty()) {
                        adapter.submit(localFallback, false)
                    } else {
                        emptyText.text = "Failed to load: ${error.message}"
                        emptyView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun bindChannelHeader(h: ChannelHeader) {
        val container = findViewById<View>(R.id.channel_header_container)
        val bannerView = findViewById<ImageView>(R.id.channel_banner)
        val avatarView = findViewById<ImageView>(R.id.channel_avatar)
        val nameView = findViewById<TextView>(R.id.channel_name)
        val statsView = findViewById<TextView>(R.id.channel_stats)
        val descView = findViewById<TextView>(R.id.channel_description)
        val subBtn = findViewById<TextView>(R.id.btn_channel_subscribe)

        container.visibility = View.VISIBLE
        nameView.text = h.name.ifBlank { intent.getStringExtra(EXTRA_TITLE).orEmpty() }
        findViewById<TextView>(R.id.list_title).text = nameView.text

        val statsParts = listOf(h.handle, h.subscribers, h.videoCount)
            .filter { it.isNotBlank() }
        statsView.text = statsParts.joinToString(" · ")

        if (h.banner.isNotBlank()) {
            bannerView.visibility = View.VISIBLE
            bannerView.load(h.banner) { crossfade(150) }
        }
        if (h.avatar.isNotBlank()) {
            avatarView.load(h.avatar) {
                crossfade(150)
                transformations(CircleCropTransformation())
            }
        }
        if (h.description.isNotBlank()) {
            descView.visibility = View.VISIBLE
            descView.text = h.description
        }
        // Tapping the description opens the full "More info" channel sheet
        descView.setOnClickListener { showChannelAboutSheet() }

        channelSubscribed = h.isSubscribed

        fun updateSubUi() {
            subBtn.text = if (channelSubscribed) "Subscribed" else "Subscribe"
            subBtn.setBackgroundResource(
                if (channelSubscribed) R.drawable.bg_account_pill
                else R.drawable.bg_primary_button
            )
            subBtn.setTextColor(
                getColor(if (channelSubscribed) R.color.nexus_white else R.color.nexus_black)
            )
        }
        updateSubUi()

        subBtn.setOnClickListener {
            if (!Session.isLoggedIn) {
                android.widget.Toast.makeText(
                    this,
                    "Sign in to subscribe",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val target = !channelSubscribed
            channelSubscribed = target
            updateSubUi()
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    InnerTube.setSubscribed(browseId, target)
                }
                if (!ok) {
                    channelSubscribed = !target
                    updateSubUi()
                    android.widget.Toast.makeText(
                        this@ListActivity,
                        "Action failed",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showChannelAboutSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(
            this,
            R.style.Theme_Nexus_BottomSheet
        )
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(18), dp(14), dp(18), dp(26))
        }
        root.addView(TextView(this).apply {
            text = "About ${findViewById<TextView>(R.id.channel_name).text}"
            setTextColor(getColor(R.color.nexus_white))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(this).apply {
            text = "Loading channel info…"
            setTextColor(getColor(R.color.nexus_text_muted))
            textSize = 13f
        })
        root.addView(android.widget.ScrollView(this).apply { addView(body) })
        dialog.setContentView(root)
        dialog.show()

        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { InnerTube.channelAbout(browseId) }.getOrNull()
            }
            body.removeAllViews()
            if (info == null) {
                body.addView(TextView(this@ListActivity).apply {
                    text = "Could not load channel details."
                    setTextColor(getColor(R.color.nexus_text_muted))
                })
                return@launch
            }

            fun addSection(
                label: String,
                value: String,
                clickableUrl: String? = null
            ) {
                if (value.isBlank()) return
                body.addView(TextView(this@ListActivity).apply {
                    text = label.uppercase()
                    setTextColor(getColor(R.color.nexus_text_dim))
                    textSize = 11f
                    letterSpacing = 0.1f
                    setPadding(0, dp(12), 0, dp(3))
                })
                body.addView(TextView(this@ListActivity).apply {
                    text = value
                    setTextColor(getColor(R.color.nexus_white))
                    textSize = 13.5f
                    if (!clickableUrl.isNullOrBlank()) {
                        setOnClickListener {
                            runCatching {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(clickableUrl))
                                )
                            }
                        }
                    }
                })
            }

            addSection("Description", info.description)
            addSection("Channel URL", info.canonicalUrl, info.canonicalUrl)
            addSection("Subscribers", info.subscriberCount)
            addSection("Videos", info.videoCount)
            addSection("Total Views", info.totalViews)
            addSection("Joined", info.joinedDate)
            addSection("Country", info.country)

            if (info.links.isNotEmpty()) {
                body.addView(TextView(this@ListActivity).apply {
                    text = "LINKS"
                    setTextColor(getColor(R.color.nexus_text_dim))
                    textSize = 11f
                    letterSpacing = 0.1f
                    setPadding(0, dp(12), 0, dp(4))
                })
                info.links.forEach { (title, linkUrl) ->
                    body.addView(TextView(this@ListActivity).apply {
                        text = "$title · $linkUrl"
                        setTextColor(getColor(R.color.nexus_white))
                        textSize = 13f
                        setPadding(0, dp(4), 0, dp(4))
                        setOnClickListener {
                            runCatching {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(linkUrl))
                                )
                            }
                        }
                    })
                }
            }
        }
    }

    private fun mergeWithLocal(remote: List<VideoItem>): List<VideoItem> {
        val local = when (browseId) {
            "VLWL" -> LocalStore.getWatchLater()
            "VLLL" -> LocalStore.getFavorites()
            "FEhistory" -> LocalStore.getHistory()
            else -> emptyList()
        }
        if (local.isEmpty()) return remote
        val map = LinkedHashMap<String, VideoItem>()
        local.forEach { map[it.id] = it }
        remote.forEach { map.putIfAbsent(it.id, it) }
        return map.values.toList()
    }

    // ---------------------------------------------------- Channel tabs

    private fun setupChannelTabs() {
        val tabs = listOf(
            R.id.ch_tab_overview to "overview",
            R.id.ch_tab_videos to "videos",
            R.id.ch_tab_playlists to "playlists"
        )
        fun renderTabState() {
            tabs.forEach { (id, key) ->
                val v = findViewById<TextView>(id)
                val active = key == channelTab
                v.setBackgroundResource(
                    if (active) R.drawable.bg_primary_button else R.drawable.bg_account_pill
                )
                v.setTextColor(
                    getColor(if (active) R.color.nexus_black else R.color.nexus_white)
                )
            }
        }
        tabs.forEach { (id, key) ->
            findViewById<View>(id).setOnClickListener {
                if (channelTab == key) return@setOnClickListener
                channelTab = key
                continuation = null
                adapter.clear()
                emptyView.visibility = View.GONE
                renderTabState()
                load(true)
            }
        }
    }

    // ---------------------------------------------------- Search filters

    private fun setupSearchFilters() {
        val bar = findViewById<View>(R.id.search_filters_bar)
        val row = findViewById<LinearLayout>(R.id.search_filters_row)
        bar.visibility = View.VISIBLE
        val filters = listOf(
            "All" to null,
            "Videos" to "EgIQAQ==",
            "Channels" to "EgIQAg==",
            "Playlists" to "EgIQAw==",
            "Live" to "EgJAAQ==",
            "This week" to "EgIIAw=="
        )
        val d = resources.displayMetrics.density
        fun renderChips() {
            row.removeAllViews()
            filters.forEach { (label, param) ->
                val active = searchFilterParams == param
                val chip = TextView(this).apply {
                    text = label
                    textSize = 12f
                    setBackgroundResource(
                        if (active) R.drawable.bg_primary_button
                        else R.drawable.bg_account_pill
                    )
                    setTextColor(
                        getColor(if (active) R.color.nexus_black else R.color.nexus_white)
                    )
                    setPadding(
                        (14 * d).toInt(),
                        (7 * d).toInt(),
                        (14 * d).toInt(),
                        (7 * d).toInt()
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (8 * d).toInt() }
                    setOnClickListener {
                        searchFilterParams = param
                        continuation = null
                        adapter.clear()
                        emptyView.visibility = View.GONE
                        renderChips()
                        load(true)
                    }
                }
                row.addView(chip)
            }
        }
        renderChips()
    }

    // ---------------------------------------------------- Playlist header

    private fun bindPlaylistHeaderIfNeeded(items: List<VideoItem>) {
        val isPlaylistScreen = mode == MODE_BROWSE &&
            (browseId.startsWith("VL") || browseId == "FEhistory")
        val header = findViewById<View>(R.id.playlist_header_container)
        if (!isPlaylistScreen || items.isEmpty()) {
            loadedItems = emptyList()
            header.visibility = View.GONE
            return
        }
        loadedItems = items.filter { !it.isPlaylist && !it.isChannel }
        header.visibility = View.VISIBLE
        findViewById<TextView>(R.id.pl_header_title).text =
            intent.getStringExtra(EXTRA_TITLE) ?: "Playlist"
        findViewById<TextView>(R.id.pl_header_meta).text =
            "${loadedItems.size} videos · Playlist"
        loadedItems.firstOrNull()?.thumbnail?.takeIf { it.isNotBlank() }?.let {
            findViewById<ImageView>(R.id.pl_header_thumb).load(it) { crossfade(120) }
        }
        findViewById<View>(R.id.btn_pl_play_all).setOnClickListener {
            startPlaylistPlayback(loadedItems)
        }
        findViewById<View>(R.id.btn_pl_shuffle).setOnClickListener {
            startPlaylistPlayback(loadedItems.shuffled())
        }
    }

    private fun startPlaylistPlayback(list: List<VideoItem>) {
        val first = list.firstOrNull() ?: return
        com.nexus.videoclient.playback.PlaybackSession.setQueue(list.drop(1))
        LocalStore.recordVideoWatched(first)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, first.id)
                .putExtra(PlayerActivity.EXTRA_TITLE, first.title)
        )
    }

    private fun openItem(item: VideoItem) {
        if (item.isChannel) {
            startActivity(
                Intent(this, ListActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_CHANNEL)
                    .putExtra(EXTRA_BROWSE_ID, item.channelId.ifBlank { item.id })
                    .putExtra(EXTRA_TITLE, item.title)
            )
            return
        }
        if (item.isPlaylist) {
            val id = if (item.id.startsWith("VL")) item.id else "VL${item.id}"
            startActivity(
                Intent(this, ListActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_BROWSE)
                    .putExtra(EXTRA_BROWSE_ID, id)
                    .putExtra(EXTRA_TITLE, item.title)
            )
            return
        }

        // Clicking a video on a playlist screen queues the remaining videos
        val isPlaylistScreen = mode == MODE_BROWSE &&
            (browseId.startsWith("VL") || browseId == "FEhistory")
        if (isPlaylistScreen) {
            val index = loadedItems.indexOfFirst { it.id == item.id }
            if (index >= 0 && index < loadedItems.lastIndex) {
                com.nexus.videoclient.playback.PlaybackSession
                    .setQueue(loadedItems.drop(index + 1))
            }
        }

        LocalStore.recordVideoWatched(item)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, item.id)
                .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
        )
    }
}
