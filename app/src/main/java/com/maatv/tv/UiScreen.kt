package com.maatv.tv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    val area get() = w.toLong() * h
    fun contains(x: Int, y: Int) = x in l until r && y in t until b
    fun containsCenterOf(o: Box) = contains(o.cx, o.cy)

    companion object {
        private val RE = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
        fun parse(s: String?): Box {
            val m = s?.let { RE.find(it) } ?: return Box(0, 0, 0, 0)
            val (l, t, r, b) = m.destructured
            return Box(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }
    }
}

class UiNode(
    val text: String,
    val desc: String,
    val resId: String,
    val cls: String,
    val box: Box,
    val clickable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val pkg: String,
    val parent: UiNode?,
) {
    /** What a person would read on this element. */
    val label: String get() = text.ifBlank { desc }.trim()
    val actionable get() = clickable || focusable
}

/** One snapshot of what is on the TV screen, parsed from `uiautomator dump`. */
class UiScreen(val nodes: List<UiNode>) {

    val screen: Box = nodes.firstOrNull()?.box ?: Box(0, 0, 1920, 1080)

    /** The app on screen. */
    val pkg: String = nodes.firstOrNull()?.pkg ?: ""

    fun focused(): UiNode? = nodes.filter { it.focused && it.box.area > 0 }.minByOrNull { it.box.area }

    /** Distinct visible labels, top-to-bottom, left-to-right. */
    fun labels(): List<UiNode> = nodes
        .filter { it.label.isNotBlank() && it.box.area > 0 }
        .distinctBy { it.label to it.box }
        .sortedWith(compareBy({ it.box.t / 40 }, { it.box.l }))

    /**
     * Finds an element by its visible text: exact match first, then "contains".
     * When the text appears more than once, [nearX]/[nearY] (screen fractions) pick the closest.
     */
    fun find(label: String, nearX: Float? = null, nearY: Float? = null): UiNode? {
        val want = norm(label)
        if (want.isEmpty()) return null
        val visible = nodes.filter { it.box.area > 0 && it.label.isNotBlank() }
        fun List<UiNode>.best(): UiNode? {
            if (nearX == null || nearY == null) return firstOrNull()
            val x = screen.l + nearX * screen.w
            val y = screen.t + nearY * screen.h
            return minByOrNull { (it.box.cx - x) * (it.box.cx - x) + (it.box.cy - y) * (it.box.cy - y) }
        }
        return visible.filter { norm(it.label) == want }.best()
            ?: visible.filter { norm(it.label).contains(want) }.sortedBy { it.label.length }.best()
            ?: visible.filter { want.contains(norm(it.label)) && it.label.length >= 3 }.sortedByDescending { it.label.length }.best()
    }

    /** The smallest interesting element under a point (used when tapping the TV mirror). */
    fun at(x: Int, y: Int): UiNode? = nodes
        .filter { it.box.contains(x, y) && it.box.area > 0 && it.box != screen && (it.actionable || it.label.isNotBlank()) }
        // Smallest first; on a tie (a frame and its labelled picture), the one with text, so it is recorded by name.
        .minWithOrNull(compareBy<UiNode> { it.box.area }.thenBy { it.label.isBlank() })

    /** The element the remote's OK button would act on for this node. */
    fun actionTarget(n: UiNode): UiNode {
        var cur: UiNode? = n
        while (cur != null) {
            if (cur.actionable && cur.box != screen) return cur
            cur = cur.parent
        }
        return n
    }

    /**
     * The part of [target] to use as a poster: its biggest picture, so cards that show the title
     * and details beside the image (SonyLIV's search results) give a clean poster.
     */
    fun posterBox(target: UiNode): Box {
        fun inside(n: UiNode): Boolean {
            var p = n.parent
            while (p != null) {
                if (p === target) return true
                p = p.parent
            }
            return false
        }
        return nodes
            .filter { it.cls.endsWith("ImageView") && it.box.area * 4 >= target.box.area && inside(it) }
            .maxByOrNull { it.box.area }?.box ?: target.box
    }

    fun findSearchEntry(): UiNode? = nodes.filter { it.box.area > 0 }.firstOrNull { n ->
        val hay = (n.label + " " + n.resId).lowercase()
        hay.contains("search") || hay.contains("खोज") || hay.contains("सर्च")
    }

    /** True when most of the text on screen is the same, i.e. we are still on the same page. */
    fun isSameScreenAs(other: UiScreen): Boolean {
        val a = labels().map { norm(it.label) }.toSet()
        val b = other.labels().map { norm(it.label) }.toSet()
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() == b.isEmpty()
        return (a intersect b).size.toFloat() / (a union b).size >= 0.5f
    }

    fun findTextField(): UiNode? = nodes.firstOrNull { it.cls.endsWith("EditText") && it.box.area > 0 }

