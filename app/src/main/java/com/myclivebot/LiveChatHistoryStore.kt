package com.myclivebot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.myclivebot.core.LiveComment

/**
 * Session chat history survives Activity stop/start while the foreground LIVE
 * service keeps receiving messages. SQLite avoids oversized SharedPreferences.
 */
class LiveChatHistoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "tiklbot_live_chat.db",
    null,
    2
) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE live_comments (
                id TEXT PRIMARY KEY NOT NULL,
                username TEXT NOT NULL,
                message TEXT NOT NULL,
                avatar_url TEXT,
                received_at INTEGER NOT NULL,
                reply_to TEXT,
                is_moderator INTEGER NOT NULL DEFAULT 0,
                is_follower INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_live_comments_received_at ON live_comments(received_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE live_comments ADD COLUMN is_moderator INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE live_comments ADD COLUMN is_follower INTEGER")
        }
    }

    fun add(comment: LiveComment) {
        val values = ContentValues().apply {
            put("id", comment.id)
            put("username", comment.displayName)
            put("message", comment.text)
            put("avatar_url", comment.avatarUrl)
            put("received_at", comment.receivedAt)
            put("reply_to", comment.replyToDisplayName)
            put("is_moderator", if (comment.isModerator) 1 else 0)
            if (comment.isFollower != null) put("is_follower", if (comment.isFollower == true) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            "live_comments",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getAll(): List<LiveComment> {
        val comments = ArrayList<LiveComment>()
        readableDatabase.query(
            "live_comments",
            arrayOf("id", "username", "message", "avatar_url", "received_at", "reply_to", "is_moderator", "is_follower"),
            null,
            null,
            null,
            null,
            "received_at ASC, rowid ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                comments += LiveComment(
                    userId = "",
                    displayName = cursor.getString(1).orEmpty(),
                    text = cursor.getString(2).orEmpty(),
                    avatarUrl = cursor.getString(3),
                    receivedAt = cursor.getLong(4),
                    id = cursor.getString(0),
                    replyToDisplayName = cursor.getString(5),
                    isModerator = cursor.getInt(6) != 0,
                    isFollower = if (cursor.isNull(7)) null else cursor.getInt(7) != 0
                )
            }
        }
        return comments
    }

    fun clear() {
        writableDatabase.delete("live_comments", null, null)
    }
}
