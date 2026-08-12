package com.echo.audiolinkplayer.core

import org.json.JSONObject

/**
 * A user-created folder. Nesting is expressed by [parentId] alone, so the depth
 * is only bounded by [Library.MAX_DEPTH] — the tree itself has no fixed shape.
 */
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val order: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("parentId", parentId ?: JSONObject.NULL)
        put("order", order)
    }

    companion object {
        fun fromJson(o: JSONObject) = Folder(
            id = o.optString("id"),
            name = o.optString("name"),
            parentId = o.optString("parentId").takeIf { it.isNotEmpty() && it != "null" },
            order = o.optInt("order")
        )
    }
}

/** Everything the library holds. Metadata only — no media, ever. */
data class LibraryState(
    val folders: List<Folder> = emptyList(),
    val tracks: List<Track> = emptyList()
)

/**
 * Pure tree operations over [LibraryState]. Kept free of Android types so the
 * ordering and re-parenting rules stay easy to reason about in one place.
 */
object Library {

    /** Deep enough for any sane organisation scheme, shallow enough to stay navigable. */
    const val MAX_DEPTH = 8

    fun foldersIn(state: LibraryState, parentId: String?): List<Folder> =
        state.folders.filter { it.parentId == parentId }.sortedBy { it.order }

    fun tracksIn(state: LibraryState, parentId: String?): List<Track> =
        state.tracks.filter { it.parentId == parentId }.sortedBy { it.order }

    fun folder(state: LibraryState, id: String?): Folder? =
        id?.let { fid -> state.folders.firstOrNull { it.id == fid } }

    /** Root-first chain of folders leading to [id], excluding the root itself. */
    fun pathTo(state: LibraryState, id: String?): List<Folder> {
        val path = ArrayDeque<Folder>()
        var cursor = folder(state, id)
        var guard = 0
        while (cursor != null && guard++ < MAX_DEPTH * 2) {
            path.addFirst(cursor)
            cursor = folder(state, cursor.parentId)
        }
        return path.toList()
    }

    fun depthOf(state: LibraryState, id: String?): Int = pathTo(state, id).size

    /** [id] plus every folder beneath it. Used to block moving a folder into itself. */
    fun subtreeIds(state: LibraryState, id: String): Set<String> {
        val out = mutableSetOf(id)
        var frontier = listOf(id)
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < MAX_DEPTH * 2) {
            frontier = state.folders.filter { it.parentId in frontier }.map { it.id }
            out += frontier
        }
        return out
    }

    /** How many folders deep the tallest branch under [id] runs. */
    fun subtreeHeight(state: LibraryState, id: String): Int {
        var height = 1
        var frontier = listOf(id)
        var guard = 0
        while (guard++ < MAX_DEPTH * 2) {
            frontier = state.folders.filter { it.parentId in frontier }.map { it.id }
            if (frontier.isEmpty()) break
            height++
        }
        return height
    }

    fun countsIn(state: LibraryState, folderId: String): Pair<Int, Int> {
        val ids = subtreeIds(state, folderId)
        return state.folders.count { it.parentId in ids } to
            state.tracks.count { it.parentId in ids }
    }

    private fun nextOrder(existing: List<Int>): Int = (existing.maxOrNull() ?: -1) + 1

    fun nextFolderOrder(state: LibraryState, parentId: String?): Int =
        nextOrder(foldersIn(state, parentId).map { it.order })

    fun nextTrackOrder(state: LibraryState, parentId: String?): Int =
        nextOrder(tracksIn(state, parentId).map { it.order })

    /**
     * Moves an item one slot up or down among its siblings by swapping order
     * values, then normalises so the numbers stay dense.
     */
    fun reorderFolders(state: LibraryState, folderId: String, delta: Int): LibraryState {
        val siblings = foldersIn(state, folder(state, folderId)?.parentId).toMutableList()
        val from = siblings.indexOfFirst { it.id == folderId }
        val to = from + delta
        if (from < 0 || to !in siblings.indices) return state
        siblings.add(to, siblings.removeAt(from))
        val renumbered = siblings.mapIndexed { i, f -> f.copy(order = i) }.associateBy { it.id }
        return state.copy(folders = state.folders.map { renumbered[it.id] ?: it })
    }

    fun reorderTracks(state: LibraryState, trackId: String, delta: Int): LibraryState {
        val siblings = tracksIn(state, state.tracks.firstOrNull { it.id == trackId }?.parentId)
            .toMutableList()
        val from = siblings.indexOfFirst { it.id == trackId }
        val to = from + delta
        if (from < 0 || to !in siblings.indices) return state
        siblings.add(to, siblings.removeAt(from))
        val renumbered = siblings.mapIndexed { i, t -> t.copy(order = i) }.associateBy { it.id }
        return state.copy(tracks = state.tracks.map { renumbered[it.id] ?: it })
    }

    /** Removes a folder together with everything inside it. */
    fun deleteFolder(state: LibraryState, folderId: String): LibraryState {
        val ids = subtreeIds(state, folderId)
        return state.copy(
            folders = state.folders.filterNot { it.id in ids },
            tracks = state.tracks.filterNot { it.parentId in ids }
        )
    }

    /** Null when the move is legal, otherwise the reason it is not. */
    fun moveFolderProblem(state: LibraryState, folderId: String, targetId: String?): String? {
        if (targetId == folderId) return "不能移动到它自己里面"
        if (targetId != null && targetId in subtreeIds(state, folderId)) {
            return "不能移动到自己的子文件夹里"
        }
        val newDepth = depthOf(state, targetId) + subtreeHeight(state, folderId)
        if (newDepth > MAX_DEPTH) return "层级会超过 $MAX_DEPTH 层"
        return null
    }
}
