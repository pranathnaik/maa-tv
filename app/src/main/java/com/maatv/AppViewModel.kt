package com.maatv

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.maatv.data.AppData
import com.maatv.data.ImportItem
import com.maatv.data.ImportResult
import com.maatv.data.ShowPacks
import com.maatv.data.I18n
import com.maatv.data.I18n.t
import com.maatv.data.Step
import com.maatv.data.Store
import com.maatv.data.Tile
import com.maatv.tv.Box
import com.maatv.tv.Runner
import com.maatv.tv.TvApp
import com.maatv.tv.TvClient
import com.maatv.tv.UiNode
import com.maatv.tv.UiScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

sealed interface Conn {
    data object Idle : Conn
    data object Connecting : Conn
    data object Connected : Conn
    data class Failed(val reason: String) : Conn
}

/** What Mom sees while a tile is playing. */
data class RunState(
    val title: String,
    val message: String,
    val done: Boolean = false,
    val ok: Boolean = false,
    /** Technical reason for a failure, shown small under the message. */
    val detail: String? = null,
    val poster: String? = null,
)

/** A share file being imported: its shows, which are ticked, and what's already here. */
data class ImportPreview(
    val items: List<ImportItem> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val existing: Set<String> = emptySet(),
    val unsupported: List<String> = emptyList(),
    val error: String? = null,
)

