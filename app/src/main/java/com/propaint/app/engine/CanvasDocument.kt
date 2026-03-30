package com.propaint.app.engine

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Layer(
    val id: Int, var name: String,
    val content: TiledSurface,
    var opacity: Float = 1f,
    var blendMode: Int = PixelOps.BLEND_NORMAL,
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    var isClipToBelow: Boolean = false,
    var isAlphaLocked: Boolean = false,
) {
    /** Indirect 描画用サブレイヤー */
    var sublayer: TiledSurface? = null
}

/**
 * 全キャンバス状態の単一ソース。
 *
 * v1→v2 で修正した致命的バグ:
 *  ・ストローク中のレイヤー切替で描画不能
 *    → strokeLayerId でストローク開始レイヤーを記録し、endStroke は必ずそこに合成
 *  ・サブレイヤーが放置される
 *    → レイヤー切替時に強制 endStroke / サブレイヤー破棄
 */
class CanvasDocument(val width: Int, val height: Int) {
    init {
        require(width > 0) { "canvas width must be > 0, got $width" }
        require(height > 0) { "canvas height must be > 0, got $height" }
        require(width.toLong() * height.toLong() <= 16384L * 16384L) {
            "canvas too large: ${width}x${height}"
        }
    }

    val dirtyTracker = DirtyTileTracker()
    val brushEngine = BrushEngine(dirtyTracker)

    private val lock = ReentrantLock()
    private val _layers = ArrayList<Layer>()
    val layers: List<Layer> get() = _layers

    var activeLayerId: Int = -1; private set
    private var nextLayerId = 1
    /** レイヤー名の一意インデックス（削除後の重複防止） */
    val nextLayerNameIndex: Int get() = nextLayerId

    // Undo
    private val undoStack = ArrayList<UndoEntry>(50)
    private val redoStack = ArrayList<UndoEntry>(50)

    // フィルタープレビュー
    private var filterPreviewLayerId: Int = -1
    private var filterPreviewSnapshot: Map<Int, Tile?>? = null

    // ストローク中フラグ
    private var strokeInProgress = false
    private var strokeBrush: BrushConfig? = null
    /** サブレイヤープレビュー用: ストローク中の brush.opacity (GL スレッドから参照) */
    @Volatile var strokeOpacity: Float = 1f; private set

    // 合成キャッシュ
    private val tilesX get() = (width + Tile.SIZE - 1) / Tile.SIZE
    private val tilesY get() = (height + Tile.SIZE - 1) / Tile.SIZE
    val compositeCache: Array<IntArray?> = arrayOfNulls(tilesX * tilesY)

    init { addLayer("レイヤー 1") }

    // ── レイヤー操作 ────────────────────────────────────────────────

    fun addLayer(name: String, atIndex: Int = _layers.size): Layer = lock.withLock {
        forceEndStrokeIfNeeded()
        val l = Layer(nextLayerId++, name, TiledSurface(width, height))
        _layers.add(atIndex, l)
        if (activeLayerId < 0) activeLayerId = l.id
        PaintDebug.d(PaintDebug.Layer) { "[addLayer] id=${l.id} name=$name atIndex=$atIndex total=${_layers.size}" }
        dirtyTracker.markFullRebuild(); l
    }

    fun removeLayer(layerId: Int): Boolean = lock.withLock {
        if (_layers.size <= 1) return false
        forceEndStrokeIfNeeded()
        val idx = _layers.indexOfFirst { it.id == layerId }; if (idx < 0) return false
        pushUndoStructural()
        _layers.removeAt(idx)
        if (activeLayerId == layerId) activeLayerId = _layers[maxOf(0, idx - 1)].id
        PaintDebug.d(PaintDebug.Layer) { "[removeLayer] layerId=$layerId newActive=$activeLayerId remaining=${_layers.size}" }
        dirtyTracker.markFullRebuild(); true
    }

    fun setActiveLayer(layerId: Int) = lock.withLock {
        if (!_layers.any { it.id == layerId }) return
        // ストローク中にレイヤー切替 → 現在のストロークを強制確定
        forceEndStrokeIfNeeded()
        activeLayerId = layerId
        // レイヤー切替をレンダラに通知（サブレイヤー合成結果の反映のため）
        dirtyTracker.markFullRebuild()
    }

    fun getActiveLayer(): Layer? = _layers.find { it.id == activeLayerId }

    /** strokeLayerId で指定されたレイヤーを取得 (endStroke はこちらを使う) */
    private fun getStrokeLayer(): Layer? =
        _layers.find { it.id == brushEngine.strokeLayerId }

    fun moveLayer(from: Int, to: Int) = lock.withLock {
        if (from == to || from !in _layers.indices || to !in _layers.indices) return
        forceEndStrokeIfNeeded()
        val l = _layers.removeAt(from); _layers.add(to, l)
        dirtyTracker.markFullRebuild()
    }

    fun setLayerOpacity(id: Int, v: Float) = lock.withLock {
        _layers.find { it.id == id }?.let { it.opacity = v.coerceIn(0f, 1f); dirtyTracker.markFullRebuild() }
    }

    fun setLayerBlendMode(id: Int, mode: Int) = lock.withLock {
        _layers.find { it.id == id }?.let { it.blendMode = mode; dirtyTracker.markFullRebuild() }
    }

    fun setLayerVisibility(id: Int, v: Boolean) = lock.withLock {
        _layers.find { it.id == id }?.let { it.isVisible = v; dirtyTracker.markFullRebuild() }
    }

    fun setLayerClipToBelow(id: Int, clip: Boolean) = lock.withLock {
        _layers.find { it.id == id }?.let { it.isClipToBelow = clip; dirtyTracker.markFullRebuild() }
    }

