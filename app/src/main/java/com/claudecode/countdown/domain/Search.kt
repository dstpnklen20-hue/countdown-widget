package com.claudecode.countdown.domain

import com.claudecode.countdown.data.db.Task

/**
 * Tasks whose title or notes contain every word of [query], ignoring case. Title matches come
 * before notes-only matches, open tasks before completed ones.
 */
fun searchTasks(tasks: List<Task>, query: String): List<Task> {
    val words = query.lowercase().split(' ', '\t', '\n').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return tasks
        .filter { t ->
            val text = (t.title + "\n" + t.content).lowercase()
            words.all { it in text }
        }
        .sortedWith(
            compareBy<Task>({ it.isDone }, { t -> !words.all { it in t.title.lowercase() } }, { -it.updatedAt })
        )
}
