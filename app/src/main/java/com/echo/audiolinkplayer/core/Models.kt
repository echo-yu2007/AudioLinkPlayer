package com.echo.audiolinkplayer.core

import org.json.JSONObject

/** One playable stream reported by yt-dlp. */
data class StreamFormat(
    val formatId: String,
    val url: String,
    val ext: String,
    val protocol: String,
    val height: Int,
    val fps: Double,
    val tbr: Double,
    val abr: Double,
    val vcodec: String,
    val acodec: String,
    val filesize: Long,
    val headers: Map<String, String>
) {
    val hasVideo: Boolean get() = vcodec.isNotEmpty() && vcodec != "none"
    val hasAudio: Boolean get() = acodec.isNotEmpty() && acodec != "none"
    val isHls: Boolean get() = protocol.contains("m3u8") || ext == "m3u8"
    val isDash: Boolean get() = protocol.contains("dash") || ext == "mpd"

    /** Human label used in the quality picker. */
    val label: String
        get() = when {
            hasVideo && height > 0 -> "${height}p" + if (fps >= 50) fps.toInt().toString() else ""
            hasVideo -> ext.uppercase()
            abr > 0 -> "${abr.toInt()} kbps"
            tbr > 0 -> "${tbr.toInt()} kbps"
            else -> formatId
        }
}

/** Everything yt-dlp knows about one page URL. */
data class MediaInfo(
    val sourceUrl: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val durationMs: Long,
    val isLive: Boolean,
    val formats: List<StreamFormat>
) {
    val resolvedAt: Long = System.currentTimeMillis()

    /** Signed URLs usually die after ~1h; refresh well before that. */
    val isFresh: Boolean get() = System.currentTimeMillis() - resolvedAt < 20 * 60 * 1000L
}

/** A queue entry. Only metadata is persisted — never the media itself. */
data class Track(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val uploader: String? = null,
    val thumbnail: String? = null,
    val durationMs: Long = 0L,
    val isLive: Boolean = false,
    /** Owning folder; null means it sits at the top level. */
    val parentId: String? = null,
    /** Position among its siblings. */
    val order: Int = 0,
    /** Free-text note the user attaches to this entry. */
    val note: String = "",
    /** User-supplied title override; the extracted title stays in [title]. */
    val customTitle: String = ""
) {
    /** What the UI and the notification should show. */
    val displayTitle: String get() = customTitle.ifBlank { title }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sourceUrl", sourceUrl)
        put("title", title)
        put("uploader", uploader ?: JSONObject.NULL)
        put("thumbnail", thumbnail ?: JSONObject.NULL)
        put("durationMs", durationMs)
        put("isLive", isLive)
        put("parentId", parentId ?: JSONObject.NULL)
        put("order", order)
        put("note", note)
        put("customTitle", customTitle)
    }

    companion object {
        fun fromJson(o: JSONObject) = Track(
            id = o.optString("id"),
            sourceUrl = o.optString("sourceUrl"),
            title = o.optString("title"),
            uploader = o.optString("uploader").takeIf { it.isNotEmpty() && it != "null" },
            thumbnail = o.optString("thumbnail").takeIf { it.isNotEmpty() && it != "null" },
            durationMs = o.optLong("durationMs"),
            isLive = o.optBoolean("isLive"),
            parentId = o.optString("parentId").takeIf { it.isNotEmpty() && it != "null" },
            order = o.optInt("order"),
            note = o.optString("note"),
            customTitle = o.optString("customTitle")
        )
    }
}

/**
 * What the user wants out of a link.
 * AUDIO_ONLY is the default: it downloads the audio track only (or the smallest
 * stream that carries audio), which is what makes screen-off playback cheap.
 */
enum class PlayMode { AUDIO_ONLY, VIDEO }

/** Video quality ceiling. [maxHeight] of 0 means "let the app pick the best". */
enum class QualityCap(val maxHeight: Int, val label: String) {
    P360(360, "360p"),
    P480(480, "480p"),
    P720(720, "720p"),
    P1080(1080, "1080p"),
    BEST(Int.MAX_VALUE, "最高");

    companion object {
        fun fromName(name: String?): QualityCap =
            entries.firstOrNull { it.name == name } ?: P720
    }
}
