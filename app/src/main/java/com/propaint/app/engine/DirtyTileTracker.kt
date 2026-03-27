package com.propaint.app.engine

import java.util.concurrent.ConcurrentLinkedQueue

class DirtyTileTracker {
    private val queue = ConcurrentLinkedQueue<Long>()
    @Volatile var fullRebuildNeeded = true

    fun markDirty(tx: Int, ty: Int) { queue.add(packCoord(tx, ty)) }
    fun markFullRebuild() { fullRebuildNeeded = true }

    fun drain(): Set<Long> {
        val r = HashSet<Long>()
        while (true) { r.add(queue.poll() ?: break) }
        return r
    }

    fun checkAndClearFullRebuild(): Boolean {
        if (fullRebuildNeeded) { fullRebuildNeeded = false; return true }
        return false
    }

    companion object {
        fun packCoord(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        fun unpackX(p: Long): Int = (p shr 32).toInt()
        fun unpackY(p: Long): Int = p.toInt()
    }
}
