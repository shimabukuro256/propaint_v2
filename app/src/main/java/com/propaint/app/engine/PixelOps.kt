package com.propaint.app.engine

/**
 * CPU ピクセル演算。全て premultiplied ARGB_8888。
 * Drawpile pixels.c / blend_mode.c 準拠。
 */
object PixelOps {

    inline fun alpha(c: Int): Int = (c ushr 24) and 0xFF
    inline fun red(c: Int): Int   = (c ushr 16) and 0xFF
    inline fun green(c: Int): Int = (c ushr  8) and 0xFF
    inline fun blue(c: Int): Int  =  c          and 0xFF

    inline fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
        (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun premultiply(color: Int): Int {
        val a = alpha(color); if (a == 0) return 0; if (a == 255) return color
        return pack(a, ((color ushr 16) and 0xFF) * a / 255,
            ((color ushr 8) and 0xFF) * a / 255, (color and 0xFF) * a / 255)
    }

    fun unpremultiply(color: Int): Int {
        val a = alpha(color); if (a == 0) return 0; if (a == 255) return color
        return pack(a, minOf(255, red(color) * 255 / a),
            minOf(255, green(color) * 255 / a), minOf(255, blue(color) * 255 / a))
    }

    inline fun div255(v: Int): Int { val t = v + 128; return (t + (t shr 8)) shr 8 }

    // ── SrcOver (premultiplied) ─────────────────────────────────────

    fun blendSrcOver(dst: Int, src: Int): Int {
        val sa = alpha(src); if (sa == 0) return dst; if (sa == 255) return src
        val inv = 255 - sa
        return pack(sa + div255(alpha(dst) * inv), red(src) + div255(red(dst) * inv),
            green(src) + div255(green(dst) * inv), blue(src) + div255(blue(dst) * inv))
    }

    fun blendSrcOverOpacity(dst: Int, src: Int, opacity: Int): Int {
        if (opacity <= 0) return dst; if (opacity >= 255) return blendSrcOver(dst, src)
        val sa = div255(alpha(src) * opacity); val sr = div255(red(src) * opacity)
        val sg = div255(green(src) * opacity); val sb = div255(blue(src) * opacity)
        val inv = 255 - sa
        return pack(sa + div255(alpha(dst) * inv), sr + div255(red(dst) * inv),
            sg + div255(green(dst) * inv), sb + div255(blue(dst) * inv))
    }

    // ── Erase ───────────────────────────────────────────────────────

    fun blendErase(dst: Int, srcAlpha: Int): Int {
        if (srcAlpha <= 0) return dst; if (srcAlpha >= 255) return 0
        val keep = 255 - srcAlpha
        return pack(div255(alpha(dst) * keep), div255(red(dst) * keep),
            div255(green(dst) * keep), div255(blue(dst) * keep))
    }

    // ── Channel blend functions (unpremultiplied 0..255) ────────────

    fun blendMultiply(cb: Int, cs: Int): Int = div255(cb * cs)
    fun blendScreen(cb: Int, cs: Int): Int = cb + cs - div255(cb * cs)
    fun blendOverlay(cb: Int, cs: Int): Int =
        if (cb <= 127) minOf(255, div255(2 * cs * cb))
        else maxOf(0, 255 - div255(2 * (255 - cs) * (255 - cb)))
    fun blendHardLight(cb: Int, cs: Int): Int = blendOverlay(cs, cb)
    fun blendSoftLight(cb: Int, cs: Int): Int {
        val b = cb / 255f; val s = cs / 255f
        val r = if (s <= 0.5f) b - (1f - 2f * s) * b * (1f - b)
        else {
            val d = if (b <= 0.25f) ((16f * b - 12f) * b + 4f) * b
                    else kotlin.math.sqrt(b.toDouble()).toFloat()
            b + (2f * s - 1f) * (d - b)
        }
        return (r * 255f).toInt().coerceIn(0, 255)
    }
    fun blendDarken(cb: Int, cs: Int): Int = minOf(cb, cs)
    fun blendLighten(cb: Int, cs: Int): Int = maxOf(cb, cs)
    fun blendColorDodge(cb: Int, cs: Int): Int {
        if (cb == 0) return 0; if (cs == 255) return 255
        return minOf(255, cb * 255 / (255 - cs))
    }
    fun blendColorBurn(cb: Int, cs: Int): Int {
        if (cb == 255) return 255; if (cs == 0) return 0
        return maxOf(0, 255 - (255 - cb) * 255 / cs)
    }
    fun blendDifference(cb: Int, cs: Int): Int = kotlin.math.abs(cb - cs)
    fun blendExclusion(cb: Int, cs: Int): Int = cb + cs - div255(2 * cb * cs)
    fun blendAdd(cb: Int, cs: Int): Int = minOf(255, cb + cs)
    fun blendSubtract(cb: Int, cs: Int): Int = maxOf(0, cb - cs)
    fun blendLinearBurn(cb: Int, cs: Int): Int = maxOf(0, cb + cs - 255)
    fun blendLinearLight(cb: Int, cs: Int): Int = (cb + 2 * cs - 255).coerceIn(0, 255)
    fun blendVividLight(cb: Int, cs: Int): Int =
        if (cs <= 127) blendColorBurn(cb, minOf(255, cs * 2))
        else blendColorDodge(cb, (cs - 128) * 2)
    fun blendPinLight(cb: Int, cs: Int): Int =
        if (cs <= 127) minOf(cb, cs * 2) else maxOf(cb, (cs - 128) * 2)

    // ── HSL ヘルパー (Hue/Saturation/Color/Luminosity ブレンドモード用) ──

    /** sRGB → relative luminance (BT.709 係数) */
    private fun lum(r: Int, g: Int, b: Int): Float =
        (r * 0.299f + g * 0.587f + b * 0.114f)

    /** sRGB → saturation (max - min) */
    private fun sat(r: Int, g: Int, b: Int): Int =
        maxOf(r, g, b) - minOf(r, g, b)

    /**
     * Photoshop 方式の setSaturation:
     * 3 チャンネルを min/mid/max に振り分け、
     * mid を (mid-min)*newSat/(max-min) に、min を 0 に、max を newSat にスケール。
     */
    private fun setSat(r: Int, g: Int, b: Int, s: Int): Triple<Int, Int, Int> {
        val arr = intArrayOf(r, g, b) // 0=R, 1=G, 2=B
        // min/mid/max のインデックスを求める
        val minI: Int; val midI: Int; val maxI: Int
        if (arr[0] <= arr[1]) {
            if (arr[1] <= arr[2]) { minI = 0; midI = 1; maxI = 2 }
            else if (arr[0] <= arr[2]) { minI = 0; midI = 2; maxI = 1 }
            else { minI = 2; midI = 0; maxI = 1 }
        } else {
            if (arr[0] <= arr[2]) { minI = 1; midI = 0; maxI = 2 }
            else if (arr[1] <= arr[2]) { minI = 1; midI = 2; maxI = 0 }
            else { minI = 2; midI = 1; maxI = 0 }
        }
        if (arr[maxI] > arr[minI]) {
            arr[midI] = ((arr[midI] - arr[minI]) * s) / (arr[maxI] - arr[minI])
            arr[maxI] = s
        } else {
            arr[midI] = 0; arr[maxI] = 0
        }
        arr[minI] = 0
        return Triple(arr[0], arr[1], arr[2])
    }

    /** luminance をターゲット値に合わせるためにチャンネルをクリップ */
    private fun setLum(r: Int, g: Int, b: Int, targetLum: Float): Triple<Int, Int, Int> {
        val curLum = lum(r, g, b)
        val d = targetLum - curLum
        var nr = r + d.toInt(); var ng = g + d.toInt(); var nb = b + d.toInt()
        val l = lum(nr, ng, nb)
        val mn = minOf(nr, ng, nb); val mx = maxOf(nr, ng, nb)
        if (mn < 0) {
            val li = l.toInt()
            if (li != mn) {
                nr = li + ((nr - li) * li) / (li - mn)
                ng = li + ((ng - li) * li) / (li - mn)
                nb = li + ((nb - li) * li) / (li - mn)
            }
        }
        if (mx > 255) {
            val li = l.toInt()
            if (li != mx) {
                nr = li + ((nr - li) * (255 - li)) / (mx - li)
                ng = li + ((ng - li) * (255 - li)) / (mx - li)
                nb = li + ((nb - li) * (255 - li)) / (mx - li)
            }
        }
        return Triple(nr.coerceIn(0, 255), ng.coerceIn(0, 255), nb.coerceIn(0, 255))
    }

    /** Hue ブレンド: src の色相 + dst の彩度・輝度 */
    fun blendHueRGB(dr: Int, dg: Int, db: Int, sr: Int, sg: Int, sb: Int): Triple<Int, Int, Int> {
        val (r2, g2, b2) = setSat(sr, sg, sb, sat(dr, dg, db))
        return setLum(r2, g2, b2, lum(dr, dg, db))
    }

    /** Saturation ブレンド: dst の色相・輝度 + src の彩度 */
    fun blendSaturationRGB(dr: Int, dg: Int, db: Int, sr: Int, sg: Int, sb: Int): Triple<Int, Int, Int> {
        val (r2, g2, b2) = setSat(dr, dg, db, sat(sr, sg, sb))
        return setLum(r2, g2, b2, lum(dr, dg, db))
    }

    /** Color ブレンド: src の色相・彩度 + dst の輝度 */
    fun blendColorRGB(dr: Int, dg: Int, db: Int, sr: Int, sg: Int, sb: Int): Triple<Int, Int, Int> {
        return setLum(sr, sg, sb, lum(dr, dg, db))
    }

    /** Luminosity ブレンド: dst の色相・彩度 + src の輝度 */
    fun blendLuminosityRGB(dr: Int, dg: Int, db: Int, sr: Int, sg: Int, sb: Int): Triple<Int, Int, Int> {
        return setLum(dr, dg, db, lum(sr, sg, sb))
    }

    // ── ダブ合成 (Drawpile transient_tile_brush_apply) ──────────────

    fun applyDabToTile(
        tilePixels: IntArray,
        mask: IntArray, maskW: Int,
        color: Int, opacity: Int, blendMode: Int,
        clipL: Int, clipT: Int, clipR: Int, clipB: Int,
        maskOffX: Int, maskOffY: Int,
    ) {
        // 防御的アサーション
        if (tilePixels.size != Tile.LENGTH) {
            PaintDebug.assertFail("tilePixels.size=${tilePixels.size} expected=${Tile.LENGTH}")
            return
        }
        if (opacity !in 0..255) {
            PaintDebug.assertFail("dab opacity out of range: $opacity")
            return
        }
        if (maskW <= 0) {
            PaintDebug.assertFail("maskW <= 0: $maskW")
            return
        }
        val ca = alpha(color); val cr = red(color)
        val cg = green(color); val cb = blue(color)

        // 色比率保護: da が小さい時に小さいチャンネルが 0 に量子化され
        // 色相がずれるのを防止。div255(ch * da) >= 1 となる最小 da を事前計算。
        // (例: 暗い赤(135,33,33) → minDa=4, これ未満の da で G,B が 0 になり純赤に化ける)
        // 消しゴムはアルファのみ使用するため対象外。
        val minDa = if (blendMode == BLEND_ERASE) 1 else maxOf(1,
            if (cr > 0) (127 + cr) / cr else 0,
            if (cg > 0) (127 + cg) / cg else 0,
            if (cb > 0) (127 + cb) / cb else 0,
        )

        val maskH = mask.size / maskW
        for (ty in clipT until clipB) {
            val my = ty - maskOffY; if (my < 0 || my >= maskH) continue
            val tOff = ty * Tile.SIZE; val mOff = my * maskW
            for (tx in clipL until clipR) {
                val mx = tx - maskOffX; if (mx < 0 || mx >= maskW) continue
                val mv = mask[mOff + mx]; if (mv <= 0) continue
                val da = div255(mv * opacity); if (da < minDa) continue
                val di = tOff + tx; val dst = tilePixels[di]

                when (blendMode) {
                    BLEND_ERASE -> {
                        tilePixels[di] = blendErase(dst, div255(da * ca))
                    }
                    BLEND_MARKER -> {
                        // Marker: RGB は SrcOver で描画色が乗る（鉛筆と同じ）。
                        // Alpha は max(src, dst) で上限に留まり蓄積しない。
                        // → brush.opacity で指定した不透明度が天井になる。
                        val sa = div255(ca * da); val sr = div255(cr * da)
                        val sg = div255(cg * da); val sb = div255(cb * da)
                        val inv = 255 - sa
                        tilePixels[di] = pack(
                            maxOf(sa, alpha(dst)),
                            sr + div255(red(dst) * inv),
                            sg + div255(green(dst) * inv),
                            sb + div255(blue(dst) * inv))
                    }
                    else -> { // BLEND_NORMAL and others
                        val sa = div255(ca * da); val sr = div255(cr * da)
                        val sg = div255(cg * da); val sb = div255(cb * da)
                        val inv = 255 - sa
                        tilePixels[di] = pack(
                            sa + div255(alpha(dst) * inv),
                            sr + div255(red(dst) * inv),
                            sg + div255(green(dst) * inv),
                            sb + div255(blue(dst) * inv))
                    }
                }
            }
        }
    }

    // ── レイヤー合成 (タイル全体) ───────────────────────────────────

    fun compositeLayer(dst: IntArray, src: IntArray, opacity: Int, blendMode: Int) {
        if (opacity <= 0) return
        if (dst.size != Tile.LENGTH || src.size != Tile.LENGTH) {
            PaintDebug.assertFail("compositeLayer: dst.size=${dst.size} src.size=${src.size} expected=${Tile.LENGTH}")
            return
        }
        for (i in 0 until Tile.LENGTH) {
            val s = src[i]; if (alpha(s) == 0) continue
            val d = dst[i]
            dst[i] = when (blendMode) {
                BLEND_NORMAL -> blendSrcOverOpacity(d, s, opacity)
                BLEND_ERASE -> blendErase(d, div255(alpha(s) * opacity))
                BLEND_MARKER -> {
                    val eff = blendSrcOverOpacity(d, s, opacity)
                    pack(maxOf(alpha(eff), alpha(d)), red(eff), green(eff), blue(eff))
                }
                else -> applyChannelBlend(d, s, opacity, blendMode)
            }
        }
    }

    private fun applyChannelBlend(dst: Int, src: Int, opacity: Int, mode: Int): Int {
        val da = alpha(dst); val sa = alpha(src)
        if (da == 0) return blendSrcOverOpacity(dst, src, opacity)
        val dr = if (da > 0) red(dst) * 255 / da else 0
        val dg = if (da > 0) green(dst) * 255 / da else 0
        val db = if (da > 0) blue(dst) * 255 / da else 0
        val sr = if (sa > 0) red(src) * 255 / sa else 0
        val sg = if (sa > 0) green(src) * 255 / sa else 0
        val sb = if (sa > 0) blue(src) * 255 / sa else 0
        // HSL ブレンドモード: 3 チャンネルを一括で処理
        when (mode) {
            BLEND_HUE, BLEND_SATURATION, BLEND_COLOR, BLEND_LUMINOSITY -> {
                val (br, bg, bb) = when (mode) {
                    BLEND_HUE -> blendHueRGB(dr, dg, db, sr, sg, sb)
                    BLEND_SATURATION -> blendSaturationRGB(dr, dg, db, sr, sg, sb)
                    BLEND_COLOR -> blendColorRGB(dr, dg, db, sr, sg, sb)
                    else -> blendLuminosityRGB(dr, dg, db, sr, sg, sb) // BLEND_LUMINOSITY
                }
                val effA = div255(sa * opacity); val invA = 255 - effA
                val outA = effA + div255(da * invA); if (outA == 0) return 0
                val outR = (div255(br * effA) + div255(div255(dr * da) * invA)).coerceIn(0, outA)
                val outG = (div255(bg * effA) + div255(div255(dg * da) * invA)).coerceIn(0, outA)
                val outB = (div255(bb * effA) + div255(div255(db * da) * invA)).coerceIn(0, outA)
                return pack(outA, outR, outG, outB)
            }
        }

        val blendFn: (Int, Int) -> Int = when (mode) {
            BLEND_MULTIPLY -> ::blendMultiply; BLEND_SCREEN -> ::blendScreen
            BLEND_OVERLAY -> ::blendOverlay; BLEND_DARKEN -> ::blendDarken
            BLEND_LIGHTEN -> ::blendLighten; BLEND_COLOR_DODGE -> ::blendColorDodge
            BLEND_COLOR_BURN -> ::blendColorBurn; BLEND_HARD_LIGHT -> ::blendHardLight
            BLEND_SOFT_LIGHT -> ::blendSoftLight; BLEND_DIFFERENCE -> ::blendDifference
            BLEND_EXCLUSION -> ::blendExclusion; BLEND_ADD -> ::blendAdd
            BLEND_SUBTRACT -> ::blendSubtract; BLEND_LINEAR_BURN -> ::blendLinearBurn
            BLEND_LINEAR_LIGHT -> ::blendLinearLight; BLEND_VIVID_LIGHT -> ::blendVividLight
            BLEND_PIN_LIGHT -> ::blendPinLight
            else -> return blendSrcOverOpacity(dst, src, opacity)
        }
        val br = blendFn(dr, sr); val bg = blendFn(dg, sg); val bb = blendFn(db, sb)
        val effA = div255(sa * opacity); val invA = 255 - effA
        val outA = effA + div255(da * invA); if (outA == 0) return 0
        val outR = (div255(br * effA) + div255(div255(dr * da) * invA)).coerceIn(0, outA)
        val outG = (div255(bg * effA) + div255(div255(dg * da) * invA)).coerceIn(0, outA)
        val outB = (div255(bb * effA) + div255(div255(db * da) * invA)).coerceIn(0, outA)
        return pack(outA, outR, outG, outB)
    }

    /**
     * 逆合成: (src SrcOver dst) = composite となる src を求める。
     * blurAreaOnSurface で合成済みぼかし結果からサブレイヤー値を逆算するために使用。
     * premultiplied ARGB 前提。
     */
    fun unComposite(composite: Int, dst: Int): Int {
        val ca = alpha(composite)
        val da = alpha(dst)
        // dst が完全不透明 or 完全透明 → src = composite
        if (da >= 255 || da == 0) return composite
        // src_a = (composite_a - dst_a) * 255 / (255 - dst_a)
        val sa = ((ca - da) * 255 / (255 - da)).coerceIn(0, 255)
        if (sa == 0) return 0
        val invSa = 1f - sa / 255f
        val sr = (red(composite) - red(dst) * invSa).coerceIn(0f, sa.toFloat()).toInt()
        val sg = (green(composite) - green(dst) * invSa).coerceIn(0f, sa.toFloat()).toInt()
        val sb = (blue(composite) - blue(dst) * invSa).coerceIn(0f, sa.toFloat()).toInt()
        return pack(sa, sr, sg, sb)
    }

    /** premultiplied 色の線形補間 (smudge mix 用) */
    fun lerpColor(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a; if (t >= 1f) return b
        val t1 = 1f - t
        return pack(
            (alpha(a) * t1 + alpha(b) * t).toInt(),
            (red(a) * t1 + red(b) * t).toInt(),
            (green(a) * t1 + green(b) * t).toInt(),
            (blue(a) * t1 + blue(b) * t).toInt(),
        )
    }

    // ── Blend mode IDs (Drawpile blend_mode.h 準拠) ─────────────────

    const val BLEND_NORMAL = 0
    const val BLEND_MULTIPLY = 1
    const val BLEND_SCREEN = 2
    const val BLEND_OVERLAY = 3
    const val BLEND_DARKEN = 4
    const val BLEND_LIGHTEN = 5
    const val BLEND_COLOR_DODGE = 6
    const val BLEND_COLOR_BURN = 7
    const val BLEND_HARD_LIGHT = 8
    const val BLEND_SOFT_LIGHT = 9
    const val BLEND_DIFFERENCE = 10
    const val BLEND_EXCLUSION = 11
    const val BLEND_ADD = 12
    const val BLEND_SUBTRACT = 13
    const val BLEND_LINEAR_BURN = 14
    const val BLEND_ERASE = 15
    const val BLEND_HUE = 16
    const val BLEND_SATURATION = 17
    const val BLEND_COLOR = 18
    const val BLEND_LUMINOSITY = 19
    const val BLEND_LINEAR_LIGHT = 20
    const val BLEND_VIVID_LIGHT = 21
    const val BLEND_PIN_LIGHT = 22
    const val BLEND_MARKER = 23

    val BLEND_MODE_NAMES = arrayOf(
        "通常", "乗算", "スクリーン", "オーバーレイ",
        "暗く", "明るく", "覆い焼きカラー", "焼き込みカラー",
        "ハードライト", "ソフトライト", "差の絶対値", "除外",
        "加算", "減算", "焼き込みリニア", "消しゴム",
        "色相", "彩度", "カラー", "輝度",
        "リニアライト", "ビビッドライト", "ピンライト", "マーカー",
    )
}
