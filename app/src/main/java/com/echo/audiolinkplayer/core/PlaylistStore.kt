package com.echo.audiolinkplayer.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** Persists the queue as plain metadata JSON — a few KB, never any media. */
object PlaylistStore {

    private fun file(context: Context) = File(context.filesDir, "playlist.json")

    suspend fun load(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val f = file(context)
        if (!f.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(Track::fromJson)
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(context: Context, tracks: List<Track>) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            tracks.forEach { arr.put(it.toJson()) }
            file(context).writeText(arr.toString())
        }
        Unit
    }
}
