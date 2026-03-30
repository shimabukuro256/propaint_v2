package com.propaint.app.engine

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ダブマスク: 円形ブラシ先端の強度マップ。Drawpile BrushStamp 相当。
 *
 * @param scale 大ブラシ用ダウンスケール係数。1.0 = 等倍。
 *              scale < 1.0 の場合、data は縮小されたマスクであり、
 *              applyDabToTile で 1/scale 倍にアップスケールして適用する。
 */
class DabMask(
    val left: Int, val top: Int, val diameter: Int, val data: IntArray,
    val scale: Float = 1f,
)

/**
 * LUT ベースのダブマスク生成。Drawpile paint.c generate_classic_lut / get_mask 準拠。
 *
 * 大ブラシ (直径 > MAX_DAB_DIAMETER) ではダウンスケールした小さなマスクを生成し、
 * applyDabToTile 側でスケール係数を適用する。これにより OOM/GC 暴発を防止。
 */
object DabMaskGenerator {
    private const val LUT_RADIUS = 128
    private const val LUT_SIZE = LUT_RADIUS * LUT_RADIUS
    private val lutCache = arrayOfNulls<IntArray>(101)

    /**
     * ダブマスクの最大直径 (px)。MemoryConfig から取得。
     * デバイス RAM に応じて動的に変更される。
     */
    val MAX_DAB_DIAMETER: Int get() = MemoryConfig.maxDabDiameter

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

        // ── 大ブラシ: ダウンスケール生成 ──
        val rawD = ceil(radius).toInt() + 2
        val actualD = if (rawD % 2 == 0) rawD + 1 else rawD

        if (actualD > MAX_DAB_DIAMETER) {
            // 縮小倍率を計算し、小さなマスクを生成
            val scale = MAX_DAB_DIAMETER.toFloat() / actualD
            val scaledR = r * scale
            val sd = MAX_DAB_DIAMETER
            val sOff = -0.5f
            val sFudge = 1f
            val sLs = ((LUT_RADIUS - 1f) / scaledR).let { it * it }
            val data = IntArray(sd * sd)
            for (y in 0 until sd) {
                val yy = (y - scaledR + sOff).let { it * it }
                val ro = y * sd
                for (x in 0 until sd) {
                    val dist = ((x - scaledR + sOff).let { it * it } + yy) * sFudge * sLs
                    data[ro + x] = if (dist.toInt() < LUT_SIZE) lut[dist.toInt()] else 0
                }
            }
            // DabMask の left/top は実際のブラシサイズに基づいて配置
            return DabMask(
                (cx - actualD / 2f).toInt(), (cy - actualD / 2f).toInt(),
                sd, data, scale = scale,
            )
        }

        // ── 通常サイズ ──
        val d = actualD
        val off: Float = if (rawD % 2 == 0) -1f else -0.5f
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
