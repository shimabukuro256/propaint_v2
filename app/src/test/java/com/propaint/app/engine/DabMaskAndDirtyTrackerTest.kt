package com.propaint.app.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DabMaskGeneratorTest {

    @Before
    fun setup() {
        mockAndroidLog()
    }

    // ── createDab 正常系 ────────────────────────────────────────────

    @Test
    fun `createDab normal radius returns non-null`() {
        val dab = DabMaskGenerator.createDab(100f, 100f, 20f, 0.8f)
        assertNotNull(dab)
        assertTrue("diameter > 0", dab!!.diameter > 0)
        assertEquals(dab.diameter * dab.diameter, dab.data.size)
    }

    @Test
    fun `createDab mask values in 0-255 range`() {
        val dab = DabMaskGenerator.createDab(100f, 100f, 30f, 0.5f)!!
        for (v in dab.data) {
            assertTrue("mask value should be in 0..255, got $v", v in 0..255)
        }
    }

    @Test
    fun `createDab center has highest intensity`() {
        val dab = DabMaskGenerator.createDab(100f, 100f, 30f, 1.0f)!!
        val center = dab.diameter / 2
        val centerValue = dab.data[center * dab.diameter + center]
        assertTrue("center value should be high with hardness=1: $centerValue", centerValue > 200)
    }

    @Test
    fun `createDab soft brush has gradient`() {
        val dab = DabMaskGenerator.createDab(100f, 100f, 30f, 0.0f)!!
        val center = dab.diameter / 2
        val centerValue = dab.data[center * dab.diameter + center]
        val edgeValue = dab.data[0]
        assertTrue("center > edge for soft brush", centerValue > edgeValue)
    }

    // ── createDab エッジケース ───────────────────────────────────────

    @Test
    fun `createDab radius less than 0_5 returns null`() {
        assertNull(DabMaskGenerator.createDab(100f, 100f, 0.4f, 0.8f))
    }

    @Test
    fun `createDab radius exactly 0_5 returns non-null`() {
        assertNotNull(DabMaskGenerator.createDab(100f, 100f, 0.5f, 0.8f))
    }

    @Test
    fun `createDab small radius returns 3x3 fallback`() {
        // radius/2 < 1 → 3x3 fallback
        val dab = DabMaskGenerator.createDab(10f, 10f, 1.5f, 0.8f)
        assertNotNull(dab)
        assertEquals(3, dab!!.diameter)
        assertEquals(9, dab.data.size)
        assertEquals(255, dab.data[4])  // center pixel
    }

    @Test
    fun `createDab NaN center returns null`() {
        assertNull(DabMaskGenerator.createDab(Float.NaN, 100f, 20f, 0.8f))
    }

    @Test
    fun `createDab Infinity center returns null`() {
        assertNull(DabMaskGenerator.createDab(100f, Float.POSITIVE_INFINITY, 20f, 0.8f))
    }

    @Test
    fun `createDab NaN radius returns null`() {
        assertNull(DabMaskGenerator.createDab(100f, 100f, Float.NaN, 0.8f))
    }

    @Test
    fun `createDab Infinity radius returns null`() {
        assertNull(DabMaskGenerator.createDab(100f, 100f, Float.POSITIVE_INFINITY, 0.8f))
    }

    // ── hardness 境界値 ─────────────────────────────────────────────

    @Test
    fun `createDab hardness 0 produces soft mask`() {
        val dab = DabMaskGenerator.createDab(100f, 100f, 20f, 0f)
        assertNotNull(dab)
    }

    @Test
    fun `createDab hardness 1 produces hard mask`() {
        val dab = DabMaskGenerator.createDab(100f, 100f, 20f, 1f)
        assertNotNull(dab)
    }

    @Test
    fun `createDab hardness out of range is clamped`() {
        // hardness > 1 → assertFail is logged but still produces a mask
        val dab = DabMaskGenerator.createDab(100f, 100f, 20f, 1.5f)
        assertNotNull(dab)
    }
}

class DirtyTileTrackerTest {

    @Test
    fun `markDirty and drain`() {
        val tracker = DirtyTileTracker()
        tracker.markDirty(1, 2)
        tracker.markDirty(3, 4)
        val dirty = tracker.drain()
        assertEquals(2, dirty.size)
    }

    @Test
    fun `drain empties the queue`() {
        val tracker = DirtyTileTracker()
        tracker.markDirty(0, 0)
        tracker.drain()
        val second = tracker.drain()
        assertTrue(second.isEmpty())
    }

    @Test
    fun `fullRebuild flag`() {
        val tracker = DirtyTileTracker()
        assertTrue("new tracker should need full rebuild", tracker.fullRebuildNeeded)
        assertTrue(tracker.checkAndClearFullRebuild())
        assertFalse("should be cleared after check", tracker.checkAndClearFullRebuild())

        tracker.markFullRebuild()
        assertTrue(tracker.checkAndClearFullRebuild())
    }

    @Test
    fun `packCoord and unpack roundtrip`() {
        val testCases = listOf(
            0 to 0, 1 to 2, -1 to -1, 100 to 200, -50 to 50,
            Int.MAX_VALUE to Int.MIN_VALUE,
        )
        for ((x, y) in testCases) {
            val packed = DirtyTileTracker.packCoord(x, y)
            assertEquals("x for ($x, $y)", x, DirtyTileTracker.unpackX(packed))
            assertEquals("y for ($x, $y)", y, DirtyTileTracker.unpackY(packed))
        }
    }

    @Test
    fun `markDirty deduplicates same coordinates`() {
        val tracker = DirtyTileTracker()
        tracker.markDirty(1, 1)
        tracker.markDirty(1, 1)
        tracker.markDirty(1, 1)
        val dirty = tracker.drain()
        // Set deduplication in drain()
        assertEquals(1, dirty.size)
    }
}
