package com.propaint.app.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * パフォーマンス回帰テスト。
 *
 * ホットパスの処理時間を計測し、閾値を超えたら FAIL。
 * CLAUDE.md 基準: フレーム > 16ms, ダブ > 1ms は異常。
 *
 * ※ CI 環境はローカルより遅いため閾値は余裕を持たせている。
 * ※ JVM ウォームアップのため各ベンチマークは複数回実行して中央値を使用。
 */
class PerformanceTest {

    @Before
    fun setup() {
        mockAndroidLog()
    }

    /** ウォームアップ + 中央値計測 */
    private fun benchmark(warmup: Int = 3, iterations: Int = 10, block: () -> Unit): Long {
        repeat(warmup) { block() }
        val times = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            block()
            times[i] = System.nanoTime() - start
        }
        times.sort()
        return times[iterations / 2] // median
    }

    // ── PixelOps ブレンド性能 ───────────────────────────────────────

    @Test
    fun `blendSrcOver throughput - full tile`() {
        val dst = IntArray(Tile.LENGTH) { PixelOps.pack(255, 100, 150, 200) }
        val src = IntArray(Tile.LENGTH) { PixelOps.premultiply(PixelOps.pack(128, 200, 50, 100)) }

        val medianNs = benchmark {
            for (i in 0 until Tile.LENGTH) {
                dst[i] = PixelOps.blendSrcOver(dst[i], src[i])
            }
        }
        val medianMs = medianNs / 1_000_000.0
        println("[Perf] blendSrcOver full tile: %.3f ms".format(medianMs))
        // 64x64=4096 pixels の SrcOver は 1ms 以下であるべき
        assertTrue("blendSrcOver tile too slow: %.3f ms".format(medianMs), medianMs < 5.0)
    }

    @Test
    fun `compositeLayer throughput`() {
        val dst = IntArray(Tile.LENGTH) { PixelOps.pack(255, 255, 255, 255) }
        val src = IntArray(Tile.LENGTH) { PixelOps.premultiply(PixelOps.pack(128, 200, 50, 100)) }

        val medianNs = benchmark {
            PixelOps.compositeLayer(dst, src, 200, PixelOps.BLEND_NORMAL)
        }
        val medianMs = medianNs / 1_000_000.0
        println("[Perf] compositeLayer: %.3f ms".format(medianMs))
        assertTrue("compositeLayer too slow: %.3f ms".format(medianMs), medianMs < 5.0)
    }

    // ── DabMask 生成性能 ────────────────────────────────────────────

    @Test
    fun `DabMask generation - small brush`() {
        val medianNs = benchmark {
            DabMaskGenerator.createDab(100f, 100f, 10f, 0.8f)
        }
        val medianUs = medianNs / 1_000.0
        println("[Perf] DabMask small (r=10): %.1f us".format(medianUs))
        // 小さいダブマスクは 100us 以下
        assertTrue("DabMask small too slow: %.1f us".format(medianUs), medianUs < 500.0)
    }

    @Test
    fun `DabMask generation - large brush`() {
        val medianNs = benchmark {
            DabMaskGenerator.createDab(500f, 500f, 100f, 0.5f)
        }
        val medianUs = medianNs / 1_000.0
        println("[Perf] DabMask large (r=100): %.1f us".format(medianUs))
        // 大きいダブマスクでも 1ms 以下
        assertTrue("DabMask large too slow: %.1f us".format(medianUs), medianUs < 2000.0)
    }

    // ── applyDabToTile 性能 ─────────────────────────────────────────

    @Test
    fun `applyDabToTile throughput`() {
        val tile = IntArray(Tile.LENGTH)
        val dab = DabMaskGenerator.createDab(32f, 32f, 20f, 0.8f)!!
        val color = PixelOps.premultiply(PixelOps.pack(255, 200, 50, 50))

        val medianNs = benchmark {
            PixelOps.applyDabToTile(
                tile, dab.data, dab.diameter,
                color, 200, PixelOps.BLEND_NORMAL,
                0, 0, minOf(dab.diameter, Tile.SIZE), minOf(dab.diameter, Tile.SIZE),
                dab.left, dab.top,
            )
        }
        val medianUs = medianNs / 1_000.0
        println("[Perf] applyDabToTile: %.1f us".format(medianUs))
        // CLAUDE.md 基準: ダブ > 1ms は異常
        assertTrue("applyDabToTile too slow: %.1f us".format(medianUs), medianUs < 1000.0)
    }

    // ── ストローク全体のスループット ────────────────────────────────

    @Test
    fun `full stroke throughput - 100 points`() {
        val surface = TiledSurface(512, 512)
        val tracker = DirtyTileTracker()
        val engine = BrushEngine(tracker)
        val brush = BrushConfig(
            size = 20f, spacing = 0.15f, hardness = 0.8f,
            opacity = 1f, density = 0.8f,
            colorPremul = 0xFF000000.toInt(),
            indirect = false,
            pressureSizeEnabled = true,
            taperEnabled = true,
        )

        val points = (0..100).map { i ->
            val t = i / 100f
            BrushEngine.StrokePoint(
                x = 50f + t * 400f,
                y = 256f + kotlin.math.sin(t * Math.PI.toFloat() * 4) * 100f,
                pressure = 0.3f + 0.7f * kotlin.math.sin(t * Math.PI.toFloat()).coerceIn(0f, 1f),
            )
        }

        val medianNs = benchmark(warmup = 2, iterations = 5) {
            surface.clear()
            engine.beginStroke(1)
            for (p in points) {
                engine.addPoint(p, surface, surface, brush)
            }
            engine.endStroke()
        }
        val medianMs = medianNs / 1_000_000.0
        println("[Perf] full stroke (100 pts, 512x512): %.1f ms".format(medianMs))
        // 100 点のストロークは 100ms 以下 (16ms/frame × 数フレーム分)
        assertTrue("full stroke too slow: %.1f ms".format(medianMs), medianMs < 200.0)
    }

    @Test
    fun `full stroke throughput - large brush 200 points`() {
        val surface = TiledSurface(1024, 1024)
        val tracker = DirtyTileTracker()
        val engine = BrushEngine(tracker)
        val brush = BrushConfig(
            size = 80f, spacing = 0.1f, hardness = 0.5f,
            opacity = 1f, density = 0.8f,
            colorPremul = 0xFF336699.toInt(),
            indirect = false,
            pressureSizeEnabled = true,
            taperEnabled = false,
        )

        val points = (0..200).map { i ->
            val t = i / 200f
            BrushEngine.StrokePoint(
                x = 100f + t * 800f,
                y = 512f + kotlin.math.sin(t * Math.PI.toFloat() * 6) * 200f,
                pressure = 0.5f + 0.5f * kotlin.math.cos(t * Math.PI.toFloat() * 2).coerceIn(0f, 1f),
            )
        }

        val medianNs = benchmark(warmup = 1, iterations = 3) {
            surface.clear()
            engine.beginStroke(1)
            for (p in points) {
                engine.addPoint(p, surface, surface, brush)
            }
            engine.endStroke()
        }
        val medianMs = medianNs / 1_000_000.0
        println("[Perf] large stroke (200 pts, 80px brush, 1024x1024): %.1f ms".format(medianMs))
        // 大きいブラシのストロークは 500ms 以下
        assertTrue("large stroke too slow: %.1f ms".format(medianMs), medianMs < 1000.0)
    }

    // ── TiledSurface 操作性能 ───────────────────────────────────────

    @Test
    fun `TiledSurface snapshot COW performance`() {
        val surface = TiledSurface(1024, 1024)
        // Fill some tiles
        for (tx in 0 until surface.tilesX) {
            for (ty in 0 until surface.tilesY) {
                surface.getOrCreateMutable(tx, ty).fill(0xFFFF0000.toInt())
            }
        }

        val medianNs = benchmark {
            val snap = surface.snapshot()
            // Simulate undo: decRef all snapshot tiles
            for (i in snap.tiles.indices) {
                snap.tiles[i]?.decRef()
                snap.tiles[i] = null
            }
        }
        val medianUs = medianNs / 1_000.0
        println("[Perf] snapshot+release 1024x1024 (${surface.tilesX * surface.tilesY} tiles): %.1f us".format(medianUs))
        // COW snapshot はタイルポインタのコピーだけなので高速
        assertTrue("snapshot too slow: %.1f us".format(medianUs), medianUs < 1000.0)
    }

    @Test
    fun `toPixelArray performance - 1024x1024`() {
        val surface = TiledSurface(1024, 1024)
        for (tx in 0 until surface.tilesX) {
            for (ty in 0 until surface.tilesY) {
                surface.getOrCreateMutable(tx, ty).fill(0xFFFF0000.toInt())
            }
        }

        val medianNs = benchmark(warmup = 2, iterations = 5) {
            surface.toPixelArray()
        }
        val medianMs = medianNs / 1_000_000.0
        println("[Perf] toPixelArray 1024x1024: %.1f ms".format(medianMs))
        // 1M pixels のコピーは 50ms 以下
        assertTrue("toPixelArray too slow: %.1f ms".format(medianMs), medianMs < 100.0)
    }

    // ── sRGB ↔ リニア変換性能 ──────────────────────────────────────

    @Test
    fun `pixelToLinear64 and linear64ToPixel throughput`() {
        val pixels = IntArray(Tile.LENGTH) { PixelOps.premultiply(PixelOps.pack(200, 150, 100, 50)) }
        val linears = LongArray(Tile.LENGTH)

        val medianNs = benchmark {
            for (i in pixels.indices) {
                linears[i] = PixelOps.pixelToLinear64(pixels[i])
            }
            for (i in linears.indices) {
                pixels[i] = PixelOps.linear64ToPixel(linears[i])
            }
        }
        val medianUs = medianNs / 1_000.0
        println("[Perf] sRGB↔linear roundtrip (4096 pixels): %.1f us".format(medianUs))
        assertTrue("sRGB conversion too slow: %.1f us".format(medianUs), medianUs < 2000.0)
    }

    // ── レイヤー合成性能 ────────────────────────────────────────────

    @Test
    fun `multi-layer composite - 5 layers on 512x512`() {
        val doc = CanvasDocument(512, 512)
        // Add 4 more layers (total 5)
        repeat(4) { doc.addLayer("Layer ${it + 2}") }
        // Fill all layers with different colors
        for ((idx, layer) in doc.layers.withIndex()) {
            for (i in layer.content.tiles.indices) {
                val tile = layer.content.getOrCreateMutable(
                    i % layer.content.tilesX, i / layer.content.tilesX
                )
                tile.fill(PixelOps.premultiply(PixelOps.pack(128, (50 * idx) % 256, 100, 200)))
            }
        }

        val medianNs = benchmark(warmup = 2, iterations = 5) {
            doc.rebuildCompositeCache()
        }
        val medianMs = medianNs / 1_000_000.0
        println("[Perf] composite 5 layers 512x512: %.1f ms".format(medianMs))
        // 5 レイヤーの合成は 1 フレーム (16ms) の数倍以内
        assertTrue("multi-layer composite too slow: %.1f ms".format(medianMs), medianMs < 200.0)
    }
}
