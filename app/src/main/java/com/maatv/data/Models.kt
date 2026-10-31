package com.maatv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One recorded action. A tile is a list of these, replayed in order. */
@Serializable
sealed interface Step {
    /** Fresh start: Home, force-stop the app, open it. */
    @Serializable @SerialName("launch")
    data class LaunchApp(val pkg: String, val appName: String) : Step

    /** Waits for an element with this text (profile name, show title, "Play"...) and selects it. */
    @Serializable @SerialName("select")
    data class Select(
        val label: String,
        /** Where it was when recorded (fractions of the screen), to pick the right one if the text repeats. */
        val xFrac: Float? = null,
        val yFrac: Float? = null,
    ) : Step

    /** Selects whatever element is at this spot (for pictures with no text). Fractions of the screen. */
    @Serializable @SerialName("selectAt")
    data class SelectAt(val xFrac: Float, val yFrac: Float) : Step

    /** Opens the app's search and types the query. */
    @Serializable @SerialName("search")
    data class Search(val query: String) : Step

    @Serializable @SerialName("type")
    data class TypeText(val text: String) : Step

    /** A remote button, e.g. "DPAD_DOWN", "DPAD_CENTER", "BACK". */
    @Serializable @SerialName("key")
    data class Key(val key: String) : Step

    @Serializable @SerialName("wait")
    data class Wait(val seconds: Int) : Step

    /**
     * On a show's page: press Resume / Continue / Watch Latest Episode / Watch Now / Play,
     * whichever the app shows; if there is none, open Episodes and play the first one.
     */
    @Serializable @SerialName("play")
    data object Play : Step
}

fun Step.describe(): String = when (this) {
    is Step.LaunchApp -> "Open $appName"
    is Step.Select -> "Select “$label”"
    is Step.SelectAt -> "Select item at ${(xFrac * 100).toInt()}%, ${(yFrac * 100).toInt()}%"
    is Step.Search -> "Search “$query”"
    is Step.TypeText -> "Type “$text”"
    is Step.Key -> "Press ${KEY_NAMES[key] ?: key}"
    is Step.Wait -> "Wait $seconds s"
    Step.Play -> "Play (resume, latest or first episode)"
}

val KEY_NAMES = mapOf(
    "DPAD_UP" to "▲ Up",
    "DPAD_DOWN" to "▼ Down",
    "DPAD_LEFT" to "◀ Left",
    "DPAD_RIGHT" to "▶ Right",
    "DPAD_CENTER" to "OK",
    "BACK" to "Back",
    "HOME" to "Home",
    "MEDIA_PLAY_PAUSE" to "Play/Pause",
)

@Serializable
data class Tile(
    val id: String,
    val name: String,
    /** File name of the poster inside the app's files dir. */
    val poster: String? = null,
    val steps: List<Step> = emptyList(),
    /** How long the last successful run took, in seconds (shown in Settings). */
    val lastRunSeconds: Int? = null,
)

@Serializable
data class AppData(
    val tvIp: String = "",
    val pin: String = "1234",
    /** Language of Mom's screen: "en" or "hi". */
    val language: String = "en",
    val tiles: List<Tile> = emptyList(),
)
