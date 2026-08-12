package com.echo.audiolinkplayer.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists the library as plain metadata JSON — a few KB, never any media. */
object PlaylistStore {

    private fun file(context: Context) = File(context.filesDir, "playlist.json")

    suspend fun load(context: Context): LibraryState = withContext(Dispatchers.IO) {
        val f = file(context)
        if (!f.exists()) return@withContext LibraryState()
        runCatching {
            val text = f.readText().trim()
            // v1 stored a bare array of tracks; those all belong at the top level.
            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                val tracks = (0 until arr.length()).mapIndexedNotNull { i, _ ->
                    arr.optJSONObject(i)?.let(Track::fromJson)?.copy(order = i)
                }
                return@runCatching LibraryState(tracks = tracks)
            }
            val root = JSONObject(text)
            val folders = root.optJSONArray("folders")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Folder::fromJson) }
            }.orEmpty()
            val tracks = root.optJSONArray("tracks")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Track::fromJson) }
            }.orEmpty()
            LibraryState(folders = folders, tracks = tracks)
        }.getOrDefault(LibraryState())
    }

    suspend fun save(context: Context, state: LibraryState) = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject().apply {
                put("version", 2)
                put("folders", JSONArray().also { arr -> state.folders.forEach { arr.put(it.toJson()) } })
                put("tracks", JSONArray().also { arr -> state.tracks.forEach { arr.put(it.toJson()) } })
            }
            file(context).writeText(root.toString())
        }
        Unit
    }
}
