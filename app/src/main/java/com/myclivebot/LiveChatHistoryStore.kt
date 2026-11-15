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
    1
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
                reply_to TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_live_comments_received_at ON live_comments(received_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the initial session-history schema.
    }

    fun add(comment: LiveComment) {
        val values = ContentValues().apply {
            put("id", comment.id)
            put("username", comment.displayName)
            put("message", comment.text)
            put("avatar_url", comment.avatarUrl)
            put("received_at", comment.receivedAt)
            put("reply_to", comment.replyToDisplayName)
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
            arrayOf("id", "username", "message", "avatar_url", "received_at", "reply_to"),
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
                    replyToDisplayName = cursor.getString(5)
                )
            }
        }
        return comments
    }

    fun clear() {
        writableDatabase.delete("live_comments", null, null)
    }
}