    fun setLayerLocked(id: Int, locked: Boolean) = lock.withLock {
        _layers.find { it.id == id }?.let { it.isLocked = locked }
    }

    fun duplicateLayer(id: Int): Layer? = lock.withLock {
        forceEndStrokeIfNeeded()
        val src = _layers.find { it.id == id } ?: return null
        val idx = _layers.indexOf(src)
        val copy = Layer(nextLayerId++, "${src.name} コピー", src.content.snapshot(),
            src.opacity, src.blendMode, src.isVisible)
        _layers.add(idx + 1, copy)
        dirtyTracker.markFullRebuild(); copy
    }

    fun mergeDown(id: Int): Boolean = lock.withLock {
        forceEndStrokeIfNeeded()
        val idx = _layers.indexOfFirst { it.id == id }; if (idx <= 0) return false
        pushUndoStructural()
        val upper = _layers[idx]; val lower = _layers[idx - 1]
        compositeTwoLayers(lower.content, upper.content, upper.opacity, upper.blendMode)
        _layers.removeAt(idx)
        if (activeLayerId == upper.id) activeLayerId = lower.id
        dirtyTracker.markFullRebuild(); true
    }

    /**
     * 複数レイヤーを一括結合する。undo は1回分のみ記録。
     * @param ids 結合対象レイヤーIDのリスト（ドキュメント内の順序は問わない）
     * @return 結合先レイヤーの ID、失敗時は -1
     */
    fun batchMergeLayers(ids: List<Int>): Int = lock.withLock {
        if (ids.size < 2) return -1
        forceEndStrokeIfNeeded()
        // ドキュメント順にソート（下から上）
        val sortedIndices = ids.mapNotNull { id -> _layers.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            .distinct().sorted()
        if (sortedIndices.size < 2) return -1
        pushUndoStructural()
        // 最も下のレイヤーに順次結合
        val baseIdx = sortedIndices[0]
        val base = _layers[baseIdx]
        // 上のレイヤーから順に結合（インデックスがずれないよう逆順で除去）
        val toRemove = mutableListOf<Int>()
        for (i in 1 until sortedIndices.size) {
            val srcLayer = _layers.getOrNull(sortedIndices[i]) ?: continue
            compositeTwoLayers(base.content, srcLayer.content, srcLayer.opacity, srcLayer.blendMode)
            toRemove.add(sortedIndices[i])
        }
        // 上から除去（インデックスずれ防止のため降順）
        for (removeIdx in toRemove.sortedDescending()) {
            val removed = _layers.removeAt(removeIdx)
            if (activeLayerId == removed.id) activeLayerId = base.id
        }
        dirtyTracker.markFullRebuild()
        base.id
    }

    fun clearLayer(id: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == id } ?: return
        pushUndoTileDelta(layer)
        layer.content.clear()
        dirtyTracker.markFullRebuild()
    }

    /** レイヤーコンテンツを (dx, dy) ピクセル移動する */
    fun translateLayerContent(id: Int, dx: Int, dy: Int) = lock.withLock {
        if (dx == 0 && dy == 0) return
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == id } ?: return
        PaintDebug.d(PaintDebug.Layer) { "[translateLayerContent] layerId=$id dx=$dx dy=$dy" }
        pushUndoTileDelta(layer)

