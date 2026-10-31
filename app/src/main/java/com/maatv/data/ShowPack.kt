package com.maatv.data

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The file for sharing shows with another phone. It holds each show's name, recorded steps and
 * poster, and nothing about this home: no TV address, PIN or TV access key.
 */
@Serializable
data class ShowPack(
    val format: String = ShowPacks.FORMAT,
    val version: Int = ShowPacks.VERSION,
    val exportedAt: String = "",
    val tiles: List<SharedTile> = emptyList(),
)

@Serializable
data class SharedTile(
    val name: String,
    /** Kept as raw JSON so one unknown step (from a newer app) only skips that show. */
    val steps: JsonArray,
    /** JPEG, base64. */
    val poster: String? = null,
)

/** A show read from a share file, ready to add. */
class ImportItem(
    val name: String,
    val steps: List<Step>,
    val poster: ByteArray?,
    /** Things to check on this TV, e.g. a profile name that may differ. */
    val notes: List<String>,
) {
    val appName: String? get() = steps.filterIsInstance<Step.LaunchApp>().firstOrNull()?.appName
}

sealed interface ImportResult {
    data class Ok(val items: List<ImportItem>, val unsupported: List<String>) : ImportResult
    data class Error(val message: String) : ImportResult
}

object ShowPacks {
    const val FORMAT = "maatv-shows"
    const val VERSION = 1
    const val EXTENSION = "maatv"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val stepsSerializer = ListSerializer(Step.serializer())

    fun fileName(): String = "MaaTV-shows-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.$EXTENSION"

    fun export(tiles: List<Tile>, posterBytes: (String?) -> ByteArray?): String {
        val pack = ShowPack(
            exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()),
            tiles = tiles.map { t ->
                SharedTile(
                    name = t.name,
                    steps = json.encodeToJsonElement(stepsSerializer, t.steps).jsonArray,
                    poster = posterBytes(t.poster)?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                )
            },
        )
        return json.encodeToString(ShowPack.serializer(), pack)
    }

    fun parse(text: String): ImportResult {
        val pack = try {
            json.decodeFromString(ShowPack.serializer(), text)
        } catch (e: Exception) {
            return ImportResult.Error("This is not a Maa TV shows file.")
        }
        if (pack.format != FORMAT) return ImportResult.Error("This is not a Maa TV shows file.")
        if (pack.version > VERSION) {
            return ImportResult.Error("This file was made by a newer Maa TV. Please update the app on this phone first.")
        }
        val unsupported = mutableListOf<String>()
        val items = pack.tiles.mapNotNull { st ->
            val steps = try {
                json.decodeFromJsonElement(stepsSerializer, st.steps as JsonElement)
            } catch (e: Exception) {
                unsupported += st.name
                return@mapNotNull null
            }
            val poster = st.poster?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            ImportItem(st.name, steps, poster, notesFor(steps))
        }
        return ImportResult.Ok(items, unsupported)
    }

    /** A Select before any search is usually a profile pick, and profile names differ per family. */
    private fun notesFor(steps: List<Step>): List<String> {
        val firstSearch = steps.indexOfFirst { it is Step.Search }.let { if (it < 0) steps.size else it }
        return steps.take(firstSearch).filterIsInstance<Step.Select>().map {
            "Picks the profile “${it.label}”. If this TV uses a different profile name, edit the show and pick yours."
        }
    }
}
