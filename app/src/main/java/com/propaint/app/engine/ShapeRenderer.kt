package com.propaint.app.engine

import kotlin.math.*

/**
 * 図形・塗りつぶし・グラデーション描画。
 * 全操作は TiledSurface に直接ピクセルを書き込む。
 */
object ShapeRenderer {

    // ── 直線 (Bresenham + アンチエイリアス) ─────────────────────

    /**
     * アンチエイリアス付き直線を描画 (Wu のアルゴリズム)。
     * @param surface 描画先
     * @param x0, y0 始点
     * @param x1, y1 終点
     * @param color premultiplied ARGB
     * @param thickness 線幅 (1以上)
     */
    fun drawLine(
        surface: TiledSurface, x0: Float, y0: Float, x1: Float, y1: Float,
        color: Int, thickness: Float = 1f,
    ) {
        require(thickness > 0f) { "thickness must be > 0" }
        if (color == 0) return

        if (thickness <= 1.5f) {
            drawLineWu(surface, x0, y0, x1, y1, color)
        } else {
            // 太い線: 法線方向にオフセットした平行四辺形として塗りつぶし
            drawThickLine(surface, x0, y0, x1, y1, color, thickness)
        }
    }

    private fun drawLineWu(surface: TiledSurface, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        val steep = abs(y1 - y0) > abs(x1 - x0)
        var ax = x0; var ay = y0; var bx = x1; var by = y1
        if (steep) { ax = y0; ay = x0; bx = y1; by = x1 }
        if (ax > bx) { var t = ax; ax = bx; bx = t; t = ay; ay = by; by = t }

        val dx = bx - ax; val dy = by - ay
        val gradient = if (dx < 0.001f) 1f else dy / dx

        // 端点1
        var xEnd = round(ax); var yEnd = ay + gradient * (xEnd - ax)
        var xGap = 1f - fpart(ax + 0.5f)
        val xPx1 = xEnd.toInt(); val yPx1 = yEnd.toInt()
        if (steep) {
            plotAA(surface, yPx1, xPx1, color, (1f - fpart(yEnd)) * xGap)
            plotAA(surface, yPx1 + 1, xPx1, color, fpart(yEnd) * xGap)
        } else {
            plotAA(surface, xPx1, yPx1, color, (1f - fpart(yEnd)) * xGap)
            plotAA(surface, xPx1, yPx1 + 1, color, fpart(yEnd) * xGap)
        }
        var intery = yEnd + gradient

        // 端点2
        xEnd = round(bx); yEnd = by + gradient * (xEnd - bx)
        xGap = fpart(bx + 0.5f)
        val xPx2 = xEnd.toInt(); val yPx2 = yEnd.toInt()
        if (steep) {
            plotAA(surface, yPx2, xPx2, color, (1f - fpart(yEnd)) * xGap)
            plotAA(surface, yPx2 + 1, xPx2, color, fpart(yEnd) * xGap)
        } else {
            plotAA(surface, xPx2, yPx2, color, (1f - fpart(yEnd)) * xGap)
            plotAA(surface, xPx2, yPx2 + 1, color, fpart(yEnd) * xGap)
        }

        // メインループ
        for (x in (xPx1 + 1) until xPx2) {
            val iy = intery.toInt()
            val f = fpart(intery)
            if (steep) {
                plotAA(surface, iy, x, color, 1f - f)
                plotAA(surface, iy + 1, x, color, f)
            } else {
                plotAA(surface, x, iy, color, 1f - f)
                plotAA(surface, x, iy + 1, color, f)
            }
            intery += gradient
        }
    }

