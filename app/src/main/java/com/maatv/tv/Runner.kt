package com.maatv.tv

import android.util.Log
import com.maatv.data.I18n.t
import com.maatv.data.Step
import com.maatv.data.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Replays a tile's steps on the TV, reading the screen to find profiles, shows and buttons. */
class Runner(private val tv: TvClient) {
    private companion object {
        const val TAG = "MaaTV"

        /** Show-page buttons that start playback, in order of preference. */
        val PLAY_WORDS = listOf(
            "resume", "continue watching", "continue", "watch latest episode", "watch now", "play now", "play", "watch",
        )

        /** Apps whose search screen opens from a link. */
        val SEARCH_LINKS = mapOf("com.sonyliv" to "sony://search")
    }

    /** The app the current tile opened, so later steps know which app they are in. */
    private var currentPkg: String? = null

    /**
     * Runs a whole tile. [say] receives short status lines for Mom's screen.
     * Returns how long it took, in milliseconds; each step's time is logged.
     */
    suspend fun runAll(steps: List<Step>, say: (String) -> Unit): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        currentPkg = null
        if (!tv.isAwake()) {
            say(t("Turning on the TV…", "TV चालू कर रहे हैं…"))
            tv.wake()
            delay(4000)
        }
        var i = 0
        while (i < steps.size) {
            coroutineContext.ensureActive()
            val t0 = System.currentTimeMillis()
            runStep(steps[i], steps.getOrNull(i + 1), say)
            Log.d(TAG, "timing: ${steps[i].describe()} took ${System.currentTimeMillis() - t0} ms")
            i++
        }
        // A tile that ends by playing must really be playing before we tell Mom it is.
        if (steps.lastOrNull() == Step.Play) {
            val t0 = System.currentTimeMillis()
            val deadline = t0 + 20_000
            while (!tv.isPlaying()) {
                if (System.currentTimeMillis() > deadline) throw TvException("The video did not start")
                delay(800)
            }
            Log.d(TAG, "timing: waiting for video took ${System.currentTimeMillis() - t0} ms")
        }
        val total = System.currentTimeMillis() - start
        Log.d(TAG, "timing: total $total ms")
        total
    }

    /** Reads the screen until [ready] is true, for up to [timeoutMs]. Returns that screen, or null. */
    private suspend fun waitUntil(timeoutMs: Long, ready: (UiScreen) -> Boolean): UiScreen? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val s = tv.dumpUi()
            if (ready(s)) return s
            if (System.currentTimeMillis() > deadline) return null
            delay(300)
        }
    }

    /**
     * Runs one step. [next] lets a Select give up early when the app skipped a screen,
     * e.g. it remembered the profile and went straight to the home page.
     */
    suspend fun runStep(step: Step, next: Step? = null, say: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        when (step) {
            is Step.LaunchApp -> {
                say(t("Opening ${step.appName}…", "${step.appName} खोल रहे हैं…"))
                currentPkg = step.pkg
                // Apps whose search opens from a link (SonyLIV) don't need a clean restart:
                // the link opens search from any screen, even when the app isn't running.
                if (!(step.pkg in SEARCH_LINKS && next is Step.Search)) {
                    tv.key("HOME")
                    tv.forceStop(step.pkg)
                    delay(600)
                    tv.launch(step.pkg)
                    waitForApp(step.pkg)
                }
            }
            is Step.Select -> {
                say(t("Selecting “${step.label}”…", "चुन रहे हैं: ${step.label}"))
                val found = waitAndSelect({ s -> s.find(step.label, step.xFrac, step.yFrac) }, finderFor(next))
                if (found == Outcome.NOT_FOUND) {
                    // The last step is what Mom asked for ("Watch Latest Episode"): missing it is a failure.
                    if (next == null) throw TvException("“${step.label}” not found on the TV")
                    say(t("Couldn’t find “${step.label}”, continuing…", "“${step.label}” नहीं मिला, आगे बढ़ रहे हैं…"))
                }
            }
            is Step.SelectAt -> {
                say(t("Selecting…", "चुन रहे हैं…"))
                delay(1500)
                val s = tv.dumpUi()
                val x = (s.screen.l + step.xFrac * s.screen.w).toInt()
                val y = (s.screen.t + step.yFrac * s.screen.h).toInt()
                if (!navigateAndSelect(s) { it.at(x, y) }) tv.tap(x, y)
                delay(1500)
            }
            is Step.Search -> {
                say(t("Searching for the show…", "शो ढूँढ रहे हैं…"))
                search(step.query, (next as? Step.Select)?.label)
            }
            is Step.TypeText -> {
                tv.typeText(step.text)
                delay(1500)
            }
            is Step.Key -> {
                tv.key(step.key)
                delay(1200)
            }
            is Step.Wait -> delay(step.seconds * 1000L)
            Step.Play -> {
                say(t("Starting the episode…", "एपिसोड शुरू कर रहे हैं…"))
                play()
            }
        }
    }

    /** Waits (up to ~20 s) until the app is past its splash screen and shows something selectable. */
    private suspend fun waitForApp(pkg: String) {
        delay(1000)
        waitUntil(19_000) { s -> s.pkg == pkg && s.nodes.any { it.actionable && it.box.area > 0 } && s.labels().isNotEmpty() }
        delay(300)
    }

    /** Finds the show page's play button, best first: carry on where Mom left off, else the newest. */
    private fun findPlayButton(s: UiScreen): UiNode? {
        val labelled = s.nodes.filter { it.box.area > 0 && it.label.isNotBlank() }
        for (word in PLAY_WORDS) {
            labelled.firstOrNull { n ->
                val l = UiScreen.norm(n.label)
                l.startsWith(word) && "list" !in l && "trailer" !in l
            }?.let { return it }
        }
        return null
    }

    private suspend fun play() {
        // Picking a video (rather than a show) may already have started it; players can take
        // several seconds to begin, so keep checking while waiting for the page's buttons.
        repeat(6) {
            if (tv.isPlaying()) return
            val s = tv.dumpUi()
            findPlayButton(s)?.let { button ->
                val label = button.label
                Log.d(TAG, "play: pressing '$label'")
                navigateAndSelect(s) { findPlayButton(it)?.takeIf { n -> n.label == label } }
                return
            }
            delay(600)
        }
        // No play button (SonyLIV shows only "My List" until something is watched):
        // open Episodes and start the first one. Next time the app offers Resume.
        if (tv.isPlaying()) return
        var s = tv.dumpUi()
        if (s.find("Episodes") != null) {
            navigateAndSelect(s) { it.find("Episodes") }
            s = waitUntil(5000) { it.nodes.any { n -> n.resId.endsWith("episode_item_title") } } ?: tv.dumpUi()
        }
        val first = s.nodes
            .filter { (it.resId.endsWith("episode_item_title") || it.resId.endsWith("episode_num_name")) && it.box.area > 0 }
            .minWithOrNull(compareBy<UiNode>({ it.box.t }, { it.box.l }))
            ?: throw TvException("No play button or episode found")
        val label = first.label
        Log.d(TAG, "play: no play button, starting first episode '$label'")
        navigateAndSelect(s) { it.find(label) }
    }

    private enum class Outcome { SELECTED, NEXT_ALREADY_VISIBLE, NOT_FOUND }

    private fun finderFor(step: Step?): ((UiScreen) -> UiNode?)? = when (step) {
        is Step.Select -> { s -> s.find(step.label, step.xFrac, step.yFrac) }
        is Step.Search -> { s -> s.findSearchEntry() }
        else -> null
    }

    private suspend fun waitAndSelect(
        find: (UiScreen) -> UiNode?,
        nextFind: ((UiScreen) -> UiNode?)?,
        timeoutMs: Long = 15_000,
    ): Outcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = tv.dumpUi()
            if (find(s) != null) {
                navigateAndSelect(s, find)
                // The next step waits for its own screen, so only a short settle here.
                delay(500)
                return Outcome.SELECTED
            }
            if (nextFind != null && nextFind(s) != null) return Outcome.NEXT_ALREADY_VISIBLE
            delay(300)
        }
        return Outcome.NOT_FOUND
    }

    /**
     * Selects an element. On TV apps a tap usually only moves the highlight, so: tap it,
     * check the highlight landed there, then press OK. If the tap already opened something,
     * stop. If the highlight went elsewhere, walk there with the arrow keys instead.
     */
    private suspend fun navigateAndSelect(first: UiScreen, find: (UiScreen) -> UiNode?): Boolean {
        val n0 = find(first) ?: return false
        val t0 = first.actionTarget(n0)
        Log.d(TAG, "select: tap '${n0.label}' ${t0.box}")
        tv.tap(t0.box.cx, t0.box.cy)
        delay(400)
        var after = tv.dumpUi()
        if (after.isSameScreenAs(first)) {
            // Some apps react to a tap slowly; look again before pressing OK, or OK lands on the next screen.
            delay(700)
            after = tv.dumpUi()
        }
        // The tap opened it if the element is gone, or the whole screen changed
        // (e.g. ZEE5's home also shows "pr", in its menu, after the profile screen).
        if (!after.isSameScreenAs(first)) return true.also { Log.d(TAG, "select: screen changed, done") }
        val n1 = find(after) ?: return true.also { Log.d(TAG, "select: target gone, done") }
        val t1 = after.actionTarget(n1)
        val f1 = after.focused()
        Log.d(TAG, "select: focus=${f1?.box} target=${t1.box}")
        if (f1 != null && isOn(after, f1, t1)) {
            // Highlighted but not opened. A second tap usually opens it (ZEE5's menu even ignores OK);
            // if not, OK.
            tv.tap(t1.box.cx, t1.box.cy)
            delay(900)
            val again = tv.dumpUi()
            if (!again.isSameScreenAs(after) || find(again) == null) return true.also { Log.d(TAG, "select: 2nd tap opened it") }
            Log.d(TAG, "select: pressing OK")
            tv.key("DPAD_CENTER")
            return true
        }
        return navigateWithArrows(after, find)
    }

    private fun isOn(s: UiScreen, focus: UiNode, target: UiNode) =
        target.box.containsCenterOf(focus.box) || focus.box.containsCenterOf(target.box) ||
            s.actionTarget(focus) === target

    /** Moves the TV's focus to the element with the arrow keys and presses OK, the way a person would. */
    private suspend fun navigateWithArrows(first: UiScreen, find: (UiScreen) -> UiNode?): Boolean {
        var s = first
        var lastFocus: Box? = null
        var stuck = 0
        repeat(25) {
            val n = find(s) ?: return false
            val target = s.actionTarget(n)
            val focus = s.focused()
            if (focus == null) {
                tv.tap(target.box.cx, target.box.cy)
                return true
            }
            if (isOn(s, focus, target)) {
                tv.key("DPAD_CENTER")
                return true
            }
            stuck = if (focus.box == lastFocus) stuck + 1 else 0
            if (stuck >= 2) {
                tv.tap(target.box.cx, target.box.cy)
                return true
            }
            lastFocus = focus.box

            val dx = target.box.cx - focus.box.cx
            val dy = target.box.cy - focus.box.cy
            val sameRow = target.box.t < focus.box.b && focus.box.t < target.box.b
            val sameCol = target.box.l < focus.box.r && focus.box.l < target.box.r
            var horizontal = when {
                sameRow -> true
                sameCol -> false
                else -> abs(dx) > abs(dy) * 3 // TV layouts are rows: go vertical first
            }
            if (stuck == 1) horizontal = !horizontal
            tv.key(
                if (horizontal) (if (dx > 0) "DPAD_RIGHT" else "DPAD_LEFT")
                else (if (dy > 0) "DPAD_DOWN" else "DPAD_UP")
            )
            delay(350)
            s = tv.dumpUi()
        }
        val n = find(s) ?: return false
        val t = s.actionTarget(n)
        tv.tap(t.box.cx, t.box.cy)
        return true
    }

    /**
     * Opens the app's search screen and types the query. [showTitle] is the result the next step
     * picks; if the app lists it under recent searches, one tap replaces typing.
     */
    private suspend fun search(query: String, showTitle: String?) {
        // In the recorder, Search can be pressed without opening the app first: ask the screen.
        val pkg = currentPkg ?: tv.dumpUi().pkg
        val link = SEARCH_LINKS[pkg]
        if (link != null) {
            // Some apps (SonyLIV) have unlabelled menu icons, but open search from a link.
            tv.openLink(link, pkg, fresh = true)
            val s = waitUntil(20_000) { OnScreenKeyboard.from(it) != null || it.findTextField() != null }
                ?: throw TvException("The search screen did not open")
            // SonyLIV fills in its recent searches a moment after the keyboard appears.
            val titles = listOfNotNull(showTitle, query)
            val withRecent = waitUntil(4000) { recentSearch(it, titles) != null }
            Log.d(TAG, "search: want $titles; screen has ${(withRecent ?: s).labels().map { it.label }.take(12)}")
            if (withRecent != null && pickRecentSearch(withRecent, titles)) return
            type(query, withRecent ?: s)
            return
        }
        var s = tv.dumpUi()
        for (attempt in 1..5) {
            if (s.findSearchEntry() != null) break
            // Many TV apps (ZEE5 included) hide Search in a menu that opens from the left edge.
            // If we are on some inner page, Back first.
            tv.key(if (attempt == 3) "BACK" else "DPAD_LEFT")
            delay(1500)
            s = tv.dumpUi()
        }
        Log.d(TAG, "search: entry=${s.findSearchEntry()?.let { it.label + " " + it.resId + " " + it.box }}")
        if (s.findSearchEntry() == null) throw TvException("Could not find a Search button in this app")
        navigateAndSelect(s) { it.findSearchEntry() }
        delay(2500)
        type(query)
    }

    /** Taps the show in the app's "recent searches" list, if it is there. True if the search box got it. */
    private fun recentSearch(screen: UiScreen, titles: List<String>): UiNode? {
        val wanted = titles.map { UiScreen.norm(it) }
        return screen.nodes.firstOrNull { n ->
            n.box.area > 0 && !n.cls.endsWith("EditText") && UiScreen.norm(n.label) in wanted
        }
    }

    private suspend fun pickRecentSearch(s: UiScreen, titles: List<String>): Boolean {
        val item = recentSearch(s, titles) ?: return false
        Log.d(TAG, "search: picking recent search '${item.label}'")
        navigateAndSelect(s) { recentSearch(it, titles) }
        val filled = waitUntil(4000) { hasTyped(it, item.label) }
        Log.d(TAG, "search: recent search ${if (filled != null) "filled the box" else "did not fill the box"}")
        return filled != null
    }

    private fun hasTyped(s: UiScreen, query: String): Boolean {
        val typed = s.findTextField()?.text?.lowercase()?.filterNot { it.isWhitespace() } ?: return false
        return typed.contains(query.lowercase().filterNot { it.isWhitespace() })
    }

    /**
     * Types into the search box. If the app shows its own on-screen keyboard, use it: in some apps
     * (SonyLIV) typed letters act like remote keys instead, move the highlight onto a result, and
     * the next OK would play it. Otherwise send the text directly.
     */
    private suspend fun type(query: String, current: UiScreen? = null) {
        var s = current ?: tv.dumpUi()
        val keyboard = OnScreenKeyboard.from(s)
        if (keyboard != null && keyboard.canType(query)) {
            var kb: OnScreenKeyboard = keyboard
            for (attempt in 1..2) {
                if (attempt > 1) kb = OnScreenKeyboard.from(tv.dumpUi()) ?: kb
                val t0 = System.currentTimeMillis()
                val out = tv.typeOnKeyboard(kb.spec(query))
                if (out == null) tv.inject(kb.ops(query))
                Log.d(TAG, "type: used the app's on-screen keyboard in ${System.currentTimeMillis() - t0} ms")
                // The helper reports the box's final text; trust it instead of reading the screen again.
                val typed = out?.substringAfterLast("typed: ", "")?.lineSequence()?.firstOrNull()
                if (typed != null && typed.lowercase().filterNot { it.isWhitespace() }
                        .contains(query.lowercase().filterNot { it.isWhitespace() })
                ) return
                delay(800)
                s = tv.dumpUi()
                if (hasTyped(s, query)) return
                // A letter got lost (the highlight jumped mid-typing). Clear the box and type again.
                val got = s.findTextField()?.text ?: ""
                Log.d(TAG, "type: got '$got'")
                val clear = kb.clearOps(got.length + 1) ?: break
                tv.inject(clear)
                delay(1500)
            }
            // Don't fall back to typed text here: in these apps it moves the highlight onto results.
            Log.d(TAG, "type: on-screen keyboard gave '${s.findTextField()?.text}'")
            return
        }

        // Most TV apps (ZEE5 included) take typed letters even with their own on-screen keyboard.
        // Only if the text did not land in the search box, select the box and type again.
        tv.typeText(query)
        delay(1500)
        s = tv.dumpUi()
        val field = s.findTextField()
        // Compare without spaces: some in-app keyboards (ZEE5) drop typed spaces, and search still works.
        val typed = field?.text?.lowercase()?.filterNot { it.isWhitespace() } ?: ""
        if (field != null && !typed.contains(query.lowercase().filterNot { it.isWhitespace() })) {
            navigateAndSelect(s) { it.findTextField() }
            delay(1000)
            tv.typeText(query)
            delay(1500)
        }
        if (tv.isKeyboardShown()) tv.key("ENTER")
        delay(3000)
    }
}
