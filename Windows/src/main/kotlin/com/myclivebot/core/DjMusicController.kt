package com.myclivebot.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DjMusicTrack(val videoId: String, val title: String, val thumbnailUrl: String, val duration: String = "", val requestedBy: String = "Tú", val artistName: String = "", val artistUrl: String = "", val artistThumbnailUrl: String = "")
data class DjMusicState(
    val followersOnly: Boolean = false,
    val moderatorsOnly: Boolean = false,
    val pendingSearches: List<Pair<String, String>> = emptyList(),
    val activeSearch: Pair<String, String>? = null,
    val currentTrack: DjMusicTrack? = null,
    val queue: List<DjMusicTrack> = emptyList(),
    val muted: Boolean = false,
    val volume: Int = 70,
    val message: String = "Busca una canción o escribe !play en el LIVE."
)

object DjMusicController {
    private val mutableState = MutableStateFlow(DjMusicState())
    val state: StateFlow<DjMusicState> = mutableState.asStateFlow()

    @Synchronized
    fun setFollowersOnly(enabled: Boolean) { mutableState.value = mutableState.value.copy(followersOnly = enabled, moderatorsOnly = false) }
    @Synchronized
    fun setModeratorsOnly(enabled: Boolean) { mutableState.value = mutableState.value.copy(followersOnly = false, moderatorsOnly = enabled) }
    @Synchronized
    fun setMuted(muted: Boolean) { mutableState.value = mutableState.value.copy(muted = muted) }
    @Synchronized
    fun setVolume(volume: Int) { mutableState.value = mutableState.value.copy(volume = volume.coerceIn(0, 100)) }

    @Synchronized
    fun handleLiveComment(comment: LiveComment) {
        val text = comment.text.trim()
        when {
            text.equals("!skip", true) -> if (comment.isModerator) skip("La moderación saltó la canción.")
            text.startsWith("!play ", true) -> {
                val current = mutableState.value
                if (current.moderatorsOnly && !comment.isModerator) return
                if (current.followersOnly && comment.isFollower != true) return
                val query = text.substringAfter(' ').trim()
                if (query.isNotBlank()) enqueueSearch(query, comment.displayName)
            }
        }
    }

    @Synchronized
    fun enqueueSearch(query: String, requester: String = "Tú") {
        val clean = query.trim()
        if (clean.isBlank()) return
        val current = mutableState.value
        if (current.pendingSearches.size >= 50) {
            mutableState.value = current.copy(message = "La cola de solicitudes está llena. Inténtalo más tarde.")
            return
        }
        mutableState.value = current.copy(
            pendingSearches = current.pendingSearches + (clean to requester),
            message = "$requester pidió: $clean"
        )
    }

    @Synchronized
    fun takeNextSearch(): Pair<String, String>? {
        val current = mutableState.value
        current.activeSearch?.let { return it }
        val next = current.pendingSearches.firstOrNull() ?: return null
        mutableState.value = current.copy(
            activeSearch = next,
            pendingSearches = current.pendingSearches.drop(1)
        )
        return next
    }

    @Synchronized
    fun completeSearch(query: String, requester: String) {
        val current = mutableState.value
        if (current.activeSearch == (query to requester)) {
            mutableState.value = current.copy(activeSearch = null)
        }
    }

    @Synchronized
    fun addTrack(track: DjMusicTrack) {
        val current = mutableState.value
        mutableState.value = if (current.currentTrack == null) current.copy(currentTrack = track, message = "Reproduciendo: ${track.title}")
        else current.copy(queue = current.queue + track, message = "Agregada a la cola: ${track.title}")
    }

    @Synchronized
    fun skip(message: String = "Canción saltada.") {
        val current = mutableState.value
        val next = current.queue.firstOrNull()
        mutableState.value = current.copy(
            currentTrack = next,
            queue = if (next == null) emptyList() else current.queue.drop(1),
            message = if (next == null) "No hay más canciones en la cola." else "$message Reproduciendo: ${next.title}"
        )
    }

    @Synchronized
    fun moveQueueTrack(fromIndex: Int, toIndex: Int) {
        val current = mutableState.value
        if (fromIndex !in current.queue.indices || toIndex !in current.queue.indices || fromIndex == toIndex) return
        val reordered = current.queue.toMutableList()
        val moved = reordered.removeAt(fromIndex)
        reordered.add(toIndex, moved)
        mutableState.value = current.copy(queue = reordered)
    }

    @Synchronized
    fun playQueueTrackNow(index: Int) {
        val current = mutableState.value
        if (index !in current.queue.indices) return
        val selected = current.queue[index]
        val remaining = current.queue.toMutableList().also { it.removeAt(index) }
        current.currentTrack?.let { remaining.add(0, it) }
        mutableState.value = current.copy(
            currentTrack = selected,
            queue = remaining,
            message = "Reproduciendo ahora: ${selected.title}"
        )
    }

    @Synchronized
    fun playTrackNow(track: DjMusicTrack) {
        val current = mutableState.value
        val nextQueue = current.queue.toMutableList()
        current.currentTrack?.let { nextQueue.add(0, it) }
        mutableState.value = current.copy(
            currentTrack = track,
            queue = nextQueue,
            message = "Reproduciendo ahora: ${track.title}"
        )
    }

    @Synchronized
    fun removeQueueTrack(index: Int) {
        val current = mutableState.value
        if (index !in current.queue.indices) return
        val removed = current.queue[index]
        mutableState.value = current.copy(
            queue = current.queue.filterIndexed { itemIndex, _ -> itemIndex != index },
            message = "Eliminada de la cola: ${removed.title}"
        )
    }

    @Synchronized
    fun clearQueue() { mutableState.value = mutableState.value.copy(queue = emptyList(), message = "Cola vaciada.") }
    @Synchronized
    fun setMessage(message: String) { mutableState.value = mutableState.value.copy(message = message) }
}