    private fun drawThickLine(
        surface: TiledSurface, x0: Float, y0: Float, x1: Float, y1: Float,
        color: Int, thickness: Float,
    ) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.001f) return
        val nx = -dy / len * thickness / 2f
        val ny = dx / len * thickness / 2f

        // 4頂点の平行四辺形
        val points = listOf(
            Pair((x0 + nx).toInt(), (y0 + ny).toInt()),
            Pair((x1 + nx).toInt(), (y1 + ny).toInt()),
            Pair((x1 - nx).toInt(), (y1 - ny).toInt()),
            Pair((x0 - nx).toInt(), (y0 - ny).toInt()),
        )
        fillPolygon(surface, points, color)
    }

    // ── 矩形 ─────────────────────────────────────────────────

    fun drawRect(surface: TiledSurface, left: Int, top: Int, right: Int, bottom: Int, color: Int, thickness: Float = 1f) {
        val l = left.toFloat(); val t = top.toFloat()
        val r = right.toFloat(); val b = bottom.toFloat()
        drawLine(surface, l, t, r, t, color, thickness)
        drawLine(surface, r, t, r, b, color, thickness)
        drawLine(surface, r, b, l, b, color, thickness)
        drawLine(surface, l, b, l, t, color, thickness)
    }

    fun fillRect(surface: TiledSurface, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        if (color == 0) return
        val l = max(0, left); val t = max(0, top)
        val r = min(surface.width, right); val b = min(surface.height, bottom)
        for (y in t until b) {
            for (x in l until r) {
                plotBlend(surface, x, y, color)
            }
        }
    }

    // ── 楕円 ─────────────────────────────────────────────────

    fun drawEllipse(surface: TiledSurface, left: Int, top: Int, right: Int, bottom: Int, color: Int, thickness: Float = 1f) {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        if (rx <= 0f || ry <= 0f) return
        // 楕円をステップで描画
        val perimeter = (2 * PI * sqrt((rx * rx + ry * ry) / 2f)).toFloat()
        val steps = max(32, (perimeter / 2).toInt())
        var prevX = cx + rx; var prevY = cy
        for (i in 1..steps) {
            val angle = 2f * PI.toFloat() * i / steps
            val nx = cx + rx * cos(angle)
            val ny = cy + ry * sin(angle)
            drawLine(surface, prevX, prevY, nx, ny, color, thickness)
            prevX = nx; prevY = ny
        }
    }

    fun fillEllipse(surface: TiledSurface, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        if (color == 0) return
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        if (rx <= 0f || ry <= 0f) return
        val l = max(0, left); val t = max(0, top)
        val r = min(surface.width, right); val b = min(surface.height, bottom)
        for (y in t until b) {
            val dy = (y + 0.5f - cy) / ry
            for (x in l until r) {
                val dx = (x + 0.5f - cx) / rx
                val dist = dx * dx + dy * dy
                if (dist <= 1f) {
                    // アンチエイリアス
                    val alpha = if (dist > 0.95f) ((1f - dist) / 0.05f * 255f).toInt().coerceIn(0, 255) else 255
                    if (alpha >= 255) {
                        plotBlend(surface, x, y, color)
                    } else if (alpha > 0) {
                        val a = PixelOps.alpha(color) * alpha / 255
                        val scaled = PixelOps.pack(
                            a,
                            PixelOps.div255(PixelOps.red(color) * alpha),
                            PixelOps.div255(PixelOps.green(color) * alpha),
                            PixelOps.div255(PixelOps.blue(color) * alpha),
                        )
                        plotBlend(surface, x, y, scaled)
                    }
                }
            }
        }
    }

    // ── 塗りつぶし (フラッドフィル) ────────────────────────────

    /**
     * バケツ塗りつぶし。指定座標から連続する類似色の領域を塗りつぶす。
     * @param tolerance 色の許容範囲 (0..255)
     */
    fun floodFill(surface: TiledSurface, startX: Int, startY: Int, fillColor: Int, tolerance: Int = 0) {
        val w = surface.width; val h = surface.height
        if (startX < 0 || startX >= w || startY < 0 || startY >= h) return
        val targetColor = surface.getPixelAt(startX, startY)
        val tol = tolerance.coerceIn(0, 255)

        // 塗りつぶし色がターゲットと同じなら何もしない (無限ループ防止)
        if (tol == 0 && targetColor == fillColor) return

        val visited = BooleanArray(w * h)
        val queue = ArrayDeque<Int>(4096)
        val startIdx = startY * w + startX
        queue.add(startIdx)
        visited[startIdx] = true

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % w; val y = idx / w
            val pixel = surface.getPixelAt(x, y)
            if (!isColorSimilar(targetColor, pixel, tol)) continue

            // ピクセルを書き込む
            val tx = x / Tile.SIZE; val ty = y / Tile.SIZE
            if (tx in 0 until surface.tilesX && ty in 0 until surface.tilesY) {
                val tile = surface.getOrCreateMutable(tx, ty)
                tile.pixels[(y - ty * Tile.SIZE) * Tile.SIZE + (x - tx * Tile.SIZE)] = fillColor
            }

            // 4方向
            if (x > 0 && !visited[idx - 1]) { visited[idx - 1] = true; queue.add(idx - 1) }
            if (x < w - 1 && !visited[idx + 1]) { visited[idx + 1] = true; queue.add(idx + 1) }
            if (y > 0 && !visited[idx - w]) { visited[idx - w] = true; queue.add(idx - w) }
            if (y < h - 1 && !visited[idx + w]) { visited[idx + w] = true; queue.add(idx + w) }
        }
        PaintDebug.d(PaintDebug.Layer) { "[Shape] floodFill ($startX,$startY) color=${fillColor.toUInt().toString(16)}" }
    }

    // ── グラデーション ────────────────────────────────────────

    enum class GradientType { Linear, Radial }

    /**
     * グラデーションを描画。
     * @param startColor, endColor premultiplied ARGB
     */
    fun drawGradient(
        surface: TiledSurface,
        startX: Float, startY: Float, endX: Float, endY: Float,
        startColor: Int, endColor: Int,
        type: GradientType = GradientType.Linear,
    ) {
        val w = surface.width; val h = surface.height
        val dx = endX - startX; val dy = endY - startY
        val lenSq = dx * dx + dy * dy
        if (lenSq < 0.001f) return

        for (y in 0 until h) {
            for (x in 0 until w) {
                val t = when (type) {
                    GradientType.Linear -> {
                        val px = x - startX; val py = y - startY
                        ((px * dx + py * dy) / lenSq).coerceIn(0f, 1f)
                    }
                    GradientType.Radial -> {
                        val px = x - startX; val py = y - startY
                        val dist = sqrt(px * px + py * py)
                        val maxDist = sqrt(lenSq)
                        (dist / maxDist).coerceIn(0f, 1f)
                    }
                }
                val pixel = PixelOps.lerpColor(startColor, endColor, t)
                if (pixel == 0) continue
                plotBlend(surface, x, y, pixel)
            }
        }
        PaintDebug.d(PaintDebug.Layer) { "[Shape] gradient type=$type ($startX,$startY)->($endX,$endY)" }
    }

    // ── ポリゴン塗りつぶし (スキャンライン) ──────────────────

    private fun fillPolygon(surface: TiledSurface, points: List<Pair<Int, Int>>, color: Int) {
        if (points.size < 3) return
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for ((_, py) in points) { minY = min(minY, py); maxY = max(maxY, py) }
        minY = max(0, minY); maxY = min(surface.height - 1, maxY)

        for (y in minY..maxY) {
            val yf = y + 0.5f
            val intersections = mutableListOf<Float>()
            for (i in points.indices) {
                val (x0, y0) = points[i]
                val (x1, y1) = points[(i + 1) % points.size]
                if (y0 == y1) continue
                val y0f = y0.toFloat(); val y1f = y1.toFloat()
                if (yf < min(y0f, y1f) || yf >= max(y0f, y1f)) continue
                val t = (yf - y0f) / (y1f - y0f)
                intersections.add(x0 + t * (x1 - x0))
            }
            intersections.sort()
            var i = 0
            while (i + 1 < intersections.size) {
                val xl = max(0, intersections[i].toInt())
                val xr = min(surface.width - 1, intersections[i + 1].toInt())
                for (x in xl..xr) plotBlend(surface, x, y, color)
                i += 2
            }
        }
    }

    // ── ヘルパー ──────────────────────────────────────────────

    /** ピクセルを SrcOver でブレンド書き込み */
    private fun plotBlend(surface: TiledSurface, x: Int, y: Int, color: Int) {
        if (x < 0 || x >= surface.width || y < 0 || y >= surface.height) return
        val tx = x / Tile.SIZE; val ty = y / Tile.SIZE
        val tile = surface.getOrCreateMutable(tx, ty)
        val idx = (y - ty * Tile.SIZE) * Tile.SIZE + (x - tx * Tile.SIZE)
        tile.pixels[idx] = PixelOps.blendSrcOver(tile.pixels[idx], color)
    }

    /** アンチエイリアス付きプロット (0..1 の不透明度) */
    private fun plotAA(surface: TiledSurface, x: Int, y: Int, color: Int, brightness: Float) {
        if (brightness <= 0f) return
        val b = brightness.coerceIn(0f, 1f)
        val a = (PixelOps.alpha(color) * b).toInt()
        if (a <= 0) return
        val scaled = PixelOps.pack(
            a,
            PixelOps.div255(PixelOps.red(color) * (b * 255f).toInt()),
            PixelOps.div255(PixelOps.green(color) * (b * 255f).toInt()),
            PixelOps.div255(PixelOps.blue(color) * (b * 255f).toInt()),
        )
        plotBlend(surface, x, y, scaled)
    }

    private fun fpart(x: Float): Float = x - floor(x)
    private fun round(x: Float): Float = floor(x + 0.5f)

    private fun isColorSimilar(c1: Int, c2: Int, tolerance: Int): Boolean {
        val up1 = PixelOps.unpremultiply(c1)
        val up2 = PixelOps.unpremultiply(c2)
        val da = abs(PixelOps.alpha(up1) - PixelOps.alpha(up2))
        val dr = abs(PixelOps.red(up1) - PixelOps.red(up2))
        val dg = abs(PixelOps.green(up1) - PixelOps.green(up2))
        val db = abs(PixelOps.blue(up1) - PixelOps.blue(up2))
        return max(da, max(dr, max(dg, db))) <= tolerance
    }
}
