package com.propaint.app.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.Typeface

/**
 * テキストをレイヤーにラスタライズする。
 * Android の Canvas/Paint API でレンダリングし、TiledSurface に書き込む。
 */
object TextRenderer {

    /**
     * テキストの設定パラメータ。
     * テキストレイヤーの再編集用にメタデータとして保持される。
     */
    data class TextConfig(
        val text: String,
        val fontSize: Float = 48f,
        val color: Int = 0xFF000000.toInt(),  // premultiplied ARGB
        val fontFamily: String = "",          // 空 = デフォルト
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isVertical: Boolean = false,      // 縦書き
        val x: Float = 0f,                    // 配置位置 X
        val y: Float = 0f,                    // 配置位置 Y
        val letterSpacing: Float = 0f,        // 字間 (em)
        val lineSpacing: Float = 1.2f,        // 行間倍率
    ) {
        init {
            require(fontSize > 0f) { "fontSize must be > 0, got $fontSize" }
            require(!fontSize.isNaN() && !fontSize.isInfinite()) { "fontSize is NaN/Inf" }
            require(lineSpacing > 0f) { "lineSpacing must be > 0" }
        }
    }

    /**
     * テキストを TiledSurface に描画する。
     * @param surface 描画先（直接書き込み）
     * @param config テキスト設定
     */
    fun renderText(surface: TiledSurface, config: TextConfig) {
        if (config.text.isEmpty()) return

        val w = surface.width; val h = surface.height
        // Android Paint でレンダリング
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = config.fontSize
            color = androidColorFromPremul(config.color)
            letterSpacing = config.letterSpacing
            typeface = resolveTypeface(config.fontFamily, config.isBold, config.isItalic)
        }

        // テキスト描画サイズを計測
        val lines = config.text.split("\n")
        val lineHeight = config.fontSize * config.lineSpacing

        // Bitmap に描画
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (config.isVertical) {
            // 縦書き: 1文字ずつ縦に並べる
            var colX = config.x
            for (line in lines) {
                var charY = config.y + config.fontSize
                for (ch in line) {
                    val charStr = ch.toString()
                    val charWidth = paint.measureText(charStr)
                    // 文字を中央揃えで配置
                    canvas.drawText(charStr, colX - charWidth / 2f, charY, paint)
                    charY += config.fontSize * config.lineSpacing
                }
                colX -= config.fontSize * 1.5f // 右から左へ
            }
        } else {
            // 横書き
            var drawY = config.y + config.fontSize  // baseline
            for (line in lines) {
                canvas.drawText(line, config.x, drawY, paint)
                drawY += lineHeight
            }
        }

        // Bitmap → TiledSurface に書き込み
        val bitmapPixels = IntArray(w * h)
        bitmap.getPixels(bitmapPixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        // Android の Bitmap は straight alpha なので premultiply
        for (ty in 0 until surface.tilesY) {
            for (tx in 0 until surface.tilesX) {
                val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
                var hasPixel = false
                for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        val pixel = bitmapPixels[py * w + px]
                        if (pixel != 0) { hasPixel = true; break }
                    }
                    if (hasPixel) break
                }
                if (!hasPixel) continue
                val tile = surface.getOrCreateMutable(tx, ty)
                for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        val pixel = bitmapPixels[py * w + px]
                        if (pixel == 0) continue
                        val premul = PixelOps.premultiply(pixel)
                        val idx = ly * Tile.SIZE + lx
                        tile.pixels[idx] = PixelOps.blendSrcOver(tile.pixels[idx], premul)
                    }
                }
            }
        }
        PaintDebug.d(PaintDebug.Layer) {
            "[TextRenderer] rendered '${config.text.take(20)}' size=${config.fontSize} at (${config.x},${config.y})"
        }
    }

    /**
     * テキストの描画サイズを計測する。
     * @return Pair(width, height)
     */
    fun measureText(config: TextConfig): Pair<Float, Float> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = config.fontSize
            letterSpacing = config.letterSpacing
            typeface = resolveTypeface(config.fontFamily, config.isBold, config.isItalic)
        }
        val lines = config.text.split("\n")
        val lineHeight = config.fontSize * config.lineSpacing

        if (config.isVertical) {
            val maxChars = lines.maxOfOrNull { it.length } ?: 0
            val totalWidth = lines.size * config.fontSize * 1.5f
            val totalHeight = maxChars * config.fontSize * config.lineSpacing
            return Pair(totalWidth, totalHeight)
        }

        var maxWidth = 0f
        for (line in lines) {
            val w = paint.measureText(line)
            if (w > maxWidth) maxWidth = w
        }
        val totalHeight = lines.size * lineHeight
        return Pair(maxWidth, totalHeight)
    }

    private fun resolveTypeface(family: String, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val base = if (family.isNotEmpty()) {
            Typeface.create(family, style)
        } else {
            Typeface.create(Typeface.DEFAULT, style)
        }
        return base
    }

    /** premultiplied ARGB → Android Color (straight alpha) */
    private fun androidColorFromPremul(premul: Int): Int {
        val up = PixelOps.unpremultiply(premul)
        return android.graphics.Color.argb(
            PixelOps.alpha(up), PixelOps.red(up), PixelOps.green(up), PixelOps.blue(up)
        )
    }
}
