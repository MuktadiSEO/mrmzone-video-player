package com.mrmzone.player.ui

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.mrmzone.player.R
import com.mrmzone.player.data.Folder
import com.mrmzone.player.data.Library
import com.mrmzone.player.data.MediaRepo
import com.mrmzone.player.data.MetaFiller
import com.mrmzone.player.data.Prefs
import com.mrmzone.player.data.Video
import com.mrmzone.player.player.PlayerActivity
import com.mrmzone.player.util.Fmt
import java.io.File
import java.util.concurrent.Executors

/**
 * Library screen. Root mode shows tabs FOLDERS / VIDEOS / RECENT.
 * When started with EXTRA_FOLDER it shows only the videos of that folder.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_FOLDER_NAME = "folderName"
        private val io = Executors.newSingleThreadExecutor()
    }

    private lateinit var prefs: Prefs
    private lateinit var adapter: LibraryAdapter
    private lateinit var list: RecyclerView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var statusBar: TextView
    private lateinit var tabs: TabLayout
    private lateinit var fab: ExtendedFloatingActionButton
    private var folderPath: String? = null
    private var tab = 0
    private var query = ""
    private var videos: List<Video> = emptyList()
    private var currentVideos: List<Video> = emptyList()
    private var loaded = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent(this, PlayerActivity::class.java).setData(uri))
        }
    }
    private var pendingDelete: Video? = null
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            pendingDelete?.let { removedLocally(it) }
            // Android 10 needs a second try after the user granted access
            if (Build.VERSION.SDK_INT == 29) pendingDelete?.let { deleteNow(it) }
        }
        pendingDelete = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        folderPath = intent.getStringExtra(EXTRA_FOLDER)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        statusBar = findViewById(R.id.statusBar)
        tabs = findViewById(R.id.tabs)
        fab = findViewById(R.id.fab)
        list = findViewById(R.id.list)
        refresh = findViewById(R.id.refresh)
        refresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_orange), ContextCompat.getColor(this, R.color.brand_blue))
        refresh.setOnRefreshListener { reload() }

        if (folderPath != null) {
            findViewById<View>(R.id.logo).visibility = View.GONE
            toolbar.setNavigationIcon(R.drawable.ic_back_w)
            toolbar.setNavigationContentDescription("Back")
            toolbar.setNavigationOnClickListener { finish() }
            findViewById<TextView>(R.id.title).text = intent.getStringExtra(EXTRA_FOLDER_NAME) ?: File(folderPath!!).name
            findViewById<TextView>(R.id.subtitle).text = folderPath
            tabs.visibility = View.GONE
            tab = 1
        } else {
            listOf("FOLDERS", "VIDEOS", "RECENT").forEach { tabs.addTab(tabs.newTab().setText(it)) }
            tab = prefs.tab.coerceIn(0, 2)
            tabs.getTabAt(tab)?.select()
            tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(t: TabLayout.Tab) { tab = t.position; prefs.tab = tab; render() }
                override fun onTabUnselected(t: TabLayout.Tab) {}
                override fun onTabReselected(t: TabLayout.Tab) { list.smoothScrollToPosition(0) }
            })
        }

        adapter = LibraryAdapter(prefs, ::openFolder, ::play, ::videoMenu)
        list.adapter = adapter
        list.setHasFixedSize(true)
        buildCommandBar()
        fab.setOnClickListener { resumeLast() }
        findViewById<MaterialButton>(R.id.emptyButton).setOnClickListener { askPermission(true) }
        askPermission(false)
        reload()
    }

    override fun onResume() {
        super.onResume()
        if (loaded) render() // refresh resume bars / recent list after coming back from the player
    }

    // ---------------- permissions ----------------
    private fun readPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, readPerm()) == PackageManager.PERMISSION_GRANTED) return true
        return Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    }

    private fun askPermission(fromButton: Boolean) {
        if (hasPermission()) return
        if (fromButton && !shouldShowRequestPermissionRationale(readPerm()) && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("asked", false)) {
            // permanently denied -> open app settings
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            return
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("asked", true).apply()
        val perms = mutableListOf(readPerm())
        if (Build.VERSION.SDK_INT >= 34) perms += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    // ---------------- data ----------------
    private fun reload() {
        if (!hasPermission()) { refresh.isRefreshing = false; loaded = true; render(); return }
        refresh.isRefreshing = true
        io.execute {
            val all = MediaRepo.scan(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                videos = all
                loaded = true
                refresh.isRefreshing = false
                render()
                MetaFiller.fill(this, all) { if (!isDestroyed) { videos = MetaFiller.apply(this, videos); render() } }
            }
        }
    }

    private fun render() {
        val grid = prefs.grid
        val by = prefs.sortBy; val asc = prefs.sortAsc
        var rows: List<Row>
        val source = folderPath?.let { p -> videos.filter { it.folderPath == p } } ?: videos
        when {
            folderPath == null && tab == 0 && query.isBlank() -> {
                val folders = Library.sortFolders(Library.group(source), by, asc)
                rows = folders.map { Row.F(it) }
                currentVideos = emptyList()
            }
            tab == 2 && folderPath == null -> {
                val byKey = videos.associateBy { it.key }
                currentVideos = prefs.history().mapNotNull { h -> byKey[h.key] }
                currentVideos = Library.search(currentVideos, query)
                rows = currentVideos.map { Row.V(it) }
            }
            else -> {
                currentVideos = Library.sortVideos(Library.search(source, query), by, asc)
                rows = currentVideos.map { Row.V(it) }
            }
        }
        val span = if (grid) maxOf(2, (resources.configuration.screenWidthDp / 170)) else 1
        val lm = list.layoutManager
        if (grid && (lm !is GridLayoutManager || lm.spanCount != span)) list.layoutManager = GridLayoutManager(this, span)
        else if (!grid && (lm == null || lm is GridLayoutManager)) list.layoutManager = LinearLayoutManager(this)
        list.setPadding(if (grid) dp(5) else 0, if (grid) dp(5) else 0, if (grid) dp(5) else 0, dp(88))
        adapter.submit(rows, grid)
        updateEmpty(rows.isEmpty())
        updateStatus()
        fab.visibility = if (prefs.history().isNotEmpty()) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }

    private fun updateEmpty(isEmpty: Boolean) {
        val empty = findViewById<View>(R.id.empty)
        val title = findViewById<TextView>(R.id.emptyTitle)
        val text = findViewById<TextView>(R.id.emptyText)
        val btn = findViewById<MaterialButton>(R.id.emptyButton)
        val noPerm = !hasPermission()
        empty.visibility = if (loaded && (isEmpty || noPerm)) View.VISIBLE else View.GONE
        list.visibility = if (noPerm) View.GONE else View.VISIBLE
        btn.visibility = if (noPerm) View.VISIBLE else View.GONE
        when {
            noPerm -> { title.text = "Allow access to videos"; text.text = "MRM Zone Player needs permission to find the videos on your phone." }
            query.isNotBlank() -> { title.text = "No results"; text.text = "Nothing matches “$query”" }
            tab == 2 && folderPath == null -> { title.text = "Nothing played yet"; text.text = "Videos you watch will appear here so you can continue where you left off." }
            else -> { title.text = "No videos"; text.text = "Videos on your phone will show up here. Pull down to refresh." }
        }
    }

    private fun updateStatus() {
        val src = folderPath?.let { p -> videos.filter { it.folderPath == p } } ?: videos
        val folders = src.map { it.folderPath }.distinct().size
        statusBar.text = if (folderPath != null) "Videos: ${src.size}   •   Size: ${Fmt.size(src.sumOf { it.size })}   •   Sort: ${prefs.sortBy.label}"
        else "Videos: ${src.size}   •   Folders: $folders   •   Size: ${Fmt.size(src.sumOf { it.size })}"
    }

    // ---------------- command bar (IDM style) ----------------
    private fun buildCommandBar() {
        val bar = findViewById<LinearLayout>(R.id.commandBar)
        fun cmd(icon: Int, label: String, tint: Int = R.color.brand_blue, action: () -> Unit) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_command, bar, false)
            v.findViewById<ImageView>(R.id.icon).apply { setImageResource(icon); imageTintList = ContextCompat.getColorStateList(context, tint) }
            v.findViewById<TextView>(R.id.label).text = label
            v.contentDescription = label
            v.setOnClickListener { action() }
            bar.addView(v)
        }
        cmd(R.drawable.ic_play_w, "Resume Last", R.color.brand_orange) { resumeLast() }
        cmd(R.drawable.ic_folder_w, "Open File", R.color.folder) { openFile.launch(arrayOf("video/*", "audio/*", "application/octet-stream", "*/*")) }
        cmd(R.drawable.ic_stream, "Network Stream") { Dialogs.openUrl(this) }
        cmd(R.drawable.ic_sort, "Sort") { Dialogs.sort(this, prefs) { render() } }
        cmd(R.drawable.ic_grid, "View") { prefs.grid = !prefs.grid; render() }
        cmd(R.drawable.ic_refresh, "Refresh", R.color.ok) { reload() }
        cmd(R.drawable.ic_settings, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        cmd(R.drawable.ic_share, "Tell a Friend") { tellFriend() }
    }

    // ---------------- menu ----------------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        val sv = menu.findItem(R.id.m_search).actionView as SearchView
        sv.queryHint = "Search videos"
        sv.maxWidth = Int.MAX_VALUE
        if (query.isNotEmpty()) { menu.findItem(R.id.m_search).expandActionView(); sv.setQuery(query, false) }
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String) = true
            override fun onQueryTextChange(q: String): Boolean { if (q != query) { query = q; render() }; return true }
        })
        menu.findItem(R.id.m_view).setIcon(if (prefs.grid) R.drawable.ic_list else R.drawable.ic_grid)
        menu.findItem(R.id.m_clear_history).isVisible = folderPath == null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_view -> { prefs.grid = !prefs.grid; render() }
            R.id.m_sort -> Dialogs.sort(this, prefs) { render() }
            R.id.m_refresh -> reload()
            R.id.m_open_url -> Dialogs.openUrl(this)
            R.id.m_clear_history -> Dialogs.confirm(this, "Clear history?", "Forget all playback positions and the Recent list.") { prefs.clearHistory(); render() }
            R.id.m_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.m_about -> Dialogs.about(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------------- actions ----------------
    private fun openFolder(f: Folder) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_FOLDER, f.path).putExtra(EXTRA_FOLDER_NAME, f.name))
    }

    private fun play(v: Video, fromStart: Boolean = false) {
        val playlist = if (currentVideos.contains(v)) currentVideos else listOf(v)
        PlayerActivity.start(this, playlist, playlist.indexOf(v).coerceAtLeast(0), fromStart)
    }

    private fun resumeLast() {
        val h = prefs.history().firstOrNull() ?: run { toast("Nothing played yet"); return }
        val v = videos.firstOrNull { it.key == h.key }
        if (v != null) {
            val siblings = Library.sortVideos(videos.filter { it.folderPath == v.folderPath }, prefs.sortBy, prefs.sortAsc)
            PlayerActivity.start(this, siblings, siblings.indexOf(v).coerceAtLeast(0), false)
        } else startActivity(Intent(this, PlayerActivity::class.java).setData(Uri.parse(h.uri)).putExtra(PlayerActivity.EXTRA_TITLE, h.title))
    }

    private fun videoMenu(v: Video, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Play")
        if (prefs.position(v.key) > 0) pm.menu.add(0, 2, 1, "Play from start")
        pm.menu.add(0, 3, 2, "Properties")
        pm.menu.add(0, 4, 3, "Share")
        if (tab == 2 && folderPath == null) pm.menu.add(0, 6, 4, "Remove from Recent")
        pm.menu.add(0, 5, 5, "Delete")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> play(v)
                2 -> play(v, true)
                3 -> Dialogs.properties(this, v, prefs)
                4 -> share(v)
                5 -> Dialogs.confirm(this, "Delete video?", "“${v.name}” will be permanently deleted from your phone.", "Delete") { delete(v) }
                6 -> { prefs.removeHistory(v.key); render() }
            }
            true
        }
        pm.show()
    }

    private fun share(v: Video) {
        val i = Intent(Intent.ACTION_SEND).setType(v.mime.ifEmpty { "video/*" })
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(v.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share video"))
    }

    private fun delete(v: Video) {
        val uri = Uri.parse(v.uri)
        if (Build.VERSION.SDK_INT >= 30) {
            pendingDelete = v
            val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else deleteNow(v)
    }

    private fun deleteNow(v: Video) {
        try {
            val n = contentResolver.delete(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id), null, null)
            if (n > 0 || File(v.path).delete()) removedLocally(v) else toast("Could not delete")
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                pendingDelete = v
                deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } else toast("Could not delete: ${e.message}")
        }
    }

    private fun removedLocally(v: Video) {
        videos = videos.filter { it.id != v.id }
        prefs.removeHistory(v.key)
        toast("Deleted")
        render()
    }

    private fun tellFriend() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Try MRM Zone Player – plays every video format (MKV, AVI, MP4, AC3, DTS...) smoothly!")
        startActivity(Intent.createChooser(i, "Tell a friend"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
