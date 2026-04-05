package com.propaint.app.engine

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class DirtyTileTracker {
    private val queue = ConcurrentLinkedQueue<Long>()
    private val fullRebuild = AtomicBoolean(true)

    fun markDirty(tx: Int, ty: Int) { queue.add(packCoord(tx, ty)) }
    fun markFullRebuild() { fullRebuild.set(true) }

    fun drain(): Set<Long> {
        val r = HashSet<Long>()
        while (true) { r.add(queue.poll() ?: break) }
        return r
    }

    /** アトミックに check-and-clear。GL スレッドとエンジンスレッドの競合を防止。 */
    fun checkAndClearFullRebuild(): Boolean = fullRebuild.compareAndSet(true, false)

    companion object {
        fun packCoord(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        fun unpackX(p: Long): Int = (p shr 32).toInt()
        fun unpackY(p: Long): Int = p.toInt()
    }
}
