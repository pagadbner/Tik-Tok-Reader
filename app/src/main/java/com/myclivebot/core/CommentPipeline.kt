package com.myclivebot.core

import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
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
    private val alertScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TikLBot-Alert-Batching").apply { isDaemon = true }
        }
    private val pendingFollows = LinkedHashMap<String, String>()
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
        private const val MAX_GIFT_GROUPS_PER_USER = 64
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
        // A welcome is emitted once per accepted commenter in this connection.
        welcomeTracker.messageFor(
            comment,
            current,
            filter.usernameForSpeech(comment, current)
        )?.let { welcome ->
            val welcomeSpeech = filter.toSpeech(
                comment.copy(text = welcome),
                current.copy(readUsername = false, onlyUsername = false)
            )
            speechQueue.enqueue(welcomeSpeech, comment = true)
        }

        // A speaker is named once when they take the floor. Consecutive comments
        // from that same person omit the name until another accepted speaker appears.
        val announceSpeaker = speakerTracker.isNewSpeaker(comment)

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
        val queued = speechQueue.enqueue(speech, itemId = comment.id, comment = true)
        if (queued) commentsQueued.incrementAndGet()
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
            if (closed) return false
            if (!pendingFollows.containsKey(key)) {
                if (pendingFollows.size < MAX_BATCH_USERS) pendingFollows[key] = name
                else pendingFollowOverflow++
            }
            scheduleFollowFlushLocked(followBatchWindowMs.coerceAtLeast(0L))
        }
        return true
    }

    private fun queueGift(event: LiveEvent, current: ReaderSettings): Boolean {
        if (!GiftAlertRules.shouldAnnounce(event, current)) return false
        val name = ChatCommandParser.spokenUsername(event.displayName)
        val key = event.userId.trim().ifBlank { name.lowercase() }
        if (key.isBlank()) return false
        val count = GiftAlertRules.repeatCount(event).toLong()
        synchronized(alertLock) {
            if (closed) return false
            val groupId = event.giftGroupId.trim()
            val existing = pendingGifts[key]
            if (existing != null) {
                if (groupId.isBlank()) {
                    existing.count = addGiftCounts(existing.count, count)
                } else {
                    val previousGroupCount = existing.groupCounts[groupId]
                    when {
                        previousGroupCount == null -> {
                            if (existing.groupCounts.size < MAX_GIFT_GROUPS_PER_USER) {
                                existing.groupCounts[groupId] = count
                            }
                            existing.count = addGiftCounts(existing.count, count)
                        }
                        count > previousGroupCount -> {
                            existing.groupCounts[groupId] = count
                            existing.count = addGiftCounts(existing.count, count - previousGroupCount)
                        }
                    }
                }
            } else if (pendingGifts.size < MAX_BATCH_USERS) {
                val groupCounts = if (groupId.isBlank()) LinkedHashMap() else linkedMapOf(groupId to count)
                pendingGifts[key] = GiftBatchItem(name, count, groupCounts)
            } else {
                pendingGiftOverflowUsers++
                pendingGiftOverflowCount = if (Long.MAX_VALUE - pendingGiftOverflowCount < count) {
                    Long.MAX_VALUE
                } else pendingGiftOverflowCount + count
            }
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

    @Synchronized
    fun isNewSpeaker(comment: LiveComment): Boolean {
        val key = comment.userId.trim().takeIf { it.isNotBlank() }
            ?: comment.displayName.trim().lowercase()
        if (key.isBlank()) return false
        val changed = key != lastSpeakerKey
        lastSpeakerKey = key
        return changed
    }
}
