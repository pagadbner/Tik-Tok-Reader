package com.myclivebot.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop SQLite history store. All database access is serialized through one
 * bounded writer so LIVE event callbacks never block on disk I/O.
 */
class DesktopLiveChatHistoryStore(
    databasePath: Path = defaultDatabasePath()
) : AutoCloseable {
    private val connection: Connection
    private val closed = AtomicBoolean(false)
    private val writerLock = Any()
    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2048),
        { task -> Thread(task, "TikLBot-chat-history").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    init {
        val absolute = databasePath.toAbsolutePath()
        absolute.parent?.let(Files::createDirectories)
        connection = DriverManager.getConnection("jdbc:sqlite:$absolute")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS live_comments (
                    id TEXT PRIMARY KEY NOT NULL,
                    user_id TEXT NOT NULL DEFAULT '',
                    display_name TEXT NOT NULL,
                    message TEXT NOT NULL,
                    avatar_url TEXT,
                    is_moderator INTEGER NOT NULL DEFAULT 0,
                    is_follower INTEGER,
                    received_at INTEGER NOT NULL,
                    reply_to_display_name TEXT,
                    content_language TEXT,
                    profile_username TEXT NOT NULL DEFAULT '',
                    reply_to_profile_username TEXT
                )
                """.trimIndent()
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_live_comments_received_at ON live_comments(received_at)"
            )
            statement.execute("PRAGMA user_version=2")
        }
    }

    fun addAsync(comment: LiveComment): Boolean {
        if (closed.get()) return false
        return try {
            writer.execute { insert(comment) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    fun getAll(): List<LiveComment> = afterPendingWrites {
        val result = ArrayList<LiveComment>()
        connection.prepareStatement(
            """
            SELECT id, user_id, display_name, message, avatar_url, is_moderator,
                   is_follower, received_at, reply_to_display_name, content_language,
                   profile_username, reply_to_profile_username
            FROM live_comments
            ORDER BY received_at ASC, rowid ASC
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val followerValue = rows.getInt("is_follower")
                    val follower = if (rows.wasNull()) null else followerValue != 0
                    result.add(
                        LiveComment(
                            userId = rows.getString("user_id").orEmpty(),
                            displayName = rows.getString("display_name").orEmpty(),
                            text = rows.getString("message").orEmpty(),
                            avatarUrl = rows.getString("avatar_url"),
                            isModerator = rows.getInt("is_moderator") != 0,
                            isFollower = follower,
                            receivedAt = rows.getLong("received_at"),
                            id = rows.getString("id"),
                            replyToDisplayName = rows.getString("reply_to_display_name"),
                            contentLanguage = rows.getString("content_language"),
                            profileUsername = rows.getString("profile_username").orEmpty(),
                            replyToProfileUsername = rows.getString("reply_to_profile_username")
                        )
                    )
                }
            }
        }
        result
    }

    fun clear() {
        afterPendingWrites {
            connection.createStatement().use { it.executeUpdate("DELETE FROM live_comments") }
        }
    }

    fun count(): Int = afterPendingWrites {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM live_comments").use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }
    }

    private fun insert(comment: LiveComment) {
        connection.prepareStatement(
            """
            INSERT OR REPLACE INTO live_comments (
                id, user_id, display_name, message, avatar_url, is_moderator,
                is_follower, received_at, reply_to_display_name, content_language,
                profile_username, reply_to_profile_username
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, comment.id)
            statement.setString(2, comment.userId)
            statement.setString(3, comment.displayName)
            statement.setString(4, comment.text)
            statement.setString(5, comment.avatarUrl)
            statement.setInt(6, if (comment.isModerator) 1 else 0)
            if (comment.isFollower == null) statement.setNull(7, Types.INTEGER)
            else statement.setInt(7, if (comment.isFollower) 1 else 0)
            statement.setLong(8, comment.receivedAt)
            statement.setString(9, comment.replyToDisplayName)
            statement.setString(10, comment.contentLanguage)
            statement.setString(11, comment.profileUsername)
            statement.setString(12, comment.replyToProfileUsername)
            statement.executeUpdate()
        }
    }

    private fun <T> afterPendingWrites(block: () -> T): T {
        check(!closed.get()) { "El historial de chat ya está cerrado." }
        val task = FutureTask(Callable { block() })
        synchronized(writerLock) {
            check(!closed.get()) { "El historial de chat ya está cerrado." }
            try {
                writer.execute(task)
            } catch (_: RejectedExecutionException) {
                writer.queue.put(task)
            }
        }
        return task.get()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        writer.shutdown()
        if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
            writer.shutdownNow()
            writer.awaitTermination(2, TimeUnit.SECONDS)
        }
        connection.close()
    }

    companion object {
        private fun defaultDatabasePath(): Path {
            val local = System.getenv("LOCALAPPDATA")
            val base = if (!local.isNullOrBlank()) Paths.get(local) else Paths.get(System.getProperty("user.home"))
            val currentDirectory = base.resolve(if (!local.isNullOrBlank()) "Tik Tok Reader" else ".tik-tok-reader")
            val legacyDirectory = base.resolve(if (!local.isNullOrBlank()) "TikLBot" else ".tiklbot")
            val current = currentDirectory.resolve("live-chat.db")
            val legacy = legacyDirectory.resolve("live-chat.db")

            // Preserve existing Windows chat history after the product folder rename.
            if (!Files.exists(current) && Files.exists(legacy)) {
                val sidecars = listOf("", "-wal", "-shm")
                val copied = runCatching {
                    Files.createDirectories(currentDirectory)
                    sidecars.forEach { suffix ->
                        val source = legacy.resolveSibling(legacy.fileName.toString() + suffix)
                        val target = current.resolveSibling(current.fileName.toString() + suffix)
                        if (Files.exists(source)) Files.copy(source, target)
                    }
                }.isSuccess
                if (!copied) {
                    // Keep the original database and its WAL untouched if migration is incomplete.
                    sidecars.forEach { suffix ->
                        runCatching { Files.deleteIfExists(current.resolveSibling(current.fileName.toString() + suffix)) }
                    }
                }
            }
            return if (Files.exists(current)) current else if (Files.exists(legacy)) legacy else current
        }
    }
}