        // 元ピクセルを取得 → クリア → オフセットして書き戻し
        val src = layer.content.toPixelArray()
        layer.content.clear()
        val w = width; val h = height
        for (sy in 0 until h) {
            val dstY = sy + dy
            if (dstY < 0 || dstY >= h) continue
            for (sx in 0 until w) {
                val dstX = sx + dx
                if (dstX < 0 || dstX >= w) continue
                val pixel = src[sy * w + sx]
                if (pixel == 0) continue
                val tx = dstX / Tile.SIZE; val ty = dstY / Tile.SIZE
                val tile = layer.content.getOrCreateMutable(tx, ty)
                val lx = dstX - tx * Tile.SIZE; val ly = dstY - ty * Tile.SIZE
                tile.pixels[ly * Tile.SIZE + lx] = pixel
            }
        }
        dirtyTracker.markFullRebuild()
    }

    // ── 描画操作 ────────────────────────────────────────────────────

    fun beginStroke(brush: BrushConfig) = lock.withLock {
        val layer = getActiveLayer() ?: return
        if (layer.isLocked) {
            PaintDebug.d(PaintDebug.Brush) { "[beginStroke] layer ${layer.id} is locked, ignoring" }
            return
        }

        // 前のストロークが残っていたら強制確定
        forceEndStrokeIfNeeded()

        pushUndoTileDelta(layer)

        // BrushEngine にストローク開始レイヤーを記録
        brushEngine.beginStroke(layer.id)
        strokeBrush = brush
        strokeInProgress = true
        strokeOpacity = brush.opacity

        // Indirect モード: サブレイヤーを作成
        val useIndirect = brush.indirect && !brush.isEraser && !brush.isBlur
        if (useIndirect) {
            layer.sublayer = TiledSurface(width, height)
        }
        PaintDebug.d(PaintDebug.Brush) { "[beginStroke] layerId=${layer.id} indirect=$useIndirect size=${brush.size}" }
    }

    fun strokeTo(point: BrushEngine.StrokePoint, brush: BrushConfig) = lock.withLock {
        if (!strokeInProgress) return
        val layer = getStrokeLayer() ?: return

        // 描画先: Indirect → sublayer, Direct → content
        val drawTarget = layer.sublayer ?: layer.content
        // サンプリング元: 常に layer.content (sublayer ではない!)
        // ※ これが Drawpile の get_sample_layer_content パターン
        val sampleSource = layer.content

        brushEngine.addPoint(point, drawTarget, sampleSource, brush)
        // ※ markFullRebuild() は不要: applyDabToSurface() 内で個別タイルの
        //   markDirty(tx, ty) が呼ばれるため、変更タイルのみ再合成される
    }

    fun endStroke(brush: BrushConfig) = lock.withLock {
        if (!strokeInProgress) return
        val layer = getStrokeLayer()
        brushEngine.endStroke()
        strokeInProgress = false
        strokeBrush = null
        strokeOpacity = 1f

        if (layer != null) {
            val sub = layer.sublayer
            if (sub != null) {
                // サブレイヤーを本体に opacity で合成 (Drawpile merge_sublayer)
                val op255 = (brush.opacity * 255f).toInt().coerceIn(0, 255)
                compositeTwoLayers(layer.content, sub, brush.opacity, PixelOps.BLEND_NORMAL)
                layer.sublayer = null
            }
        }
        // COW 参照を解放してからクリア
        for (e in redoStack) releaseUndoEntry(e)
        redoStack.clear()
        dirtyTracker.markFullRebuild()
    }

    /** ストローク中にレイヤー切替等が起きた場合の強制確定 */
    private fun forceEndStrokeIfNeeded() {
        if (!strokeInProgress) return
        val brush = strokeBrush ?: BrushConfig()
        val layer = getStrokeLayer()
        brushEngine.endStroke()
        strokeInProgress = false
        strokeBrush = null
        strokeOpacity = 1f
        if (layer != null) {
            val sub = layer.sublayer
            if (sub != null) {
                val op255 = (brush.opacity * 255f).toInt().coerceIn(0, 255)
                compositeTwoLayers(layer.content, sub, brush.opacity, PixelOps.BLEND_NORMAL)
                layer.sublayer = null
            }
        }
    }

    // ── スポイト (Eyedropper) ────────────────────────────────────────

    /** 合成済みキャンバスから色をサンプリング (メインスレッドから呼ばれる) */
    fun eyedropperAt(px: Int, py: Int): Int = lock.withLock {
        rebuildCompositeCache()
        if (px < 0 || px >= width || py < 0 || py >= height) return 0xFF000000.toInt()
        val tx = px / Tile.SIZE; val ty = py / Tile.SIZE
        val idx = ty * tilesX + tx
        val data = compositeCache[idx] ?: return 0xFFFFFFFF.toInt()
        val lx = px - tx * Tile.SIZE; val ly = py - ty * Tile.SIZE
        return data[ly * Tile.SIZE + lx]
    }

    // ── レイヤー合成 ────────────────────────────────────────────────

    fun rebuildCompositeCache() {
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            rebuildCompositeTile(tx, ty, ty * tilesX + tx)
        }
    }

    fun rebuildCompositeTile(tx: Int, ty: Int, cacheIdx: Int) {
        val result = compositeCache[cacheIdx]
            ?: IntArray(Tile.LENGTH).also { compositeCache[cacheIdx] = it }
        result.fill(0xFFFFFFFF.toInt())

        // クリッピング用ベースタイル追跡
        var clipBaseTile: IntArray? = null
        var isBaseVisible = false

        for (layer in _layers) {
            // --- クリッピングベース状態の更新 ---
            // 非クリッピングレイヤー = 新しいベース候補
            if (!layer.isClipToBelow) {
                val layerVisible = layer.isVisible && (layer.opacity * 255f).toInt() > 0
                val mainTile = layer.content.getTile(tx, ty)
                val subTile = layer.sublayer?.getTile(tx, ty)
                val hasTile = mainTile != null || subTile != null

                if (layerVisible && hasTile) {
                    isBaseVisible = true
                    // clipBaseTile は下で合成後に設定される
                } else {
                    // ベースが非表示 / 透明 / タイルなし → クリッピング子も非表示
                    isBaseVisible = layerVisible && !hasTile
                    // タイルがないベースでも visible なら clipBaseTile は null
                    // (子レイヤーはクリップ対象がないので描画されない)
                    clipBaseTile = null
                }
            }

            if (!layer.isVisible) continue
            val op255 = (layer.opacity * 255f).toInt(); if (op255 <= 0) continue

            val mainTile = layer.content.getTile(tx, ty)
            val subTile = layer.sublayer?.getTile(tx, ty)

            // クリッピングレイヤーの場合: ベースが非表示 or ベースタイルなし → スキップ
            if (layer.isClipToBelow) {
                if (!isBaseVisible || clipBaseTile == null) continue
            }

            if (mainTile == null && subTile == null) {
                // タイルがないレイヤーは描画するものがない
                continue
            }

            // 本体 + サブレイヤー合成
            // サブレイヤープレビューには strokeOpacity を適用
            // (endStroke で brush.opacity で合成されるのと一致させる)
            val subOp255 = (strokeOpacity * 255f).toInt().coerceIn(0, 255)
            val srcPixels: IntArray = when {
                mainTile != null && subTile != null -> {
                    mainTile.pixels.copyOf().also {
                        PixelOps.compositeLayer(it, subTile.pixels, subOp255, PixelOps.BLEND_NORMAL)
                    }
                }
                subTile != null -> {
                    if (subOp255 >= 255) subTile.pixels
                    else {
                        // サブレイヤーのみ存在する場合も opacity を適用
                        IntArray(Tile.LENGTH).also {
                            PixelOps.compositeLayer(it, subTile.pixels, subOp255, PixelOps.BLEND_NORMAL)
                        }
                    }
                }
                else -> mainTile!!.pixels
            }

            // クリッピング
            if (layer.isClipToBelow && clipBaseTile != null) {
                val clipped = srcPixels.copyOf()
                for (i in 0 until Tile.LENGTH) {
                    val ma = PixelOps.alpha(clipBaseTile[i])
                    if (ma < 255) {
                        val sa = PixelOps.alpha(clipped[i])
                        val na = PixelOps.div255(sa * ma)
                        if (na == 0) { clipped[i] = 0; continue }
                        if (sa > 0) {
                            val s = na.toFloat() / sa
                            clipped[i] = PixelOps.pack(na,
                                (PixelOps.red(clipped[i]) * s).toInt(),
                                (PixelOps.green(clipped[i]) * s).toInt(),
                                (PixelOps.blue(clipped[i]) * s).toInt())
                        }
                    }
                }
                PixelOps.compositeLayer(result, clipped, op255, layer.blendMode)
            } else {
                PixelOps.compositeLayer(result, srcPixels, op255, layer.blendMode)
                clipBaseTile = srcPixels // 次のクリッピング用ベース更新
            }
        }
    }

    private fun compositeTwoLayers(dst: TiledSurface, src: TiledSurface, opacity: Float, blendMode: Int) {
        val op255 = (opacity * 255f).toInt().coerceIn(0, 255)
        for (ty in 0 until src.tilesY) for (tx in 0 until src.tilesX) {
            val st = src.getTile(tx, ty) ?: continue
            val dt = dst.getOrCreateMutable(tx, ty)
            PixelOps.compositeLayer(dt.pixels, st.pixels, op255, blendMode)
            dirtyTracker.markDirty(tx, ty)
        }
    }

    // ── フィルター ──────────────────────────────────────────────────

    fun applyHslFilter(layerId: Int, hue: Float, sat: Float, lit: Float) = lock.withLock {
        // NaN/Infinity 防御
        if (hue.isNaN() || hue.isInfinite() || sat.isNaN() || sat.isInfinite() || lit.isNaN() || lit.isInfinite()) {
            PaintDebug.assertFail("HSL filter NaN/Inf: hue=$hue sat=$sat lit=$lit")
            return
        }
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        PaintDebug.d(PaintDebug.Layer) { "[applyHslFilter] layerId=$layerId hue=$hue sat=$sat lit=$lit" }
        pushUndoTileDelta(layer)
        for (i in layer.content.tiles.indices) {
            val tile = layer.content.tiles[i] ?: continue
            val mt = if (tile.refCount > 1) { tile.decRef(); tile.mutableCopy().also { layer.content.tiles[i] = it } } else tile
            for (p in mt.pixels.indices) {
                val c = mt.pixels[p]; val a = PixelOps.alpha(c); if (a == 0) continue
                val up = PixelOps.unpremultiply(c)
                val hsv = FloatArray(3)
                android.graphics.Color.RGBToHSV(PixelOps.red(up), PixelOps.green(up), PixelOps.blue(up), hsv)
                hsv[0] = (hsv[0] + hue + 360f) % 360f
                hsv[1] = (hsv[1] + sat).coerceIn(0f, 1f)
                hsv[2] = (hsv[2] + lit).coerceIn(0f, 1f)
                val rgb = android.graphics.Color.HSVToColor(a, hsv)
                mt.pixels[p] = PixelOps.premultiply(rgb)
            }
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyBrightnessContrast(layerId: Int, brightness: Float, contrast: Float) = lock.withLock {
        // NaN/Infinity 防御
        if (brightness.isNaN() || brightness.isInfinite() || contrast.isNaN() || contrast.isInfinite()) {
            PaintDebug.assertFail("brightnessContrast NaN/Inf: brightness=$brightness contrast=$contrast")
            return
        }
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        PaintDebug.d(PaintDebug.Layer) { "[applyBrightnessContrast] layerId=$layerId brightness=$brightness contrast=$contrast" }
        pushUndoTileDelta(layer)
        // contrast をクランプして分母ゼロ回避 (259/255 ≈ 1.016 で分母=0)
        val safeContrast = contrast.coerceIn(-1f, 0.99f)
        val factor = (259f * (safeContrast * 255f + 255f)) / (255f * (259f - safeContrast * 255f))
        for (i in layer.content.tiles.indices) {
            val tile = layer.content.tiles[i] ?: continue
            val mt = if (tile.refCount > 1) { tile.decRef(); tile.mutableCopy().also { layer.content.tiles[i] = it } } else tile
            for (p in mt.pixels.indices) {
                val c = mt.pixels[p]; val a = PixelOps.alpha(c); if (a == 0) continue
                val up = PixelOps.unpremultiply(c)
                var r = PixelOps.red(up); var g = PixelOps.green(up); var b = PixelOps.blue(up)
                // brightness
                r = (r + brightness * 255f).toInt(); g = (g + brightness * 255f).toInt(); b = (b + brightness * 255f).toInt()
                // contrast
                r = (factor * (r - 128) + 128).toInt(); g = (factor * (g - 128) + 128).toInt(); b = (factor * (b - 128) + 128).toInt()
                mt.pixels[p] = PixelOps.premultiply(PixelOps.pack(a, r.coerceIn(0,255), g.coerceIn(0,255), b.coerceIn(0,255)))
            }
        }
        dirtyTracker.markFullRebuild()
    }

    /**
     * レイヤー全体にブラーフィルタを適用（直接確定、Undo 対応）。
     * blurType: BLUR_GAUSSIAN (3パスボックスブラー) / BLUR_STACK (スタックブラー)
     */
    fun applyBlurFilter(layerId: Int, radius: Int, blurType: Int = BLUR_GAUSSIAN) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        val safeRadius = radius.coerceIn(1, 100)
        PaintDebug.d(PaintDebug.Layer) { "[applyBlurFilter] layerId=$layerId radius=$safeRadius type=$blurType" }
        pushUndoTileDelta(layer)

        val src = layer.content.toPixelArray()
        val w = layer.content.width; val h = layer.content.height

        when (blurType) {
            BLUR_GAUSSIAN -> {
                val buf = src.copyOf()
                boxBlurPass(src, buf, w, h, safeRadius)
                boxBlurPass(buf, src, w, h, safeRadius)
                boxBlurPass(src, buf, w, h, safeRadius)
                writePixelsToLayer(layer, buf, w, h)
            }
            BLUR_STACK -> {
                stackBlur(src, w, h, safeRadius)
                writePixelsToLayer(layer, src, w, h)
            }
        }
        dirtyTracker.markFullRebuild()
    }

    /**
     * 水平+垂直ボックスブラー1パス (premultiplied ARGB)。
     * スライディングウィンドウで O(n) 実装。
     */
    private fun boxBlurPass(input: IntArray, output: IntArray, w: Int, h: Int, radius: Int) {
        val temp = IntArray(w * h)
        val d = radius * 2 + 1

        // 水平ブラー → temp
        for (y in 0 until h) {
            var rAcc = 0L; var gAcc = 0L; var bAcc = 0L; var aAcc = 0L
            val rowOff = y * w
            // 初期ウィンドウ
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
                // スライド
                val addX = (x + radius + 1).coerceAtMost(w - 1)
                val remX = (x - radius).coerceAtLeast(0)
                val ac = input[rowOff + addX]; val rc = input[rowOff + remX]
                aAcc += PixelOps.alpha(ac) - PixelOps.alpha(rc)
                rAcc += PixelOps.red(ac) - PixelOps.red(rc)
                gAcc += PixelOps.green(ac) - PixelOps.green(rc)
                bAcc += PixelOps.blue(ac) - PixelOps.blue(rc)
            }
        }

        // 垂直ブラー temp → output
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

    // ── Stack Blur ─────────────────────────────────────────────────

    /**
     * Stack Blur (Mario Klingemann 方式)。
     * 三角カーネルによる重み付き平均で、ボックスブラーより滑らかな結果を得る。
     * 計算量は O(n) で半径に依存しない。
     */
    private fun stackBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        if (radius < 1) return
        val div = radius * 2 + 1
        val divSum = (radius + 1) * (radius + 1) // 三角カーネルの重み合計

        val stack = IntArray(div * 4) // ARGB × div

        // ── 水平パス ──
        for (y in 0 until h) {
            var rSum = 0L; var gSum = 0L; var bSum = 0L; var aSum = 0L
            var rInSum = 0L; var gInSum = 0L; var bInSum = 0L; var aInSum = 0L
            var rOutSum = 0L; var gOutSum = 0L; var bOutSum = 0L; var aOutSum = 0L

            val rowOff = y * w

            // スタック初期化
            for (i in -radius..radius) {
                val sx = i.coerceIn(0, w - 1)
                val c = pixels[rowOff + sx]
                val ca = PixelOps.alpha(c); val cr = PixelOps.red(c)
                val cg = PixelOps.green(c); val cb = PixelOps.blue(c)
                val si = (i + radius) * 4
                stack[si] = ca; stack[si + 1] = cr; stack[si + 2] = cg; stack[si + 3] = cb

                val weight = radius + 1 - kotlin.math.abs(i)
                aSum += ca * weight; rSum += cr * weight; gSum += cg * weight; bSum += cb * weight
                if (i > 0) {
                    aInSum += ca; rInSum += cr; gInSum += cg; bInSum += cb
                } else {
                    aOutSum += ca; rOutSum += cr; gOutSum += cg; bOutSum += cb
                }
            }

            var stackPtr = radius
            for (x in 0 until w) {
                pixels[rowOff + x] = PixelOps.pack(
                    (aSum / divSum).toInt().coerceIn(0, 255),
                    (rSum / divSum).toInt().coerceIn(0, 255),
                    (gSum / divSum).toInt().coerceIn(0, 255),
                    (bSum / divSum).toInt().coerceIn(0, 255),
                )

                aSum -= aOutSum; rSum -= rOutSum; gSum -= gOutSum; bSum -= bOutSum

                val outIdx = ((stackPtr - radius + div) % div) * 4
                aOutSum -= stack[outIdx]; rOutSum -= stack[outIdx + 1]
                gOutSum -= stack[outIdx + 2]; bOutSum -= stack[outIdx + 3]

                val addX = (x + radius + 1).coerceAtMost(w - 1)
                val nc = pixels[rowOff + addX]
                val na = PixelOps.alpha(nc); val nr = PixelOps.red(nc)
                val ng = PixelOps.green(nc); val nb = PixelOps.blue(nc)
                stack[outIdx] = na; stack[outIdx + 1] = nr; stack[outIdx + 2] = ng; stack[outIdx + 3] = nb

                aInSum += na; rInSum += nr; gInSum += ng; bInSum += nb
                aSum += aInSum; rSum += rInSum; gSum += gInSum; bSum += bInSum

                stackPtr = (stackPtr + 1) % div
                val inIdx = (stackPtr % div) * 4
                aOutSum += stack[inIdx]; rOutSum += stack[inIdx + 1]
                gOutSum += stack[inIdx + 2]; bOutSum += stack[inIdx + 3]
                aInSum -= stack[inIdx]; rInSum -= stack[inIdx + 1]
                gInSum -= stack[inIdx + 2]; bInSum -= stack[inIdx + 3]
            }
        }

        // ── 垂直パス ──
        for (x in 0 until w) {
            var rSum = 0L; var gSum = 0L; var bSum = 0L; var aSum = 0L
            var rInSum = 0L; var gInSum = 0L; var bInSum = 0L; var aInSum = 0L
            var rOutSum = 0L; var gOutSum = 0L; var bOutSum = 0L; var aOutSum = 0L

            for (i in -radius..radius) {
                val sy = i.coerceIn(0, h - 1)
                val c = pixels[sy * w + x]
                val ca = PixelOps.alpha(c); val cr = PixelOps.red(c)
                val cg = PixelOps.green(c); val cb = PixelOps.blue(c)
                val si = (i + radius) * 4
                stack[si] = ca; stack[si + 1] = cr; stack[si + 2] = cg; stack[si + 3] = cb

                val weight = radius + 1 - kotlin.math.abs(i)
                aSum += ca * weight; rSum += cr * weight; gSum += cg * weight; bSum += cb * weight
                if (i > 0) {
                    aInSum += ca; rInSum += cr; gInSum += cg; bInSum += cb
                } else {
                    aOutSum += ca; rOutSum += cr; gOutSum += cg; bOutSum += cb
                }
            }

            var stackPtr = radius
            for (y in 0 until h) {
                pixels[y * w + x] = PixelOps.pack(
                    (aSum / divSum).toInt().coerceIn(0, 255),
                    (rSum / divSum).toInt().coerceIn(0, 255),
                    (gSum / divSum).toInt().coerceIn(0, 255),
                    (bSum / divSum).toInt().coerceIn(0, 255),
                )

                aSum -= aOutSum; rSum -= rOutSum; gSum -= gOutSum; bSum -= bOutSum

                val outIdx = ((stackPtr - radius + div) % div) * 4
                aOutSum -= stack[outIdx]; rOutSum -= stack[outIdx + 1]
                gOutSum -= stack[outIdx + 2]; bOutSum -= stack[outIdx + 3]

                val addY = (y + radius + 1).coerceAtMost(h - 1)
                val nc = pixels[addY * w + x]
                val na = PixelOps.alpha(nc); val nr = PixelOps.red(nc)
                val ng = PixelOps.green(nc); val nb = PixelOps.blue(nc)
                stack[outIdx] = na; stack[outIdx + 1] = nr; stack[outIdx + 2] = ng; stack[outIdx + 3] = nb

                aInSum += na; rInSum += nr; gInSum += ng; bInSum += nb
                aSum += aInSum; rSum += rInSum; gSum += gInSum; bSum += bInSum

                stackPtr = (stackPtr + 1) % div
                val inIdx = (stackPtr % div) * 4
                aOutSum += stack[inIdx]; rOutSum += stack[inIdx + 1]
                gOutSum += stack[inIdx + 2]; bOutSum += stack[inIdx + 3]
                aInSum -= stack[inIdx]; rInSum -= stack[inIdx + 1]
                gInSum -= stack[inIdx + 2]; bInSum -= stack[inIdx + 3]
            }
        }
    }

    // ── フィルタープレビュー ─────────────────────────────────────

    /** フィルター種別 */
    companion object {
        const val BLUR_GAUSSIAN = 0
        const val BLUR_STACK = 1
    }

    /**
     * プレビュー開始: 対象レイヤーのタイルスナップショットを保持。
     * 既にプレビュー中なら何もしない（レイヤーが同じ場合）。
     */
    fun beginFilterPreview(layerId: Int) = lock.withLock {
        if (filterPreviewSnapshot != null && filterPreviewLayerId == layerId) return
        cancelFilterPreviewInternal()
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        filterPreviewLayerId = layerId
        val snaps = HashMap<Int, Tile?>()
        for (i in layer.content.tiles.indices) {
            val tile = layer.content.tiles[i]
            if (tile != null) tile.incRef()
            snaps[i] = tile
        }
        filterPreviewSnapshot = snaps
        PaintDebug.d(PaintDebug.Layer) { "[beginFilterPreview] layerId=$layerId tiles=${snaps.size}" }
    }

    /**
     * プレビュー用ブラー適用: スナップショットから復元 → ブラー適用。
     * Undo には積まない。スライダー変更ごとに呼ばれる。
     */
    fun applyBlurPreview(layerId: Int, radius: Int, blurType: Int) = lock.withLock {
        val snaps = filterPreviewSnapshot ?: return
        if (filterPreviewLayerId != layerId) return
        val layer = _layers.find { it.id == layerId } ?: return
        val safeRadius = radius.coerceIn(1, 100)

        // スナップショットからタイルを復元
        restoreTilesFromSnapshot(layer, snaps)

        // ブラー処理
        val src = layer.content.toPixelArray()
        val w = layer.content.width; val h = layer.content.height

        when (blurType) {
            BLUR_GAUSSIAN -> {
                val buf = src.copyOf()
                boxBlurPass(src, buf, w, h, safeRadius)
                boxBlurPass(buf, src, w, h, safeRadius)
                boxBlurPass(src, buf, w, h, safeRadius)
                writePixelsToLayer(layer, buf, w, h)
            }
            BLUR_STACK -> {
                stackBlur(src, w, h, safeRadius)
                writePixelsToLayer(layer, src, w, h)
            }
        }
        dirtyTracker.markFullRebuild()
    }

    /**
     * プレビュー確定: Undo にスナップショットを積み、プレビュー状態をクリア。
     */
    fun commitFilterPreview() = lock.withLock {
        val snaps = filterPreviewSnapshot ?: return
        val layer = _layers.find { it.id == filterPreviewLayerId }
        if (layer != null) {
            // スナップショットを Undo に積む (所有権移転)
            undoStack.add(UndoEntry.TileDelta(layer.id, snaps))
            if (undoStack.size > 50) {
                releaseUndoEntry(undoStack.removeAt(0))
            }
            PaintDebug.d(PaintDebug.Layer) { "[commitFilterPreview] layerId=${layer.id}" }
        } else {
            releaseSnapshot(snaps)
        }
        filterPreviewSnapshot = null
        filterPreviewLayerId = -1
    }

    /**
     * プレビューキャンセル: スナップショットからタイルを復元し、プレビュー状態をクリア。
     */
    fun cancelFilterPreview() = lock.withLock {
        cancelFilterPreviewInternal()
    }

    private fun cancelFilterPreviewInternal() {
        val snaps = filterPreviewSnapshot ?: return
        val layer = _layers.find { it.id == filterPreviewLayerId }
        if (layer != null) {
            restoreTilesFromSnapshot(layer, snaps)
            dirtyTracker.markFullRebuild()
        }
        releaseSnapshot(snaps)
        filterPreviewSnapshot = null
        filterPreviewLayerId = -1
    }

    /** スナップショットからレイヤータイルを復元 (参照は incRef して保持) */
    private fun restoreTilesFromSnapshot(layer: Layer, snaps: Map<Int, Tile?>) {
        for (i in layer.content.tiles.indices) {
            val old = layer.content.tiles[i]
            old?.decRef()
            val snapTile = snaps[i]
            if (snapTile != null) {
                snapTile.incRef()
                layer.content.tiles[i] = snapTile
            } else {
                layer.content.tiles[i] = null
            }
        }
    }

    /** ピクセル配列をレイヤータイルに書き戻す */
    private fun writePixelsToLayer(layer: Layer, buf: IntArray, w: Int, h: Int) {
        layer.content.clear()
        for (ty in 0 until layer.content.tilesY) for (tx in 0 until layer.content.tilesX) {
            val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
            var hasPixel = false
            outer@ for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= h) break
                for (lx in 0 until Tile.SIZE) {
                    val px = bx + lx; if (px >= w) break
                    if (buf[py * w + px] != 0) { hasPixel = true; break@outer }
                }
            }
            if (!hasPixel) continue
            val tile = layer.content.getOrCreateMutable(tx, ty)
            for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= h) break
                for (lx in 0 until Tile.SIZE) {
                    val px = bx + lx; if (px >= w) break
                    tile.pixels[ly * Tile.SIZE + lx] = buf[py * w + px]
                }
            }
        }
    }

    /** スナップショットのタイル参照を解放 */
    private fun releaseSnapshot(snaps: Map<Int, Tile?>) {
        for ((_, tile) in snaps) tile?.decRef()
    }

    // ── Undo/Redo ───────────────────────────────────────────────────

    sealed class UndoEntry {
        data class TileDelta(val layerId: Int, val snapshots: Map<Int, Tile?>) : UndoEntry()
        data class Structural(val snapshots: List<LayerSnapshot>, val activeId: Int) : UndoEntry()
    }

    data class LayerSnapshot(
        val id: Int, val name: String, val surface: TiledSurface,
        val opacity: Float, val blendMode: Int, val isVisible: Boolean, val isClip: Boolean,
    )

    private fun pushUndoTileDelta(layer: Layer) {
        // COW 最適化: ピクセルの深コピーではなく、タイル参照を incRef で保持
        // 後で getOrCreateMutable が書込時にコピーを作成する (Copy-on-Write)
        val snaps = HashMap<Int, Tile?>()
        for (i in layer.content.tiles.indices) {
            val tile = layer.content.tiles[i]
            if (tile != null) {
                tile.incRef()
                snaps[i] = tile
            } else {
                snaps[i] = null // null タイル（透明）も記録
            }
        }
        undoStack.add(UndoEntry.TileDelta(layer.id, snaps))
        if (undoStack.size > 50) {
            // 古いエントリの参照を解放
            val removed = undoStack.removeAt(0)
            releaseUndoEntry(removed)
        }
        PaintDebug.d(PaintDebug.Undo) { "[pushUndo] layerId=${layer.id} tiles=${snaps.size} stackSize=${undoStack.size}" }
    }

    private fun pushUndoStructural() {
        undoStack.add(UndoEntry.Structural(
            _layers.map { LayerSnapshot(it.id, it.name, it.content.snapshot(), it.opacity, it.blendMode, it.isVisible, it.isClipToBelow) },
            activeLayerId,
        ))
        if (undoStack.size > 50) undoStack.removeAt(0)
    }

    fun undo(): Boolean = lock.withLock {
        if (undoStack.isEmpty()) return false
        forceEndStrokeIfNeeded()
        PaintDebug.d(PaintDebug.Undo) { "[undo] stackSize=${undoStack.size}" }
        val entry = undoStack.removeLast()
        when (entry) {
            is UndoEntry.TileDelta -> {
                val layer = _layers.find { it.id == entry.layerId } ?: run {
                    releaseUndoEntry(entry); return false
                }
                // 現在の状態を redo に保存 (COW)
                val redoSnaps = HashMap<Int, Tile?>()
                for (i in layer.content.tiles.indices) {
                    val tile = layer.content.tiles[i]
                    if (tile != null) tile.incRef()
                    redoSnaps[i] = tile
                }
                redoStack.add(UndoEntry.TileDelta(entry.layerId, redoSnaps))
                // 復元: スナップショットのタイル参照で置換
                for (i in layer.content.tiles.indices) {
                    val oldTile = layer.content.tiles[i]
                    oldTile?.decRef()
                    val snapTile = entry.snapshots[i]
                    if (snapTile != null) {
                        // snapTile の参照は entry が持っていたものをそのまま引き継ぎ
                        // (新たに incRef は不要、entry の所有権移転)
                        layer.content.tiles[i] = snapTile
                    } else {
                        layer.content.tiles[i] = null
                    }
                }
            }
            is UndoEntry.Structural -> {
                pushRedoStructural()
                _layers.clear()
                for (s in entry.snapshots) {
                    _layers.add(Layer(s.id, s.name, s.surface, s.opacity, s.blendMode, s.isVisible, isClipToBelow = s.isClip))
                }
                activeLayerId = entry.activeId
                nextLayerId = (_layers.maxOfOrNull { it.id } ?: 0) + 1
            }
        }
        dirtyTracker.markFullRebuild(); true
    }

    fun redo(): Boolean = lock.withLock {
        if (redoStack.isEmpty()) return false
        forceEndStrokeIfNeeded()
        PaintDebug.d(PaintDebug.Undo) { "[redo] stackSize=${redoStack.size}" }
        val entry = redoStack.removeLast()
        when (entry) {
            is UndoEntry.TileDelta -> {
                val layer = _layers.find { it.id == entry.layerId } ?: run {
                    releaseUndoEntry(entry); return false
                }
                // 現在の状態を undo に保存 (COW)
                val undoSnaps = HashMap<Int, Tile?>()
                for (i in layer.content.tiles.indices) {
                    val tile = layer.content.tiles[i]
                    if (tile != null) tile.incRef()
                    undoSnaps[i] = tile
                }
                undoStack.add(UndoEntry.TileDelta(entry.layerId, undoSnaps))
                // 復元
                for (i in layer.content.tiles.indices) {
                    val oldTile = layer.content.tiles[i]
                    oldTile?.decRef()
                    val snapTile = entry.snapshots[i]
                    if (snapTile != null) {
                        layer.content.tiles[i] = snapTile
                    } else {
                        layer.content.tiles[i] = null
                    }
                }
            }
            is UndoEntry.Structural -> {
                pushUndoStructural()
                _layers.clear()
                for (s in entry.snapshots) {
                    _layers.add(Layer(s.id, s.name, s.surface, s.opacity, s.blendMode, s.isVisible, isClipToBelow = s.isClip))
                }
                activeLayerId = entry.activeId
                nextLayerId = (_layers.maxOfOrNull { it.id } ?: 0) + 1
            }
        }
        dirtyTracker.markFullRebuild(); true
    }

    /** Undo/Redo エントリのタイル参照を解放 */
    private fun releaseUndoEntry(entry: UndoEntry) {
        when (entry) {
            is UndoEntry.TileDelta -> {
                for ((_, tile) in entry.snapshots) tile?.decRef()
            }
            is UndoEntry.Structural -> { /* TiledSurface.snapshot の参照は GC に任せる */ }
        }
    }

    private fun pushRedoStructural() {
        redoStack.add(UndoEntry.Structural(
            _layers.map { LayerSnapshot(it.id, it.name, it.content.snapshot(), it.opacity, it.blendMode, it.isVisible, it.isClipToBelow) },
            activeLayerId,
        ))
    }

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** プロジェクトファイル読み込み用: 全レイヤーを差し替え */
    fun replaceAllLayers(newLayers: List<Layer>, activeId: Int, nextId: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        _layers.clear()
        _layers.addAll(newLayers)
        activeLayerId = if (newLayers.any { it.id == activeId }) activeId else newLayers.first().id
        nextLayerId = nextId
        undoStack.clear(); redoStack.clear()
        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[replaceAllLayers] layers=${_layers.size} activeId=$activeLayerId nextId=$nextLayerId" }
    }

    /**
     * 画像をレイヤーとしてインポートする。
     * キャンバスサイズに合わせてセンタリング配置。
     */
    fun importImageAsLayer(name: String, pixels: IntArray, imgW: Int, imgH: Int): Layer = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = Layer(nextLayerId++, name, TiledSurface(width, height))
        // 画像をキャンバス中央に配置
        val offsetX = (width - imgW) / 2
        val offsetY = (height - imgH) / 2
        for (sy in 0 until imgH) {
            val dstY = sy + offsetY
            if (dstY < 0 || dstY >= height) continue
            for (sx in 0 until imgW) {
                val dstX = sx + offsetX
                if (dstX < 0 || dstX >= width) continue
                val pixel = pixels[sy * imgW + sx]
                if (pixel == 0) continue
                val premul = PixelOps.premultiply(pixel)
                val tx = dstX / Tile.SIZE; val ty = dstY / Tile.SIZE
                val tile = layer.content.getOrCreateMutable(tx, ty)
                val lx = dstX - tx * Tile.SIZE; val ly = dstY - ty * Tile.SIZE
                tile.pixels[ly * Tile.SIZE + lx] = premul
            }
        }
        _layers.add(layer)
        activeLayerId = layer.id
        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[importImageAsLayer] name=$name ${imgW}x${imgH} -> layer ${layer.id}" }
        layer
    }

    /** 合成済み全ピクセル (エクスポート用) */
    fun getCompositePixels(): IntArray {
        rebuildCompositeCache()
        val out = IntArray(width * height)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val data = compositeCache[ty * tilesX + tx] ?: continue
            val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
            for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= height) break
                System.arraycopy(data, ly * Tile.SIZE, out, py * width + bx, minOf(Tile.SIZE, width - bx))
            }
        }
        return out
    }
}
