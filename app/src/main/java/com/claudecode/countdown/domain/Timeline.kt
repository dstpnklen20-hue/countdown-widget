package com.claudecode.countdown.domain

import java.time.ZoneId

const val MINUTES_PER_DAY = 24 * 60

/** Tasks with a time but no length take this much of the timeline. */
const val DEFAULT_BLOCK_MINUTES = 30

/** A block on a day's timeline, in minutes after midnight; [end] is exclusive. */
data class TimeBlock<T>(val item: T, val start: Int, val end: Int)

/**
 * A block with its place: [lane] of [lanes] side-by-side columns within its overlap group, and
 * [depth] blocks of the same lane it is drawn over (see [layoutBlocks]).
 */
data class PlacedBlock<T>(val item: T, val start: Int, val end: Int, val lane: Int, val lanes: Int, val depth: Int = 0)

/** Where a timed entry sits: from its start time if it has one, else from its due time. */
fun blockMinutes(dueAt: Long, startAt: Long?, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
    val due = localTimeOf(dueAt, zone).let { it.hour * 60 + it.minute }
    val start = startAt?.takeIf { it < dueAt && dueAt - it < 24 * 3_600_000L }
        ?.let { localTimeOf(it, zone) }?.let { it.hour * 60 + it.minute }
        ?.takeIf { it < due }
    return if (start != null) start to due
    else due to (due + DEFAULT_BLOCK_MINUTES).coerceAtMost(MINUTES_PER_DAY)
}

/** A block starting at least this long after the one it overlaps sits on top of it, indented. */
const val NEST_AFTER_MINUTES = 30

/**
 * Lays blocks out like Google Calendar: blocks that overlap (directly or through a chain) form a
 * group whose width is shared between lanes. A block takes the first free lane; when none is free
 * but it starts [NEST_AFTER_MINUTES] or more after the latest block of a lane (whose title then
 * stays visible), it is drawn over that block, one [PlacedBlock.depth] step in; otherwise it opens
 * a lane of its own. Non-overlapping blocks keep the full width.
 */
fun <T> layoutBlocks(blocks: List<TimeBlock<T>>): List<PlacedBlock<T>> {
    val sorted = blocks.sortedWith(compareBy({ it.start }, { -it.end }))
    val out = mutableListOf<PlacedBlock<T>>()
    var group = mutableListOf<Triple<TimeBlock<T>, Int, Int>>()
    // The blocks still running in each lane, oldest first.
    var lanes = mutableListOf<MutableList<TimeBlock<T>>>()
    var groupEnd = -1

    fun flush() {
        val count = lanes.size.coerceAtLeast(1)
        group.forEach { (b, lane, depth) -> out += PlacedBlock(b.item, b.start, b.end, lane, count, depth) }
        group = mutableListOf()
        lanes = mutableListOf()
    }

    for (b in sorted) {
        if (b.start >= groupEnd && group.isNotEmpty()) flush()
        lanes.forEach { running -> running.removeAll { it.end <= b.start } }
        val free = lanes.indexOfFirst { it.isEmpty() }
        val nest = if (free >= 0) -1 else lanes.indexOfFirst { it.last().start + NEST_AFTER_MINUTES <= b.start }
        val lane = when {
            free >= 0 -> free
            nest >= 0 -> nest
            else -> lanes.size.also { lanes += mutableListOf<TimeBlock<T>>() }
        }
        val depth = lanes[lane].size
        lanes[lane] += b
        group += Triple(b, lane, depth)
        groupEnd = maxOf(if (group.size == 1) b.end else groupEnd, b.end)
    }
    if (group.isNotEmpty()) flush()
    return out
}
