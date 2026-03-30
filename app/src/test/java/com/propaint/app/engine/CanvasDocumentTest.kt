package com.propaint.app.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CanvasDocumentTest {

    private lateinit var doc: CanvasDocument

    @Before
    fun setup() {
        mockAndroidLog()
        doc = CanvasDocument(256, 256)
    }

    // ── 初期状態 ────────────────────────────────────────────────────

    @Test
    fun `new document has one layer`() {
        assertEquals(1, doc.layers.size)
        assertEquals("レイヤー 1", doc.layers[0].name)
    }

    @Test
    fun `new document activeLayerId matches first layer`() {
        assertEquals(doc.layers[0].id, doc.activeLayerId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects zero width`() {
        CanvasDocument(0, 256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects zero height`() {
        CanvasDocument(256, 0)
    }

    // ── レイヤー追加 ────────────────────────────────────────────────

    @Test
    fun `addLayer increases layer count`() {
        doc.addLayer("レイヤー 2")
        assertEquals(2, doc.layers.size)
        assertEquals("レイヤー 2", doc.layers[1].name)
    }

    @Test
    fun `addLayer at specific index`() {
        doc.addLayer("Bottom", atIndex = 0)
        assertEquals("Bottom", doc.layers[0].name)
        assertEquals("レイヤー 1", doc.layers[1].name)
    }

    @Test
    fun `addLayer returns new layer with unique id`() {
        val l1 = doc.layers[0]
        val l2 = doc.addLayer("レイヤー 2")
        assertNotEquals(l1.id, l2.id)
    }

    // ── レイヤー削除 ────────────────────────────────────────────────

    @Test
    fun `removeLayer removes the layer`() {
        val l2 = doc.addLayer("レイヤー 2")
        assertTrue(doc.removeLayer(l2.id))
        assertEquals(1, doc.layers.size)
    }

    @Test
    fun `removeLayer prevents removing last layer`() {
        assertFalse(doc.removeLayer(doc.layers[0].id))
        assertEquals(1, doc.layers.size)
    }

    @Test
    fun `removeLayer updates activeLayerId when active is removed`() {
        // BUG対策: レイヤー削除後に activeLayerIndex が更新されているか
        val l1 = doc.layers[0]
        val l2 = doc.addLayer("レイヤー 2")
        doc.setActiveLayer(l2.id)
        assertEquals(l2.id, doc.activeLayerId)

        doc.removeLayer(l2.id)
        // active should fall back to remaining layer
        assertEquals(l1.id, doc.activeLayerId)
    }

    @Test
    fun `removeLayer with nonexistent id returns false`() {
        assertFalse(doc.removeLayer(9999))
    }

    // ── レイヤー切替 ────────────────────────────────────────────────

    @Test
    fun `setActiveLayer changes active layer`() {
        val l2 = doc.addLayer("レイヤー 2")
        doc.setActiveLayer(l2.id)
        assertEquals(l2.id, doc.activeLayerId)
    }

    @Test
    fun `setActiveLayer ignores nonexistent id`() {
        val originalActive = doc.activeLayerId
        doc.setActiveLayer(9999)
        assertEquals(originalActive, doc.activeLayerId)
    }

    @Test
    fun `getActiveLayer returns correct layer`() {
        val active = doc.getActiveLayer()
        assertNotNull(active)
        assertEquals(doc.activeLayerId, active!!.id)
    }

    // ── レイヤー移動 ────────────────────────────────────────────────

    @Test
    fun `moveLayer swaps positions`() {
        val l1Id = doc.layers[0].id
        val l2 = doc.addLayer("レイヤー 2")
        doc.moveLayer(0, 1)
        assertEquals(l2.id, doc.layers[0].id)
        assertEquals(l1Id, doc.layers[1].id)
    }

    @Test
    fun `moveLayer same position is no-op`() {
        val l1Name = doc.layers[0].name
        doc.addLayer("レイヤー 2")
        doc.moveLayer(0, 0)
        assertEquals(l1Name, doc.layers[0].name)
    }

    @Test
    fun `moveLayer out of bounds is no-op`() {
        doc.addLayer("レイヤー 2")
        val names = doc.layers.map { it.name }
        doc.moveLayer(-1, 0)
        doc.moveLayer(0, 5)
        assertEquals(names, doc.layers.map { it.name })
    }

    // ── レイヤープロパティ ──────────────────────────────────────────

    @Test
    fun `setLayerOpacity clamps to 0-1`() {
        val id = doc.layers[0].id
        doc.setLayerOpacity(id, 1.5f)
        assertEquals(1f, doc.layers[0].opacity, 0.001f)
        doc.setLayerOpacity(id, -0.5f)
        assertEquals(0f, doc.layers[0].opacity, 0.001f)
    }

    @Test
    fun `setLayerBlendMode updates blend mode`() {
        val id = doc.layers[0].id
        doc.setLayerBlendMode(id, PixelOps.BLEND_MULTIPLY)
        assertEquals(PixelOps.BLEND_MULTIPLY, doc.layers[0].blendMode)
    }

    @Test
    fun `setLayerVisibility toggles visibility`() {
        val id = doc.layers[0].id
        assertTrue(doc.layers[0].isVisible)
        doc.setLayerVisibility(id, false)
        assertFalse(doc.layers[0].isVisible)
    }

    @Test
    fun `setLayerLocked locks layer`() {
        val id = doc.layers[0].id
        assertFalse(doc.layers[0].isLocked)
        doc.setLayerLocked(id, true)
        assertTrue(doc.layers[0].isLocked)
    }

    // ── レイヤー複製 ────────────────────────────────────────────────

    @Test
    fun `duplicateLayer creates copy with data`() {
        val id = doc.layers[0].id
        // Write a pixel to original
        val tile = doc.layers[0].content.getOrCreateMutable(0, 0)
        tile.pixels[0] = 0xFFFF0000.toInt()

        val copy = doc.duplicateLayer(id)
        assertNotNull(copy)
        assertEquals(2, doc.layers.size)
        // Copy should have the same pixel data
        assertEquals(0xFFFF0000.toInt(), copy!!.content.getTile(0, 0)!!.pixels[0])
        // But modifying copy shouldn't affect original (COW)
    }

    @Test
    fun `duplicateLayer nonexistent returns null`() {
        assertNull(doc.duplicateLayer(9999))
    }

    // ── レイヤー結合 ────────────────────────────────────────────────

    @Test
    fun `mergeDown combines two layers`() {
        doc.addLayer("レイヤー 2")
        assertEquals(2, doc.layers.size)
        val topId = doc.layers[1].id
        assertTrue(doc.mergeDown(topId))
        assertEquals(1, doc.layers.size)
    }

    @Test
    fun `mergeDown on bottom layer returns false`() {
        doc.addLayer("レイヤー 2")
        val bottomId = doc.layers[0].id
        assertFalse(doc.mergeDown(bottomId))
    }

    // ── clearLayer ──────────────────────────────────────────────────

    @Test
    fun `clearLayer removes all pixel data`() {
        val id = doc.layers[0].id
        doc.layers[0].content.getOrCreateMutable(0, 0).fill(0xFFFF0000.toInt())
        doc.clearLayer(id)
        // All tiles should be cleared
        assertNull(doc.layers[0].content.getTile(0, 0))
    }

    // ── Undo / Redo ─────────────────────────────────────────────────

    @Test
    fun `undo with empty stack returns false`() {
        assertFalse(doc.undo())
    }

    @Test
    fun `redo with empty stack returns false`() {
        assertFalse(doc.redo())
    }

    @Test
    fun `undo restores layer after removal`() {
        val l2 = doc.addLayer("レイヤー 2")
        val l2Id = l2.id
        doc.removeLayer(l2Id)
        assertEquals(1, doc.layers.size)

        assertTrue(doc.undo())
        assertEquals(2, doc.layers.size)
        assertTrue(doc.layers.any { it.id == l2Id })
    }

    @Test
    fun `redo after undo restores removal`() {
        val l2 = doc.addLayer("レイヤー 2")
        doc.removeLayer(l2.id)
        doc.undo()
        assertEquals(2, doc.layers.size)

        assertTrue(doc.redo())
        assertEquals(1, doc.layers.size)
    }

    @Test
    fun `undo restores pixel data after clearLayer`() {
        val id = doc.layers[0].id
        val tile = doc.layers[0].content.getOrCreateMutable(0, 0)
        val color = 0xFFFF0000.toInt()
        tile.pixels[0] = color

        doc.clearLayer(id)
        // pixels are gone
        assertNull(doc.layers[0].content.getTile(0, 0))

        assertTrue(doc.undo())
        // pixels restored
        assertEquals(color, doc.layers[0].content.getTile(0, 0)!!.pixels[0])
    }

    // ── ストローク (ロックされたレイヤー) ───────────────────────────

    @Test
    fun `beginStroke on locked layer does not start stroke`() {
        val id = doc.layers[0].id
        doc.setLayerLocked(id, true)
        val brush = BrushConfig()
        doc.beginStroke(brush)
        // strokeLayerId should remain -1 (no stroke started)
        assertEquals(-1, doc.brushEngine.strokeLayerId)
    }

    // ── 合成キャッシュ ──────────────────────────────────────────────

    @Test
    fun `compositeCache has correct size`() {
        val tilesX = (256 + Tile.SIZE - 1) / Tile.SIZE
        val tilesY = (256 + Tile.SIZE - 1) / Tile.SIZE
        assertEquals(tilesX * tilesY, doc.compositeCache.size)
    }

    @Test
    fun `rebuildCompositeCache fills white background`() {
        // BUG対策: レイヤー合成が全体黒 → 背景が透明（白で開始すること）
        doc.rebuildCompositeCache()
        val first = doc.compositeCache[0]
        assertNotNull(first)
        // First pixel should be white (0xFFFFFFFF) since background is white
        // and the single layer is empty/transparent
        assertEquals(0xFFFFFFFF.toInt(), first!![0])
    }
}
