package com.propaint.app.engine

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

/** ダブマスク: 円形ブラシ先端の強度マップ。Drawpile BrushStamp 相当。 */
class DabMask(
    val left: Int, val top: Int, val diameter: Int, val data: IntArray,
)

/**
 * LUT ベースのダブマスク生成。Drawpile paint.c generate_classic_lut / get_mask 準拠。
 */
object DabMaskGenerator {
    private const val LUT_RADIUS = 128
    private const val LUT_SIZE = LUT_RADIUS * LUT_RADIUS
    private val lutCache = arrayOfNulls<IntArray>(101)

    private fun generateLut(idx: Int): IntArray {
        lutCache[idx]?.let { return it }
        val lut = IntArray(LUT_SIZE)
        val h = 1.0 - (idx / 100.0)
        val exp = if (h < 0.0000004) 1000000.0 else 0.4 / h
        val rad = LUT_RADIUS.toDouble()
        for (i in 0 until LUT_SIZE) {
            lut[i] = ((1.0 - (sqrt(i.toDouble()) / rad).pow(exp).pow(2.0)) * 255.0)
                .toInt().coerceIn(0, 255)
        }
        lutCache[idx] = lut; return lut
    }

    fun createDab(cx: Float, cy: Float, radius: Float, hardness: Float): DabMask? {
        if (radius < 0.5f) return null
        // 防御的チェック: NaN/Infinity 防止
        if (cx.isNaN() || cx.isInfinite() || cy.isNaN() || cy.isInfinite()) {
            PaintDebug.assertFail("DabMask NaN/Inf: cx=$cx cy=$cy")
            return null
        }
        if (radius.isNaN() || radius.isInfinite()) {
            PaintDebug.assertFail("DabMask radius NaN/Inf: $radius")
            return null
        }
        if (hardness !in 0f..1f) {
            PaintDebug.assertFail("DabMask hardness out of range: $hardness")
        }
        val hi = (hardness * 100f).toInt().coerceIn(0, 100)
        val lut = generateLut(hi)
        val r = radius / 2f
        if (r < 1f) {
            val data = IntArray(9); data[4] = 255
            return DabMask(cx.toInt() - 1, cy.toInt() - 1, 3, data)
        }
        val rawD = ceil(radius).toInt() + 2
        val d: Int; val off: Float
        if (rawD % 2 == 0) { d = rawD + 1; off = -1f } else { d = rawD; off = -0.5f }
        val fudge = when { r < 4f -> 0.8f; rawD % 2 == 0 && r < 8f -> 0.9f; else -> 1f }
        val ls = ((LUT_RADIUS - 1f) / r).let { it * it }
        val data = IntArray(d * d)
        for (y in 0 until d) {
            val yy = (y - r + off).let { it * it }
            val ro = y * d
            for (x in 0 until d) {
                val dist = ((x - r + off).let { it * it } + yy) * fudge * ls
                data[ro + x] = if (dist.toInt() < LUT_SIZE) lut[dist.toInt()] else 0
            }
        }
        return DabMask((cx - d / 2f).toInt(), (cy - d / 2f).toInt(), d, data)
    }
}
