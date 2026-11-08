package com.myclivebot.core

import java.util.ArrayDeque

class CommentQueue(private val capacity: Int = 30) {
    private val queue = ArrayDeque<LiveComment>()

    @Synchronized fun offer(comment: LiveComment) {
        while (queue.size >= capacity.coerceAtLeast(1)) queue.removeFirst()
        queue.addLast(comment)
    }

    @Synchronized fun poll(): LiveComment? = if (queue.isEmpty()) null else queue.removeFirst()
    @Synchronized fun clear() = queue.clear()
    @Synchronized fun size(): Int = queue.size
}
