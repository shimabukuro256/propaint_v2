package com.propaint.app.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ストローク描画の回帰テスト (スクリーンショット比較)。
 *
 * 固定パラメータでストロークを打ち、結果をゴールデンイメージと比較。
 * CLAUDE.md の既知バグパターンに対応:
 *  - 描画色がくすむ (premultiplied alpha 二重適用)
 *  - 半透明で重ね描きすると黒ずむ (SrcOver 数式)
 *  - 消しゴムが白く塗る (DestOut ブレンド)
 *  - ストロークが途切れる (spacing/補間)
 */
class StrokeRegressionTest {

    @Before
    fun setup() {
        mockAndroidLog()
    }

    // ── ヘルパー ────────────────────────────────────────────────────

    private fun createSurface(w: Int = 128, h: Int = 128): TiledSurface = TiledSurface(w, h)

    private fun drawStroke(
        surface: TiledSurface,
        brush: BrushConfig,
        points: List<BrushEngine.StrokePoint>,
    ) {
        val tracker = DirtyTileTracker()
        val engine = BrushEngine(tracker)
        engine.beginStroke(1)
        for (p in points) {
            engine.addPoint(p, surface, surface, brush)
        }
        engine.endStroke()
    }

    private fun straightLine(
        x0: Float, y0: Float, x1: Float, y1: Float,
        steps: Int = 20, pressure: Float = 1f,
    ): List<BrushEngine.StrokePoint> {
        return (0..steps).map { i ->
            val t = i.toFloat() / steps
            BrushEngine.StrokePoint(
                x = x0 + (x1 - x0) * t,
                y = y0 + (y1 - y0) * t,
                pressure = pressure,
            )
        }
    }

    /** ゴールデンイメージ比較 (白背景合成版) */
    private fun assertGoldenOnWhite(name: String, surface: TiledSurface) {
        val pixels = GoldenImageHelper.surfaceToPixelsOnWhite(surface)
        GoldenImageHelper.assertMatchesGolden(name, pixels, surface.width, surface.height)
    }

    /** ゴールデンイメージ比較 (透明背景版) */
    private fun assertGolden(name: String, surface: TiledSurface) {
        val pixels = GoldenImageHelper.surfaceToPixels(surface)
        GoldenImageHelper.assertMatchesGolden(name, pixels, surface.width, surface.height)
    }

    // ── テストケース ────────────────────────────────────────────────

    @Test
    fun `opaque black stroke on white - basic regression`() {
        val surface = createSurface()
        val brush = BrushConfig(
            size = 12f, spacing = 0.15f, hardness = 0.8f,
            opacity = 1f, density = 1f,
            colorPremul = 0xFF000000.toInt(),
            indirect = false, pressureSizeEnabled = false, taperEnabled = false,
        )
        drawStroke(surface, brush, straightLine(20f, 64f, 108f, 64f))

        assertGoldenOnWhite("opaque_black_stroke", surface)

        // 追加検証: ストローク中心に黒いピクセルがあること
        val centerPixel = surface.getPixelAt(64, 64)
        assertTrue("center should have paint", PixelOps.alpha(centerPixel) > 200)
    }

    @Test
    fun `red color fidelity - no premul muddiness`() {
        // BUG対策: 描画色がくすむ → premultiplied alpha 二重適用
        val surface = createSurface()
        val redPremul = PixelOps.premultiply(0xFFFF0000.toInt())
        val brush = BrushConfig(
            size = 20f, spacing = 0.1f, hardness = 1f,
            opacity = 1f, density = 1f,
            colorPremul = redPremul,
            indirect = false, pressureSizeEnabled = false, taperEnabled = false,
        )
        drawStroke(surface, brush, straightLine(20f, 64f, 108f, 64f))

        assertGoldenOnWhite("red_color_fidelity", surface)

        // ストローク中央の色を検証: unpremultiply して R が高く G,B が低いこと
        val pixel = surface.getPixelAt(64, 64)
        val straight = PixelOps.unpremultiply(pixel)
        val r = PixelOps.red(straight)
        val g = PixelOps.green(straight)
        val b = PixelOps.blue(straight)
        assertTrue("red should be dominant: R=$r G=$g B=$b", r > 200 && g < 30 && b < 30)
    }

    @Test
    fun `semi-transparent overlap - no darkening`() {
        // BUG対策: 半透明で重ね描きすると黒ずむ
        val surface = createSurface()
        val brush = BrushConfig(
            size = 30f, spacing = 0.1f, hardness = 0.5f,
            opacity = 0.3f, density = 0.8f,
            colorPremul = PixelOps.premultiply(0xFFFF0000.toInt()),
            indirect = true, pressureSizeEnabled = false, taperEnabled = false,
        )
        // 2本のストロークを重ねる
        drawStroke(surface, brush, straightLine(20f, 50f, 108f, 50f))
        drawStroke(surface, brush, straightLine(20f, 64f, 108f, 64f))
        drawStroke(surface, brush, straightLine(20f, 78f, 108f, 78f))

        assertGoldenOnWhite("semitrans_overlap", surface)

        // 重なり部分の色を検証: 黒ずんでいないこと
        val overlapPixel = surface.getPixelAt(64, 64)
        val str = PixelOps.unpremultiply(overlapPixel)
        val r = PixelOps.red(str)
        val g = PixelOps.green(str)
        val b = PixelOps.blue(str)
        // 赤の半透明なので R > G,B であること。黒ずみなら全チャンネルが低くなる。
        assertTrue("overlap should not darken: R=$r G=$g B=$b", r > g && r > b)
    }

