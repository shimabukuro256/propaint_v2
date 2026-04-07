package com.propaint.app.engine

/**
 * レイヤーグループ（フォルダ）。
 * フラットなレイヤーリスト上で開始マーカーと終了マーカーの間に子レイヤーを挟む方式ではなく、
 * Layer に groupId フィールドを持たせて管理する軽量方式を採用。
 *
 * 理由: 既存の _layers: ArrayList<Layer> を維持し、rebuildCompositeTile のロジックを
 * 最小限の変更で済ませるため。
 */
data class LayerGroupInfo(
    val id: Int,
    var name: String,
    var isExpanded: Boolean = true,
    var isVisible: Boolean = true,
    var opacity: Float = 1f,
    var blendMode: Int = PixelOps.BLEND_NORMAL,
    var displayOrder: Int = 0, // フォルダとレイヤーの統一的な層順管理用
) {
    init {
        require(id > 0) { "group id must be > 0, got $id" }
        require(opacity in 0f..1f) { "opacity must be 0..1, got $opacity" }
    }
}

/**
 * レイヤーマスク。白(255)=表示、黒(0)=非表示のグレースケール TiledSurface。
 * レイヤーの alpha チャンネルに乗算されて合成結果を制御する。
 *
 * マスク編集中は、通常レイヤーと同じ BrushEngine でダブを適用できる。
 * マスクのタイルは premultiplied ARGB だが、alpha チャンネルのみ使用。
 * (白 = 0xFFFFFFFF, 黒 = 0xFF000000 で描画し、alpha は常に 255)
 */
object LayerMaskOps {

    /**
     * 新しいマスクサーフェスを作成する。
     * @param fillWhite true=全表示 (白で埋める), false=全非表示 (黒で埋める)
     */
    fun createMask(width: Int, height: Int, fillWhite: Boolean = true): TiledSurface {
        require(width > 0 && height > 0) { "mask size must be > 0" }
        val surface = TiledSurface(width, height)
        if (fillWhite) {
            // 白(不透明)で全タイルを埋める
            for (ty in 0 until surface.tilesY) {
                for (tx in 0 until surface.tilesX) {
                    val tile = surface.getOrCreateMutable(tx, ty)
                    tile.fill(0xFFFFFFFF.toInt())
                }
            }
        }
        // fillWhite=false の場合は null タイル (= 透明 = 非表示) のままでOK
        return surface
    }

    /**
     * マスクのルミナンス値を取得 (0..255)。
     * マスクタイルの RGB の輝度を返す。null タイル = 0 (非表示)。
     */
    fun getMaskLuminance(mask: TiledSurface, px: Int, py: Int): Int {
        val pixel = mask.getPixelAt(px, py)
        if (pixel == 0) return 0
        val up = PixelOps.unpremultiply(pixel)
        // BT.601 luminance (整数演算)
        return (PixelOps.red(up) * 77 + PixelOps.green(up) * 150 + PixelOps.blue(up) * 29) shr 8
    }

    /**
     * レイヤーマスクを合成タイルに適用する。
     * srcPixels のアルファチャンネルにマスクの輝度を乗算。
     * @param srcPixels 対象タイルピクセル (premultiplied ARGB, 直接変更される)
     * @param mask マスクサーフェス
     * @param tileX タイル X 座標
     * @param tileY タイル Y 座標
     */
    fun applyMaskToTile(srcPixels: IntArray, mask: TiledSurface, tileX: Int, tileY: Int) {
        if (srcPixels.size != Tile.LENGTH) {
            PaintDebug.assertFail("applyMaskToTile: bad size ${srcPixels.size}")
            return
        }
        val maskTile = mask.getTile(tileX, tileY)
        if (maskTile == null) {
            // マスクタイルなし = 全非表示 → srcPixels を全クリア
            srcPixels.fill(0)
            return
        }
        for (ly in 0 until Tile.SIZE) {
            for (lx in 0 until Tile.SIZE) {
                val idx = ly * Tile.SIZE + lx
                val maskPixel = maskTile.pixels[idx]
                // マスクが完全白なら何もしない
                if (maskPixel == 0xFFFFFFFF.toInt()) continue
                // マスクが透明なら src も透明に
                if (maskPixel == 0) {
                    srcPixels[idx] = 0
                    continue
                }
                // マスクの輝度を取得
                val up = PixelOps.unpremultiply(maskPixel)
                val lum = (PixelOps.red(up) * 77 + PixelOps.green(up) * 150 + PixelOps.blue(up) * 29) shr 8

                // src の各チャンネルにマスク輝度を乗算
                val src = srcPixels[idx]
                if (src == 0) continue
                srcPixels[idx] = PixelOps.pack(
                    PixelOps.div255(PixelOps.alpha(src) * lum),
                    PixelOps.div255(PixelOps.red(src) * lum),
                    PixelOps.div255(PixelOps.green(src) * lum),
                    PixelOps.div255(PixelOps.blue(src) * lum),
                )
            }
        }
    }

    /**
     * 選択範囲からレイヤーマスクを生成する。
     * SelectionManager のマスク (ByteArray 0..255) を TiledSurface に変換。
     */
    fun createMaskFromSelection(selection: SelectionManager): TiledSurface {
        val w = selection.width; val h = selection.height
        val surface = TiledSurface(w, h)
        for (ty in 0 until surface.tilesY) {
            for (tx in 0 until surface.tilesX) {
                val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
                var hasPixel = false
                for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        val v = selection.getMaskValue(px, py)
                        if (v > 0) { hasPixel = true; break }
                    }
                    if (hasPixel) break
                }
                if (!hasPixel) continue
                val tile = surface.getOrCreateMutable(tx, ty)
                for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        val v = selection.getMaskValue(px, py)
                        // グレースケール: v を RGB に、alpha は常に 255
                        tile.pixels[ly * Tile.SIZE + lx] = PixelOps.pack(255, v, v, v)
                    }
                }
            }
        }
        return surface
    }
}
