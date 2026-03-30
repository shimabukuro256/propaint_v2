package com.propaint.app.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * MemoryConfig のティア設定と推定メモリ計算のユニットテスト。
 */
class MemoryConfigTest {

    @Before
    fun setUp() {
        // テストごとにデフォルトに戻す
        MemoryConfig.applyTier(6000) // 6GB = デフォルト的な中間ティア
    }

    // ── ティア選択テスト ─────────────────────────────────────

    @Test
    fun `2GB tier for low-memory device`() {
        MemoryConfig.applyTier(1800)
        assertEquals("2GB", MemoryConfig.tierName)
        assertEquals(256, MemoryConfig.maxDabDiameter)
        assertEquals(128, MemoryConfig.maxBlurRadius)
        assertEquals(15, MemoryConfig.maxUndoEntries)
        assertEquals(2048, MemoryConfig.maxCanvasSize)
        assertEquals(8, MemoryConfig.maxLayers)
        assertEquals(30_000L, MemoryConfig.autoSaveIntervalMs)
    }

    @Test
    fun `4GB tier`() {
        MemoryConfig.applyTier(4000)
        assertEquals("4GB", MemoryConfig.tierName)
        assertEquals(384, MemoryConfig.maxDabDiameter)
        assertEquals(256, MemoryConfig.maxBlurRadius)
        assertEquals(30, MemoryConfig.maxUndoEntries)
        assertEquals(3072, MemoryConfig.maxCanvasSize)
        assertEquals(12, MemoryConfig.maxLayers)
    }

    @Test
    fun `6GB tier`() {
        MemoryConfig.applyTier(6000)
        assertEquals("6GB", MemoryConfig.tierName)
        assertEquals(512, MemoryConfig.maxDabDiameter)
        assertEquals(384, MemoryConfig.maxBlurRadius)
        assertEquals(40, MemoryConfig.maxUndoEntries)
        assertEquals(4096, MemoryConfig.maxCanvasSize)
        assertEquals(16, MemoryConfig.maxLayers)
    }

    @Test
    fun `8GB tier`() {
        MemoryConfig.applyTier(8000)
        assertEquals("8GB", MemoryConfig.tierName)
        assertEquals(512, MemoryConfig.maxDabDiameter)
        assertEquals(512, MemoryConfig.maxBlurRadius)
        assertEquals(50, MemoryConfig.maxUndoEntries)
        assertEquals(6144, MemoryConfig.maxCanvasSize)
        assertEquals(24, MemoryConfig.maxLayers)
    }

    @Test
    fun `10GB tier`() {
        MemoryConfig.applyTier(10000)
        assertEquals("10GB", MemoryConfig.tierName)
        assertEquals(640, MemoryConfig.maxDabDiameter)
        assertEquals(640, MemoryConfig.maxBlurRadius)
        assertEquals(60, MemoryConfig.maxUndoEntries)
        assertEquals(8192, MemoryConfig.maxCanvasSize)
        assertEquals(32, MemoryConfig.maxLayers)
    }

    @Test
    fun `12GB tier`() {
        MemoryConfig.applyTier(12000)
        assertEquals("12GB", MemoryConfig.tierName)
        assertEquals(768, MemoryConfig.maxDabDiameter)
        assertEquals(768, MemoryConfig.maxBlurRadius)
        assertEquals(80, MemoryConfig.maxUndoEntries)
        assertEquals(10240, MemoryConfig.maxCanvasSize)
        assertEquals(40, MemoryConfig.maxLayers)
    }

    @Test
    fun `14GB tier`() {
        MemoryConfig.applyTier(14000)
        assertEquals("14GB", MemoryConfig.tierName)
        assertEquals(896, MemoryConfig.maxDabDiameter)
        assertEquals(896, MemoryConfig.maxBlurRadius)
        assertEquals(100, MemoryConfig.maxUndoEntries)
        assertEquals(12288, MemoryConfig.maxCanvasSize)
        assertEquals(48, MemoryConfig.maxLayers)
    }