    @Test
    fun `eraser removes paint - not white paint`() {
        // BUG対策: 消しゴムが白く塗る
        val surface = createSurface()

        // まず赤で描画
        val paintBrush = BrushConfig(
            size = 20f, spacing = 0.1f, hardness = 0.8f,
            opacity = 1f, density = 1f,
            colorPremul = 0xFFFF0000.toInt(),
            indirect = false, pressureSizeEnabled = false, taperEnabled = false,
        )
        drawStroke(surface, paintBrush, straightLine(20f, 64f, 108f, 64f))

        // 消しゴムで中央を消す
        val eraserBrush = BrushConfig(
            size = 15f, spacing = 0.1f, hardness = 1f,
            opacity = 1f, density = 1f,
            colorPremul = 0xFF000000.toInt(),
            isEraser = true,
            indirect = false, pressureSizeEnabled = false, taperEnabled = false,
        )
        drawStroke(surface, eraserBrush, straightLine(50f, 64f, 78f, 64f))

        assertGolden("eraser_removes_paint", surface)

        // 消した部分が透明 (alpha≈0) であること。白(0xFFFFFFFF)ではないこと。
        val erasedPixel = surface.getPixelAt(64, 64)
        val alpha = PixelOps.alpha(erasedPixel)
        assertTrue("erased area should be transparent (alpha=$alpha), not white", alpha < 30)
    }

    @Test
    fun `soft brush gradient - smooth falloff`() {
        val surface = createSurface()
        val brush = BrushConfig(
            size = 40f, spacing = 0.08f, hardness = 0.0f,  // 最も柔らかい
            opacity = 1f, density = 1f,
            colorPremul = 0xFF000000.toInt(),
            indirect = false, pressureSizeEnabled = false, taperEnabled = false,
        )
        drawStroke(surface, brush, straightLine(30f, 64f, 98f, 64f))

        assertGoldenOnWhite("soft_brush_gradient", surface)

        // ストローク中心は濃く、外側は薄いこと
        val center = PixelOps.alpha(surface.getPixelAt(64, 64))
        val edge = PixelOps.alpha(surface.getPixelAt(64, 44))  // 20px away
        assertTrue("center ($center) should be denser than edge ($edge)", center > edge)
    }

    @Test
    fun `diagonal stroke - no gaps`() {
        // BUG対策: ストロークが途切れる → spacing/補間
        val surface = createSurface()
        val brush = BrushConfig(
            size = 8f, spacing = 0.2f, hardness = 0.9f,
            opacity = 1f, density = 1f,
            colorPremul = 0xFF000000.toInt(),
            indirect = false, pressureSizeEnabled = false, taperEnabled = false,
        )
        drawStroke(surface, brush, straightLine(10f, 10f, 118f, 118f, steps = 30))

        assertGoldenOnWhite("diagonal_no_gaps", surface)

        // 対角線上の複数点にピクセルが存在すること (隙間なし)
        val checkPoints = listOf(32 to 32, 48 to 48, 64 to 64, 80 to 80, 96 to 96)
        for ((x, y) in checkPoints) {
            val a = PixelOps.alpha(surface.getPixelAt(x, y))
            assertTrue("diagonal at ($x,$y) should have paint (alpha=$a)", a > 100)
        }
    }

    @Test
    fun `pressure variation - size changes`() {
        val surface = createSurface(256, 128)
        val brush = BrushConfig(
            size = 30f, spacing = 0.12f, hardness = 0.7f,
            opacity = 1f, density = 1f,
            colorPremul = 0xFF333399.toInt(),
            indirect = false,
            pressureSizeEnabled = true,
            pressureSizeIntensity = 100,
            minSizeRatio = 0.1f,
            taperEnabled = false,
        )
        // 圧が低い→高い→低いストローク
        val points = (0..40).map { i ->
            val t = i.toFloat() / 40
            val pressure = if (t < 0.5f) t * 2f else (1f - t) * 2f
            BrushEngine.StrokePoint(
                x = 20f + t * 216f,
                y = 64f,
                pressure = pressure.coerceIn(0.05f, 1f),
            )
        }
        drawStroke(surface, brush, points)

        assertGoldenOnWhite("pressure_variation", surface)
    }

    @Test
    fun `marker blend mode - opacity ceiling`() {
        val surface = createSurface()
        val brush = BrushConfig(
            size = 25f, spacing = 0.1f, hardness = 0.5f,
            opacity = 0.5f, density = 1f,
            colorPremul = PixelOps.premultiply(0xFFFFFF00.toInt()),
            isMarker = true,
            indirect = false, pressureSizeEnabled = false, taperEnabled = false,
        )
        // 同じ場所を何度も通るストローク
        drawStroke(surface, brush, straightLine(20f, 64f, 108f, 64f, steps = 50))

        assertGoldenOnWhite("marker_opacity_ceiling", surface)

        // マーカーは opacity を天井として蓄積しないので、alpha が brush.opacity 付近で止まるはず
        val pixel = surface.getPixelAt(64, 64)
        val alpha = PixelOps.alpha(pixel)
        // 0.5 * 255 = 127 付近、多少の誤差を許容
        assertTrue("marker alpha ($alpha) should cap near 127", alpha in 90..160)
    }
}
