package com.propaint.app.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BrushEngineTest {

    private lateinit var dirtyTracker: DirtyTileTracker
    private lateinit var engine: BrushEngine

    @Before
    fun setup() {
        mockAndroidLog()
        dirtyTracker = DirtyTileTracker()
        engine = BrushEngine(dirtyTracker)
    }

    // ── beginStroke / endStroke ──────────────────────────────────────

    @Test
    fun `beginStroke sets strokeLayerId`() {
        engine.beginStroke(1)
        assertEquals(1, engine.strokeLayerId)
    }

    @Test
    fun `endStroke resets strokeLayerId`() {
        engine.beginStroke(1)
        engine.endStroke()
        assertEquals(-1, engine.strokeLayerId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `beginStroke rejects zero layerId`() {
        engine.beginStroke(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `beginStroke rejects negative layerId`() {
        engine.beginStroke(-1)
    }

    // ── addPoint 防御チェック ────────────────────────────────────────

    @Test
    fun `addPoint rejects NaN coordinates`() {
        // BUG対策: NaN 座標で描画が壊れない
        engine.beginStroke(1)
        val surface = TiledSurface(256, 256)
        val brush = BrushConfig(size = 10f)
        val nanPoint = BrushEngine.StrokePoint(Float.NaN, 100f)
        // Should not crash
        engine.addPoint(nanPoint, surface, surface, brush)
    }

    @Test
    fun `addPoint rejects Infinity coordinates`() {
        engine.beginStroke(1)
        val surface = TiledSurface(256, 256)
        val brush = BrushConfig(size = 10f)
        val infPoint = BrushEngine.StrokePoint(Float.POSITIVE_INFINITY, 100f)
        engine.addPoint(infPoint, surface, surface, brush)
    }

    @Test
    fun `addPoint clamps out-of-range pressure`() {
        // BUG対策: 筆圧 0.0 で radius = 0 になる問題
        engine.beginStroke(1)
        val surface = TiledSurface(256, 256)
        val brush = BrushConfig(size = 10f, spacing = 0.25f)
        val negPressure = BrushEngine.StrokePoint(100f, 100f, pressure = -0.5f)
        // Should not crash, pressure should be clamped
        engine.addPoint(negPressure, surface, surface, brush)

        val overPressure = BrushEngine.StrokePoint(110f, 100f, pressure = 2.0f)
        engine.addPoint(overPressure, surface, surface, brush)
    }

    @Test
    fun `addPoint rejects zero brush size`() {
        engine.beginStroke(1)
        val surface = TiledSurface(256, 256)
        val brush = BrushConfig(size = 0f)
        val point = BrushEngine.StrokePoint(100f, 100f)
        // Should return early without crash
        engine.addPoint(point, surface, surface, brush)
    }

    @Test
    fun `addPoint rejects zero spacing`() {
        // BUG対策: spacing = 0 で無限ループにならないか
        engine.beginStroke(1)
        val surface = TiledSurface(256, 256)
        val brush = BrushConfig(size = 10f, spacing = 0f)
        val p1 = BrushEngine.StrokePoint(100f, 100f)
        val p2 = BrushEngine.StrokePoint(150f, 100f)
        // Should return early, not loop forever
        engine.addPoint(p1, surface, surface, brush)
        engine.addPoint(p2, surface, surface, brush)
    }

    @Test
    fun `addPoint rejects negative spacing`() {
        engine.beginStroke(1)
        val surface = TiledSurface(256, 256)
        val brush = BrushConfig(size = 10f, spacing = -0.1f)
        val p1 = BrushEngine.StrokePoint(100f, 100f)
        engine.addPoint(p1, surface, surface, brush)
    }

    // ── 正常なストローク ────────────────────────────────────────────

    @Test
    fun `normal stroke produces dirty tiles`() {
        engine.beginStroke(1)
        val surface = TiledSurface(256, 256)
        val brush = BrushConfig(
            size = 20f,
            spacing = 0.25f,
            hardness = 0.8f,
            opacity = 1f,
            colorPremul = 0xFF000000.toInt(),
            indirect = false,
        )
        // Two points to create a segment
        engine.addPoint(BrushEngine.StrokePoint(32f, 32f, 1f), surface, surface, brush)
        engine.addPoint(BrushEngine.StrokePoint(40f, 32f, 1f), surface, surface, brush)
        engine.endStroke()

        // Dirty tracker should have been notified
        val dirty = dirtyTracker.drain()
        assertTrue("Expected dirty tiles from stroke, got none", dirty.isNotEmpty())
    }

    @Test
    fun `stroke writes pixels to surface`() {
        engine.beginStroke(1)
        val surface = TiledSurface(256, 256)
        val brush = BrushConfig(
            size = 20f,
            spacing = 0.15f,
            hardness = 1f,
            opacity = 1f,
            density = 1f,
            colorPremul = 0xFFFF0000.toInt(),  // opaque red
            indirect = false,
            pressureSizeEnabled = false,  // サイズ変動なし
            taperEnabled = false,         // テーパーなし
        )
        // 十分離れた2点でダブが配置されるようにする
        engine.addPoint(BrushEngine.StrokePoint(32f, 32f, 1f), surface, surface, brush)
        engine.addPoint(BrushEngine.StrokePoint(60f, 32f, 1f), surface, surface, brush)
        engine.endStroke()

        // Tile at (0,0) should have some non-zero pixels
        val tile = surface.getTile(0, 0)
        assertNotNull("Tile should exist after stroke", tile)
        assertFalse("Tile should not be blank after stroke", tile!!.isBlank())
    }

    // ── 手振れ補正 (stabilizer) ─────────────────────────────────────

    @Test
    fun `stabilizer smooths coordinates`() {
        engine.beginStroke(1)
        val surface = TiledSurface(512, 512)
        val brush = BrushConfig(
            size = 10f,
            spacing = 0.25f,
            stabilizer = 0.8f,  // strong smoothing
            indirect = false,
        )
        // Feed a sharp corner — stabilizer should smooth it
        engine.addPoint(BrushEngine.StrokePoint(100f, 100f, 1f), surface, surface, brush)
        engine.addPoint(BrushEngine.StrokePoint(200f, 100f, 1f), surface, surface, brush)
        // No crash is the main check; smoothing correctness is visual
        engine.endStroke()
    }

    @Test
    fun `stabilizer zero is bypass`() {
        engine.beginStroke(1)
        val surface = TiledSurface(256, 256)
        val brush = BrushConfig(
            size = 10f,
            spacing = 0.25f,
            stabilizer = 0f,
            indirect = false,
        )
        engine.addPoint(BrushEngine.StrokePoint(100f, 100f, 1f), surface, surface, brush)
        engine.addPoint(BrushEngine.StrokePoint(110f, 100f, 1f), surface, surface, brush)
        engine.endStroke()
    }

    // ── StrokePoint data class ──────────────────────────────────────

    @Test
    fun `StrokePoint defaults`() {
        val pt = BrushEngine.StrokePoint(10f, 20f)
        assertEquals(10f, pt.x, 0f)
        assertEquals(20f, pt.y, 0f)
        assertEquals(1f, pt.pressure, 0f)
        assertEquals(0L, pt.timestamp)
    }
}
