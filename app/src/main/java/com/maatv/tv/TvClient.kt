package com.maatv.tv

import android.util.Log
import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class TvException(message: String) : Exception(message)

/** A launchable app on the TV. */
data class TvApp(val pkg: String, val name: String)

/**
 * Talks to the Google TV over Wi-Fi ADB (port 5555).
 * All methods block, so call them from Dispatchers.IO.
 */
/**
 * @param dumpDex the screen-reading helper (assets/maatv-dump.dex), copied to the TV on connect.
 */
class TvClient(private val keyDir: File, private val dumpDex: File) {

    @Volatile private var dadb: Dadb? = null
    @Volatile var host: String = ""
        private set

    private val keyPair: AdbKeyPair by lazy {
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        AdbKeyPair.read(priv, pub)
    }

    /** Quick TCP check, so we never hang on a TV that is fully off. */
    fun isReachable(h: String = host, timeoutMs: Int = 4000): Boolean = (1..2).any {
        try {
            Socket().use { it.connect(InetSocketAddress(h, ADB_PORT), timeoutMs); true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Connects and runs a test command. The first time, the TV shows an
     * "Allow USB debugging?" prompt; this waits until someone presses Allow.
     */
    @Synchronized
    fun connect(h: String) {
        if (!isReachable(h)) throw TvException("TV not reachable at $h")
        close()
        host = h
        // 90 s read timeout: long enough to press "Allow" on the TV, short enough never to hang forever.
        dadb = Dadb.create(h, ADB_PORT, keyPair, 4000, 90_000)
        val out = dadb!!.shell("echo maatv-ok").allOutput
        if (!out.contains("maatv-ok")) throw TvException("Unexpected reply from TV: $out")
        installDumper()
    }

    @Synchronized
    fun close() {
        synchronized(helperLock) { stopHelper() }
        try { dadb?.close() } catch (_: Exception) {}
        dadb = null
    }

    val isConnected get() = dadb != null

    fun shell(cmd: String): String {
        val d = dadb ?: throw TvException("Not connected to TV")
        return try {
            d.shell(cmd).allOutput
        } catch (e: Exception) {
            Log.w(TAG, "shell failed: $cmd", e)
            // The connection may have dropped (TV slept, Wi-Fi blip). Reconnect once.
            if (host.isEmpty()) throw e
            connect(host)
            dadb!!.shell(cmd).allOutput
        }
    }

    // ---- Remote buttons ----

    fun key(name: String) {
        val code = android.view.KeyEvent.keyCodeFromString("KEYCODE_$name")
        if (code == android.view.KeyEvent.KEYCODE_UNKNOWN || helperCall("input k:$code") == null) {
            shell("input keyevent KEYCODE_$name")
        }
    }

    fun tap(x: Int, y: Int) {
        if (helperCall("input t:$x:$y") == null) shell("input tap $x $y")
    }

    /** Types ASCII text into the focused field (Hindi is not supported by `input text`). */
    fun typeText(text: String) {
        val escaped = text.replace("'", "'\\''").replace(" ", "%s")
        shell("input text '$escaped'")
    }

    fun isKeyboardShown(): Boolean = shell("dumpsys input_method | grep mInputShown").contains("mInputShown=true")

    /** Sends a quick sequence of taps/keys (see Dump.java for the ops format). */
    fun inject(ops: String) {
        if (helperCall("input $ops") != null) return
        if (hasDumper) {
            shell("CLASSPATH=$DUMPER_DEX:/system/framework/uiautomator.jar app_process /system/bin com.maatv.dump.Dump input $ops")
            return
        }
        // Without the helper, the same thing with one slow `input` command per step.
        shell(ops.split(",").joinToString("; ") { op ->
            val p = op.split(":")
            when (p[0]) {
                "t" -> "input tap ${p[1]} ${p[2]}"
                "k" -> "input keyevent ${p[1]}"
                else -> "sleep 0.${p[1].padStart(3, '0')}"
            }
        })
    }

    /**
     * Types on an app's own keyboard while watching the search box (see Dump.java). Returns the
     * helper's output (ending "typed: <box text>"), or null if the helper is not installed, so the
     * caller can fall back to blind taps.
     */
    fun typeOnKeyboard(spec: String): String? {
        if (!hasDumper) return null
        return helperCall("type $spec")
            ?: shell("CLASSPATH=$DUMPER_DEX:/system/framework/uiautomator.jar app_process /system/bin com.maatv.dump.Dump type $spec")
    }

    /** True while a video (or its ad) is playing on the TV. */
    fun isPlaying(): Boolean = shell("dumpsys media_session").contains("state=PlaybackState {state=3")

    // ---- Power ----

    fun isAwake(): Boolean = shell("dumpsys power | grep -m1 mWakefulness=").contains("Awake")

    fun wake() { key("WAKEUP") }

    // ---- Apps ----

    fun forceStop(pkg: String) { shell("am force-stop $pkg") }

    fun launch(pkg: String) {
        val out = shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1")
        if (out.contains("No activities found")) {
            shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        }
    }

    /**
     * Opens [uri] in [pkg]. With [fresh], the app's screens are cleared first (its process is kept,
     * so this is quicker than a restart): some apps (SonyLIV) ignore links while they are open in
     * the background and just show their last screen.
     */
    fun openLink(uri: String, pkg: String, fresh: Boolean = false) {
        val flags = if (fresh) "-f 0x10008000 " else "" // NEW_TASK | CLEAR_TASK
        shell("am start -a android.intent.action.VIEW -d '${uri.replace("'", "")}' $flags$pkg")
    }

    fun launchableApps(): List<TvApp> {
        val out = shell(
            "cmd package query-activities --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.LEANBACK_LAUNCHER"
        )
        var pkgs = out.lines().map { it.trim() }.filter { it.contains('/') }.map { it.substringBefore('/') }
        if (pkgs.isEmpty()) {
            pkgs = shell("pm list packages -3").lines().filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
        }
        return pkgs.distinct()
            .map { TvApp(it, KNOWN_APPS[it] ?: prettify(it)) }
            .sortedWith(compareBy({ it.pkg !in KNOWN_APPS }, { it.name.lowercase() }))
    }

    // ---- Seeing the screen ----

    private var screenW = 1920
    private var screenH = 1080
    @Volatile private var hasDumper = false

    // ---- The always-running helper (Dump.java "serve") ----

    private var helper: AdbShellStream? = null
    private val helperLock = Any()
    /** After repeated helper failures, use the slower way for a while instead of retrying every call. */
    private var helperPausedUntil = 0L

    /**
     * Sends one command to the helper that stays running on the TV, starting it if needed.
     * Returns its output, or null if it isn't available (callers then use the slower way).
     */
    private fun helperCall(cmd: String): String? = synchronized(helperLock) {
        if (!hasDumper || System.currentTimeMillis() < helperPausedUntil) return null
        repeat(2) {
            try {
                val h = helper ?: startHelper()
                h.write("$cmd\n")
                return readUntil(h, "##DONE##")
            } catch (e: Exception) {
                Log.w(TAG, "helper failed on '$cmd', restarting it", e)
                stopHelper()
            }
        }
        helperPausedUntil = System.currentTimeMillis() + 30_000
        null
    }

    private fun startHelper(): AdbShellStream {
        val d = dadb ?: throw TvException("Not connected to TV")
        // Only one program can read the TV's screen at a time: make sure no old helper is left.
        killHelpers()
        val h = d.openShell("CLASSPATH=$DUMPER_DEX:/system/framework/uiautomator.jar app_process /system/bin com.maatv.dump.Dump serve")
        readUntil(h, "##READY##")
        helper = h
        return h
    }

    private fun stopHelper() {
        try { helper?.close() } catch (_: Exception) {}
        helper = null
        // Closing the connection doesn't always end the program on the TV; a stuck one would
        // block every later screen read, so stop it there too.
        killHelpers()
    }

    private fun killHelpers() {
        try { dadb?.shell("pkill -f com.maatv.dump.Dump") } catch (_: Exception) {}
    }

    private fun readUntil(h: AdbShellStream, marker: String): String {
        val out = StringBuilder()
        while (true) {
            when (val p = h.read()) {
                is AdbShellPacket.StdOut -> out.append(String(p.payload))
                is AdbShellPacket.Exit -> throw TvException("helper stopped")
                else -> {}
            }
            val i = out.indexOf(marker)
            if (i >= 0) return out.substring(0, i)
        }
    }

    private fun installDumper() {
        hasDumper = try {
            dadb!!.push(dumpDex, DUMPER_DEX, 420 /* 0644 */, System.currentTimeMillis())
            Regex("""(\d+)x(\d+)""").findAll(shell("wm size")).lastOrNull()?.destructured?.let { (w, h) ->
                screenW = w.toInt(); screenH = h.toInt()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not install screen reader helper", e)
            false
        }
    }

    /**
     * Returns the accessibility tree of the current TV screen. Uses our helper, which works
     * on animated screens; falls back to `uiautomator dump`, which waits for the screen to be still.
     */
    fun dumpUi(): UiScreen {
        // Fast path: the always-running helper writes the screen, then we just read the file.
        helperCall("dump $UI_FILE $screenW $screenH")?.let { out ->
            if ("dumped to" in out) {
                val xml = shell("cat $UI_FILE 2>/dev/null")
                val start = xml.indexOf("<?xml")
                if (start >= 0) return UiScreen.parse(xml.substring(start))
            }
        }
        repeat(4) { attempt ->
            // Delete first: a failed dump would otherwise return the previous screen.
            val reader = if (hasDumper && attempt < 3) {
                "CLASSPATH=$DUMPER_DEX:/system/framework/uiautomator.jar app_process /system/bin " +
                    "com.maatv.dump.Dump $UI_FILE $screenW $screenH"
            } else {
                "uiautomator dump $UI_FILE"
            }
            val xml = shell("rm -f $UI_FILE; $reader >/dev/null 2>&1; cat $UI_FILE 2>/dev/null")
            val start = xml.indexOf("<?xml")
            if (start >= 0) return UiScreen.parse(xml.substring(start))
            Thread.sleep(700)
        }
        return UiScreen.EMPTY
    }

    fun screenshot(dst: File) {
        shell("screencap -p $SHOT_FILE")
        val d = dadb ?: throw TvException("Not connected to TV")
        d.pull(dst, SHOT_FILE)
    }

    companion object {
        const val ADB_PORT = 5555
        private const val TAG = "MaaTV"
        private const val UI_FILE = "/sdcard/maatv_ui.xml"
        private const val DUMPER_DEX = "/data/local/tmp/maatv-dump.dex"
        private const val SHOT_FILE = "/sdcard/maatv_shot.png"

        val KNOWN_APPS = mapOf(
            "com.graymatrix.did" to "ZEE5",
            "com.sonyliv" to "SonyLIV",
            "in.startv.hotstar" to "JioHotstar",
            "in.startv.hotstaronly" to "JioHotstar",
            "com.jio.media.ondemand" to "JioCinema",
            "com.google.android.youtube.tv" to "YouTube",
            "com.google.android.youtube.tvkids" to "YouTube Kids",
            "com.netflix.ninja" to "Netflix",
            "com.amazon.amazonvideo.livingroom" to "Prime Video",
            "com.tv.v18.viola" to "JioCinema",
            "com.mxtech.videoplayer.television" to "MX Player",
            "com.balaji.alt" to "ALTT",
            "com.ryzmedia.mxplayer" to "MX Player",
            "com.google.android.youtube.tvmusic" to "YouTube Music",
            "com.spotify.tv.android" to "Spotify",
            "com.google.android.tv" to "Live TV",
            "com.google.android.tvlauncher" to "Home",
        )

        private fun prettify(pkg: String): String =
            pkg.split('.').filter { it !in setOf("com", "in", "tv", "android", "app", "google") }
                .lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg

        /** Finds devices on the phone's Wi-Fi network with the ADB port open. */
        suspend fun scanNetwork(): List<String> = withContext(Dispatchers.IO) {
            val prefixes = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress!!.substringBeforeLast('.') }
                .distinct()
            val gate = Semaphore(48)
            coroutineScope {
                prefixes.flatMap { p -> (1..254).map { "$p.$it" } }.map { ip ->
                    async {
                        gate.withPermit {
                            try {
                                // Home Wi-Fi with power saving can take well over 400 ms to answer.
                                Socket().use { it.connect(InetSocketAddress(ip, ADB_PORT), 1500) }
                                ip
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }
    }
}
