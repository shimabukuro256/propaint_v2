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

    /** レイヤーマスク (null = マスクなし) */
    var mask: TiledSurface? = null
    /** マスクを有効にするか */
    var isMaskEnabled: Boolean = true
    /** マスクを編集中か (true の場合、ブラシ操作はマスクに適用される) */
    var isEditingMask: Boolean = false

    /** レイヤーグループ ID (0 = グループなし) */
    var groupId: Int = 0

    /** テキストレイヤーの設定 (null = 通常レイヤー) */
    var textConfig: TextRenderer.TextConfig? = null
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
        val maxSize = MemoryConfig.maxCanvasSize.toLong()
        require(width <= maxSize && height <= maxSize) {
            "canvas too large: ${width}x${height} (max=${maxSize}x${maxSize} for ${MemoryConfig.tierName} device)"
        }
    }

    val dirtyTracker = DirtyTileTracker()
    val brushEngine by lazy { BrushEngine(dirtyTracker, selectionManager) }

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
    /** フィルタープレビュー用再利用バッファ (スライダー操作中のアロケーション回避) */
    private var filterPreviewBuf: IntArray? = null
    private var filterPreviewBuf2: IntArray? = null

    // ピクセルコピー変形（Word/Excel風）
    private var pixelCopyBuffer: IntArray? = null
    private var pixelCopyBounds: android.graphics.Rect? = null
    private var pixelCopyOriginalPixels: IntArray? = null // キャンセル用のオリジナル保存

    // ストローク中フラグ
    private var strokeInProgress = false
    private var strokeBrush: BrushConfig? = null
    /** サブレイヤープレビュー用: ストローク中の brush.opacity (GL スレッドから参照) */
    @Volatile var strokeOpacity: Float = 1f; private set

    // 合成キャッシュ (ダブルバッファ)
    // backBuffer: エンジンスレッド (lock 内) が書き込む
    // frontBuffer: GL スレッドが読み取る (compositeCache として公開)
    // swapCompositeTile() で dirty タイルのみ back→front にコピーする
    private val tilesX get() = (width + Tile.SIZE - 1) / Tile.SIZE
    private val tilesY get() = (height + Tile.SIZE - 1) / Tile.SIZE
    private val backBuffer: Array<IntArray?> = arrayOfNulls(tilesX * tilesY)
    private val frontBuffer: Array<IntArray?> = arrayOfNulls(tilesX * tilesY)

    /** GL スレッドが参照する合成済みタイルキャッシュ (読み取り専用) */
    val compositeCache: Array<IntArray?> get() = frontBuffer

    /** rebuildCompositeTile 内のサブレイヤー合成用 scratch buffer (GC 回避) */
    private val compositeScratch = IntArray(Tile.LENGTH)

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

        // COW スナップショットからタイル単位で読み取り、オフセットして書き戻す
        // toPixelArray() の width*height*4 バイト割り当てを回避
        val snapshot = layer.content.snapshot()
        layer.content.clear()
        val w = width; val h = height
        for (sty in 0 until snapshot.tilesY) {
            for (stx in 0 until snapshot.tilesX) {
                val srcTile = snapshot.getTile(stx, sty) ?: continue
                val baseSrcX = stx * Tile.SIZE
                val baseSrcY = sty * Tile.SIZE
                for (ly in 0 until Tile.SIZE) {
                    val srcY = baseSrcY + ly
                    if (srcY >= h) break
                    val dstY = srcY + dy
                    if (dstY < 0 || dstY >= h) continue
                    for (lx in 0 until Tile.SIZE) {
                        val srcX = baseSrcX + lx
                        if (srcX >= w) break
                        val pixel = srcTile.pixels[ly * Tile.SIZE + lx]
                        if (pixel == 0) continue
                        val dstX = srcX + dx
                        if (dstX < 0 || dstX >= w) continue
                        val dtx = dstX / Tile.SIZE; val dty = dstY / Tile.SIZE
                        val tile = layer.content.getOrCreateMutable(dtx, dty)
                        val dlx = dstX - dtx * Tile.SIZE; val dly = dstY - dty * Tile.SIZE
                        tile.pixels[dly * Tile.SIZE + dlx] = pixel
                    }
                }
            }
        }
        snapshot.clear() // COW 参照を解放
        dirtyTracker.markFullRebuild()
    }

    // ── 描画操作 ────────────────────────────────────────────────────

    /** 選択マスクが有効な場合のストローク前スナップショット (選択範囲外の復元用) */
    private var _strokeSelSnapshot: TiledSurface? = null

    fun beginStroke(brush: BrushConfig) = lock.withLock {
        val layer = getActiveLayer() ?: return
        if (layer.isLocked) {
            PaintDebug.d(PaintDebug.Brush) { "[beginStroke] layer ${layer.id} is locked, ignoring" }
            return
        }

        // 前のストロークが残っていたら強制確定
        forceEndStrokeIfNeeded()

        pushUndoTileDelta(layer)

        // 選択マスクが有効な場合: COW スナップショット (タイル参照の incRef のみ、64MB コピー不要)
        _strokeSelSnapshot = if (selectionManager.hasSelection) {
            layer.content.snapshot()
        } else null

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
                // 選択マスクが有効な場合: サブレイヤーの選択範囲外ピクセルを除去してから合成
                // → Indirect ブラシ (筆/水彩) が境界で範囲外ピクセルをブレンドするのを防ぐ
                if (selectionManager.hasSelection) {
                    maskSublayerWithSelection(sub)
                }
                // サブレイヤーを本体に opacity で合成 (Drawpile merge_sublayer)
                compositeTwoLayers(layer.content, sub, brush.opacity, PixelOps.BLEND_NORMAL)
                layer.sublayer = null
            }

            // 選択マスクが有効な場合: 選択範囲外のピクセルを元に戻す
            val snap = _strokeSelSnapshot
            if (snap != null && selectionManager.hasSelection) {
                restoreOutsideSelectionFromSurface(layer.content, snap)
            }
            _strokeSelSnapshot?.clear() // COW 参照を解放
            _strokeSelSnapshot = null
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
                // 選択マスクが有効な場合: サブレイヤーの選択範囲外ピクセルを除去してから合成
                if (selectionManager.hasSelection) {
                    maskSublayerWithSelection(sub)
                }
                compositeTwoLayers(layer.content, sub, brush.opacity, PixelOps.BLEND_NORMAL)
                layer.sublayer = null
            }
            // 選択マスクが有効な場合: 選択範囲外のピクセルを元に戻す
            val snap = _strokeSelSnapshot
            if (snap != null && selectionManager.hasSelection) {
                restoreOutsideSelectionFromSurface(layer.content, snap)
            }
        }
        _strokeSelSnapshot?.clear() // COW 参照を解放
        _strokeSelSnapshot = null
    }

    // ── スポイト (Eyedropper) ────────────────────────────────────────

    /** 合成済みキャンバスから色をサンプリング (メインスレッドから呼ばれる) */
    fun eyedropperAt(px: Int, py: Int): Int = lock.withLock {
        if (px < 0 || px >= width || py < 0 || py >= height) return 0xFF000000.toInt()
        val tx = px / Tile.SIZE; val ty = py / Tile.SIZE
        val idx = ty * tilesX + tx
        // 1タイルだけ再合成 (全タイル再構築は不要)
        rebuildCompositeTile(tx, ty, idx)
        val data = backBuffer[idx] ?: return 0xFFFFFFFF.toInt()
        val lx = px - tx * Tile.SIZE; val ly = py - ty * Tile.SIZE
        return data[ly * Tile.SIZE + lx]
    }

    // ── レイヤー合成 ────────────────────────────────────────────────

    fun rebuildCompositeCache() {
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            rebuildCompositeTile(tx, ty, ty * tilesX + tx)
        }
    }

    /**
     * backBuffer → frontBuffer にタイルデータをコピーする。
     * GL スレッドの onDrawFrame から呼ばれ、合成直後のタイルを
     * GL テクスチャアップロード用に安全に公開する。
     */
    fun publishCompositeTile(cacheIdx: Int) {
        val src = backBuffer[cacheIdx] ?: return
        val dst = frontBuffer[cacheIdx]
            ?: IntArray(Tile.LENGTH).also { frontBuffer[cacheIdx] = it }
        System.arraycopy(src, 0, dst, 0, Tile.LENGTH)
    }

    /** 全タイルを一括で publish (fullRebuild 時用) */
    fun publishAllCompositeTiles() {
        for (i in backBuffer.indices) {
            publishCompositeTile(i)
        }
    }

    fun rebuildCompositeTile(tx: Int, ty: Int, cacheIdx: Int) {
        val result = backBuffer[cacheIdx]
            ?: IntArray(Tile.LENGTH).also { backBuffer[cacheIdx] = it }
        result.fill(0xFFFFFFFF.toInt())

        // クリッピング用ベースタイル追跡
        var clipBaseTile: IntArray? = null
        var isBaseVisible = false

        for (layer in _layers) {
            // --- グループ非表示チェック ---
            if (layer.groupId > 0) {
                val group = _layerGroups[layer.groupId]
                if (group != null && !group.isVisible) continue
            }

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
            // scratch buffer を再利用して GC 圧力を削減
            // (rebuildCompositeTile は lock 内で単一スレッドからのみ呼ばれる)
            val srcPixels: IntArray = when {
                mainTile != null && subTile != null -> {
                    System.arraycopy(mainTile.pixels, 0, compositeScratch, 0, Tile.LENGTH)
                    PixelOps.compositeLayer(compositeScratch, subTile.pixels, subOp255, PixelOps.BLEND_NORMAL)
                    compositeScratch
                }
                subTile != null -> {
                    if (subOp255 >= 255) subTile.pixels
                    else {
                        // サブレイヤーのみ存在する場合も opacity を適用
                        compositeScratch.fill(0)
                        PixelOps.compositeLayer(compositeScratch, subTile.pixels, subOp255, PixelOps.BLEND_NORMAL)
                        compositeScratch
                    }
                }
                else -> mainTile!!.pixels
            }

            // レイヤーマスク適用
            val maskedPixels = if (layer.mask != null && layer.isMaskEnabled) {
                val copy = srcPixels.copyOf()
                LayerMaskOps.applyMaskToTile(copy, layer.mask!!, tx, ty)
                copy
            } else {
                srcPixels
            }

            // グループ不透明度の乗算
            val effectiveOp255 = if (layer.groupId > 0) {
                val group = _layerGroups[layer.groupId]
                if (group != null) (op255 * group.opacity).toInt().coerceIn(0, 255) else op255
            } else op255

            // クリッピング
            if (layer.isClipToBelow && clipBaseTile != null) {
                val clipped = maskedPixels.copyOf()
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
                PixelOps.compositeLayer(result, clipped, effectiveOp255, layer.blendMode)
            } else {
                PixelOps.compositeLayer(result, maskedPixels, effectiveOp255, layer.blendMode)
                // 次のクリッピング用ベース更新
                // maskedPixels が compositeScratch を指す場合は copyOf で独立コピーを保持
                clipBaseTile = if (maskedPixels === compositeScratch) maskedPixels.copyOf() else maskedPixels
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
        if (hue.isNaN() || hue.isInfinite() || sat.isNaN() || sat.isInfinite() || lit.isNaN() || lit.isInfinite()) {
            PaintDebug.assertFail("HSL filter NaN/Inf: hue=$hue sat=$sat lit=$lit")
            return
        }
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        PaintDebug.d(PaintDebug.Layer) { "[applyHslFilter] layerId=$layerId hue=$hue sat=$sat lit=$lit" }
        pushUndoTileDelta(layer)
        applyFilterWithSelection(layer) { pixels ->
            val hsv = FloatArray(3)
            for (i in pixels.indices) {
                val c = pixels[i]; val a = PixelOps.alpha(c); if (a == 0) continue
                val up = PixelOps.unpremultiply(c)
                android.graphics.Color.RGBToHSV(PixelOps.red(up), PixelOps.green(up), PixelOps.blue(up), hsv)
                hsv[0] = (hsv[0] + hue + 360f) % 360f
                hsv[1] = (hsv[1] + sat).coerceIn(0f, 1f)
                hsv[2] = (hsv[2] + lit).coerceIn(0f, 1f)
                val rgb = android.graphics.Color.HSVToColor(a, hsv)
                pixels[i] = PixelOps.premultiply(rgb)
            }
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyBrightnessContrast(layerId: Int, brightness: Float, contrast: Float) = lock.withLock {
        if (brightness.isNaN() || brightness.isInfinite() || contrast.isNaN() || contrast.isInfinite()) {
            PaintDebug.assertFail("brightnessContrast NaN/Inf: brightness=$brightness contrast=$contrast")
            return
        }
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        PaintDebug.d(PaintDebug.Layer) { "[applyBrightnessContrast] layerId=$layerId brightness=$brightness contrast=$contrast" }
        pushUndoTileDelta(layer)
        val safeContrast = contrast.coerceIn(-1f, 0.99f)
        val factor = (259f * (safeContrast * 255f + 255f)) / (255f * (259f - safeContrast * 255f))
        applyFilterWithSelection(layer) { pixels ->
            for (i in pixels.indices) {
                val c = pixels[i]; val a = PixelOps.alpha(c); if (a == 0) continue
                val up = PixelOps.unpremultiply(c)
                var r = PixelOps.red(up); var g = PixelOps.green(up); var b = PixelOps.blue(up)
                r = (r + brightness * 255f).toInt(); g = (g + brightness * 255f).toInt(); b = (b + brightness * 255f).toInt()
                r = (factor * (r - 128) + 128).toInt(); g = (factor * (g - 128) + 128).toInt(); b = (factor * (b - 128) + 128).toInt()
                pixels[i] = PixelOps.premultiply(PixelOps.pack(a, r.coerceIn(0,255), g.coerceIn(0,255), b.coerceIn(0,255)))
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

        val w = layer.content.width; val h = layer.content.height
        applyFilterWithSelection(layer) { pixels ->
            when (blurType) {
                BLUR_GAUSSIAN -> {
                    val buf = pixels.copyOf()
                    boxBlurPass(pixels, buf, w, h, safeRadius)
                    boxBlurPass(buf, pixels, w, h, safeRadius)
                    boxBlurPass(pixels, buf, w, h, safeRadius)
                    System.arraycopy(buf, 0, pixels, 0, pixels.size)
                }
                BLUR_STACK -> {
                    stackBlur(pixels, w, h, safeRadius)
                }
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

        // ブラー処理 — 再利用バッファでスライダー操作中のアロケーションを回避
        val w = layer.content.width; val h = layer.content.height
        val pixelCount = w * h
        val src = filterPreviewBuf?.let { if (it.size >= pixelCount) it else null }
            ?: IntArray(pixelCount).also { filterPreviewBuf = it }
        layer.content.toPixelArray(src)

        when (blurType) {
            BLUR_GAUSSIAN -> {
                val buf = filterPreviewBuf2?.let { if (it.size >= pixelCount) it else null }
                    ?: IntArray(pixelCount).also { filterPreviewBuf2 = it }
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
            if (undoStack.size > MemoryConfig.maxUndoEntries) {
                releaseUndoEntry(undoStack.removeAt(0))
            }
            PaintDebug.d(PaintDebug.Layer) { "[commitFilterPreview] layerId=${layer.id}" }
        } else {
            releaseSnapshot(snaps)
        }
        filterPreviewSnapshot = null
        filterPreviewLayerId = -1
        filterPreviewBuf = null  // プレビュー終了時にバッファ解放
        filterPreviewBuf2 = null
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
        filterPreviewBuf = null
        filterPreviewBuf2 = null
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
        if (undoStack.size > MemoryConfig.maxUndoEntries) {
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
        if (undoStack.size > MemoryConfig.maxUndoEntries) undoStack.removeAt(0)
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
            is UndoEntry.Structural -> {
                // snapshot() で incRef されたタイル参照を解放
                for (snap in entry.snapshots) {
                    snap.surface.clear() // 内部で全タイルを decRef → null にする
                }
            }
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

    /** 合成済み全ピクセル (エクスポート用)。lock 内で backBuffer から読み取る。 */
    fun getCompositePixels(): IntArray {
        rebuildCompositeCache()
        val out = IntArray(width * height)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val data = backBuffer[ty * tilesX + tx] ?: continue
            val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
            for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= height) break
                System.arraycopy(data, ly * Tile.SIZE, out, py * width + bx, minOf(Tile.SIZE, width - bx))
            }
        }
        return out
    }

    // ── 選択ツール統合 ──────────────────────────────────────────

    val selectionManager: SelectionManager by lazy { SelectionManager(width, height) }

    /**
     * 選択範囲外のピクセルを COW スナップショットから復元する (タイル単位)。
     * toPixelArray() を使わないため、メモリ効率が大幅に向上。
     */
    private fun restoreOutsideSelectionFromSurface(surface: TiledSurface, original: TiledSurface) {
        val w = width; val h = height
        val sm = selectionManager
        for (ty in 0 until surface.tilesY) {
            for (tx in 0 until surface.tilesX) {
                val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
                var needsRestore = false
                // このタイル内に選択範囲外ピクセルがあるかチェック
                check@ for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        if (sm.getMaskValue(px, py) < 255) { needsRestore = true; break@check }
                    }
                }
                if (!needsRestore) continue
                val origTile = original.getTile(tx, ty)
                val tile = surface.getOrCreateMutable(tx, ty)
                for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        val maskVal = sm.getMaskValue(px, py)
                        if (maskVal == 255) continue
                        val idx = ly * Tile.SIZE + lx
                        val origPixel = origTile?.pixels?.get(idx) ?: 0
                        if (maskVal == 0) {
                            tile.pixels[idx] = origPixel
                        } else {
                            val newPixel = tile.pixels[idx]
                            tile.pixels[idx] = blendSelectionPixel(origPixel, newPixel, maskVal / 255f)
                        }
                    }
                }
            }
        }
    }

    /**
     * 選択範囲外のピクセルを元の状態に復元する (IntArray 版)。
     * フィルター適用 (applyFilterWithSelection) で使用。
     */
    private fun restoreOutsideSelection(surface: TiledSurface, originalPixels: IntArray) {
        val w = width; val h = height
        val sm = selectionManager
        for (ty in 0 until surface.tilesY) {
            for (tx in 0 until surface.tilesX) {
                val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
                var needsRestore = false
                // このタイル内に選択範囲外ピクセルがあるかチェック
                check@ for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        if (sm.getMaskValue(px, py) < 255) { needsRestore = true; break@check }
                    }
                }
                if (!needsRestore) continue
                val tile = surface.getOrCreateMutable(tx, ty)
                for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        val maskVal = sm.getMaskValue(px, py)
                        if (maskVal == 255) continue  // 完全選択 → 変更後の値を維持
                        val idx = ly * Tile.SIZE + lx
                        val origPixel = originalPixels[py * w + px]
                        if (maskVal == 0) {
                            // 選択範囲外 → 元に戻す
                            tile.pixels[idx] = origPixel
                        } else {
                            // 部分選択 → ブレンド
                            val newPixel = tile.pixels[idx]
                            tile.pixels[idx] = blendSelectionPixel(origPixel, newPixel, maskVal / 255f)
                        }
                    }
                }
            }
        }
    }

    /**
     * サブレイヤーの選択範囲外ピクセルをゼロクリアする。
     * Indirect ブラシ (筆/水彩) が選択境界でサンプリングした範囲外色を
     * 合成に持ち込まないようにする。
     */
    private fun maskSublayerWithSelection(sub: TiledSurface) {
        val w = width; val h = height
        val sm = selectionManager
        for (ty in 0 until sub.tilesY) {
            for (tx in 0 until sub.tilesX) {
                val tile = sub.getTile(tx, ty) ?: continue
                val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
                var modified = false
                for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        val maskVal = sm.getMaskValue(px, py)
                        if (maskVal == 255) continue  // 完全選択 → そのまま
                        val idx = ly * Tile.SIZE + lx
                        if (maskVal == 0) {
                            // 選択範囲外 → ゼロクリア
                            if (tile.pixels[idx] != 0) {
                                tile.pixels[idx] = 0
                                modified = true
                            }
                        } else {
                            // 部分選択 → マスク値に応じて減衰
                            val p = tile.pixels[idx]
                            if (p != 0) {
                                val f = maskVal / 255f
                                tile.pixels[idx] = PixelOps.pack(
                                    (PixelOps.alpha(p) * f).toInt().coerceIn(0, 255),
                                    (PixelOps.red(p) * f).toInt().coerceIn(0, 255),
                                    (PixelOps.green(p) * f).toInt().coerceIn(0, 255),
                                    (PixelOps.blue(p) * f).toInt().coerceIn(0, 255),
                                )
                                modified = true
                            }
                        }
                    }
                }
            }
        }
    }

    /** 2ピクセルを選択マスク値でブレンドする */
    private fun blendSelectionPixel(orig: Int, modified: Int, selAlpha: Float): Int {
        val ia = 1f - selAlpha
        return PixelOps.pack(
            (PixelOps.alpha(orig) * ia + PixelOps.alpha(modified) * selAlpha).toInt().coerceIn(0, 255),
            (PixelOps.red(orig) * ia + PixelOps.red(modified) * selAlpha).toInt().coerceIn(0, 255),
            (PixelOps.green(orig) * ia + PixelOps.green(modified) * selAlpha).toInt().coerceIn(0, 255),
            (PixelOps.blue(orig) * ia + PixelOps.blue(modified) * selAlpha).toInt().coerceIn(0, 255),
        )
    }

    /**
     * フィルタをピクセル配列に適用し、選択マスクを考慮してレイヤーに書き戻す。
     * 選択がない場合はフィルタ結果をそのまま書き戻す。
     */
    private fun applyFilterWithSelection(layer: Layer, action: (IntArray) -> Unit) {
        val pixels = layer.content.toPixelArray()
        val originalPixels = if (selectionManager.hasSelection) pixels.copyOf() else null
        action(pixels)
        if (originalPixels != null) {
            // 選択範囲外のピクセルを元に戻す
            val w = width; val h = height
            for (i in pixels.indices) {
                val y = i / w; val x = i % w
                val maskVal = selectionManager.getMaskValue(x, y)
                if (maskVal == 255) continue
                if (maskVal == 0) {
                    pixels[i] = originalPixels[i]
                } else {
                    pixels[i] = blendSelectionPixel(originalPixels[i], pixels[i], maskVal / 255f)
                }
            }
        }
        writePixelsToLayer(layer, pixels, width, height)
    }

    // ── レイヤーグループ ────────────────────────────────────────

    // LinkedHashMap で挿入順序（UI での表示順）を保持
    private val _layerGroups = LinkedHashMap<Int, LayerGroupInfo>()
    val layerGroups: Map<Int, LayerGroupInfo> get() = _layerGroups
    private var nextGroupId = 1

    fun createLayerGroup(name: String): LayerGroupInfo = lock.withLock {
        // デフォルト名の場合は一意な番号を付与
        val finalName = if (name == "フォルダ") {
            val count = _layerGroups.count { it.value.name.startsWith("フォルダ") }
            "フォルダ ${count + 1}"
        } else {
            name
        }
        val group = LayerGroupInfo(nextGroupId++, finalName)
        _layerGroups[group.id] = group
        PaintDebug.d(PaintDebug.Layer) { "[createLayerGroup] id=${group.id} name=$finalName" }
        group
    }

    /**
     * PSD インポート用: 外部からグループを直接登録する。
     * nextGroupId も更新して ID 衝突を防ぐ。
     */
    fun importLayerGroup(group: LayerGroupInfo) = lock.withLock {
        _layerGroups[group.id] = group
        if (group.id >= nextGroupId) nextGroupId = group.id + 1
        PaintDebug.d(PaintDebug.Layer) { "[importLayerGroup] id=${group.id} name=${group.name}" }
    }

    fun deleteLayerGroup(groupId: Int) = lock.withLock {
        _layerGroups.remove(groupId)
        // グループ内のレイヤーをグループ解除
        for (layer in _layers) {
            if (layer.groupId == groupId) layer.groupId = 0
        }
        PaintDebug.d(PaintDebug.Layer) { "[deleteLayerGroup] id=$groupId" }
    }

    fun setLayerGroup(layerId: Int, groupId: Int) = lock.withLock {
        val layer = _layers.find { it.id == layerId } ?: return
        layer.groupId = groupId
    }

    fun setGroupVisibility(groupId: Int, visible: Boolean) = lock.withLock {
        _layerGroups[groupId]?.isVisible = visible
        dirtyTracker.markFullRebuild()
    }

    fun setGroupOpacity(groupId: Int, opacity: Float) = lock.withLock {
        _layerGroups[groupId]?.opacity = opacity.coerceIn(0f, 1f)
        dirtyTracker.markFullRebuild()
    }

    fun reorderLayerGroup(fromGroupId: Int, toGroupId: Int) = lock.withLock {
        if (!_layerGroups.containsKey(fromGroupId) || !_layerGroups.containsKey(toGroupId)) return
        if (fromGroupId == toGroupId) return

        val groupList = _layerGroups.keys.toMutableList()
        val fromIdx = groupList.indexOf(fromGroupId)
        val toIdx = groupList.indexOf(toGroupId)

        if (fromIdx < 0 || toIdx < 0) return

        // fromGroupId を削除して toIdx の位置に再挿入
        groupList.removeAt(fromIdx)
        val newToIdx = if (fromIdx < toIdx) toIdx - 1 else toIdx
        groupList.add(newToIdx, fromGroupId)

        // LinkedHashMap を再構築
        val newGroups = LinkedHashMap<Int, LayerGroupInfo>()
        for (id in groupList) {
            newGroups[id] = _layerGroups[id]!!
        }

        _layerGroups.clear()
        _layerGroups.putAll(newGroups)
        PaintDebug.d(PaintDebug.Layer) { "[reorderLayerGroup] from=$fromGroupId to=$toGroupId" }
    }

    // ── レイヤーマスク ──────────────────────────────────────────

    fun addLayerMask(layerId: Int, fillWhite: Boolean = true) = lock.withLock {
        val layer = _layers.find { it.id == layerId } ?: return
        if (layer.mask != null) return  // 既にマスクがある
        layer.mask = LayerMaskOps.createMask(width, height, fillWhite)
        layer.isMaskEnabled = true
        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[addLayerMask] layerId=$layerId fillWhite=$fillWhite" }
    }

    fun removeLayerMask(layerId: Int) = lock.withLock {
        val layer = _layers.find { it.id == layerId } ?: return
        layer.mask = null
        layer.isMaskEnabled = false
        layer.isEditingMask = false
        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[removeLayerMask] layerId=$layerId" }
    }

    fun toggleMaskEnabled(layerId: Int) = lock.withLock {
        val layer = _layers.find { it.id == layerId } ?: return
        layer.isMaskEnabled = !layer.isMaskEnabled
        dirtyTracker.markFullRebuild()
    }

    fun toggleEditMask(layerId: Int) = lock.withLock {
        val layer = _layers.find { it.id == layerId } ?: return
        layer.isEditingMask = !layer.isEditingMask
    }

    fun addMaskFromSelection(layerId: Int) = lock.withLock {
        val layer = _layers.find { it.id == layerId } ?: return
        if (!selectionManager.hasSelection) return
        layer.mask = LayerMaskOps.createMaskFromSelection(selectionManager)
        layer.isMaskEnabled = true
        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[addMaskFromSelection] layerId=$layerId" }
    }

    // ── 選択範囲操作 ──────────────────────────────────────────────

    /** 選択範囲内のピクセルを削除（透明にする） */
    fun deleteSelection(layerId: Int) = lock.withLock {
        if (!selectionManager.hasSelection) return
        val layer = _layers.find { it.id == layerId } ?: return
        if (layer.isLocked) return
        pushUndoTileDelta(layer)
        val mask = selectionManager.mask ?: return
        val w = width; val h = height
        for (ty in 0 until layer.content.tilesY) {
            for (tx in 0 until layer.content.tilesX) {
                layer.content.tiles[ty * layer.content.tilesX + tx] ?: continue
                val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
                val mutable = layer.content.getOrCreateMutable(tx, ty)
                for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        val maskVal = mask[py * w + px].toInt() and 0xFF
                        if (maskVal > 0) {
                            val idx = ly * Tile.SIZE + lx
                            if (maskVal >= 255) {
                                mutable.pixels[idx] = 0
                            } else {
                                // 部分選択: アルファをマスク値で減算
                                val orig = mutable.pixels[idx]
                                if (orig != 0) {
                                    val factor = (255 - maskVal) / 255f
                                    val a = (PixelOps.alpha(orig) * factor).toInt().coerceIn(0, 255)
                                    val r = (PixelOps.red(orig) * factor).toInt().coerceIn(0, a)
                                    val g = (PixelOps.green(orig) * factor).toInt().coerceIn(0, a)
                                    val b = (PixelOps.blue(orig) * factor).toInt().coerceIn(0, a)
                                    mutable.pixels[idx] = PixelOps.pack(a, r, g, b)
                                }
                            }
                        }
                    }
                }
            }
        }
        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[deleteSelection] layerId=$layerId" }
    }

    /** 選択範囲内を指定色で塗りつぶす */
    fun fillSelection(layerId: Int, color: Int) = lock.withLock {
        if (!selectionManager.hasSelection) return
        val layer = _layers.find { it.id == layerId } ?: return
        if (layer.isLocked) return
        pushUndoTileDelta(layer)
        val mask = selectionManager.mask ?: return
        val premulColor = PixelOps.premultiply(color)
        val w = width; val h = height
        for (y in 0 until h) {
            for (x in 0 until w) {
                val maskVal = mask[y * w + x].toInt() and 0xFF
                if (maskVal == 0) continue
                val tx = x / Tile.SIZE; val ty = y / Tile.SIZE
                val tile = layer.content.getOrCreateMutable(tx, ty)
                val lx = x - tx * Tile.SIZE; val ly = y - ty * Tile.SIZE
                val idx = ly * Tile.SIZE + lx
                if (maskVal >= 255) {
                    tile.pixels[idx] = premulColor
                } else {
                    // 部分選択: マスク値でブレンド
                    val existing = tile.pixels[idx]
                    tile.pixels[idx] = PixelOps.blendSrcOverOpacity(existing, premulColor, maskVal)
                }
            }
        }
        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[fillSelection] layerId=$layerId color=${Integer.toHexString(color)}" }
    }

    /** 選択範囲内のピクセルを新規レイヤーにコピー */
    fun copySelection(layerId: Int): Layer? = lock.withLock {
        if (!selectionManager.hasSelection) return null
        val layer = _layers.find { it.id == layerId } ?: return null
        val mask = selectionManager.mask ?: return null
        val w = width; val h = height
        val newLayer = Layer(nextLayerId++, "${layer.name} コピー", TiledSurface(w, h))
        for (y in 0 until h) {
            for (x in 0 until w) {
                val maskVal = mask[y * w + x].toInt() and 0xFF
                if (maskVal == 0) continue
                val pixel = layer.content.getPixelAt(x, y)
                if (pixel == 0) continue
                val tx = x / Tile.SIZE; val ty = y / Tile.SIZE
                val tile = newLayer.content.getOrCreateMutable(tx, ty)
                val lx = x - tx * Tile.SIZE; val ly = y - ty * Tile.SIZE
                if (maskVal >= 255) {
                    tile.pixels[ly * Tile.SIZE + lx] = pixel
                } else {
                    val factor = maskVal / 255f
                    val a = (PixelOps.alpha(pixel) * factor).toInt().coerceIn(0, 255)
                    val r = (PixelOps.red(pixel) * factor).toInt().coerceIn(0, a)
                    val g = (PixelOps.green(pixel) * factor).toInt().coerceIn(0, a)
                    val b = (PixelOps.blue(pixel) * factor).toInt().coerceIn(0, a)
                    tile.pixels[ly * Tile.SIZE + lx] = PixelOps.pack(a, r, g, b)
                }
            }
        }
        val insertIdx = _layers.indexOf(layer) + 1
        _layers.add(insertIdx, newLayer)
        activeLayerId = newLayer.id
        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[copySelection] from=${layer.id} new=${newLayer.id}" }
        return newLayer
    }

    /** 選択範囲内のピクセルを切り取って新規レイヤーに移動 */
    fun cutSelection(layerId: Int): Layer? = lock.withLock {
        if (!selectionManager.hasSelection) return null
        val layer = _layers.find { it.id == layerId } ?: return null
        if (layer.isLocked) return null
        pushUndoTileDelta(layer)
        val newLayer = copySelection(layerId) ?: return null
        // 元レイヤーから選択範囲内のピクセルを削除
        val mask = selectionManager.mask ?: return newLayer
        val w = width; val h = height
        for (ty in 0 until layer.content.tilesY) {
            for (tx in 0 until layer.content.tilesX) {
                layer.content.tiles[ty * layer.content.tilesX + tx] ?: continue
                val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
                val mutable = layer.content.getOrCreateMutable(tx, ty)
                for (ly in 0 until Tile.SIZE) {
                    val py = by + ly; if (py >= h) break
                    for (lx in 0 until Tile.SIZE) {
                        val px = bx + lx; if (px >= w) break
                        val maskVal = mask[py * w + px].toInt() and 0xFF
                        if (maskVal > 0) {
                            val idx = ly * Tile.SIZE + lx
                            if (maskVal >= 255) {
                                mutable.pixels[idx] = 0
                            } else {
                                val orig = mutable.pixels[idx]
                                if (orig != 0) {
                                    val factor = (255 - maskVal) / 255f
                                    val a = (PixelOps.alpha(orig) * factor).toInt().coerceIn(0, 255)
                                    val r = (PixelOps.red(orig) * factor).toInt().coerceIn(0, a)
                                    val g = (PixelOps.green(orig) * factor).toInt().coerceIn(0, a)
                                    val b = (PixelOps.blue(orig) * factor).toInt().coerceIn(0, a)
                                    mutable.pixels[idx] = PixelOps.pack(a, r, g, b)
                                }
                            }
                        }
                    }
                }
            }
        }
        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[cutSelection] from=${layer.id} new=${newLayer.id}" }
        return newLayer
    }

    /** 選択範囲内のピクセルを平行移動 */
    fun moveSelection(layerId: Int, dx: Int, dy: Int) = lock.withLock {
        if (!selectionManager.hasSelection) return
        if (dx == 0 && dy == 0) return
        val layer = _layers.find { it.id == layerId } ?: return
        if (layer.isLocked) return
        pushUndoTileDelta(layer)
        val mask = selectionManager.mask ?: return
        val w = width; val h = height

        // 選択範囲内のピクセルを抽出
        val extracted = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val maskVal = mask[y * w + x].toInt() and 0xFF
                if (maskVal == 0) continue
                val pixel = layer.content.getPixelAt(x, y)
                if (pixel == 0) continue
                if (maskVal >= 255) {
                    extracted[y * w + x] = pixel
                } else {
                    val factor = maskVal / 255f
                    val a = (PixelOps.alpha(pixel) * factor).toInt().coerceIn(0, 255)
                    val r = (PixelOps.red(pixel) * factor).toInt().coerceIn(0, a)
                    val g = (PixelOps.green(pixel) * factor).toInt().coerceIn(0, a)
                    val b = (PixelOps.blue(pixel) * factor).toInt().coerceIn(0, a)
                    extracted[y * w + x] = PixelOps.pack(a, r, g, b)
                }
            }
        }

        // 元レイヤーから選択範囲内を消去
        for (y in 0 until h) {
            for (x in 0 until w) {
                val maskVal = mask[y * w + x].toInt() and 0xFF
                if (maskVal == 0) continue
                val tx = x / Tile.SIZE; val ty = y / Tile.SIZE
                layer.content.tiles[ty * layer.content.tilesX + tx] ?: continue
                val mutable = layer.content.getOrCreateMutable(tx, ty)
                val lx = x - tx * Tile.SIZE; val ly = y - ty * Tile.SIZE
                val idx = ly * Tile.SIZE + lx
                if (maskVal >= 255) {
                    mutable.pixels[idx] = 0
                } else {
                    val orig = mutable.pixels[idx]
                    if (orig != 0) {
                        val factor = (255 - maskVal) / 255f
                        val a = (PixelOps.alpha(orig) * factor).toInt().coerceIn(0, 255)
                        val r = (PixelOps.red(orig) * factor).toInt().coerceIn(0, a)
                        val g = (PixelOps.green(orig) * factor).toInt().coerceIn(0, a)
                        val b = (PixelOps.blue(orig) * factor).toInt().coerceIn(0, a)
                        mutable.pixels[idx] = PixelOps.pack(a, r, g, b)
                    }
                }
            }
        }

        // 移動先に書き込み
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = extracted[y * w + x]
                if (pixel == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val tx = nx / Tile.SIZE; val ty = ny / Tile.SIZE
                val tile = layer.content.getOrCreateMutable(tx, ty)
                val lx = nx - tx * Tile.SIZE; val ly = ny - ty * Tile.SIZE
                tile.pixels[ly * Tile.SIZE + lx] = pixel
            }
        }

        // 選択マスクも移動
        val newMask = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = mask[y * w + x]
                if (v.toInt() == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                newMask[ny * w + nx] = v
            }
        }
        selectionManager.setMask(newMask)

        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[moveSelection] layerId=$layerId dx=$dx dy=$dy" }
    }

    // ── 変形操作 ────────────────────────────────────────────────

    fun transformLayer(
        layerId: Int, centerX: Float, centerY: Float,
        scaleX: Float, scaleY: Float, angleDeg: Float,
        translateX: Float, translateY: Float,
    ) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        val selMask = getSelectionMaskForTransform()
        applyTransformWithSelection(layer) {
            TransformManager.freeTransform(layer.content, centerX, centerY, scaleX, scaleY, angleDeg, translateX, translateY, selMask)
        }
        dirtyTracker.markFullRebuild()
    }

    fun flipLayerH(layerId: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        val selMask = getSelectionMaskForTransform()
        applyTransformWithSelection(layer) {
            TransformManager.flipHorizontal(layer.content, selMask)
        }
        dirtyTracker.markFullRebuild()
    }

    fun flipLayerV(layerId: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        val selMask = getSelectionMaskForTransform()
        applyTransformWithSelection(layer) {
            TransformManager.flipVertical(layer.content, selMask)
        }
        dirtyTracker.markFullRebuild()
    }

    fun rotateLayer90CW(layerId: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        val selMask = getSelectionMaskForTransform()
        applyTransformWithSelection(layer) {
            TransformManager.rotate90CW(layer.content, selMask)
        }
        dirtyTracker.markFullRebuild()
    }

    /** ディストート（パースペクティブ変形） */
    fun distortLayer(layerId: Int, corners: FloatArray) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        val selMask = getSelectionMaskForTransform()
        applyTransformWithSelection(layer) {
            TransformManager.distort(layer.content, corners, selMask)
        }
        dirtyTracker.markFullRebuild()
    }

    /** メッシュワープ変形 */
    fun meshWarpLayer(layerId: Int, gridW: Int, gridH: Int, nodes: FloatArray) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        val selMask = getSelectionMaskForTransform()
        applyTransformWithSelection(layer) {
            TransformManager.meshWarp(layer.content, gridW, gridH, nodes, selMask)
        }
        dirtyTracker.markFullRebuild()
    }

    /** リキファイ（ゆがみ） */
    fun liquifyLayer(layerId: Int, cx: Float, cy: Float, radius: Float,
                     dirX: Float, dirY: Float, pressure: Float, mode: Int) = lock.withLock {
        val layer = _layers.find { it.id == layerId } ?: return
        if (layer.isLocked) return
        val selMask = getSelectionMaskForTransform()
        TransformManager.liquify(layer.content, cx, cy, radius, dirX, dirY, pressure, mode, selMask)
        dirtyTracker.markFullRebuild()
    }

    /** リキファイ開始 (Undo 用スナップショット) */
    fun beginLiquify(layerId: Int) = lock.withLock {
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
    }

    /**
     * 変形操作を選択マスク付きで実行する。
     * 選択マスク情報を TransformManager に渡し、選択範囲内のピクセルのみを処理。
     * 変形後に選択範囲外のピクセルを元に戻す。
     */
    private inline fun applyTransformWithSelection(layer: Layer, action: () -> Unit) {
        val originalPixels = if (selectionManager.hasSelection) {
            layer.content.toPixelArray()
        } else null

        action()

        if (originalPixels != null) {
            restoreOutsideSelection(layer.content, originalPixels)
        }
    }

    /** TransformManager 用選択マスク取得 */
    private fun getSelectionMaskForTransform(): ByteArray? {
        if (!selectionManager.hasSelection) return null
        val mask = selectionManager.mask ?: return null
        return mask.copyOf()
    }

    // ── 図形描画 ────────────────────────────────────────────────

    fun drawShape(
        layerId: Int, shapeType: String,
        left: Int, top: Int, right: Int, bottom: Int,
        color: Int, fill: Boolean, thickness: Float = 1f,
    ) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        if (layer.isLocked) return
        pushUndoTileDelta(layer)
        when (shapeType) {
            "line" -> ShapeRenderer.drawLine(layer.content, left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), color, thickness)
            "rect" -> if (fill) ShapeRenderer.fillRect(layer.content, left, top, right, bottom, color)
                      else ShapeRenderer.drawRect(layer.content, left, top, right, bottom, color, thickness)
            "ellipse" -> if (fill) ShapeRenderer.fillEllipse(layer.content, left, top, right, bottom, color)
                         else ShapeRenderer.drawEllipse(layer.content, left, top, right, bottom, color, thickness)
        }
        dirtyTracker.markFullRebuild()
    }

    fun floodFill(layerId: Int, x: Int, y: Int, color: Int, tolerance: Int = 0) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        if (layer.isLocked) return
        pushUndoTileDelta(layer)
        ShapeRenderer.floodFill(layer.content, x, y, color, tolerance)
        dirtyTracker.markFullRebuild()
    }

    fun drawGradient(
        layerId: Int, startX: Float, startY: Float, endX: Float, endY: Float,
        startColor: Int, endColor: Int, type: ShapeRenderer.GradientType,
    ) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        if (layer.isLocked) return
        pushUndoTileDelta(layer)
        ShapeRenderer.drawGradient(layer.content, startX, startY, endX, endY, startColor, endColor, type)
        dirtyTracker.markFullRebuild()
    }

    // ── テキストレイヤー ────────────────────────────────────────

    fun addTextLayer(config: TextRenderer.TextConfig): Layer = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = Layer(nextLayerId++, "テキスト: ${config.text.take(10)}", TiledSurface(width, height))
        layer.textConfig = config
        TextRenderer.renderText(layer.content, config)
        _layers.add(layer)
        activeLayerId = layer.id
        dirtyTracker.markFullRebuild()
        PaintDebug.d(PaintDebug.Layer) { "[addTextLayer] id=${layer.id} text='${config.text.take(20)}'" }
        layer
    }

    fun updateTextLayer(layerId: Int, config: TextRenderer.TextConfig) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        layer.textConfig = config
        layer.content.clear()
        TextRenderer.renderText(layer.content, config)
        dirtyTracker.markFullRebuild()
    }

    // ── 追加フィルター ──────────────────────────────────────────

    fun applyToneCurve(
        layerId: Int,
        masterCurve: IntArray? = null,
        redCurve: IntArray? = null,
        greenCurve: IntArray? = null,
        blueCurve: IntArray? = null,
    ) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        applyFilterWithSelection(layer) { pixels ->
            FilterOps.applyToneCurve(pixels, masterCurve, redCurve, greenCurve, blueCurve)
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyLevels(
        layerId: Int, inBlack: Int = 0, inWhite: Int = 255,
        gamma: Float = 1f, outBlack: Int = 0, outWhite: Int = 255,
    ) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        applyFilterWithSelection(layer) { pixels ->
            FilterOps.applyLevels(pixels, inBlack, inWhite, gamma, outBlack, outWhite)
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyColorBalance(layerId: Int, cyanRed: Int, magentaGreen: Int, yellowBlue: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        applyFilterWithSelection(layer) { pixels ->
            FilterOps.applyColorBalance(pixels, cyanRed, magentaGreen, yellowBlue)
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyUnsharpMask(layerId: Int, radius: Int = 1, amount: Float = 1f, threshold: Int = 0) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        applyFilterWithSelection(layer) { pixels ->
            FilterOps.applyUnsharpMask(pixels, width, height, radius, amount, threshold)
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyMosaic(layerId: Int, blockSize: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        applyFilterWithSelection(layer) { pixels ->
            FilterOps.applyMosaic(pixels, width, height, blockSize)
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyNoise(layerId: Int, amount: Int, monochrome: Boolean = true) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        applyFilterWithSelection(layer) { pixels ->
            FilterOps.applyNoise(pixels, amount, monochrome)
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyPosterize(layerId: Int, levels: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        applyFilterWithSelection(layer) { pixels ->
            FilterOps.applyPosterize(pixels, levels)
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyThreshold(layerId: Int, threshold: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        applyFilterWithSelection(layer) { pixels ->
            FilterOps.applyThreshold(pixels, threshold)
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyGradientMap(layerId: Int, gradientLut: IntArray) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        applyFilterWithSelection(layer) { pixels ->
            FilterOps.applyGradientMap(pixels, gradientLut)
        }
        dirtyTracker.markFullRebuild()
    }

    // ─── ピクセルコピー変形（Word/Excel風）────────────────────

    /**
     * 選択範囲内のピクセルをコピーしバッファに保存、元のピクセルを削除（切り取り）
     * @return バウンディングボックス {left, top, right, bottom} を Map で返す
     */
    fun startPixelCopy(layerId: Int): android.graphics.Rect = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return android.graphics.Rect()
        val boundsArray = selectionManager.getBounds() ?: return android.graphics.Rect()

        pushUndoTileDelta(layer)

        // boundsArray = [left, top, right, bottom]
        val left = boundsArray[0]
        val top = boundsArray[1]
        val right = boundsArray[2]
        val bottom = boundsArray[3]

        // バウンディングボックス内のピクセルをコピー
        val copyWidth = right - left
        val copyHeight = bottom - top
        val totalPixels = copyWidth * copyHeight
        pixelCopyBuffer = IntArray(totalPixels)
        pixelCopyOriginalPixels = IntArray(totalPixels) // キャンセル用

        var pixIdx = 0
        for (ty in top until bottom) {
            for (tx in left until right) {
                val tileX = tx / 64
                val tileY = ty / 64
                val inTileX = tx % 64
                val inTileY = ty % 64
                val tileIndex = tileY * ((width + 63) / 64) + tileX
                val tile = layer.content.tiles.getOrNull(tileIndex)
                val pixelVal = tile?.pixels?.get(inTileY * 64 + inTileX) ?: 0
                pixelCopyBuffer!![pixIdx] = pixelVal
                pixelCopyOriginalPixels!![pixIdx] = pixelVal
                pixIdx++
            }
        }

        // オリジナルのピクセルを削除（選択範囲を削除）
        deleteSelection(layerId)
        pixelCopyBounds = android.graphics.Rect(left, top, right, bottom)
        dirtyTracker.markFullRebuild()

        return pixelCopyBounds!!
    }

    /**
     * ピクセルコピーを確定（変形後のピクセルをレイヤーに貼り付け）
     * @param x, y: 左上位置
     * @param scaleX, scaleY: スケール倍率
     * @param rotation: 回転角度（度数法）
     */
    fun applyPixelCopy(
        layerId: Int,
        x: Int,
        y: Int,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        rotation: Float = 0f,
    ) = lock.withLock {
        val layer = _layers.find { it.id == layerId } ?: return
        val buffer = pixelCopyBuffer ?: return
        val bounds = pixelCopyBounds ?: return

        val copyWidth = bounds.right - bounds.left
        val copyHeight = bounds.bottom - bounds.top

        // 簡略実装：変形なし。実装時はバイリニア変換で対応
        var pixIdx = 0
        for (dy in 0 until copyHeight) {
            for (dx in 0 until copyWidth) {
                val pixelVal = buffer[pixIdx]
                val px = x + dx
                val py = y + dy

                if (px in 0 until width && py in 0 until height) {
                    val tileX = px / 64
                    val tileY = py / 64
                    val inTileX = px % 64
                    val inTileY = py % 64
                    val tileIndex = tileY * ((width + 63) / 64) + tileX
                    val tile = layer.content.tiles.getOrNull(tileIndex)
                    tile?.pixels?.set(inTileY * 64 + inTileX, pixelVal)
                }
                pixIdx++
            }
        }

        pixelCopyBuffer = null
        pixelCopyBounds = null
        pixelCopyOriginalPixels = null
        dirtyTracker.markFullRebuild()
    }

    /** ピクセルコピーをキャンセル（オリジナルピクセルを復元） */
    fun cancelPixelCopy() = lock.withLock {
        // Undo/Redo で対応可能なため、ここでは単にバッファをクリア
        pixelCopyBuffer = null
        pixelCopyBounds = null
        pixelCopyOriginalPixels = null
    }
}
