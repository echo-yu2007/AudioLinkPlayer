package com.echo.audiolinkplayer.core

/** The concrete stream(s) chosen for playback. */
data class Selection(
    val primary: StreamFormat,
    /** Non-null when [primary] is video-only and needs a separate audio track merged in. */
    val extraAudio: StreamFormat?,
    val label: String
)

object FormatPicker {

    /**
     * AUDIO_ONLY prefers a real audio-only stream. Sites that never publish one
     * (Pornhub, most tube sites) fall back to the *smallest* muxed stream, which
     * still costs a fraction of the bandwidth of watching it.
     */
    fun pick(info: MediaInfo, mode: PlayMode, cap: QualityCap): Selection? {
        val formats = info.formats.filter { it.url.isNotEmpty() }
        if (formats.isEmpty()) return null

        if (mode == PlayMode.AUDIO_ONLY) {
            bestAudioOnly(formats)?.let { return Selection(it, null, it.label) }
            val muxed = formats.filter { it.hasAudio && it.hasVideo }
            val smallest = muxed.minByOrNull { rank(it) }
            if (smallest != null) return Selection(smallest, null, "音频 (${smallest.label} 源)")
            return formats.firstOrNull { it.hasAudio }?.let { Selection(it, null, it.label) }
        }

        val video = formats.filter { it.hasVideo }
        if (video.isEmpty()) {
            return bestAudioOnly(formats)?.let { Selection(it, null, it.label) }
        }

        // Prefer muxed streams: one connection, no merging, and every tube site has them.
        val muxed = video.filter { it.hasAudio }
        val underCap = { list: List<StreamFormat> ->
            list.filter { it.height in 1..cap.maxHeight }
                .maxByOrNull { it.height * 1_000_000L + it.tbr.toLong() }
                ?: list.filter { it.height == 0 }.maxByOrNull { it.tbr }
                ?: list.minByOrNull { it.height }
        }

        underCap(muxed)?.let { return Selection(it, null, it.label) }

        val bestVideo = underCap(video) ?: return null
        val audio = bestAudioOnly(formats)
        return Selection(bestVideo, audio, bestVideo.label)
    }

    /** The distinct video qualities offered to the user for a given media. */
    fun videoOptions(info: MediaInfo): List<StreamFormat> =
        info.formats.filter { it.hasVideo && !it.isDash }
            .sortedByDescending { it.height * 1_000_000L + it.tbr.toLong() }
            .distinctBy { it.height.takeIf { h -> h > 0 } ?: it.formatId }

    /** Build a selection for one explicitly chosen video format. */
    fun forFormat(info: MediaInfo, format: StreamFormat): Selection =
        if (format.hasAudio) Selection(format, null, format.label)
        else Selection(format, bestAudioOnly(info.formats), format.label)

    private fun bestAudioOnly(formats: List<StreamFormat>): StreamFormat? =
        formats.filter { it.hasAudio && !it.hasVideo }
            .maxByOrNull { if (it.abr > 0) it.abr else it.tbr }

    private fun rank(f: StreamFormat): Long =
        (if (f.height > 0) f.height.toLong() else 9_999L) * 10_000L + f.tbr.toLong()
}