/** The setup recorder: a live mirror of the TV plus the steps recorded so far. */
data class EditorState(
    val tileId: String,
    val name: String = "",
    val steps: List<Step> = emptyList(),
    val poster: Bitmap? = null,
    val mirror: Bitmap? = null,
    val screen: UiScreen = UiScreen.EMPTY,
    val apps: List<TvApp> = emptyList(),
    val busy: String? = null,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val tv = TvClient(
        app.filesDir,
        File(app.filesDir, "maatv-dump.dex").also { f -> app.assets.open("maatv-dump.dex").use { i -> f.outputStream().use { i.copyTo(it) } } },
    )
    private val runner = Runner(tv)
    private val tvLock = Mutex()

    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    private val _run = MutableStateFlow<RunState?>(null)
    val run: StateFlow<RunState?> = _run.asStateFlow()

    private val _scan = MutableStateFlow<List<String>?>(null)
    val scan: StateFlow<List<String>?> = _scan.asStateFlow()

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val _import = MutableStateFlow<ImportPreview?>(null)
    val importPreview: StateFlow<ImportPreview?> = _import.asStateFlow()

    /** Packages installed on the connected TV, to flag shows whose app is missing. */
    private val _tvApps = MutableStateFlow<Set<String>?>(null)
    val tvApps: StateFlow<Set<String>?> = _tvApps.asStateFlow()

    private var runJob: Job? = null
    private val posterCache = mutableMapOf<String, Bitmap?>()

    init {
        I18n.hindi = _data.value.language == "hi"
        if (_data.value.tvIp.isNotBlank()) connect(_data.value.tvIp)
        watchTv()
    }

    private fun update(f: (AppData) -> AppData) {
        _data.update(f)
        viewModelScope.launch(Dispatchers.IO) { store.save(_data.value) }
    }

    // ---------- Connection ----------

    fun connect(ip: String) {
        val host = ip.trim()
        if (host.isEmpty()) return
        update { it.copy(tvIp = host) }
        viewModelScope.launch(Dispatchers.IO) {
            _conn.value = Conn.Connecting
            _conn.value = try {
                tvLock.withLock { tv.connect(host) }
                Conn.Connected
            } catch (e: Exception) {
                Conn.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (_conn.value == Conn.Connected) refreshTvApps()
        }
    }

    /** Tries the saved TV again (the "Try again" button on Mom's screen). */
    fun reconnect() = connect(_data.value.tvIp)

    /**
     * Keeps the TV status honest: every few seconds, check the TV is still reachable, and
     * reconnect by itself when it comes back (e.g. after it was switched off with the remote).
     */
    private fun watchTv() = viewModelScope.launch(Dispatchers.IO) {
        while (true) {
            delay(6000)
            val ip = _data.value.tvIp
            // Don't disturb a show that is starting, or the recorder.
            if (ip.isBlank() || tvLock.isLocked || _conn.value == Conn.Connecting) continue
            val up = tv.isReachable(ip, 2500)
            when {
                !up && _conn.value !is Conn.Failed -> {
                    tv.close()
                    _conn.value = Conn.Failed("TV not reachable")
                }
                up && _conn.value !is Conn.Connected -> connect(ip)
            }
        }
    }

    /** Reconnects if needed. Returns false when the TV cannot be reached. */
    private suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        val ip = _data.value.tvIp
        if (ip.isBlank() || !tv.isReachable(ip)) {
            _conn.value = Conn.Failed("TV not reachable")
            return@withContext false
        }
        if (tv.isConnected && tv.host == ip) return@withContext true
        try {
            tv.connect(ip)
            _conn.value = Conn.Connected
            true
        } catch (e: Exception) {
            _conn.value = Conn.Failed(e.message ?: "error")
            false
        }
    }

    fun scanNetwork() {
        _scan.value = null
        viewModelScope.launch { _scan.value = TvClient.scanNetwork() }
    }

    // ---------- Mom's screen ----------

    fun poster(name: String?): Bitmap? = name?.let { posterCache.getOrPut(it) { store.loadPoster(it) } }

    fun play(tile: Tile) {
        runJob?.cancel()
        runJob = viewModelScope.launch {
            _run.value = RunState(tile.name, t("Connecting to the TV…", "TV से जुड़ रहे हैं…"), poster = tile.poster)
            try {
                if (!ensureConnected()) {
                    _run.value = RunState(
                        tile.name,
                        t(
                            "The TV is off or not on Wi-Fi.\nTurn it on with the remote, then tap again.",
                            "TV बंद है या Wi-Fi से नहीं जुड़ा।\nरिमोट से TV चालू करें, फिर दोबारा दबाएँ।",
                        ),
                        done = true,
                    )
                    return@launch
                }
                val ms = tvLock.withLock {
                    runner.runAll(tile.steps) { msg -> _run.update { it?.copy(message = msg) } }
                }
                val secs = ((ms + 500) / 1000).toInt()
                update { d -> d.copy(tiles = d.tiles.map { if (it.id == tile.id) it.copy(lastRunSeconds = secs) else it }) }
                _run.value = RunState(tile.name, t("Now playing on the TV", "TV पर चल रहा है"), done = true, ok = true, poster = tile.poster)
                delay(5000)
                if (_run.value?.ok == true) _run.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _run.value = RunState(tile.name, t("Something went wrong. Please tap again.", "कुछ गड़बड़ हुई। दोबारा दबाएँ।"), done = true, detail = e.message, poster = tile.poster)
            }
        }
    }

    fun stop() {
        runJob?.cancel()
        _run.value = null
        remote("HOME")
    }

    fun dismissRun() { _run.value = null }

    /** Remote buttons on Mom's screen and in the recorder. Fire and forget. */
    fun remote(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (ensureConnected()) tv.key(key)
            } catch (_: Exception) {}
        }
    }

    // ---------- Settings ----------

    fun setPin(pin: String) = update { it.copy(pin = pin) }

    private fun refreshTvApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _tvApps.value = try {
                tv.launchableApps().map { it.pkg }.toSet()
            } catch (e: Exception) {
                null
            }
        }
    }

    // ---------- Sharing shows with another phone ----------

    /** Writes all shows (steps and posters, nothing about this home) to a file for sharing. */
    suspend fun exportShows(): File = withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, ShowPacks.fileName()).apply {
            writeText(ShowPacks.export(_data.value.tiles) { store.posterBytes(it) })
        }
    }

    /** Reads a share file (from the import button, or opened from WhatsApp/Files) and shows the preview. */
    fun openImport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            } catch (e: Exception) {
                null
            }
            val existing = _data.value.tiles.map { it.name.lowercase() }.toSet()
            _import.value = when (val r = text?.let { ShowPacks.parse(it) } ?: ImportResult.Error("Could not open the file.")) {
                is ImportResult.Error -> ImportPreview(error = r.message)
                is ImportResult.Ok -> ImportPreview(
                    items = r.items,
                    // Tick everything this phone doesn't have yet.
                    selected = r.items.indices.filter { r.items[it].name.lowercase() !in existing }.toSet(),
                    existing = existing,
                    unsupported = r.unsupported,
                )
            }
        }
    }

    fun toggleImport(index: Int) = _import.update { p ->
        p?.copy(selected = if (index in p.selected) p.selected - index else p.selected + index)
    }

    fun cancelImport() { _import.value = null }

    /** Adds the ticked shows. Returns how many were added. */
    fun confirmImport(): Int {
        val p = _import.value ?: return 0
        val added = p.selected.sorted().map { i ->
            val item = p.items[i]
            val id = UUID.randomUUID().toString()
            Tile(id, item.name, item.poster?.let { store.savePosterBytes(id, it) }, item.steps)
        }
        update { d -> d.copy(tiles = d.tiles + added) }
        _import.value = null
        return added.size
    }

    fun setLanguage(lang: String) {
        I18n.hindi = lang == "hi"
        update { it.copy(language = lang) }
    }

    fun deleteTile(id: String) {
        val tile = _data.value.tiles.firstOrNull { it.id == id } ?: return
        store.deletePoster(tile.poster)
        posterCache.remove(tile.poster)
        update { d -> d.copy(tiles = d.tiles.filterNot { it.id == id }) }
    }

    fun moveTile(id: String, by: Int) = update { d ->
        val list = d.tiles.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in list.indices) return@update d
        list.add(j, list.removeAt(i))
        d.copy(tiles = list)
    }

    // ---------- Recorder ----------

    fun openEditor(tileId: String?) {
        val existing = _data.value.tiles.firstOrNull { it.id == tileId }
        _editor.value = EditorState(
            tileId = existing?.id ?: UUID.randomUUID().toString(),
            name = existing?.name ?: "",
            steps = existing?.steps ?: emptyList(),
            poster = poster(existing?.poster),
        )
        tvJob("Loading TV apps…") {
            val apps = tv.launchableApps()
            _editor.update { it?.copy(apps = apps) }
            refreshMirrorNow()
        }
    }

    fun closeEditor() { _editor.value = null }

    fun setEditorName(name: String) = _editor.update { it?.copy(name = name) }

    fun refreshMirror() = tvJob("Looking at the TV…") { refreshMirrorNow() }

    /** Runs a step on the TV right now and, if [record], adds it to the tile. */
    fun doStep(step: Step, record: Boolean = true, label: String = "Working on the TV…") = tvJob(label) {
        runner.runStep(step)
        if (record) _editor.update { it?.copy(steps = it.steps + step) }
        refreshMirrorNow()
    }

    fun startWithApp(app: TvApp) {
        _editor.update { it?.copy(steps = emptyList()) }
        doStep(Step.LaunchApp(app.pkg, app.name), label = "Opening ${app.name}…")
    }

    /** Called when the recorder's mirror is tapped: selects that element on the TV and records it. */
    fun selectOnMirror(node: UiNode) {
        val ed = _editor.value ?: return
        val screen = ed.screen
        val xFrac = (node.box.cx - screen.screen.l).toFloat() / screen.screen.w
        val yFrac = (node.box.cy - screen.screen.t).toFloat() / screen.screen.h
        val step = if (node.label.isNotBlank() && screen.find(node.label, xFrac, yFrac) === node) {
            Step.Select(node.label, xFrac, yFrac)
        } else {
            Step.SelectAt(xFrac, yFrac)
        }
        // The first thing picked after a search is the show: use it for the tile's name and picture.
        if (ed.steps.lastOrNull() is Step.Search) {
            val target = screen.actionTarget(node)
            val crop = cropMirror(screen.posterBox(target))
            _editor.update {
                it?.copy(
                    name = it.name.ifBlank { node.label.ifBlank { (ed.steps.last() as Step.Search).query } },
                    poster = it.poster ?: crop,
                )
            }
        }
        doStep(step, label = "Selecting on the TV…")
    }

    fun usePosterFrom(node: UiNode) {
        val ed = _editor.value ?: return
        cropMirror(ed.screen.posterBox(ed.screen.actionTarget(node)))?.let { bmp -> _editor.update { it?.copy(poster = bmp) } }
    }

    fun undoStep() = _editor.update { it?.copy(steps = it.steps.dropLast(1)) }

    fun removeStep(index: Int) = _editor.update { it?.copy(steps = it.steps.filterIndexed { i, _ -> i != index }) }

    fun testEditor() {
        val ed = _editor.value ?: return
        tvJob("Testing from the start…") {
            runner.runAll(ed.steps) { msg -> _editor.update { it?.copy(busy = msg) } }
            refreshMirrorNow()
        }
    }

    fun saveEditor(): Boolean {
        val ed = _editor.value ?: return false
        if (ed.name.isBlank() || ed.steps.isEmpty()) {
            _editor.update { it?.copy(error = "Give the tile a name and record at least one step.") }
            return false
        }
        val old = _data.value.tiles.firstOrNull { it.id == ed.tileId }
        val posterName = ed.poster?.let { store.savePoster(ed.tileId, it) } ?: old?.poster
        posterName?.let { posterCache.remove(it) }
        val tile = Tile(ed.tileId, ed.name.trim(), posterName, ed.steps)
        update { d ->
            d.copy(tiles = if (old != null) d.tiles.map { if (it.id == tile.id) tile else it } else d.tiles + tile)
        }
        _editor.value = null
        return true
    }

    private fun tvJob(label: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _editor.update { it?.copy(busy = label, error = null) }
            try {
                if (!ensureConnected()) throw IllegalStateException("Can't reach the TV. Is it on and on the same Wi-Fi?")
                tvLock.withLock { block() }
                _editor.update { it?.copy(busy = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editor.update { it?.copy(busy = null, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private suspend fun refreshMirrorNow() = withContext(Dispatchers.IO) {
        val file = File(getApplication<Application>().cacheDir, "mirror.png")
        tv.screenshot(file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = if (bounds.outWidth >= 2560) 2 else 1 }
        val bmp = BitmapFactory.decodeFile(file.path, opts)
        val screen = tv.dumpUi()
        _editor.update { it?.copy(mirror = bmp, screen = screen) }
    }

    private fun cropMirror(box: Box): Bitmap? {
        val ed = _editor.value ?: return null
        val bmp = ed.mirror ?: return null
        val sx = bmp.width.toFloat() / ed.screen.screen.w
        val sy = bmp.height.toFloat() / ed.screen.screen.h
        val l = (box.l * sx).toInt().coerceIn(0, bmp.width - 1)
        val t = (box.t * sy).toInt().coerceIn(0, bmp.height - 1)
        val w = (box.w * sx).toInt().coerceIn(1, bmp.width - l)
        val h = (box.h * sy).toInt().coerceIn(1, bmp.height - t)
        if (w < 40 || h < 40) return null
        return Bitmap.createBitmap(bmp, l, t, w, h)
    }

    override fun onCleared() {
        tv.close()
    }
}