    @Test
    fun `16GB tier for high-memory device`() {
        MemoryConfig.applyTier(16000)
        assertEquals("16GB", MemoryConfig.tierName)
        assertEquals(1024, MemoryConfig.maxDabDiameter)
        assertEquals(1024, MemoryConfig.maxBlurRadius)
        assertEquals(120, MemoryConfig.maxUndoEntries)
        assertEquals(16384, MemoryConfig.maxCanvasSize)
        assertEquals(64, MemoryConfig.maxLayers)
        assertEquals(120_000L, MemoryConfig.autoSaveIntervalMs)
    }

    // ── 境界値テスト ────────────────────────────────────────

    @Test
    fun `tier boundary - exactly 3000MB selects 4GB`() {
        MemoryConfig.applyTier(3000)
        assertEquals("4GB", MemoryConfig.tierName)
    }

    @Test
    fun `tier boundary - 2999MB selects 2GB`() {
        MemoryConfig.applyTier(2999)
        assertEquals("2GB", MemoryConfig.tierName)
    }

    @Test
    fun `tier boundary - 15000MB selects 16GB`() {
        MemoryConfig.applyTier(15000)
        assertEquals("16GB", MemoryConfig.tierName)
    }

    @Test
    fun `very high RAM still uses 16GB tier`() {
        MemoryConfig.applyTier(32000)
        assertEquals("16GB", MemoryConfig.tierName)
    }

    // ── メモリ推定テスト ────────────────────────────────────

    @Test
    fun `estimateMemoryMb returns positive for small canvas`() {
        MemoryConfig.applyTier(8000)
        val mb = MemoryConfig.estimateMemoryMb(1024, 1024, 4)
        assertTrue("Expected positive memory estimate, got $mb", mb > 0)
    }

    @Test
    fun `estimateMemoryMb scales with canvas size`() {
        MemoryConfig.applyTier(8000)
        val small = MemoryConfig.estimateMemoryMb(1024, 1024, 4)
        val large = MemoryConfig.estimateMemoryMb(4096, 4096, 4)
        assertTrue("Larger canvas should use more memory: $small vs $large", large > small)
    }

    @Test
    fun `estimateMemoryMb scales with layer count`() {
        MemoryConfig.applyTier(8000)
        val few = MemoryConfig.estimateMemoryMb(2048, 2048, 2)
        val many = MemoryConfig.estimateMemoryMb(2048, 2048, 10)
        assertTrue("More layers should use more memory: $few vs $many", many > few)
    }

    // ── 全ティアでパラメータの単調増加を検証 ─────────────────

    @Test
    fun `all tiers have monotonically increasing limits`() {
        val rams = listOf(2000, 4000, 6000, 8000, 10000, 12000, 14000, 16000)
        var prevDab = 0
        var prevBlur = 0
        var prevUndo = 0
        var prevCanvas = 0
        var prevLayers = 0

        for (ram in rams) {
            MemoryConfig.applyTier(ram)
            assertTrue("maxDabDiameter should increase: ${MemoryConfig.maxDabDiameter} > $prevDab",
                MemoryConfig.maxDabDiameter >= prevDab)
            assertTrue("maxBlurRadius should increase: ${MemoryConfig.maxBlurRadius} > $prevBlur",
                MemoryConfig.maxBlurRadius >= prevBlur)
            assertTrue("maxUndoEntries should increase: ${MemoryConfig.maxUndoEntries} > $prevUndo",
                MemoryConfig.maxUndoEntries >= prevUndo)
            assertTrue("maxCanvasSize should increase: ${MemoryConfig.maxCanvasSize} > $prevCanvas",
                MemoryConfig.maxCanvasSize >= prevCanvas)
            assertTrue("maxLayers should increase: ${MemoryConfig.maxLayers} > $prevLayers",
                MemoryConfig.maxLayers >= prevLayers)

            prevDab = MemoryConfig.maxDabDiameter
            prevBlur = MemoryConfig.maxBlurRadius
            prevUndo = MemoryConfig.maxUndoEntries
            prevCanvas = MemoryConfig.maxCanvasSize
            prevLayers = MemoryConfig.maxLayers
        }
    }
}
