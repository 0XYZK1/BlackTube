package com.nexus.videoclient.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nexus.videoclient.R
import com.nexus.videoclient.data.InnerTube
import com.nexus.videoclient.data.LocalStore
import com.nexus.videoclient.data.Page
import com.nexus.videoclient.data.Session
import com.nexus.videoclient.data.VideoItem
import com.nexus.videoclient.playback.PlaybackService
import com.nexus.videoclient.playback.PlaybackSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class Tab { HOME, SUBS, LIBRARY }

    private lateinit var adapter: VideoAdapter
    private lateinit var feed: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var emptyAction: Button
    private lateinit var emptySkip: TextView
    private lateinit var libraryPanel: View
    private lateinit var avatar: ImageView

    // Sortiertes Profil / Bibliothek
    private lateinit var profileAvatarLarge: ImageView
    private lateinit var profileNameView: TextView
    private lateinit var profileStatusView: TextView
    private lateinit var profileAuthButton: TextView
    private lateinit var playlistsCarousel: LinearLayout
    private lateinit var playlistsEmptyHint: TextView

    // Animiertes Fullscreen-Such-Overlay (mit Live-Vorschlägen & Suchverlauf)
    private lateinit var searchOverlay: View
    private lateinit var searchInput: EditText
    private lateinit var searchClearBtn: View
    private lateinit var searchSuggestionsContainer: LinearLayout
    private lateinit var searchSectionLabel: TextView
    private lateinit var searchClearHistoryBtn: TextView
    private var suggestJob: Job? = null

    // Mini-Player (persistent on every screen)
    private lateinit var miniPlayerController: MiniPlayerController

    private var tab = Tab.HOME
    private var continuation: String? = null
    private var loading = false
    private var statsSyncedThisSession = false

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { afterLogin() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        feed = findViewById(R.id.feed)
        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.empty_view)
        emptyText = findViewById(R.id.empty_text)
        emptyAction = findViewById(R.id.empty_action)
        emptySkip = findViewById(R.id.empty_skip)
        libraryPanel = findViewById(R.id.library_panel)
        avatar = findViewById(R.id.account_avatar)
        avatar.makeCircular()

        // Profil-Karten-Elemente
        profileAvatarLarge = findViewById(R.id.profile_avatar_large)
        profileAvatarLarge.makeCircular()
        profileNameView = findViewById(R.id.profile_name)
        profileStatusView = findViewById(R.id.profile_status)
        profileAuthButton = findViewById(R.id.btn_profile_auth)
        playlistsCarousel = findViewById(R.id.playlists_carousel)
        playlistsEmptyHint = findViewById(R.id.playlists_empty_hint)

        // Such-Overlay
        searchOverlay = findViewById(R.id.search_overlay)
        searchInput = findViewById(R.id.search_input)
        searchClearBtn = findViewById(R.id.btn_search_clear)
        searchSuggestionsContainer = findViewById(R.id.search_suggestions_container)
        searchSectionLabel = findViewById(R.id.search_section_label)
        searchClearHistoryBtn = findViewById(R.id.btn_clear_search_history)
        setupSearchOverlay()

        // Mini-Player
        miniPlayerController = MiniPlayerController(this)

        findViewById<View>(R.id.btn_notifications).setOnClickListener {
            showNotificationsSheet()
        }

        adapter = VideoAdapter(
            onClick = { openItem(it) },
            onChannelClick = { openChannelFromItem(it) },
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
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - 4) loadMore()
            }
        })

        findViewById<View>(R.id.btn_search).setOnClickListener { openSearchOverlay() }
        // Oben rechts auf das runde Profilbild tippen -> direkt in den sortierten Profil-Bereich!
        findViewById<View>(R.id.btn_account).setOnClickListener { selectTab(Tab.LIBRARY) }

        emptyAction.setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }
        emptySkip.setOnClickListener {
            Session.guestMode = true
            selectTab(Tab.HOME)
        }

        findViewById<View>(R.id.tab_home).setOnClickListener { selectTab(Tab.HOME) }
        findViewById<View>(R.id.tab_subs).setOnClickListener { selectTab(Tab.SUBS) }
        findViewById<View>(R.id.tab_library).setOnClickListener { selectTab(Tab.LIBRARY) }

        findViewById<View>(R.id.row_playlists).setOnClickListener {
            openBrowse("FEplaylists", "All Playlists")
        }
        findViewById<View>(R.id.row_liked).setOnClickListener {
            openBrowse("VLLL", "Liked & Favorites")
        }
        findViewById<View>(R.id.row_watch_later).setOnClickListener {
            openBrowse("VLWL", "Watch Later")
        }
        findViewById<View>(R.id.row_history).setOnClickListener {
            openBrowse("FEhistory", "History")
        }

        findViewById<View>(R.id.row_manage_subs).setOnClickListener {
            showBulkUnsubscribeSheet()
        }
        findViewById<View>(R.id.row_manage_likes).setOnClickListener {
            showBulkUnlikeSheet()
        }
        findViewById<View>(R.id.row_sync_stats).setOnClickListener {
            syncStatsFromYouTube(showToast = true)
        }

        profileAuthButton.setOnClickListener {
            if (Session.isLoggedIn) {
                signOut()
            } else {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                searchOverlay.visibility == View.VISIBLE -> closeSearchOverlay()
                tab != Tab.HOME -> selectTab(Tab.HOME)
                else -> finish()
            }
        }

        restoreSessionIfPossible()
        selectTab(Tab.HOME)
        loadAccount()
        askForNotificationPermissionOnStart()
        handleIntent(intent)
    }

    private fun askForNotificationPermissionOnStart() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getSharedPreferences("nexus_permissions", MODE_PRIVATE)
        if (prefs.getBoolean("notif_asked", false)) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        runCatching {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 73)
        }
    }

    /**
     * Holt eine noch gültige YouTube-Sitzung aus dem WebView-Cookie-Speicher zurück,
     * bevor die Anmelde-Aufforderung gezeigt wird.
     */
    private fun restoreSessionIfPossible() {
        if (Session.isLoggedIn) return
        runCatching { Session.captureFromWebView() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------------------- Mini-Player

    override fun onStart() {
        super.onStart()
        miniPlayerController.onStart()
        if (tab == Tab.LIBRARY) refreshWatchStatsUi()
    }

    override fun onPause() {
        miniPlayerController.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            PlaybackSession.stop()
            PlaybackService.stop(this)
        }
        super.onDestroy()
    }

    // --------------------------------------------------------- Fullscreen-Suche

    private fun setupSearchOverlay() {
        findViewById<View>(R.id.btn_search_back).setOnClickListener { closeSearchOverlay() }
        searchClearBtn.setOnClickListener {
            searchInput.setText("")
            searchInput.requestFocus()
        }
        searchClearHistoryBtn.setOnClickListener {
            LocalStore.clearSearchHistory()
            renderSearchHistory()
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = searchInput.text.toString().trim()
                if (q.isNotEmpty()) executeSearch(q)
                true
            } else {
                false
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty().trim()
                searchClearBtn.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                suggestJob?.cancel()
                if (q.isEmpty()) {
                    renderSearchHistory()
                } else {
                    suggestJob = lifecycleScope.launch {
                        delay(140)
                        val suggestions = withContext(Dispatchers.IO) {
                            InnerTube.suggestions(q)
                        }
                        renderLiveSuggestions(q, suggestions)
                    }
                }
            }
        })
    }

    private fun openSearchOverlay() {
        searchOverlay.alpha = 0f
        searchOverlay.translationY = -dp(18).toFloat()
        searchOverlay.visibility = View.VISIBLE
        searchOverlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180)
            .start()

        renderSearchHistory()
        searchInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearchOverlay() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchOverlay.animate()
            .alpha(0f)
            .translationY(-dp(12).toFloat())
            .setDuration(150)
            .withEndAction { searchOverlay.visibility = View.GONE }
            .start()
    }

    private fun renderSearchHistory() {
        val history = LocalStore.getSearchHistory()
        searchSectionLabel.text = if (history.isEmpty()) "NO RECENT SEARCHES" else "RECENT SEARCHES"
        searchClearHistoryBtn.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        searchSuggestionsContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        history.forEach { query ->
            val row = inflater.inflate(
                R.layout.item_search_suggestion,
                searchSuggestionsContainer,
                false
            )
            row.findViewById<TextView>(R.id.suggest_icon).text = "🕘"
            row.findViewById<TextView>(R.id.suggest_text).text = query
            val removeBtn = row.findViewById<TextView>(R.id.suggest_action)
            removeBtn.text = "✕"
            removeBtn.setOnClickListener {
                LocalStore.removeSearchQuery(query)
                renderSearchHistory()
            }
            row.setOnClickListener { executeSearch(query) }
            searchSuggestionsContainer.addView(row)
        }
    }

    private fun renderLiveSuggestions(typed: String, suggestions: List<String>) {
        searchSectionLabel.text = "SUGGESTIONS"
        searchClearHistoryBtn.visibility = View.GONE
        searchSuggestionsContainer.removeAllViews()

        val all = LinkedHashSet<String>()
        all.add(typed)
        all.addAll(suggestions)

        val inflater = LayoutInflater.from(this)
        all.forEach { suggestion ->
            val row = inflater.inflate(
                R.layout.item_search_suggestion,
                searchSuggestionsContainer,
                false
            )
            row.findViewById<TextView>(R.id.suggest_icon).text = "⌕"
            row.findViewById<TextView>(R.id.suggest_text).text = suggestion
            val fillBtn = row.findViewById<TextView>(R.id.suggest_action)
            fillBtn.text = "↖"
            fillBtn.setOnClickListener {
                searchInput.setText(suggestion)
                searchInput.setSelection(suggestion.length)
            }
            row.setOnClickListener { executeSearch(suggestion) }
            searchSuggestionsContainer.addView(row)
        }
    }

    private fun executeSearch(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        LocalStore.addSearchQuery(clean)
        closeSearchOverlay()
        startActivity(
            Intent(this, ListActivity::class.java)
                .putExtra(ListActivity.EXTRA_MODE, ListActivity.MODE_SEARCH)
                .putExtra(ListActivity.EXTRA_QUERY, clean)
                .putExtra(ListActivity.EXTRA_TITLE, clean)
        )
    }

    // --------------------------------------------------------- Tabs & Profil

    private fun updateTabVisuals(next: Tab) {
        val entries = listOf(
            Triple(R.id.tab_home, R.id.tab_home_icon, R.id.tab_home_label) to Tab.HOME,
            Triple(R.id.tab_subs, R.id.tab_subs_icon, R.id.tab_subs_label) to Tab.SUBS,
            Triple(R.id.tab_library, R.id.tab_library_icon, R.id.tab_library_label) to Tab.LIBRARY
        )
        entries.forEach { (ids, t) ->
            val active = t == next
            val container = findViewById<View>(ids.first)
            val icon = findViewById<ImageView>(ids.second)
            val label = findViewById<TextView>(ids.third)
            val color = getColor(if (active) R.color.nexus_white else R.color.nexus_text_dim)

            icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
            label.setTextColor(color)
            label.setTypeface(
                null,
                if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )

            if (active) {
                container.animate().cancel()
                container.scaleX = 0.90f
                container.scaleY = 0.90f
                container.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }
    }

    // -------------------------------------------------- Smart account tools

    private fun showBulkUnsubscribeSheet() {
        if (!Session.isLoggedIn) {
            toast("Sign in with Google to manage subscriptions")
            return
        }
        val dialog = BottomSheetDialog(this, R.style.Theme_Nexus_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        val title = TextView(this).apply {
            text = "Manage Subscriptions"
            setTextColor(getColor(R.color.nexus_white))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
        val btnSelectAll = TextView(this).apply {
            text = "Select all"
            setTextColor(getColor(R.color.nexus_white))
            textSize = 12.5f
            setBackgroundResource(R.drawable.bg_account_pill)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val btnUnsub = TextView(this).apply {
            text = "Unsubscribe (0)"
            setTextColor(getColor(R.color.nexus_black))
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_primary_button)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        actionRow.addView(btnSelectAll)
        actionRow.addView(btnUnsub)

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply {
            text = "Loading subscribed channels…"
            setTextColor(getColor(R.color.nexus_text_muted))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(14))
        }
        listBox.addView(status)

        root.addView(title)
        root.addView(actionRow)
        root.addView(android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
            )
            addView(listBox)
        })
        dialog.setContentView(root)
        dialog.show()

        lifecycleScope.launch {
            val channels = withContext(Dispatchers.IO) {
                runCatching { InnerTube.subscribedChannels() }.getOrDefault(emptyList())
            }
            val selected = LinkedHashSet<String>()
            val checkboxes = ArrayList<android.widget.CheckBox>()

            fun updateCounter() {
                btnUnsub.text = "Unsubscribe (${selected.size})"
            }

            listBox.removeAllViews()
            if (channels.isEmpty()) {
                listBox.addView(TextView(this@MainActivity).apply {
                    text = "No subscriptions found."
                    setTextColor(getColor(R.color.nexus_text_muted))
                })
                return@launch
            }

            channels.forEach { ch ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(10), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                val cb = android.widget.CheckBox(this@MainActivity).apply {
                    buttonTintList = android.content.res.ColorStateList.valueOf(
                        getColor(R.color.nexus_white)
                    )
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(ch.id) else selected.remove(ch.id)
                        updateCounter()
                    }
                }
                checkboxes.add(cb)
                val av = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                        marginEnd = dp(10)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    makeCircular()
                    if (ch.avatar.isNotBlank()) {
                        load(ch.avatar) { transformations(CircleCropTransformation()) }
                    }
                }
                val nameTv = TextView(this@MainActivity).apply {
                    text = ch.name
                    setTextColor(getColor(R.color.nexus_white))
                    textSize = 13.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                row.addView(cb)
                row.addView(av)
                row.addView(nameTv)
                row.setOnClickListener { cb.isChecked = !cb.isChecked }
                listBox.addView(row)
            }

            btnSelectAll.setOnClickListener {
                val selectAll = selected.size < channels.size
                checkboxes.forEach { it.isChecked = selectAll }
                btnSelectAll.text = if (selectAll) "Deselect all" else "Select all"
            }

            btnUnsub.setOnClickListener {
                if (selected.isEmpty()) return@setOnClickListener
                val targets = selected.toList()
                btnUnsub.isEnabled = false
                lifecycleScope.launch {
                    var done = 0
                    withContext(Dispatchers.IO) {
                        targets.forEach { cid ->
                            if (InnerTube.setSubscribed(cid, false)) done++
                            kotlinx.coroutines.delay(220)
                        }
                    }
                    InnerTube.clearCache()
                    toast("Unsubscribed from $done channels")
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showBulkUnlikeSheet() {
        if (!Session.isLoggedIn) {
            toast("Sign in with Google to manage liked videos")
            return
        }
        val dialog = BottomSheetDialog(this, R.style.Theme_Nexus_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        val title = TextView(this).apply {
            text = "Manage Liked Videos"
            setTextColor(getColor(R.color.nexus_white))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
        val btnSelectAll = TextView(this).apply {
            text = "Select all"
            setTextColor(getColor(R.color.nexus_white))
            textSize = 12.5f
            setBackgroundResource(R.drawable.bg_account_pill)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val btnUnlike = TextView(this).apply {
            text = "Unlike (0)"
            setTextColor(getColor(R.color.nexus_black))
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_primary_button)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        actionRow.addView(btnSelectAll)
        actionRow.addView(btnUnlike)

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox.addView(TextView(this).apply {
            text = "Loading liked videos…"
            setTextColor(getColor(R.color.nexus_text_muted))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(14))
        })

        root.addView(title)
        root.addView(actionRow)
        root.addView(android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420)
            )
            addView(listBox)
        })
        dialog.setContentView(root)
        dialog.show()

        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) {
                runCatching { InnerTube.browse("VLLL").items }.getOrDefault(emptyList())
            }
            val selected = LinkedHashSet<String>()
            val checkboxes = ArrayList<android.widget.CheckBox>()

            fun updateCounter() {
                btnUnlike.text = "Unlike (${selected.size})"
            }

            listBox.removeAllViews()
            if (videos.isEmpty()) {
                listBox.addView(TextView(this@MainActivity).apply {
                    text = "No liked videos found."
                    setTextColor(getColor(R.color.nexus_text_muted))
                })
                return@launch
            }

            videos.forEach { v ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(10), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                val cb = android.widget.CheckBox(this@MainActivity).apply {
                    buttonTintList = android.content.res.ColorStateList.valueOf(
                        getColor(R.color.nexus_white)
                    )
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(v.id) else selected.remove(v.id)
                        updateCounter()
                    }
                }
                checkboxes.add(cb)
                val titleTv = TextView(this@MainActivity).apply {
                    text = v.title
                    setTextColor(getColor(R.color.nexus_white))
                    textSize = 13f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                row.addView(cb)
                row.addView(titleTv)
                row.setOnClickListener { cb.isChecked = !cb.isChecked }
                listBox.addView(row)
            }

            btnSelectAll.setOnClickListener {
                val selectAll = selected.size < videos.size
                checkboxes.forEach { it.isChecked = selectAll }
                btnSelectAll.text = if (selectAll) "Deselect all" else "Select all"
            }

            btnUnlike.setOnClickListener {
                if (selected.isEmpty()) return@setOnClickListener
                val targets = selected.toList()
                btnUnlike.isEnabled = false
                lifecycleScope.launch {
                    var done = 0
                    withContext(Dispatchers.IO) {
                        targets.forEach { vid ->
                            if (InnerTube.removeLike(vid)) done++
                            kotlinx.coroutines.delay(220)
                        }
                    }
                    InnerTube.clearCache()
                    toast("Removed like from $done videos")
                    syncStatsFromYouTube(false)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showNotificationsSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_Nexus_BottomSheet)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_account_pill)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        })
        root.addView(TextView(this).apply {
            text = "Notifications"
            setTextColor(getColor(R.color.nexus_white))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(12))
        })

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val statusView = TextView(this).apply {
            text = if (Session.isLoggedIn) {
                "Loading notifications…"
            } else {
                "Sign in with Google to view comment replies and channel notifications."
            }
            setTextColor(getColor(R.color.nexus_text_muted))
            textSize = 13f
            setPadding(dp(4), dp(12), dp(4), dp(18))
        }
        listBox.addView(statusView)

        val scroll = android.widget.ScrollView(this).apply { addView(listBox) }
        root.addView(scroll)
        dialog.setContentView(root)
        dialog.show()

        if (!Session.isLoggedIn) return
        findViewById<View>(R.id.notif_badge_dot).visibility = View.GONE

        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { InnerTube.notifications() }.getOrDefault(emptyList())
            }
            listBox.removeAllViews()
            if (items.isEmpty()) {
                listBox.addView(TextView(this@MainActivity).apply {
                    text = "No notifications yet."
                    setTextColor(getColor(R.color.nexus_text_muted))
                    textSize = 13f
                    setPadding(dp(4), dp(12), dp(4), dp(18))
                })
                return@launch
            }
            items.forEach { n ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                    setOnClickListener {
                        dialog.dismiss()
                        if (n.videoId.isNotBlank()) openVideo(n.videoId)
                    }
                }
                val av = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.bg_avatar_circle)
                    makeCircular()
                    if (n.avatar.isNotBlank()) {
                        load(n.avatar) { transformations(CircleCropTransformation()) }
                    }
                }
                val textCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        marginStart = dp(12)
                        marginEnd = dp(10)
                    }
                }
                textCol.addView(TextView(this@MainActivity).apply {
                    text = n.title
                    setTextColor(getColor(R.color.nexus_white))
                    textSize = 13f
                    maxLines = 3
                })
                if (n.timestamp.isNotBlank()) {
                    textCol.addView(TextView(this@MainActivity).apply {
                        text = n.timestamp
                        setTextColor(getColor(R.color.nexus_text_dim))
                        textSize = 11f
                        setPadding(0, dp(3), 0, 0)
                    })
                }
                row.addView(av)
                row.addView(textCol)
                if (n.thumbnail.isNotBlank()) {
                    row.addView(ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(64), dp(36))
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        load(n.thumbnail)
                    })
                }
                listBox.addView(row)
            }
        }
    }

    private fun selectTab(next: Tab) {
        tab = next
        continuation = null
        adapter.clear()
        emptyView.visibility = View.GONE

        updateTabVisuals(next)

        if (next == Tab.LIBRARY) {
            feed.visibility = View.GONE
            libraryPanel.alpha = 0f
            libraryPanel.visibility = View.VISIBLE
            libraryPanel.animate().alpha(1f).setDuration(180).start()
            refreshProfileDashboard()
            return
        }

        libraryPanel.visibility = View.GONE
        feed.visibility = View.VISIBLE

        if (!Session.isLoggedIn) {
            if (next == Tab.SUBS) {
                showEmpty(
                    "Sign in with your Google account to see your subscriptions.",
                    true
                )
            } else if (!Session.guestMode) {
                showEmpty(
                    "Sign in with your Google account to see your personal feed, subscriptions, and playlists.",
                    true
                )
            } else {
                // Guest mode on Home: load a clean starter feed
                loadGuestHomeFeed()
            }
            return
        }
        loadFeed()
    }

    private fun refreshProfileDashboard() {
        val loggedIn = Session.isLoggedIn
        if (loggedIn && !statsSyncedThisSession) syncStatsFromYouTube(false)
        profileNameView.text = if (loggedIn) {
            Session.accountName ?: "YouTube Account"
        } else {
            "Local profile (not signed in)"
        }
        profileStatusView.text = if (loggedIn) {
            "Synced · v${appVersion()} · Original audio active"
        } else {
            "Local library active · v${appVersion()}"
        }
        profileAuthButton.text = if (loggedIn) "Sign out" else "Sign in with Google"

        val avatarUrl = Session.accountAvatar
        if (loggedIn && !avatarUrl.isNullOrBlank()) {
            profileAvatarLarge.load(avatarUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        } else {
            profileAvatarLarge.setImageDrawable(null)
            profileAvatarLarge.setBackgroundResource(R.drawable.bg_avatar_circle)
        }

        refreshWatchStatsUi()
        loadUserPlaylistsIntoDashboard()
    }

    private fun refreshWatchStatsUi() {
        val local = LocalStore.getWatchStats()
        val synced = LocalStore.getSyncedAccountStats()
        val totalWatch = local.totalWatchSeconds + synced.syncedHistoryWatchSeconds
        val totalVideos = maxOf(local.videosWatched, synced.syncedHistoryVideoCount)
        val totalLikes = maxOf(local.likesGiven, synced.totalLikedCount)

        findViewById<TextView>(R.id.stat_watch_time).text =
            LocalStore.formatWatchTime(totalWatch)
        findViewById<TextView>(R.id.stat_videos_watched).text = totalVideos.toString()
        findViewById<TextView>(R.id.stat_likes_given).text = totalLikes.toString()
    }

    private fun syncStatsFromYouTube(showToast: Boolean = false) {
        if (!Session.isLoggedIn) {
            if (showToast) toast("Sign in with Google first")
            return
        }
        if (showToast) toast("Scanning watch history & likes…")
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { InnerTube.syncAccountWatchStats() }
            }
            statsSyncedThisSession = true
            refreshWatchStatsUi()
            if (showToast) toast("Stats updated from YouTube")
        }
    }

    private fun loadUserPlaylistsIntoDashboard() {
        playlistsCarousel.removeAllViews()
        if (!Session.isLoggedIn) {
            playlistsEmptyHint.text = "Sign in to see your YouTube playlists here."
            playlistsEmptyHint.visibility = View.VISIBLE
            return
        }
        playlistsEmptyHint.text = "Loading playlists…"
        playlistsEmptyHint.visibility = View.VISIBLE

        lifecycleScope.launch {
            val playlists = withContext(Dispatchers.IO) {
                runCatching { InnerTube.userPlaylists() }.getOrDefault(emptyList())
            }
            playlistsCarousel.removeAllViews()
            if (playlists.isEmpty()) {
                playlistsEmptyHint.text = "Tap “View all” to open your playlists."
                playlistsEmptyHint.visibility = View.VISIBLE
                return@launch
            }
            playlistsEmptyHint.visibility = View.GONE
            val inflater = LayoutInflater.from(this@MainActivity)
            playlists.forEach { pl ->
                val card = inflater.inflate(
                    R.layout.item_playlist_chip,
                    playlistsCarousel,
                    false
                )
                val thumb = card.findViewById<ImageView>(R.id.pl_chip_thumb)
                if (pl.thumbnail.isNotBlank()) {
                    thumb.load(pl.thumbnail) { crossfade(150) }
                }
                card.findViewById<TextView>(R.id.pl_chip_title).text = pl.title
                card.findViewById<TextView>(R.id.pl_chip_count).text =
                    pl.views.ifBlank { "Playlist" }
                card.setOnClickListener { openItem(pl) }
                playlistsCarousel.addView(card)
            }
        }
    }

    private fun loadGuestHomeFeed() {
        loading = true
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { InnerTube.search("Gaming Tech Music Documentary 4K") }
            }
            loading = false
            progress.visibility = View.GONE
            result.onSuccess { page ->
                continuation = page.continuation
                adapter.submit(page.items, false)
            }.onFailure { error ->
                showEmpty("Failed to load: ${error.message}", false)
            }
        }
    }

    private fun loadFeed() {
        loading = true
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (tab) {
                        Tab.HOME -> InnerTube.browse("FEwhat_to_watch")
                        Tab.SUBS -> InnerTube.browse("FEsubscriptions")
                        Tab.LIBRARY -> Page(emptyList(), null)
                    }
                }
            }
            loading = false
            progress.visibility = View.GONE
            result.onSuccess { page ->
                continuation = page.continuation
                adapter.submit(page.items, false)
                feed.scrollToPosition(0)
                if (page.items.isEmpty()) {
                    showEmpty("Nothing here right now. Check back later.", false)
                }
            }.onFailure { error ->
                showEmpty("Failed to load: ${error.message}", true)
            }
        }
    }

    private fun loadMore() {
        val token = continuation ?: return
        loading = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (tab) {
                        Tab.HOME -> if (Session.isLoggedIn) {
                            InnerTube.browse("FEwhat_to_watch", token)
                        } else {
                            InnerTube.search("Gaming Tech Music Documentary 4K", token)
                        }
                        Tab.SUBS -> InnerTube.browse("FEsubscriptions", token)
                        Tab.LIBRARY -> Page(emptyList(), null)
                    }
                }
            }
            loading = false
            result.onSuccess { page ->
                continuation = page.continuation
                adapter.submit(page.items, true)
            }
        }
    }

    private fun showEmpty(message: String, login: Boolean) {
        emptyText.text = message
        emptyAction.visibility = if (login) View.VISIBLE else View.GONE
        emptySkip.visibility = if (login) View.VISIBLE else View.GONE
        emptyView.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- Account

    private fun loadAccount() {
        if (!Session.isLoggedIn) {
            avatar.setImageDrawable(null)
            avatar.setBackgroundResource(R.drawable.bg_dot_muted)
            return
        }
        Session.accountAvatar?.let { url ->
            avatar.background = null
            avatar.load(url) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        }
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { InnerTube.accountInfo() }.getOrNull()
            }
            if (info == null || isDestroyed) return@launch
            Session.updateAccount(info.name, info.avatar)
            if (info.avatar.isNotBlank()) {
                avatar.background = null
                avatar.load(info.avatar) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
            }
            if (tab == Tab.LIBRARY) refreshProfileDashboard()
        }

        // Background check for unread notifications -> bell badge dot
        lifecycleScope.launch {
            val hasUnread = withContext(Dispatchers.IO) {
                runCatching { InnerTube.notifications().any { it.unread } }
                    .getOrDefault(false)
            }
            if (isDestroyed) return@launch
            findViewById<View>(R.id.notif_badge_dot).visibility =
                if (hasUnread) View.VISIBLE else View.GONE
        }
    }

    private fun afterLogin() {
        InnerTube.clearCache()
        if (Session.isLoggedIn) Session.guestMode = false
        loadAccount()
        selectTab(tab)
        toast(if (Session.isLoggedIn) "Signed in" else "Sign-in not completed")
    }

    private fun signOut() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            Session.clear()
            InnerTube.clearCache()
            avatar.setImageDrawable(null)
            avatar.setBackgroundResource(R.drawable.bg_dot_muted)
            toast("Signed out")
            selectTab(tab)
        }
    }

    private fun openBrowse(browseId: String, title: String) {
        startActivity(
            Intent(this, ListActivity::class.java)
                .putExtra(ListActivity.EXTRA_MODE, ListActivity.MODE_BROWSE)
                .putExtra(ListActivity.EXTRA_BROWSE_ID, browseId)
                .putExtra(ListActivity.EXTRA_TITLE, title)
        )
    }

    private fun openChannelFromItem(item: VideoItem) {
        if (item.channelId.isNotBlank()) {
            startActivity(
                Intent(this, ListActivity::class.java)
                    .putExtra(ListActivity.EXTRA_MODE, ListActivity.MODE_CHANNEL)
                    .putExtra(ListActivity.EXTRA_BROWSE_ID, item.channelId)
                    .putExtra(ListActivity.EXTRA_TITLE, item.channel)
            )
        } else if (item.channel.isNotBlank()) {
            executeSearch(item.channel)
        }
    }

    private fun openItem(item: VideoItem) {
        if (item.isChannel) {
            startActivity(
                Intent(this, ListActivity::class.java)
                    .putExtra(ListActivity.EXTRA_MODE, ListActivity.MODE_CHANNEL)
                    .putExtra(
                        ListActivity.EXTRA_BROWSE_ID,
                        item.channelId.ifBlank { item.id }
                    )
                    .putExtra(ListActivity.EXTRA_TITLE, item.title)
            )
            return
        }
        if (item.isPlaylist) {
            val browseId = if (item.id.startsWith("VL")) item.id else "VL${item.id}"
            startActivity(
                Intent(this, ListActivity::class.java)
                    .putExtra(ListActivity.EXTRA_MODE, ListActivity.MODE_BROWSE)
                    .putExtra(ListActivity.EXTRA_BROWSE_ID, browseId)
                    .putExtra(ListActivity.EXTRA_TITLE, item.title)
            )
        } else {
            LocalStore.recordVideoWatched(item)
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_VIDEO_ID, item.id)
                    .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
            )
        }
    }

    // ------------------------------------------------------------------ Intents

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val id = videoIdFrom(intent.data) ?: return
                openVideo(id)
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val match = Regex("https?://\\S+").find(text)?.value ?: return
                val id = videoIdFrom(Uri.parse(match)) ?: return
                openVideo(id)
            }
        }
    }

    private fun openVideo(videoId: String) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, videoId)
        )
    }

    private fun videoIdFrom(uri: Uri?): String? {
        if (uri == null) return null
        val host = uri.host?.lowercase() ?: return null
        if (host.endsWith("youtu.be")) {
            return uri.path?.trim('/')?.substringBefore('/')?.takeIf { it.length == 11 }
        }
        uri.getQueryParameter("v")?.let { return it }
        val path = uri.path ?: return null
        val short = when {
            path.startsWith("/shorts/") -> path.removePrefix("/shorts/")
            path.startsWith("/live/") -> path.removePrefix("/live/")
            else -> null
        }
        return short?.substringBefore('/')?.takeIf { it.length == 11 }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()
}
