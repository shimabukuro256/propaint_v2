package com.propaint.app.engine

import kotlin.math.*

/**
 * 追加フィルタ群。既存の CanvasDocument.applyHslFilter / applyBrightnessContrast /
 * applyBlurFilter を補完する。
 *
 * 全フィルタは IntArray (premultiplied ARGB) を in-place で変更する。
 * 呼び出し元で Undo 用スナップショットを確保し、結果を writePixelsToLayer で書き戻す。
 */
object FilterOps {

    // ── トーンカーブ ──────────────────────────────────────────

    /**
     * トーンカーブフィルタ。制御点から生成した LUT でチャンネルごとに色を変換。
     * @param pixels premultiplied ARGB 配列 (in-place 変更)
     * @param redCurve 赤チャンネルの LUT (256 エントリ、0..255)。null = 変更なし
     * @param greenCurve 緑チャンネルの LUT
     * @param blueCurve 青チャンネルの LUT
     * @param masterCurve 全チャンネル共通の LUT (R/G/B 個別の前に適用)
     */
    fun applyToneCurve(
        pixels: IntArray,
        masterCurve: IntArray? = null,
        redCurve: IntArray? = null,
        greenCurve: IntArray? = null,
        blueCurve: IntArray? = null,
    ) {
        // LUT サイズ検証
        masterCurve?.let { require(it.size == 256) { "masterCurve must have 256 entries" } }
        redCurve?.let { require(it.size == 256) { "redCurve must have 256 entries" } }
        greenCurve?.let { require(it.size == 256) { "greenCurve must have 256 entries" } }
        blueCurve?.let { require(it.size == 256) { "blueCurve must have 256 entries" } }

        for (i in pixels.indices) {
            val c = pixels[i]; val a = PixelOps.alpha(c); if (a == 0) continue
            val up = PixelOps.unpremultiply(c)
            var r = PixelOps.red(up); var g = PixelOps.green(up); var b = PixelOps.blue(up)

            // マスターカーブ
            if (masterCurve != null) {
                r = masterCurve[r.coerceIn(0, 255)]
                g = masterCurve[g.coerceIn(0, 255)]
                b = masterCurve[b.coerceIn(0, 255)]
            }
            // 個別チャンネル
            if (redCurve != null) r = redCurve[r.coerceIn(0, 255)]
            if (greenCurve != null) g = greenCurve[g.coerceIn(0, 255)]
            if (blueCurve != null) b = blueCurve[b.coerceIn(0, 255)]

            pixels[i] = PixelOps.premultiply(PixelOps.pack(a, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
        }
    }

    /**
     * 制御点からモノトーン・キュービックスプライン LUT を生成する。
     * @param controlPoints (input, output) のリスト。input/output は 0..255。
     *   最低2点 (0,0) と (255,255) を含む必要がある。
     * @return 256 エントリの LUT
     */
    fun buildCurveLut(controlPoints: List<Pair<Int, Int>>): IntArray {
        require(controlPoints.size >= 2) { "need at least 2 control points" }
        val sorted = controlPoints.sortedBy { it.first }
        val n = sorted.size
        val lut = IntArray(256)

        if (n == 2) {
            // 線形補間
            val (x0, y0) = sorted[0]; val (x1, y1) = sorted[1]
            for (i in 0..255) {
                val t = if (x1 == x0) 0f else (i - x0).toFloat() / (x1 - x0)
                lut[i] = (y0 + (y1 - y0) * t.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            }
            return lut
        }

        // Catmull-Rom スプライン
        for (i in 0..255) {
            // 対象セグメントを見つける
            var seg = 0
            for (j in 0 until n - 1) {
                if (i >= sorted[j].first && (j == n - 2 || i < sorted[j + 1].first)) {
                    seg = j; break
                }
            }
            val p0 = sorted[max(0, seg - 1)]
            val p1 = sorted[seg]
            val p2 = sorted[min(n - 1, seg + 1)]
            val p3 = sorted[min(n - 1, seg + 2)]

            val range = p2.first - p1.first
            val t = if (range == 0) 0f else (i - p1.first).toFloat() / range
            val tc = t.coerceIn(0f, 1f)
            val t2 = tc * tc; val t3 = t2 * tc
            val v = 0.5f * (
                (2 * p1.second) +
                (-p0.second + p2.second) * tc +
                (2 * p0.second - 5 * p1.second + 4 * p2.second - p3.second) * t2 +
                (-p0.second + 3 * p1.second - 3 * p2.second + p3.second) * t3
            )
            lut[i] = v.roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    // ── レベル補正 ─────────────────────────────────────────────

    /**
     * レベル補正。入力範囲 [inBlack, inWhite] → 出力範囲 [outBlack, outWhite] にリマップ。
     * @param gamma 中間値のガンマ補正 (1.0 = 補正なし, >1 = 明るく, <1 = 暗く)
     */
    fun applyLevels(
        pixels: IntArray,
        inBlack: Int = 0, inWhite: Int = 255,
        gamma: Float = 1f,
        outBlack: Int = 0, outWhite: Int = 255,
    ) {
        require(gamma > 0f && !gamma.isNaN() && !gamma.isInfinite()) { "invalid gamma: $gamma" }
        val ib = inBlack.coerceIn(0, 254); val iw = inWhite.coerceIn(ib + 1, 255)
        val ob = outBlack.coerceIn(0, 255); val ow = outWhite.coerceIn(0, 255)
        val invGamma = 1f / gamma

        // LUT 生成
        val lut = IntArray(256)
        val inRange = (iw - ib).toFloat()
        val outRange = (ow - ob).toFloat()
        for (i in 0..255) {
            val normalized = ((i - ib).toFloat() / inRange).coerceIn(0f, 1f)
            val gammaCorrect = normalized.pow(invGamma)
            lut[i] = (ob + gammaCorrect * outRange).toInt().coerceIn(0, 255)
        }

        for (i in pixels.indices) {
            val c = pixels[i]; val a = PixelOps.alpha(c); if (a == 0) continue
            val up = PixelOps.unpremultiply(c)
            val r = lut[PixelOps.red(up).coerceIn(0, 255)]
            val g = lut[PixelOps.green(up).coerceIn(0, 255)]
            val b = lut[PixelOps.blue(up).coerceIn(0, 255)]
            pixels[i] = PixelOps.premultiply(PixelOps.pack(a, r, g, b))
        }
    }

    // ── カラーバランス ─────────────────────────────────────────

    /**
     * カラーバランス。Cyan-Red / Magenta-Green / Yellow-Blue の3軸で色調を調整。
     * @param cyanRed -100..100 (負=Cyan, 正=Red)
     * @param magentaGreen -100..100 (負=Magenta, 正=Green)
     * @param yellowBlue -100..100 (負=Yellow, 正=Blue)
     */
    fun applyColorBalance(pixels: IntArray, cyanRed: Int, magentaGreen: Int, yellowBlue: Int) {
        val cr = cyanRed.coerceIn(-100, 100) * 255 / 100
        val mg = magentaGreen.coerceIn(-100, 100) * 255 / 100
        val yb = yellowBlue.coerceIn(-100, 100) * 255 / 100

        for (i in pixels.indices) {
            val c = pixels[i]; val a = PixelOps.alpha(c); if (a == 0) continue
            val up = PixelOps.unpremultiply(c)
            val r = (PixelOps.red(up) + cr).coerceIn(0, 255)
            val g = (PixelOps.green(up) + mg).coerceIn(0, 255)
            val b = (PixelOps.blue(up) + yb).coerceIn(0, 255)
            pixels[i] = PixelOps.premultiply(PixelOps.pack(a, r, g, b))
        }
    }

    // ── アンシャープマスク ─────────────────────────────────────

    /**
     * アンシャープマスク (USM)。ブラーした画像との差分でシャープ化。
     * @param pixels premultiplied ARGB (in-place 変更)
     * @param w, h 画像サイズ
     * @param radius ブラー半径 (1..20)
     * @param amount 適用強度 (0.0..5.0)
     * @param threshold ノイズ除去閾値 (0..255)
     */
    fun applyUnsharpMask(pixels: IntArray, w: Int, h: Int, radius: Int = 1, amount: Float = 1f, threshold: Int = 0) {
        require(w > 0 && h > 0) { "invalid size: ${w}x${h}" }
        require(pixels.size == w * h) { "pixel count mismatch" }
        val safeRadius = radius.coerceIn(1, 20)
        val safeAmount = amount.coerceIn(0f, 5f)
        val safeThr = threshold.coerceIn(0, 255)

        // ブラーコピーを作成 (3パスボックスブラー)
        val blurred = pixels.copyOf()
        val temp = IntArray(w * h)
        boxBlurPassARGB(blurred, temp, w, h, safeRadius)
        boxBlurPassARGB(temp, blurred, w, h, safeRadius)
        boxBlurPassARGB(blurred, temp, w, h, safeRadius)

        for (i in pixels.indices) {
            val c = pixels[i]; val a = PixelOps.alpha(c); if (a == 0) continue
            val up = PixelOps.unpremultiply(c)
            val bUp = PixelOps.unpremultiply(temp[i])

            var r = PixelOps.red(up); var g = PixelOps.green(up); var b = PixelOps.blue(up)
            val br = PixelOps.red(bUp); val bg = PixelOps.green(bUp); val bb = PixelOps.blue(bUp)

            val dr = r - br; val dg = g - bg; val db = b - bb
            // 閾値チェック
            if (abs(dr) > safeThr) r = (r + dr * safeAmount).toInt()
            if (abs(dg) > safeThr) g = (g + dg * safeAmount).toInt()
            if (abs(db) > safeThr) b = (b + db * safeAmount).toInt()

            pixels[i] = PixelOps.premultiply(PixelOps.pack(a, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
        }
    }

    // ── モザイク ───────────────────────────────────────────────

    /**
     * モザイクフィルタ。指定サイズのブロック内を平均色で塗りつぶす。
     * @param blockSize ブロックサイズ (2..200)
     */
    fun applyMosaic(pixels: IntArray, w: Int, h: Int, blockSize: Int) {
        require(w > 0 && h > 0) { "invalid size" }
        require(pixels.size == w * h) { "pixel count mismatch" }
        val bs = blockSize.coerceIn(2, 200)

        for (by in 0 until h step bs) {
            for (bx in 0 until w step bs) {
                val xe = min(bx + bs, w); val ye = min(by + bs, h)
                var rSum = 0L; var gSum = 0L; var bSum = 0L; var aSum = 0L; var count = 0

                // ブロック内の平均色を計算
                for (y in by until ye) {
                    val off = y * w
                    for (x in bx until xe) {
                        val c = pixels[off + x]
                        aSum += PixelOps.alpha(c); rSum += PixelOps.red(c)
                        gSum += PixelOps.green(c); bSum += PixelOps.blue(c)
                        count++
                    }
                }
                if (count == 0) continue
                val avg = PixelOps.pack(
                    (aSum / count).toInt(), (rSum / count).toInt(),
                    (gSum / count).toInt(), (bSum / count).toInt(),
                )

                // ブロックを平均色で埋める
                for (y in by until ye) {
                    val off = y * w
                    for (x in bx until xe) {
                        pixels[off + x] = avg
                    }
                }
            }
        }
    }

    // ── ノイズ追加 ─────────────────────────────────────────────

    /**
     * ノイズ追加。各ピクセルにランダムな明暗を加える。
     * @param amount ノイズ量 (0..100)
     * @param monochrome true=グレーノイズ, false=カラーノイズ
     */
    fun applyNoise(pixels: IntArray, amount: Int, monochrome: Boolean = true, seed: Long = System.nanoTime()) {
        val safeAmount = amount.coerceIn(0, 100)
        if (safeAmount == 0) return
        val rng = java.util.Random(seed)
        val range = safeAmount * 255 / 100

        for (i in pixels.indices) {
            val c = pixels[i]; val a = PixelOps.alpha(c); if (a == 0) continue
            val up = PixelOps.unpremultiply(c)
            var r = PixelOps.red(up); var g = PixelOps.green(up); var b = PixelOps.blue(up)

            if (monochrome) {
                val noise = rng.nextInt(range * 2 + 1) - range
                r = (r + noise).coerceIn(0, 255)
                g = (g + noise).coerceIn(0, 255)
                b = (b + noise).coerceIn(0, 255)
            } else {
                r = (r + rng.nextInt(range * 2 + 1) - range).coerceIn(0, 255)
                g = (g + rng.nextInt(range * 2 + 1) - range).coerceIn(0, 255)
                b = (b + rng.nextInt(range * 2 + 1) - range).coerceIn(0, 255)
            }
            pixels[i] = PixelOps.premultiply(PixelOps.pack(a, r, g, b))
        }
    }

    // ── ポスタリゼーション ─────────────────────────────────────

    /**
     * ポスタリゼーション。階調数を減らして posterize 効果を得る。
     * @param levels 階調数 (2..256)
     */
    fun applyPosterize(pixels: IntArray, levels: Int) {
        val safeLevel = levels.coerceIn(2, 256)
        if (safeLevel >= 256) return  // 256段階 = 変化なし

        // LUT
        val lut = IntArray(256)
        val step = 256f / safeLevel
        for (i in 0..255) {
            val bucket = (i / step).toInt().coerceIn(0, safeLevel - 1)
            lut[i] = (bucket * 255f / (safeLevel - 1)).toInt().coerceIn(0, 255)
        }

        for (i in pixels.indices) {
            val c = pixels[i]; val a = PixelOps.alpha(c); if (a == 0) continue
            val up = PixelOps.unpremultiply(c)
            pixels[i] = PixelOps.premultiply(PixelOps.pack(
                a,
                lut[PixelOps.red(up).coerceIn(0, 255)],
                lut[PixelOps.green(up).coerceIn(0, 255)],
                lut[PixelOps.blue(up).coerceIn(0, 255)],
            ))
        }
    }

    // ── 二値化 ─────────────────────────────────────────────────

    /**
     * 二値化。指定閾値で白黒に変換。
     * @param threshold 閾値 (0..255)
     */
    fun applyThreshold(pixels: IntArray, threshold: Int) {
        val thr = threshold.coerceIn(0, 255)
        for (i in pixels.indices) {
            val c = pixels[i]; val a = PixelOps.alpha(c); if (a == 0) continue
            val up = PixelOps.unpremultiply(c)
            val lum = (PixelOps.red(up) * 77 + PixelOps.green(up) * 150 + PixelOps.blue(up) * 29) shr 8
            val v = if (lum >= thr) 255 else 0
            pixels[i] = PixelOps.premultiply(PixelOps.pack(a, v, v, v))
        }
    }

    // ── グラデーションマップ ───────────────────────────────────

    /**
     * グラデーションマップ。ピクセルの輝度に応じてグラデーション色に置き換える。
     * @param gradientLut 256エントリの ARGB 色テーブル (index 0=暗い, 255=明るい)
     */
    fun applyGradientMap(pixels: IntArray, gradientLut: IntArray) {
        require(gradientLut.size == 256) { "gradientLut must have 256 entries" }
        for (i in pixels.indices) {
            val c = pixels[i]; val a = PixelOps.alpha(c); if (a == 0) continue
            val up = PixelOps.unpremultiply(c)
            val lum = (PixelOps.red(up) * 77 + PixelOps.green(up) * 150 + PixelOps.blue(up) * 29) shr 8
            val mapColor = gradientLut[lum.coerceIn(0, 255)]
            // 元の alpha を維持
            val mapUp = PixelOps.unpremultiply(mapColor)
            pixels[i] = PixelOps.premultiply(PixelOps.pack(
                a, PixelOps.red(mapUp), PixelOps.green(mapUp), PixelOps.blue(mapUp)
            ))
        }
    }

    // ── ヘルパー ──────────────────────────────────────────────

    /** 水平+垂直ボックスブラー1パス (premultiplied ARGB) */
    private fun boxBlurPassARGB(input: IntArray, output: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(w * h)
        val d = radius * 2 + 1

        for (y in 0 until h) {
            var rAcc = 0L; var gAcc = 0L; var bAcc = 0L; var aAcc = 0L
            val rowOff = y * w
            for (x in -radius..radius) {
                val sx = x.coerceIn(0, w - 1)
                val c = input[rowOff + sx]
                aAcc += PixelOps.alpha(c); rAcc += PixelOps.red(c)
                gAcc += PixelOps.green(c); bAcc += PixelOps.blue(c)
            }
            for (x in 0 until w) {
                temp[rowOff + x] = PixelOps.pack(
                    (aAcc / d).toInt(), (rAcc / d).toInt(),
                    (gAcc / d).toInt(), (bAcc / d).toInt(),
                )
                val addX = (x + radius + 1).coerceAtMost(w - 1)
                val remX = (x - radius).coerceAtLeast(0)
                val ac = input[rowOff + addX]; val rc = input[rowOff + remX]
                aAcc += PixelOps.alpha(ac) - PixelOps.alpha(rc)
                rAcc += PixelOps.red(ac) - PixelOps.red(rc)
                gAcc += PixelOps.green(ac) - PixelOps.green(rc)
                bAcc += PixelOps.blue(ac) - PixelOps.blue(rc)
            }
        }

        for (x in 0 until w) {
            var rAcc = 0L; var gAcc = 0L; var bAcc = 0L; var aAcc = 0L
            for (y in -radius..radius) {
                val sy = y.coerceIn(0, h - 1)
                val c = temp[sy * w + x]
                aAcc += PixelOps.alpha(c); rAcc += PixelOps.red(c)
                gAcc += PixelOps.green(c); bAcc += PixelOps.blue(c)
            }
            for (y in 0 until h) {
                output[y * w + x] = PixelOps.pack(
                    (aAcc / d).toInt(), (rAcc / d).toInt(),
                    (gAcc / d).toInt(), (bAcc / d).toInt(),
                )
                val addY = (y + radius + 1).coerceAtMost(h - 1)
                val remY = (y - radius).coerceAtLeast(0)
                val ac = temp[addY * w + x]; val rc = temp[remY * w + x]
                aAcc += PixelOps.alpha(ac) - PixelOps.alpha(rc)
                rAcc += PixelOps.red(ac) - PixelOps.red(rc)
                gAcc += PixelOps.green(ac) - PixelOps.green(rc)
                bAcc += PixelOps.blue(ac) - PixelOps.blue(rc)
            }
        }
    }

    private fun Float.roundToInt(): Int = (this + 0.5f).toInt()
}