    companion object {
        val EMPTY = UiScreen(emptyList())

        fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

        fun parse(xml: String): UiScreen {
            val nodes = mutableListOf<UiNode>()
            val stack = ArrayDeque<UiNode?>()
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            try {
                while (true) {
                    when (p.next()) {
                        XmlPullParser.START_TAG -> if (p.name == "node") {
                            val n = UiNode(
                                text = p.getAttributeValue(null, "text") ?: "",
                                desc = p.getAttributeValue(null, "content-desc") ?: "",
                                resId = p.getAttributeValue(null, "resource-id") ?: "",
                                cls = p.getAttributeValue(null, "class") ?: "",
                                box = Box.parse(p.getAttributeValue(null, "bounds")),
                                clickable = p.getAttributeValue(null, "clickable") == "true",
                                focusable = p.getAttributeValue(null, "focusable") == "true",
                                focused = p.getAttributeValue(null, "focused") == "true",
                                pkg = p.getAttributeValue(null, "package") ?: "",
                                parent = stack.lastOrNull(),
                            )
                            nodes += n
                            stack.addLast(n)
                        }
                        XmlPullParser.END_TAG -> if (p.name == "node") stack.removeLastOrNull()
                        XmlPullParser.END_DOCUMENT -> break
                    }
                }
            } catch (e: Exception) {
                // A truncated dump still gives us the nodes read so far.
            }
            return UiScreen(nodes)
        }
    }
}

/**
 * An app's own on-screen keyboard (a grid of letter buttons), for apps that ignore typed text.
 */
class OnScreenKeyboard private constructor(
    private val keys: Map<Char, UiNode>,
    private val space: UiNode?,
    private val delete: UiNode?,
    /** The key highlighted right now, if any. */
    private val focused: Char?,
) {

    fun canType(text: String) = text.lowercase().all { it == ' ' && space != null || it in keys }

    /**
     * Input ops for [TvClient.inject] that type [text]. On a TV keyboard a tap on a key highlights
     * it and a tap on the highlighted key presses it, so each letter is tapped twice (once if it is
     * already highlighted). No OK presses: some apps (SonyLIV) move the highlight to the top search
     * result whenever results refresh, and an OK would then play it; a stray tap only loses a letter.
     * Space buttons press on the first tap and leave the highlight where it was.
     */
    fun ops(text: String): String {
        var highlighted = focused
        return text.lowercase().mapNotNull { c ->
            if (c == ' ') return@mapNotNull space?.let { "t:${it.box.cx}:${it.box.cy},s:1200" }
            val k = keys[c] ?: return@mapNotNull null
            val tap = "t:${k.box.cx}:${k.box.cy},s:120"
            (if (highlighted == c) tap else "$tap,$tap").also { highlighted = c }
        }.joinToString(",")
    }

    /** Ops that press the app's Delete button [count] times (it presses on each tap). */
    fun clearOps(count: Int): String? =
        delete?.let { d -> List(count) { "t:${d.box.cx}:${d.box.cy},s:120" }.joinToString(",") }

    /** Key positions for [TvClient.typeOnKeyboard], which taps each key until its letter appears. */
    fun spec(text: String): String = buildList {
        delete?.let { add("~:${it.box.cx}:${it.box.cy}") }
        text.lowercase().forEach { c ->
            val k = if (c == ' ') space else keys[c]
            if (k != null) add("${if (c == ' ') '_' else c}:${k.box.cx}:${k.box.cy}")
        }
    }.joinToString(",")

    companion object {
        fun from(s: UiScreen): OnScreenKeyboard? {
            fun isKey(c: Char) = c.lowercaseChar() in 'a'..'z' || c.isDigit()
            val keys = s.nodes
                .filter { it.actionable && it.box.area > 0 && it.label.length == 1 && isKey(it.label[0]) }
                .groupBy { it.label[0].lowercaseChar() }
                .mapValues { (_, n) -> n.first() }
                .toMutableMap()
            // Some keyboards put the letter on a child TextView of the button.
            s.nodes.filter { it.label.length == 1 && isKey(it.label[0]) && it.label[0].lowercaseChar() !in keys }
                .forEach { keys[it.label[0].lowercaseChar()] = s.actionTarget(it) }
            if (keys.keys.count { it in 'a'..'z' } < 20) return null
            fun named(vararg words: String) = s.nodes.firstOrNull { n ->
                n.actionable && (n.label + " " + n.resId + " " + n.desc).lowercase().let { h -> words.any { h.contains(it) } }
            }
            val focused = keys.entries.firstOrNull { (_, n) -> n.focused || s.focused()?.let { s.actionTarget(it) === n } == true }?.key
            return OnScreenKeyboard(keys, named("space"), named("delete", "backspace", "clear_one"), focused)
        }
    }
}
