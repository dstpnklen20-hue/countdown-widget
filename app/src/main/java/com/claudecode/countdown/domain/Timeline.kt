package com.claudecode.countdown.domain

import java.time.ZoneId

const val MINUTES_PER_DAY = 24 * 60

/** Tasks with a time but no length take this much of the timeline. */
const val DEFAULT_BLOCK_MINUTES = 30

/** A block on a day's timeline, in minutes after midnight; [end] is exclusive. */
data class TimeBlock<T>(val item: T, val start: Int, val end: Int)

/** A block with its place: [lane] of [lanes] side-by-side columns within its overlap group. */
data class PlacedBlock<T>(val item: T, val start: Int, val end: Int, val lane: Int, val lanes: Int)

/** Where a timed entry sits: from its start time if it has one, else from its due time. */
fun blockMinutes(dueAt: Long, startAt: Long?, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
    val due = localTimeOf(dueAt, zone).let { it.hour * 60 + it.minute }
    val start = startAt?.takeIf { it < dueAt && dueAt - it < 24 * 3_600_000L }
        ?.let { localTimeOf(it, zone) }?.let { it.hour * 60 + it.minute }
        ?.takeIf { it < due }
    return if (start != null) start to due
    else due to (due + DEFAULT_BLOCK_MINUTES).coerceAtMost(MINUTES_PER_DAY)
}

/**
 * Lays blocks out like a calendar app: blocks that overlap (directly or through a chain) form a
 * group, each block takes the first free lane in its group, and the group shares its width
 * between that many lanes. Non-overlapping blocks keep the full width.
 */
fun <T> layoutBlocks(blocks: List<TimeBlock<T>>): List<PlacedBlock<T>> {
    val sorted = blocks.sortedWith(compareBy({ it.start }, { -it.end }))
    val out = mutableListOf<PlacedBlock<T>>()
    var group = mutableListOf<Pair<TimeBlock<T>, Int>>()
    var laneEnds = mutableListOf<Int>()
    var groupEnd = -1

    fun flush() {
        val lanes = laneEnds.size.coerceAtLeast(1)
        group.forEach { (b, lane) -> out += PlacedBlock(b.item, b.start, b.end, lane, lanes) }
        group = mutableListOf()
        laneEnds = mutableListOf()
    }

    for (b in sorted) {
        if (b.start >= groupEnd && group.isNotEmpty()) flush()
        val free = laneEnds.indexOfFirst { it <= b.start }
        val lane = if (free >= 0) free else laneEnds.size.also { laneEnds += 0 }
        laneEnds[lane] = b.end
        group += b to lane
        groupEnd = maxOf(if (group.size == 1) b.end else groupEnd, b.end)
    }
    if (group.isNotEmpty()) flush()
    return out
}
