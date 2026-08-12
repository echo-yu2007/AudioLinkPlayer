package com.echo.audiolinkplayer.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small in-memory ring buffer of what the engine actually did and said.
 * Without this, "解析失败" is a dead end for anyone trying to work out why.
 */
object Diagnostics {

    private const val MAX = 40
    private val entries = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(tag: String, text: String) {
        if (entries.size >= MAX) entries.removeFirst()
        entries.addLast("[${stamp.format(Date())}] $tag\n${text.trim()}")
    }

    @Synchronized
    fun dump(): String =
        if (entries.isEmpty()) "（还没有记录）" else entries.reversed().joinToString("\n\n")

    @Synchronized
    fun clear() = entries.clear()
}
