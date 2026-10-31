package com.maatv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.json.Json
import java.io.File

/** Saves tiles and settings as JSON, and posters as JPEGs, in the app's private storage. */
class Store(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "maatv.json")
    private val posterDir = File(dir, "posters").apply { mkdirs() }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val backup = File(dir, "maatv.json.bak")

    /**
     * Loads the shows and settings. If the file can't be read (for example it was written by a
     * newer version of the app), it is kept aside and the last backup is used, so the next save
     * never replaces Mom's shows with an empty list.
     */
    fun load(): AppData {
        for (f in listOf(file, backup)) {
            if (!f.exists()) continue
            try {
                return json.decodeFromString(AppData.serializer(), f.readText())
            } catch (e: Exception) {
                if (f == file) f.copyTo(File(dir, "maatv.json.unreadable-${System.currentTimeMillis()}"), overwrite = true)
            }
        }
        return AppData()
    }

    fun save(data: AppData) {
        if (file.exists()) file.copyTo(backup, overwrite = true)
        val tmp = File(dir, "maatv.json.tmp")
        tmp.writeText(json.encodeToString(AppData.serializer(), data))
        tmp.renameTo(file)
    }

    fun savePoster(id: String, bmp: Bitmap): String {
        val name = "$id.jpg"
        File(posterDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return name
    }

    fun loadPoster(name: String?): Bitmap? =
        name?.let { File(posterDir, it) }?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun posterBytes(name: String?): ByteArray? =
        name?.let { File(posterDir, it) }?.takeIf { it.exists() }?.readBytes()

    fun savePosterBytes(id: String, bytes: ByteArray): String {
        val name = "$id.jpg"
        File(posterDir, name).writeBytes(bytes)
        return name
    }

    fun deletePoster(name: String?) {
        name?.let { File(posterDir, it).delete() }
    }
}
