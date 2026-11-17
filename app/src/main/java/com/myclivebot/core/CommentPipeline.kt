package com.myclivebot.core

import java.io.Closeable
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class LivePipelineStats(
    val commentsReceived: Long,
    val commentsFiltered: Long,
    val commentsQueued: Long,
    val eventsReceived: Long,
    val eventsQueued: Long,
    val speechQueueSize: Int
)

/** Incoming TikTok events are filtered against the latest settings without reconnecting. */
class CommentPipeline(
    private val filter: CommentFilter,
    private val speechQueue: SpeechQueueWorker,
    initialSettings: ReaderSettings,
    private val followBatchWindowMs: Long = DEFAULT_FOLLOW_BATCH_WINDOW_MS,
    private val giftBatchWindowMs: Long = DEFAULT_GIFT_BATCH_WINDOW_MS,
    private val alertRetryDelayMs: Long = DEFAULT_ALERT_RETRY_DELAY_MS
) : Closeable {
    private val settings = AtomicReference(initialSettings)
    private val welcomeTracker = WelcomeMessageTracker()
    private val shareAlertTracker = ShareAlertTracker()
    private val likeAlertTracker = LikeAlertTracker()
    private val speakerTracker = CommentSpeakerTracker()
    private val commentsReceived = AtomicLong()
    private val commentsFiltered = AtomicLong()
    private val commentsQueued = AtomicLong()
    private val eventsReceived = AtomicLong()
    private val eventsQueued = AtomicLong()
    private val alertLock = Any()
    private val alertScheduler =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "TikLBot-Alert-Batching").apply { isDaemon = true }
        }.apply {
            // Remove cancelled debounce tasks immediately during rapid event bursts.
            removeOnCancelPolicy = true
        }
    private val pendingFollows = LinkedHashMap<String, String>()
    // A follower is announced at most once during this LIVE session, even across batches.
    private val announcedFollows = LinkedHashSet<String>()
    private val pendingGifts = LinkedHashMap<String, GiftBatchItem>()
    private var pendingFollowOverflow = 0
    private var pendingGiftOverflowUsers = 0
    private var pendingGiftOverflowCount = 0L
    private var followFlush: ScheduledFuture<*>? = null
    private var giftFlush: ScheduledFuture<*>? = null
    @Volatile private var closed = false

    private data class GiftBatchItem(
        val displayName: String,
        var count: Long,
        val groupCounts: MutableMap<String, Long> = LinkedHashMap()
    )

    companion object {
        const val DEFAULT_FOLLOW_BATCH_WINDOW_MS = 4_000L
        const val DEFAULT_GIFT_BATCH_WINDOW_MS = 3_000L
        const val DEFAULT_ALERT_RETRY_DELAY_MS = 500L
        private const val MAX_BATCH_USERS = 1_000
        private const val MAX_GIFT_GROUPS_PER_USER = 512
        private const val MAX_ANNOUNCED_FOLLOWERS = 10_000
        private const val MAX_NAMES_IN_ALERT = 3
    }

    fun updateSettings(next: ReaderSettings) {
        settings.set(next)
    }

    fun onComment(comment: LiveComment): Boolean {
        commentsReceived.incrementAndGet()
        val current = settings.get()
        if (!filter.shouldRead(comment, current)) {
            commentsFiltered.incrementAndGet()
            return false
        }
        // Preview the welcome without consuming it. The welcome and comment
        // are committed together only if the speech queue accepts both.
        val welcomeSpeech = welcomeTracker.previewMessageFor(
            comment,
            current,
            filter.usernameForSpeech(comment, current)
        )?.let { welcome ->
            filter.toSpeech(
                comment.copy(text = welcome),
                current.copy(readUsername = false, onlyUsername = false)
            )
        }

        // A speaker is named once when they take the floor. Consecutive comments
        // from that same person omit the name until another accepted speaker appears.
        val announceSpeaker = speakerTracker.shouldAnnounce(comment)

        // Commands are local speech responses only; this app does not send chat messages.
        val commandResponse = if (current.customCommandsEnabled) {
            ChatCommandParser.responseFor(
                comment.text,
                filter.usernameForSpeech(comment, current),
                current.customCommands,
                comment.isModerator
            )
        } else null
        val replyDisplayName = comment.replyToDisplayName?.takeIf { it.isNotBlank() }
        val replyProfileUsername = comment.replyToProfileUsername?.takeIf { it.isNotBlank() }
        val replyTarget = if (current.useProfileUsername) {
            replyProfileUsername ?: replyDisplayName
        } else {
            replyDisplayName ?: replyProfileUsername
        }
        val replyTargetNames = listOfNotNull(replyDisplayName, replyProfileUsername).distinct()
        val speechComment = when {
            commandResponse != null -> comment.copy(
                text = commandResponse,
                replyToDisplayName = null,
                replyToProfileUsername = null
            )
            replyTarget != null -> {
                val cleanReplyText = replyTargetNames.fold(comment.text) { text, targetName ->
                    val replyMention = Regex("(?i)@${Regex.escape(targetName)}(?![\\p{L}\\p{N}_])")
                    text.replace(replyMention, "")
                }.replace(Regex("\\s+"), " ").trim()
                val safeSpeaker = filter.usernameForSpeech(comment, current)
                val safeTarget = filter.usernameForSpeech(
                    replyDisplayName ?: replyProfileUsername.orEmpty(),
                    replyProfileUsername.orEmpty(),
                    current
                )
                comment.copy(
                    text = if (cleanReplyText.isBlank()) {
                        "${safeSpeaker} le respondió a ${safeTarget}"
                    } else {
                        "${safeSpeaker} le respondió a ${safeTarget}: ${cleanReplyText}"
                    },
                    replyToDisplayName = null,
                    replyToProfileUsername = null
                )
            }
            else -> comment
        }
        val speechSettings = current.copy(
            onlyUsername = if (commandResponse != null || replyTarget != null) false else current.onlyUsername,
            readUsername = current.readUsername && announceSpeaker && replyTarget == null
        )
        val speech = filter.toSpeech(speechComment, speechSettings)
        val queueItems = buildList {
            welcomeSpeech?.let { add(it to null) }
            add(speech to comment.id)
        }
        val queued = speechQueue.enqueueComments(queueItems)
        if (queued) {
            if (welcomeSpeech != null) welcomeTracker.confirmQueued(comment)
            speakerTracker.markQueued(comment)
            commentsQueued.incrementAndGet()
        }
        return queued
    }

    fun onEvent(event: LiveEvent): Boolean {
        eventsReceived.incrementAndGet()
        if (closed) return false
        val current = settings.get()
        val queued = when (event.type) {
            LiveEventType.FOLLOW -> queueFollow(event, current)
            LiveEventType.GIFT -> queueGift(event, current)
            LiveEventType.SHARE -> {
                val speech = shareAlertTracker.render(event, current).orEmpty()
                if (speech.isBlank()) false else {
                    val accepted = speechQueue.enqueue(speech, priority = true)
                    if (accepted) shareAlertTracker.confirmQueued(event)
                    else shareAlertTracker.rollbackIfNotQueued(event)
                    accepted
                }
            }
            LiveEventType.JOIN -> {
                val speech = if (current.readJoins &&
                    (!current.joinFollowerOnly || event.isFollower == true)
                ) {
                    "@${ChatCommandParser.spokenUsername(event.displayName).removePrefix("@")} se ha unido al live"
                } else ""
                speech.isNotBlank() && speechQueue.enqueue(speech, priority = true)
            }
            LiveEventType.LIKE -> {
                val speech = likeAlertTracker.render(event, current).orEmpty()
                if (speech.isBlank()) false else {
                    val accepted = speechQueue.enqueue(speech, priority = true)
                    if (accepted) likeAlertTracker.confirmQueued(event)
                    else likeAlertTracker.rollbackIfNotQueued(event)
                    accepted
                }
            }
        }
        if (queued) eventsQueued.incrementAndGet()
        return queued
    }

    private fun queueFollow(event: LiveEvent, current: ReaderSettings): Boolean {
        if (!current.readFollows) return false
        val name = ChatCommandParser.spokenUsername(event.displayName)
        val key = event.userId.trim().ifBlank { event.displayName.trim().lowercase() }
        if (key.isBlank()) return false
        synchronized(alertLock) {
            if (closed || pendingFollows.containsKey(key) || announcedFollows.contains(key)) return false
            if (pendingFollows.size < MAX_BATCH_USERS) pendingFollows[key] = name
            else pendingFollowOverflow++
            // Only a genuinely new follower extends the quiet window.
            scheduleFollowFlushLocked(followBatchWindowMs.coerceAtLeast(0L))
        }
        return true
    }

    private fun queueGift(event: LiveEvent, current: ReaderSettings): Boolean {
        if (!GiftAlertRules.shouldAnnounce(event, current)) return false
        val name = ChatCommandParser.spokenUsername(event.displayName)
        val key = event.userId.trim().ifBlank { event.displayName.trim().lowercase() }
        if (key.isBlank()) return false
        val count = GiftAlertRules.repeatCount(event).toLong()
        synchronized(alertLock) {
            if (closed) return false
            val groupId = event.giftGroupId.trim()
            val existing = pendingGifts[key]
            var added = false
            if (existing != null) {
                if (groupId.isBlank()) {
                    existing.count = addGiftCounts(existing.count, count)
                    added = true
                } else {
                    val previousGroupCount = existing.groupCounts[groupId]
                    when {
                        previousGroupCount == null -> {
                            // Once the per-user combo map is full, ignore unseen combo IDs
                            // rather than adding counts that could be duplicated by later updates.
                            if (existing.groupCounts.size < MAX_GIFT_GROUPS_PER_USER) {
                                existing.groupCounts[groupId] = count
                                existing.count = addGiftCounts(existing.count, count)
                                added = true
                            }
                        }
                        count > previousGroupCount -> {
                            existing.groupCounts[groupId] = count
                            existing.count = addGiftCounts(existing.count, count - previousGroupCount)
                            added = true
                        }
                    }
                }
            } else if (pendingGifts.size < MAX_BATCH_USERS) {
                val groupCounts: MutableMap<String, Long> = LinkedHashMap()
                if (groupId.isNotBlank()) groupCounts[groupId] = count
                pendingGifts[key] = GiftBatchItem(name, count, groupCounts)
                added = true
            } else {
                pendingGiftOverflowUsers++
                pendingGiftOverflowCount = if (Long.MAX_VALUE - pendingGiftOverflowCount < count) {
                    Long.MAX_VALUE
                } else pendingGiftOverflowCount + count
                added = true
            }
            if (!added) return false
            // Duplicate cumulative updates for the same combo do not extend the quiet window.
            scheduleGiftFlushLocked(giftBatchWindowMs.coerceAtLeast(0L))
        }
        return true
    }

    private fun addGiftCounts(current: Long, increment: Long): Long =
        if (Long.MAX_VALUE - current < increment) Long.MAX_VALUE else current + increment

    private fun scheduleFollowFlushLocked(delayMs: Long) {
        followFlush?.cancel(false)
        followFlush = alertScheduler.schedule(
            { flushFollowBatch() }, delayMs, TimeUnit.MILLISECONDS
        )
    }

    private fun scheduleGiftFlushLocked(delayMs: Long) {
        giftFlush?.cancel(false)
        giftFlush = alertScheduler.schedule(
            { flushGiftBatch() }, delayMs, TimeUnit.MILLISECONDS
        )
    }

    private fun flushFollowBatch() {
        synchronized(alertLock) {
            followFlush = null
            if (closed || pendingFollows.isEmpty() && pendingFollowOverflow == 0) return
            if (!settings.get().readFollows) {
                pendingFollows.clear()
                pendingFollowOverflow = 0
                return
            }
            val total = pendingFollows.size + pendingFollowOverflow
            val names = pendingFollows.values.take(MAX_NAMES_IN_ALERT)
            val speech = FollowAlertRules.renderBatch(names, total)
            if (speech != null && speechQueue.enqueue(speech, priority = true)) {
                pendingFollows.keys.forEach { announcedFollows.add(it) }
                while (announcedFollows.size > MAX_ANNOUNCED_FOLLOWERS) {
                    val iterator = announcedFollows.iterator()
                    if (iterator.hasNext()) { iterator.next(); iterator.remove() } else break
                }
                pendingFollows.clear()
                pendingFollowOverflow = 0
            } else {
                scheduleFollowFlushLocked(alertRetryDelayMs.coerceAtLeast(100L))
            }
        }
    }

    private fun flushGiftBatch() {
        synchronized(alertLock) {
            giftFlush = null
            if (closed || pendingGifts.isEmpty() && pendingGiftOverflowUsers == 0) return
            if (!settings.get().readGifts) {
                pendingGifts.clear()
                pendingGiftOverflowUsers = 0
                pendingGiftOverflowCount = 0L
                return
            }
            val items = pendingGifts.values.map { it.displayName to it.count }
            val totalCount = pendingGifts.values.fold(pendingGiftOverflowCount) { total, item ->
                if (Long.MAX_VALUE - total < item.count) Long.MAX_VALUE else total + item.count
            }
            val speech = GiftAlertRules.renderBatch(
                items,
                totalCount,
                pendingGiftOverflowUsers,
                settings.get()
            )
            if (speech != null && speechQueue.enqueue(speech, priority = true)) {
                pendingGifts.clear()
                pendingGiftOverflowUsers = 0
                pendingGiftOverflowCount = 0L
            } else {
                scheduleGiftFlushLocked(alertRetryDelayMs.coerceAtLeast(100L))
            }
        }
    }

    override fun close() {
        synchronized(alertLock) {
            if (closed) return
            closed = true
            followFlush?.cancel(false)
            giftFlush?.cancel(false)
            followFlush = null
            giftFlush = null
            pendingFollows.clear()
            announcedFollows.clear()
            pendingGifts.clear()
            pendingFollowOverflow = 0
            pendingGiftOverflowUsers = 0
            pendingGiftOverflowCount = 0L
        }
        alertScheduler.shutdownNow()
    }

    fun stats(): LivePipelineStats = LivePipelineStats(
        commentsReceived = commentsReceived.get(),
        commentsFiltered = commentsFiltered.get(),
        commentsQueued = commentsQueued.get(),
        eventsReceived = eventsReceived.get(),
        eventsQueued = eventsQueued.get(),
        speechQueueSize = speechQueue.queuedCount()
    )
}


/**
 * Tracks the current voice turn in the LIVE chat. The same user keeps the floor
 * until another accepted commenter speaks; returning after an interruption is
 * treated as a new turn and their name is announced again.
 */
class CommentSpeakerTracker {
    private var lastSpeakerKey: String? = null

    private fun keyFor(comment: LiveComment): String =
        comment.userId.trim().takeIf { it.isNotBlank() }
            ?: comment.displayName.trim().lowercase()

    /** Checks the current speaker without consuming the turn before queue acceptance. */
    @Synchronized
    fun shouldAnnounce(comment: LiveComment): Boolean {
        val key = keyFor(comment)
        return key.isNotBlank() && key != lastSpeakerKey
    }

    /** Commits the speaker turn only after the comment was accepted by the speech queue. */
    @Synchronized
    fun markQueued(comment: LiveComment) {
        keyFor(comment).takeIf { it.isNotBlank() }?.let { lastSpeakerKey = it }
    }

    /** Backwards-compatible helper used by existing callers and unit tests. */
    @Synchronized
    fun isNewSpeaker(comment: LiveComment): Boolean {
        val changed = shouldAnnounce(comment)
        if (changed) markQueued(comment)
        return changed
    }
}
