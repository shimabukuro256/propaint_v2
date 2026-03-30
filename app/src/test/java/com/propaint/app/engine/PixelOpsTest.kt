package com.propaint.app.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PixelOpsTest {

    @Before
    fun setup() {
        mockAndroidLog()
    }

    // ── pack / unpack ───────────────────────────────────────────────

    @Test
    fun `pack and unpack channels are consistent`() {
        val color = PixelOps.pack(200, 100, 50, 25)
        assertEquals(200, PixelOps.alpha(color))
        assertEquals(100, PixelOps.red(color))
        assertEquals(50, PixelOps.green(color))
        assertEquals(25, PixelOps.blue(color))
    }

    @Test
    fun `pack clamps out-of-range values`() {
        val color = PixelOps.pack(300, -10, 256, 0)
        assertEquals(255, PixelOps.alpha(color))
        assertEquals(0, PixelOps.red(color))
        assertEquals(255, PixelOps.green(color))
        assertEquals(0, PixelOps.blue(color))
    }

    // ── div255 ──────────────────────────────────────────────────────

    @Test
    fun `div255 is accurate for all byte products`() {
        // div255 should approximate v / 255 with rounding
        for (a in 0..255) {
            for (b in listOf(0, 1, 127, 128, 254, 255)) {
                val product = a * b
                val expected = (product + 127) / 255  // round-half-up reference
                val actual = PixelOps.div255(product)
                assertTrue(
                    "div255($product) = $actual, expected ~$expected",
                    kotlin.math.abs(actual - expected) <= 1
                )
            }
        }
    }

    // ── premultiply / unpremultiply ─────────────────────────────────

    @Test
    fun `premultiply identity for opaque color`() {
        val opaque = PixelOps.pack(255, 100, 200, 50)
        assertEquals(opaque, PixelOps.premultiply(opaque))
    }

    @Test
    fun `premultiply transparent returns zero`() {
        val transparent = PixelOps.pack(0, 100, 200, 50)
        assertEquals(0, PixelOps.premultiply(transparent))
    }

    @Test
    fun `premultiply then unpremultiply roundtrips with bounded error`() {
        // BUG対策: premultiplied alpha 二重適用の検出
        // 8bit 整数演算の量子化誤差は alpha が小さいほど大きくなる。
        // alpha=128 で最大誤差 ~2, alpha=64 で ~4, alpha=1 で最大。
        // 二重 premultiply は誤差が数十〜数百になるので、閾値 5 で十分検出可能。
        val testCases = listOf(
            PixelOps.pack(255, 200, 100, 50),  // opaque: 誤差 0
            PixelOps.pack(200, 10, 10, 10),    // 高alpha: 誤差 ~1
            PixelOps.pack(128, 200, 100, 50),  // 半透明: 誤差 ~2
            PixelOps.pack(64, 135, 33, 33),    // 暗い赤 低alpha: 誤差 ~4
        )
        for (original in testCases) {
            val premul = PixelOps.premultiply(original)
            val restored = PixelOps.unpremultiply(premul)
            val oa = PixelOps.alpha(original); val ra = PixelOps.alpha(restored)
            val or = PixelOps.red(original); val rr = PixelOps.red(restored)
            val og = PixelOps.green(original); val rg = PixelOps.green(restored)
            val ob = PixelOps.blue(original); val rb = PixelOps.blue(restored)
            assertEquals("alpha", oa, ra)
            assertTrue("red: $or vs $rr (diff=${kotlin.math.abs(or - rr)})", kotlin.math.abs(or - rr) <= 5)
            assertTrue("green: $og vs $rg (diff=${kotlin.math.abs(og - rg)})", kotlin.math.abs(og - rg) <= 5)
            assertTrue("blue: $ob vs $rb (diff=${kotlin.math.abs(ob - rb)})", kotlin.math.abs(ob - rb) <= 5)
        }
    }

    @Test
    fun `double premultiply detection - error exceeds threshold`() {
        // 二重 premultiply すると誤差が大幅に増えることを検証
        val color = PixelOps.pack(128, 200, 100, 50)
        val premul = PixelOps.premultiply(color)
        val doublePremul = PixelOps.premultiply(premul) // BUG: 二重適用
        val restored = PixelOps.unpremultiply(doublePremul)
        val errorR = kotlin.math.abs(PixelOps.red(color) - PixelOps.red(restored))
        // 二重 premultiply の誤差は数十以上になるはず
        assertTrue("double premul should cause large error: $errorR", errorR > 10)
    }

    @Test
    fun `premultiply ensures R,G,B lte A`() {
        // premultiplied 条件: R,G,B <= A
        for (a in listOf(1, 50, 128, 200, 255)) {
            val color = PixelOps.pack(a, 255, 255, 255)
            val premul = PixelOps.premultiply(color)
            val pa = PixelOps.alpha(premul)
            assertTrue("R <= A", PixelOps.red(premul) <= pa)
            assertTrue("G <= A", PixelOps.green(premul) <= pa)
            assertTrue("B <= A", PixelOps.blue(premul) <= pa)
        }
    }

    // ── blendSrcOver ────────────────────────────────────────────────

    @Test
    fun `blendSrcOver with transparent src returns dst`() {
        val dst = PixelOps.pack(255, 100, 150, 200)
        assertEquals(dst, PixelOps.blendSrcOver(dst, 0))
    }

    @Test
    fun `blendSrcOver with opaque src returns src`() {
        val dst = PixelOps.pack(255, 100, 150, 200)
        val src = PixelOps.pack(255, 50, 75, 100)
        assertEquals(src, PixelOps.blendSrcOver(dst, src))
    }

    @Test
    fun `blendSrcOver is premultiplied correct`() {
        // 半透明の赤を白の上に合成
        val white = PixelOps.pack(255, 255, 255, 255)
        val halfRed = PixelOps.premultiply(PixelOps.pack(128, 255, 0, 0))
        val result = PixelOps.blendSrcOver(white, halfRed)
        val a = PixelOps.alpha(result)
        val r = PixelOps.red(result)
        val g = PixelOps.green(result)
        val b = PixelOps.blue(result)
        assertEquals(255, a)
        // 赤は高く、緑・青は低くなるはず
        assertTrue("red should be high: $r", r > 200)
        assertTrue("green should be ~128: $g", g in 120..136)
        assertTrue("blue should be ~128: $b", b in 120..136)
    }

    @Test
    fun `blendSrcOver result satisfies premultiplied invariant`() {
        val dst = PixelOps.premultiply(PixelOps.pack(200, 100, 150, 200))
        val src = PixelOps.premultiply(PixelOps.pack(100, 200, 50, 100))
        val result = PixelOps.blendSrcOver(dst, src)
        val ra = PixelOps.alpha(result)
        assertTrue("R <= A", PixelOps.red(result) <= ra)
        assertTrue("G <= A", PixelOps.green(result) <= ra)
        assertTrue("B <= A", PixelOps.blue(result) <= ra)
    }

    // ── blendSrcOverOpacity ─────────────────────────────────────────

    @Test
    fun `blendSrcOverOpacity with zero opacity returns dst`() {
        val dst = PixelOps.pack(255, 100, 150, 200)
        val src = PixelOps.pack(255, 50, 75, 100)
        assertEquals(dst, PixelOps.blendSrcOverOpacity(dst, src, 0))
    }

    @Test
    fun `blendSrcOverOpacity with full opacity equals blendSrcOver`() {
        val dst = PixelOps.premultiply(PixelOps.pack(200, 100, 150, 200))
        val src = PixelOps.premultiply(PixelOps.pack(180, 200, 50, 100))
        assertEquals(
            PixelOps.blendSrcOver(dst, src),
            PixelOps.blendSrcOverOpacity(dst, src, 255)
        )
    }

    // ── blendErase ──────────────────────────────────────────────────

    @Test
    fun `blendErase with zero alpha returns dst`() {
        val dst = PixelOps.pack(255, 100, 150, 200)
        assertEquals(dst, PixelOps.blendErase(dst, 0))
    }

    @Test
    fun `blendErase with full alpha returns zero`() {
        val dst = PixelOps.pack(255, 100, 150, 200)
        assertEquals(0, PixelOps.blendErase(dst, 255))
    }

    @Test
    fun `blendErase reduces alpha proportionally`() {
        // BUG対策: 消しゴムが白く塗る → DestOut になっているか確認
        val dst = PixelOps.premultiply(PixelOps.pack(200, 100, 50, 25))
        val result = PixelOps.blendErase(dst, 128)
        // alpha は約半分になるはず
        val originalA = PixelOps.alpha(dst)
        val resultA = PixelOps.alpha(result)
        assertTrue("alpha should decrease: $originalA -> $resultA", resultA < originalA)
        // 色が白くなっていないことを確認 (premultiplied なので R,G,B も減少するはず)
        assertTrue("red should decrease", PixelOps.red(result) <= PixelOps.red(dst))
    }

    // ── Channel blend functions ─────────────────────────────────────

    @Test
    fun `blendMultiply with 255 is identity`() {
        for (v in listOf(0, 50, 128, 200, 255)) {
            assertEquals(v, PixelOps.blendMultiply(v, 255))
        }
    }

    @Test
    fun `blendMultiply with 0 is 0`() {
        for (v in listOf(0, 128, 255)) {
            assertEquals(0, PixelOps.blendMultiply(v, 0))
        }
    }

    @Test
    fun `blendScreen with 0 is identity`() {
        for (v in listOf(0, 50, 128, 200, 255)) {
            assertEquals(v, PixelOps.blendScreen(v, 0))
        }
    }

    @Test
    fun `blendScreen with 255 is 255`() {
        for (v in listOf(0, 128, 255)) {
            assertEquals(255, PixelOps.blendScreen(v, 255))
        }
    }

    @Test
    fun `blendDarken returns minimum`() {
        assertEquals(50, PixelOps.blendDarken(100, 50))
        assertEquals(100, PixelOps.blendDarken(100, 200))
    }

    @Test
    fun `blendLighten returns maximum`() {
        assertEquals(100, PixelOps.blendLighten(100, 50))
        assertEquals(200, PixelOps.blendLighten(100, 200))
    }

    @Test
    fun `blendDifference is symmetric`() {
        assertEquals(PixelOps.blendDifference(100, 200), PixelOps.blendDifference(200, 100))
    }

    @Test
    fun `blendAdd clamps at 255`() {
        assertEquals(255, PixelOps.blendAdd(200, 200))
        assertEquals(100, PixelOps.blendAdd(50, 50))
    }

    @Test
    fun `blendSubtract clamps at 0`() {
        assertEquals(0, PixelOps.blendSubtract(50, 200))
        assertEquals(150, PixelOps.blendSubtract(200, 50))
    }

    @Test
    fun `blendColorDodge edge cases`() {
        assertEquals(0, PixelOps.blendColorDodge(0, 128))    // cb=0 → 0
        assertEquals(255, PixelOps.blendColorDodge(128, 255)) // cs=255 → 255
    }

    @Test
    fun `blendColorBurn edge cases`() {
        assertEquals(255, PixelOps.blendColorBurn(255, 128))  // cb=255 → 255
        assertEquals(0, PixelOps.blendColorBurn(128, 0))      // cs=0 → 0
    }

    // ── sRGB <-> Linear ─────────────────────────────────────────────

    @Test
    fun `sRGB to linear LUT endpoints`() {
        assertEquals(0, PixelOps.SRGB_TO_LINEAR[0])
        assertEquals(65535, PixelOps.SRGB_TO_LINEAR[255])
    }

    @Test
    fun `sRGB to linear LUT is monotonically increasing`() {
        for (i in 1..255) {
            assertTrue(
                "LUT[$i]=${PixelOps.SRGB_TO_LINEAR[i]} should be >= LUT[${i - 1}]=${PixelOps.SRGB_TO_LINEAR[i - 1]}",
                PixelOps.SRGB_TO_LINEAR[i] >= PixelOps.SRGB_TO_LINEAR[i - 1]
            )
        }
    }

    @Test
    fun `pixelToLinear64 and linear64ToPixel roundtrip for opaque`() {
        val colors = listOf(
            PixelOps.pack(255, 0, 0, 0),       // black
            PixelOps.pack(255, 255, 255, 255),  // white
            PixelOps.pack(255, 255, 0, 0),      // red
            PixelOps.pack(255, 0, 255, 0),      // green
            PixelOps.pack(255, 128, 128, 128),  // mid gray
        )
        for (color in colors) {
            val linear = PixelOps.pixelToLinear64(color)
            val restored = PixelOps.linear64ToPixel(linear)
            assertEquals("alpha", PixelOps.alpha(color), PixelOps.alpha(restored))
            assertTrue("red", kotlin.math.abs(PixelOps.red(color) - PixelOps.red(restored)) <= 1)
            assertTrue("green", kotlin.math.abs(PixelOps.green(color) - PixelOps.green(restored)) <= 1)
            assertTrue("blue", kotlin.math.abs(PixelOps.blue(color) - PixelOps.blue(restored)) <= 1)
        }
    }

    @Test
    fun `pixelToLinear64 transparent returns zero`() {
        assertEquals(0L, PixelOps.pixelToLinear64(0))
    }

    // ── lerpColor ───────────────────────────────────────────────────

    @Test
    fun `lerpColor t=0 returns first color`() {
        val a = PixelOps.pack(255, 200, 100, 50)
        val b = PixelOps.pack(255, 50, 100, 200)
        assertEquals(a, PixelOps.lerpColor(a, b, 0f))
    }

    @Test
    fun `lerpColor t=1 returns second color`() {
        val a = PixelOps.pack(255, 200, 100, 50)
        val b = PixelOps.pack(255, 50, 100, 200)
        assertEquals(b, PixelOps.lerpColor(a, b, 1f))
    }

    @Test
    fun `lerpColor midpoint is reasonable`() {
        val black = PixelOps.pack(255, 0, 0, 0)
        val white = PixelOps.pack(255, 255, 255, 255)
        val mid = PixelOps.lerpColor(black, white, 0.5f)
        val r = PixelOps.red(mid)
        // リニア空間補間なので sRGB の 128 ではなく ~188 付近になるはず
        assertTrue("mid gray in linear should be >128: $r", r > 128)
        assertTrue("mid gray should be <255: $r", r < 255)
    }

    // ── compositeLayer ──────────────────────────────────────────────

    @Test
    fun `compositeLayer with zero opacity is no-op`() {
        val dst = IntArray(Tile.LENGTH) { PixelOps.pack(255, 100, 150, 200) }
        val src = IntArray(Tile.LENGTH) { PixelOps.pack(255, 50, 75, 100) }
        val dstCopy = dst.copyOf()
        PixelOps.compositeLayer(dst, src, 0, PixelOps.BLEND_NORMAL)
        assertArrayEquals(dstCopy, dst)
    }

    @Test
    fun `compositeLayer with full opacity opaque src overwrites dst`() {
        val dst = IntArray(Tile.LENGTH) { PixelOps.pack(255, 100, 150, 200) }
        val srcColor = PixelOps.pack(255, 50, 75, 100)
        val src = IntArray(Tile.LENGTH) { srcColor }
        PixelOps.compositeLayer(dst, src, 255, PixelOps.BLEND_NORMAL)
        for (i in 0 until Tile.LENGTH) {
            assertEquals(srcColor, dst[i])
        }
    }

    // ── applyDabToTile 防御チェック ─────────────────────────────────

    @Test
    fun `applyDabToTile rejects wrong tile size`() {
        val wrongSize = IntArray(100)
        val mask = IntArray(9) { 255 }
        // Should not crash, just return early
        PixelOps.applyDabToTile(
            wrongSize, mask, 3,
            PixelOps.pack(255, 255, 0, 0), 255, PixelOps.BLEND_NORMAL,
            0, 0, 3, 3, 0, 0
        )
    }

    @Test
    fun `applyDabToTile with zero opacity is no-op`() {
        val tile = IntArray(Tile.LENGTH) { PixelOps.pack(255, 128, 128, 128) }
        val tileCopy = tile.copyOf()
        val mask = IntArray(9) { 255 }
        PixelOps.applyDabToTile(
            tile, mask, 3,
            PixelOps.pack(255, 255, 0, 0), 0, PixelOps.BLEND_NORMAL,
            0, 0, 3, 3, 0, 0
        )
        assertArrayEquals(tileCopy, tile)
    }
}
