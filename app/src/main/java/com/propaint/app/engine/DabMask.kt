package com.propaint.app.engine

import kotlin.math.ceil
import kotlin.math.floor
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
 * サブピクセル精度:
 *   マスク中心に cx/cy の小数部を反映し、各ピクセルの距離を正確に計算。
 *   LUT 参照を線形補間し、整数切り捨てによる階段ノイズを除去。
 *   これにより細線でもブレゼンハム的ジャギーが発生しない。
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

    /** LUT の線形補間参照。整数添字の切り捨てによる階段状ノイズを除去。 */
    private fun lutLerp(lut: IntArray, dist: Float): Int {
        if (dist < 0f) return 255
        val idx = dist.toInt()
        if (idx >= LUT_SIZE - 1) return 0
        val frac = dist - idx
        return (lut[idx] + (lut[idx + 1] - lut[idx]) * frac).toInt().coerceIn(0, 255)
    }

    fun createDab(cx: Float, cy: Float, radius: Float, hardness: Float,
                  antiAliasing: Float = 1f): DabMask? {
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
        val safeAA = antiAliasing.coerceIn(0f, 1f)
        val hi = (hardness * 100f).toInt().coerceIn(0, 100)
        val lut = generateLut(hi)
        val r = radius / 2f

        // ── 大ブラシ: ダウンスケール生成 ──
        val rawD = ceil(radius).toInt() + 2
        val actualD = if (rawD % 2 == 0) rawD + 1 else rawD

        if (actualD > MAX_DAB_DIAMETER) {
            val scale = MAX_DAB_DIAMETER.toFloat() / actualD
            val scaledR = r * scale
            val sd = MAX_DAB_DIAMETER
            val left = floor(cx - actualD / 2f).toInt()
            val top = floor(cy - actualD / 2f).toInt()
            // サブピクセル中心 (スケール済み座標系)
            val localCx = (cx - left) * scale
            val localCy = (cy - top) * scale
            val sLs = ((LUT_RADIUS - 1f) / scaledR).let { it * it }
            val data = IntArray(sd * sd)
            // AA 帯域幅
            val aaW = if (safeAA > 0f) safeAA * maxOf(0.7f, minOf(scaledR * 0.2f, 3f)) else 0f
            val rMaaSq = if (aaW > 0f) (scaledR - aaW).coerceAtLeast(0f).let { it * it } else 0f
            val rPaaSq = if (aaW > 0f) (scaledR + aaW).let { it * it } else 0f
            for (y in 0 until sd) {
                val dy = (y + 0.5f) - localCy
                val dySq = dy * dy
                val ro = y * sd
                for (x in 0 until sd) {
                    val dx = (x + 0.5f) - localCx
                    val distSq = dx * dx + dySq
                    var v = lutLerp(lut, distSq * sLs)
                    // AA エッジフェード
                    if (aaW > 0f && distSq > rMaaSq) {
                        if (distSq >= rPaaSq) { v = 0 }
                        else {
                            val signedDist = scaledR - sqrt(distSq)
                            val t = ((signedDist + aaW) / (2f * aaW)).coerceIn(0f, 1f)
                            v = (v * t * t * (3f - 2f * t)).toInt().coerceIn(0, 255)
                        }
                    }
                    data[ro + x] = v
                }
            }
            return DabMask(left, top, sd, data, scale = scale)
        }

        // ── 通常サイズ + 極小ブラシ: サブピクセル精度マスク ──
        //
        // マスク中心を cx/cy の小数部で配置し、各ピクセル中心からの
        // 正確な距離で LUT を補間参照する。これにより:
        //  - 細線でブレゼンハム的階段パターンが出ない
        //  - 1px 未満のブラシでも適切な濃度分配が得られる
        val maskRad = ceil(r).toInt() + 1
        val d = maskRad * 2 + 1

        // floor で整数配置 → サブピクセル情報を localCx/Cy に保存
        val left = floor(cx - d * 0.5f).toInt()
        val top = floor(cy - d * 0.5f).toInt()
        val localCx = cx - left
        val localCy = cy - top

        val ls = if (r > 0.01f) ((LUT_RADIUS - 1f) / r).let { it * it } else LUT_SIZE.toFloat()

        // AA エッジ帯域幅 (ピクセル):
        //   細線ほどジャギーが目立つため最低 0.7px の AA 幅を確保。
        //   大きいブラシでは半径の 20% まで (最大 3px)。
        val aaW = if (safeAA > 0f) safeAA * maxOf(0.7f, minOf(r * 0.2f, 3f)) else 0f
        // sqrt 計算を必要な帯域のみに限定するための事前計算
        val rMinusAA = (r - aaW).coerceAtLeast(0f)
        val rMinusAASq = rMinusAA * rMinusAA
        val rPlusAA = r + aaW
        val rPlusAASq = rPlusAA * rPlusAA

        val data = IntArray(d * d)
        for (y in 0 until d) {
            val dy = (y + 0.5f) - localCy
            val dySq = dy * dy
            val ro = y * d
            for (x in 0 until d) {
                val dx = (x + 0.5f) - localCx
                val distSq = dx * dx + dySq

                // LUT 線形補間参照
                var v = lutLerp(lut, distSq * ls)

                // AA エッジフェード: 円境界の符号付き距離で smoothstep
                // 完全内側 (distSq <= rMinusAASq): v そのまま
                // 帯域内: sqrt → smoothstep で滑らかに減衰
                // 完全外側 (distSq >= rPlusAASq): 0
                if (aaW > 0f && distSq > rMinusAASq) {
                    if (distSq >= rPlusAASq) {
                        v = 0
                    } else {
                        val pixDist = sqrt(distSq)
                        val signedDist = r - pixDist  // +内側, -外側
                        val t = ((signedDist + aaW) / (2f * aaW)).coerceIn(0f, 1f)
                        val coverage = t * t * (3f - 2f * t)  // smoothstep
                        v = (v * coverage).toInt().coerceIn(0, 255)
                    }
                }

                data[ro + x] = v
            }
        }
        return DabMask(left, top, d, data)
    }
}
