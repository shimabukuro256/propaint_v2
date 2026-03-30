package com.propaint.app.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TileAndSurfaceTest {

    @Before
    fun setup() {
        mockAndroidLog()
    }

    // ── Tile basics ─────────────────────────────────────────────────

    @Test
    fun `new tile is blank`() {
        val tile = Tile()
        assertTrue(tile.isBlank())
        assertEquals(1, tile.refCount)
    }

    @Test
    fun `tile fill makes it non-blank`() {
        val tile = Tile()
        tile.fill(0xFFFF0000.toInt())
        assertFalse(tile.isBlank())
    }

    @Test
    fun `tile clear makes it blank again`() {
        val tile = Tile()
        tile.fill(0xFFFF0000.toInt())
        tile.clear()
        assertTrue(tile.isBlank())
    }

    @Test
    fun `tile size constants`() {
        assertEquals(64, Tile.SIZE)
        assertEquals(64 * 64, Tile.LENGTH)
    }

    // ── Tile COW (Copy-on-Write) ────────────────────────────────────

    @Test
    fun `incRef and decRef`() {
        val tile = Tile()
        assertEquals(1, tile.refCount)
        tile.incRef()
        assertEquals(2, tile.refCount)
        tile.decRef()
        assertEquals(1, tile.refCount)
    }

    @Test
    fun `mutableCopy creates independent copy`() {
        val original = Tile()
        original.pixels[0] = 0xFFFF0000.toInt()
        original.incRef()  // refCount = 2 (shared)

        val copy = original.mutableCopy()
        assertEquals(1, copy.refCount)  // copy has refCount=1
        assertEquals(original.pixels[0], copy.pixels[0])  // same data

        // modifying copy doesn't affect original
        copy.pixels[0] = 0xFF00FF00.toInt()
        assertEquals(0xFFFF0000.toInt(), original.pixels[0])
        assertEquals(0xFF00FF00.toInt(), copy.pixels[0])
    }

    // ── TiledSurface basics ─────────────────────────────────────────

    @Test
    fun `TiledSurface tile grid dimensions`() {
        // 128x128 canvas → 2x2 tiles (64px each)
        val surface = TiledSurface(128, 128)
        assertEquals(2, surface.tilesX)
        assertEquals(2, surface.tilesY)
    }

    @Test
    fun `TiledSurface non-aligned dimensions round up`() {
        // 100x100 → ceil(100/64) = 2
        val surface = TiledSurface(100, 100)
        assertEquals(2, surface.tilesX)
        assertEquals(2, surface.tilesY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TiledSurface rejects zero width`() {
        TiledSurface(0, 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TiledSurface rejects zero height`() {
        TiledSurface(100, 0)
    }

    // ── pixelToTile ─────────────────────────────────────────────────

    @Test
    fun `pixelToTile positive coordinates`() {
        val surface = TiledSurface(256, 256)
        assertEquals(0, surface.pixelToTile(0))
        assertEquals(0, surface.pixelToTile(63))
        assertEquals(1, surface.pixelToTile(64))
        assertEquals(1, surface.pixelToTile(127))
        assertEquals(2, surface.pixelToTile(128))
    }

    @Test
    fun `pixelToTile negative coordinates use floor division`() {
        // BUG対策: タイル負座標でクラッシュ → floorDiv を使うこと
        val surface = TiledSurface(256, 256)
        assertEquals(-1, surface.pixelToTile(-1))
        assertEquals(-1, surface.pixelToTile(-64))
        assertEquals(-2, surface.pixelToTile(-65))
    }

    // ── getTile / getOrCreateMutable ────────────────────────────────

    @Test
    fun `getTile returns null for empty surface`() {
        val surface = TiledSurface(128, 128)
        assertNull(surface.getTile(0, 0))
        assertNull(surface.getTile(1, 1))
    }

    @Test
    fun `getTile returns null for out of bounds`() {
        val surface = TiledSurface(128, 128)
        assertNull(surface.getTile(-1, 0))
        assertNull(surface.getTile(0, -1))
        assertNull(surface.getTile(2, 0))  // tilesX=2, max index=1
        assertNull(surface.getTile(0, 2))
    }

    @Test
    fun `getOrCreateMutable creates tile on demand`() {
        val surface = TiledSurface(128, 128)
        assertNull(surface.getTile(0, 0))
        val tile = surface.getOrCreateMutable(0, 0)
        assertNotNull(tile)
        assertSame(tile, surface.getTile(0, 0))
    }

    @Test
    fun `getOrCreateMutable performs COW when shared`() {
        val surface = TiledSurface(128, 128)
        val tile = surface.getOrCreateMutable(0, 0)
        tile.pixels[0] = 0xFFFF0000.toInt()
        tile.incRef()  // simulate sharing (refCount=2)

        val mutableTile = surface.getOrCreateMutable(0, 0)
        assertNotSame(tile, mutableTile)  // should be a new copy
        assertEquals(0xFFFF0000.toInt(), mutableTile.pixels[0])  // data preserved
        assertEquals(1, mutableTile.refCount)
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun `getOrCreateMutable throws for out of bounds`() {
        val surface = TiledSurface(128, 128)
        surface.getOrCreateMutable(-1, 0)
    }

    // ── getPixelAt ──────────────────────────────────────────────────

    @Test
    fun `getPixelAt returns 0 for empty tile`() {
        val surface = TiledSurface(128, 128)
        assertEquals(0, surface.getPixelAt(32, 32))
    }

    @Test
    fun `getPixelAt returns 0 for out of bounds`() {
        val surface = TiledSurface(128, 128)
        assertEquals(0, surface.getPixelAt(-1, 0))
        assertEquals(0, surface.getPixelAt(128, 0))
    }

    @Test
    fun `getPixelAt reads written pixel`() {
        val surface = TiledSurface(128, 128)
        val tile = surface.getOrCreateMutable(0, 0)
        val color = 0xFFFF0000.toInt()
        tile.pixels[10 * Tile.SIZE + 5] = color  // pixel at (5, 10) within tile
        assertEquals(color, surface.getPixelAt(5, 10))
    }

    // ── snapshot ────────────────────────────────────────────────────

    @Test
    fun `snapshot creates COW copy`() {
        val surface = TiledSurface(128, 128)
        val tile = surface.getOrCreateMutable(0, 0)
        tile.pixels[0] = 0xFFFF0000.toInt()

        val snap = surface.snapshot()
        // snapshot shares the same tile (COW)
        assertEquals(0xFFFF0000.toInt(), snap.getTile(0, 0)!!.pixels[0])

        // modifying original via getOrCreateMutable should COW-copy
        val newTile = surface.getOrCreateMutable(0, 0)
        newTile.pixels[0] = 0xFF00FF00.toInt()

        // snapshot should still have old value
        assertEquals(0xFFFF0000.toInt(), snap.getTile(0, 0)!!.pixels[0])
    }

    // ── clear ───────────────────────────────────────────────────────

    @Test
    fun `surface clear removes all tiles`() {
        val surface = TiledSurface(128, 128)
        surface.getOrCreateMutable(0, 0)
        surface.getOrCreateMutable(1, 1)
        assertNotNull(surface.getTile(0, 0))

        surface.clear()
        assertNull(surface.getTile(0, 0))
        assertNull(surface.getTile(1, 1))
    }

    // ── toPixelArray ────────────────────────────────────────────────

    @Test
    fun `toPixelArray empty surface is all zeros`() {
        val surface = TiledSurface(64, 64)
        val pixels = surface.toPixelArray()
        assertEquals(64 * 64, pixels.size)
        assertTrue(pixels.all { it == 0 })
    }

    @Test
    fun `toPixelArray reads tile data correctly`() {
        val surface = TiledSurface(64, 64)
        val tile = surface.getOrCreateMutable(0, 0)
        val color = 0xFFFF0000.toInt()
        tile.pixels[0] = color  // (0,0)

        val pixels = surface.toPixelArray()
        assertEquals(color, pixels[0])
    }
}
