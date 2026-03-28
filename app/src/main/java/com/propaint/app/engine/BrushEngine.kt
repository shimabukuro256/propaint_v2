package com.propaint.app.engine

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ブラシエンジン。
 *
 * ダブ色決定 → ダブ配置 → サブレイヤーフィルタ(筆/水彩) の順で処理。
 * 筆/水彩の混色は blurAreaOnSurface のぼかしフィルタで実現する。
 */
class BrushEngine(
    private val dirtyTracker: DirtyTileTracker,
) {
    // ── ストローク状態 ──────────────────────────────────────────────

    private var distToNextDab = 0f
    private val pointBuffer = ArrayList<StrokePoint>(512)

    // ストローク開始時のレイヤー ID (レイヤー切替バグ修正)
    var strokeLayerId: Int = -1
        private set

    // ── per-dab ブラー用バッファ (GC 回避のため再利用) ─────────────
    private var blurSrc = IntArray(0)
    private var blurTmp = IntArray(0)
    private var blurDst = IntArray(0)
    private var blurSubSrc = IntArray(0)  // サブレイヤー単独ブラー用
    private var blurSubDst = IntArray(0)
    private var blurSubTmp = IntArray(0)

    // リニアライト空間ブラー用バッファ (sRGB 空間で平均化すると色が濁るため)
    private var blurLinSrc = LongArray(0)
    private var blurLinTmp = LongArray(0)
    private var blurLinDst = LongArray(0)
    private var blurLinSubSrc = LongArray(0)
    private var blurLinSubDst = LongArray(0)
    private var blurLinSubTmp = LongArray(0)

    // ── 進行方向追従: 前回ダブ位置 ────────────────────────────────
    private var prevDabX = Float.NaN
    private var prevDabY = Float.NaN

    // ── 手振れ補正 (リアルタイム EMA フィルタ) ─────────────────────
    // 指数移動平均: 入力座標と筆圧をリアルタイムに平滑化。
    // stabilizer=0 でバイパス、stabilizer=1 で最大平滑化。
    // 後補正ではないため遅延は最小限。
    private var stabX = 0f
    private var stabY = 0f
    private var stabPressure = 0f
    private var stabInitialized = false

    data class StrokePoint(
        val x: Float, val y: Float,
        val pressure: Float = 1f,
        val timestamp: Long = 0L,
    )

    /** ストローク開始 */
    fun beginStroke(layerId: Int) {
        require(layerId > 0) { "strokeLayerId must be positive, got $layerId" }
        distToNextDab = 0f
        pointBuffer.clear()
        strokeLayerId = layerId
        stabInitialized = false
        prevDabX = Float.NaN; prevDabY = Float.NaN
        PaintDebug.d(PaintDebug.Brush) { "[beginStroke] layerId=$layerId" }
    }

    /**
     * ストロークにポイントを追加し、ダブを配置する。
     *
     * @param drawTarget  描画先サーフェス (Indirect→sublayer, Direct→layer.content)
     * @param sampleSource blur サンプリング元 (常に layer.content)
     */
    fun addPoint(
        point: StrokePoint,
        drawTarget: TiledSurface,
        sampleSource: TiledSurface,
        brush: BrushConfig,
    ) {
        // 防御的チェック: NaN/Infinity 座標は無視
        if (point.x.isNaN() || point.x.isInfinite() || point.y.isNaN() || point.y.isInfinite()) {
            PaintDebug.assertFail("StrokePoint has NaN/Inf: x=${point.x} y=${point.y}")
            return
        }
        // 防御的チェック: pressure 範囲
        val safePoint = if (point.pressure !in 0f..1f) {
            PaintDebug.assertFail("pressure out of range: ${point.pressure}")
            point.copy(pressure = point.pressure.coerceIn(0f, 1f))
        } else point

        // 防御的チェック: ブラシパラメータ
        if (brush.size <= 0f) {
            PaintDebug.assertFail("brush.size <= 0: ${brush.size}")
            return
        }
        if (brush.hardness !in 0f..1f) {
            PaintDebug.assertFail("brush.hardness out of range: ${brush.hardness}")
        }
        if (brush.spacing <= 0f) {
            PaintDebug.assertFail("brush.spacing <= 0: ${brush.spacing}, would cause infinite loop")
            return
        }

        // ── 手振れ補正: リアルタイム EMA フィルタ ──
        // stabilizer=0 → alpha=1.0 (バイパス)
        // stabilizer=1 → alpha≈0.05 (強い平滑化)
        // 座標と筆圧の両方を平滑化する。
        val smoothedPoint = if (brush.stabilizer > 0.001f) {
            // alpha: 小さいほど強い平滑化 (0.05..1.0)
            val alpha = (1f - brush.stabilizer * 0.95f).coerceIn(0.05f, 1f)
            if (!stabInitialized) {
                stabX = safePoint.x; stabY = safePoint.y
                stabPressure = safePoint.pressure
                stabInitialized = true
                safePoint
            } else {
                stabX += (safePoint.x - stabX) * alpha
                stabY += (safePoint.y - stabY) * alpha
                stabPressure += (safePoint.pressure - stabPressure) * alpha
                safePoint.copy(x = stabX, y = stabY, pressure = stabPressure)
            }
        } else safePoint

        pointBuffer.add(smoothedPoint)
        if (pointBuffer.size < 2) return

        val fromIdx = maxOf(0, pointBuffer.size - 2)
        distToNextDab = renderSegments(
            pointBuffer, fromIdx, distToNextDab,
            drawTarget, sampleSource, brush, applyExitTaper = false,
        )
    }

    fun endStroke() {
        PaintDebug.d(PaintDebug.Brush) { "[endStroke] layerId=$strokeLayerId points=${pointBuffer.size}" }
        pointBuffer.clear()
        distToNextDab = 0f
        strokeLayerId = -1
    }

    /** ベイク用: 全ストロークを再描画 */
    fun renderFullStroke(
        points: List<StrokePoint>,
        drawTarget: TiledSurface,
        sampleSource: TiledSurface,
        brush: BrushConfig,
    ) {
        if (points.size < 2) return
        prevDabX = Float.NaN; prevDabY = Float.NaN
        renderSegments(points, 0, 0f, drawTarget, sampleSource, brush, applyExitTaper = true)
    }

    // ── コア: セグメント描画 ─────────────────────────────────────────

    private fun renderSegments(
        pts: List<StrokePoint>,
        fromSegIdx: Int,
        initialDist: Float,
        drawTarget: TiledSurface,
        sampleSource: TiledSurface,
        brush: BrushConfig,
        applyExitTaper: Boolean,
    ): Float {
        val nomRad = brush.size / 2f
        val spRad = minOf(nomRad, sqrt(nomRad * 30f))
        val stepDist = maxOf(1f, spRad * 2f * brush.spacing)
        val effSpacing = (spRad / nomRad * brush.spacing).coerceAtLeast(0.001f)

        // Indirect モード (sublayer あり) かつ opacity=1.0 の場合:
        // spacing 補正を省略し rawDensity をそのまま使う。
        // sublayer 上の SrcOver 蓄積が自然に 255 へ飽和 → 100% 不透明ストロークになる。
        // (補正を掛けると整数量子化の漸近誤差で 98% 程度に留まる)
        val actualIndirect = brush.indirect && !brush.isEraser && !brush.isBlur
        val skipCompensation = (actualIndirect && brush.opacity >= 1f) || brush.isMarker

        // 累積距離 (テーパー用)
        val cumDist = FloatArray(pts.size)
        for (k in 1 until pts.size) {
            val dx = pts[k].x - pts[k - 1].x; val dy = pts[k].y - pts[k - 1].y
            cumDist[k] = cumDist[k - 1] + sqrt(dx * dx + dy * dy)
        }
        val totalLen = if (pts.isNotEmpty()) cumDist.last() else 0f
        // テーパー長: ブラシサイズに比例 (大きいブラシでも入りぬきが十分に効く)
        // 上限はブラシ半径の12倍 or 2000px のいずれか小さい方
        val taperLen = if (brush.taperEnabled && totalLen > 0f)
            (nomRad * 8f).coerceIn(20f, maxOf(400f, nomRad * 12f).coerceAtMost(2000f))
                .coerceAtMost(totalLen * 0.45f) else 0f

        // ブレンドモードID
        val blendId = when {
            brush.isEraser -> PixelOps.BLEND_ERASE
            brush.isMarker -> PixelOps.BLEND_MARKER
            else -> PixelOps.BLEND_NORMAL
        }

        var dist = initialDist

        for (i in fromSegIdx until pts.size - 1) {
            val p0 = pts[i]; val p1 = pts[i + 1]
            val pPrev = pts[maxOf(0, i - 1)]
            val pNext = pts[minOf(pts.size - 1, i + 2)]
            val dx = p1.x - p0.x; val dy = p1.y - p0.y
            val segLen = sqrt(dx * dx + dy * dy)
            if (segLen < 0.001f) continue

            while (dist <= segLen) {
                val t = (dist / segLen).coerceIn(0f, 1f)

                // Catmull-Rom
                val px = catmullRom(pPrev.x, p0.x, p1.x, pNext.x, t)
                val py = catmullRom(pPrev.y, p0.y, p1.y, pNext.y, t)
                val pressure = p0.pressure + (p1.pressure - p0.pressure) * t

                // 筆圧→サイズ (最小 0.5 = 直径1px)
                // ぼかし筆圧閾値が設定されている場合、閾値時点のサイズを下限とする
                // (閾値以下でもブラシサイズが極端に縮小しない)
                val pRad = if (brush.pressureSizeEnabled) {
                    val minRad = 0.5f
                    val curve = pressureCurve(pressure, brush.pressureSizeIntensity)
                    val rad = minRad + (nomRad - minRad) * curve
                    if (brush.blurPressureThreshold > 0f && pressure < brush.blurPressureThreshold) {
                        val threshCurve = pressureCurve(brush.blurPressureThreshold, brush.pressureSizeIntensity)
                        val threshRad = minRad + (nomRad - minRad) * threshCurve
                        maxOf(rad, threshRad)
                    } else rad
                } else nomRad

                // 筆圧→不透明度
                val pAlpha = if (brush.pressureOpacityEnabled)
                    pressureCurve(pressure, brush.pressureOpacityIntensity)
                else 1f

                // テーパー
                val dabDist = cumDist[i] + dist
                var taper = 1f
                if (taperLen > 0f) {
                    if (dabDist < taperLen) taper = smoothStep(dabDist / taperLen)
                    if (applyExitTaper) {
                        val fromEnd = totalLen - dabDist
                        if (fromEnd < taperLen) taper *= smoothStep(fromEnd / taperLen)
                    }
                }

                val finalRad = pRad * taper
                if (finalRad < 0.25f) { dist += stepDist; continue }

                // ── ぼかし筆圧判定 ──────────────────────────────────────────
                val blurThresh = brush.blurPressureThreshold
                val belowBlurThreshold = blurThresh > 0f && pressure < blurThresh
                // 閾値超過時のフェードイン: 閾値〜閾値+遷移幅の間で描画色を緩やかに導入
                val blurFadeRange = maxOf(0.05f, blurThresh * 0.5f)
                val colorFade = if (blurThresh <= 0f || pressure >= blurThresh + blurFadeRange) 1f
                    else if (pressure <= blurThresh) 0f
                    else smoothStep((pressure - blurThresh) / blurFadeRange)

                // ── ダブ色決定 ────────────────────────────────────────────
                val dabColor: Int = when {
                    // Blur: キャンバス色をサンプリング
                    brush.isBlur -> {
                        sampleSource.sampleColorAt(
                            px.toInt(), py.toInt(),
                            maxOf(2, (finalRad * brush.blurStrength).toInt()),
                        )
                    }
                    // ぼかし筆圧閾値以下: ダブ無し (フィルタのみ適用)
                    belowBlurThreshold -> 0
                    // フェードイン区間: premultiplied 色を均一にスケール
                    colorFade < 1f -> {
                        val f = (colorFade * 255f).toInt().coerceIn(0, 255)
                        val c = brush.colorPremul
                        PixelOps.pack(
                            PixelOps.div255(PixelOps.alpha(c) * f),
                            PixelOps.div255(PixelOps.red(c) * f),
                            PixelOps.div255(PixelOps.green(c) * f),
                            PixelOps.div255(PixelOps.blue(c) * f))
                    }
                    // 通常: 描画色そのまま (混色は blurAreaOnSurface で行う)
                    else -> brush.colorPremul
                }

                // 筆圧→濃度
                val pDensity = if (brush.pressureDensityEnabled)
                    brush.density * pressureCurve(pressure, brush.pressureDensityIntensity)
                else brush.density

                // ダブ不透明度
                val rawDensity = pDensity * pAlpha * taper
                if (rawDensity < 1f / 512f) { dist += stepDist; continue } // 実質的に不可視
                val dabDensity = if (skipCompensation) rawDensity
                    else spacingCompensate(rawDensity, effSpacing)
                // Direct モードの Marker は sublayer 合成がないため、
                // brush.opacity をダブ不透明度に直接乗算する。
                val directOpacity = if (brush.isMarker) brush.opacity else 1f
                val finalOpacity = (dabDensity * directOpacity * 255f).toInt().coerceIn(1, 255)

                // ── サブレイヤーフィルタ付きダブ配置 ──────────────────────
                val hasFilter = brush.sublayerFilter != BrushConfig.SUBLAYER_FILTER_NONE
                val filterRadius = maxOf(1, (nomRad * brush.filterRadiusScale).toInt())

                // ダブマスク生成 & 進行方向に垂直な縁の濃度低減
                val dab = DabMaskGenerator.createDab(px, py, finalRad * 2f, brush.hardness)
                if (dab != null && hasFilter) {
                    applyDirectionalEdgeFade(dab, px, py, finalRad)
                }
                if (dab != null && dabColor != 0) {
                    applyDabToSurface(drawTarget, dab, dabColor, finalOpacity, blendId)
                }
                // ダブ配置後にフィルタ適用 (筆/水彩の混色効果)
                if (hasFilter) {
                    blurAreaOnSurface(drawTarget, sampleSource, px.toInt(), py.toInt(),
                        filterRadius, brush.sublayerFilter)
                }
                prevDabX = px; prevDabY = py

                dist += stepDist
            }
            dist -= segLen
        }
        return dist
    }

    // ── ダブ → タイル ────────────────────────────────────────────────

    private fun applyDabToSurface(
        surface: TiledSurface, dab: DabMask,
        color: Int, opacity: Int, blendMode: Int,
    ) {
        check(dab.diameter > 0) { "dab diameter must be > 0, got ${dab.diameter}" }
        check(dab.data.size == dab.diameter * dab.diameter) {
            "dab data size mismatch: expected ${dab.diameter * dab.diameter}, got ${dab.data.size}"
        }
        check(opacity in 0..255) { "opacity out of range: $opacity" }
        val dr = dab.left + dab.diameter; val db = dab.top + dab.diameter
        val tx0 = maxOf(0, surface.pixelToTile(dab.left))
        val ty0 = maxOf(0, surface.pixelToTile(dab.top))
        val tx1 = minOf(surface.tilesX - 1, surface.pixelToTile(dr - 1))
        val ty1 = minOf(surface.tilesY - 1, surface.pixelToTile(db - 1))

        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val tile = surface.getOrCreateMutable(tx, ty)
            val ox = tx * Tile.SIZE; val oy = ty * Tile.SIZE
            PixelOps.applyDabToTile(
                tile.pixels, dab.data, dab.diameter, color, opacity, blendMode,
                maxOf(0, dab.left - ox), maxOf(0, dab.top - oy),
                minOf(Tile.SIZE, dr - ox), minOf(Tile.SIZE, db - oy),
                dab.left - ox, dab.top - oy,
            )
            dirtyTracker.markDirty(tx, ty)
        }
    }

    // ── 局所ぼかし (筆/水彩 per-dab フィルタ) ────────────────────────

    /**
     * ダブ配置後にダブ周辺を局所ぼかしし、既存色とストローク色を混色する。
     *
     * ■ Direct モード (drawTarget === sampleSource):
     *   content を直接読み取り → ぼかし → 円形フォールオフ付きで content に書き戻し。
     *   sublayer を介さないため逆合成 (unComposite) は不要。
     *   シンプルかつ安定した混色を実現。
     *
     * ■ Indirect モード (drawTarget !== sampleSource):
     *   sublayer + content の合成をぼかし、sublayer に書き戻す従来方式。
     *
     * O(n) per pixel の分離ボックスブラー + 円形フォールオフ。
     * - AVERAGING: 2パス (三角カーネル近似 = 滑らかな混色)
     * - BOX_BLUR: 1パス (にじみ効果)
     */
    private fun blurAreaOnSurface(
        drawTarget: TiledSurface, sampleSource: TiledSurface,
        cx: Int, cy: Int, radius: Int, filterType: Int,
    ) {
        val safeRadius = radius.coerceIn(1, 2000)
        val x0 = maxOf(0, cx - safeRadius)
        val y0 = maxOf(0, cy - safeRadius)
        val x1 = minOf(drawTarget.width - 1, cx + safeRadius)
        val y1 = minOf(drawTarget.height - 1, cy + safeRadius)
        if (x0 > x1 || y0 > y1) return

        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val size = w * h

        // バッファ再利用 (GC 回避)
        if (blurSrc.size < size) {
            blurSrc = IntArray(size); blurTmp = IntArray(size); blurDst = IntArray(size)
        }
        if (blurLinSrc.size < size) {
            blurLinSrc = LongArray(size); blurLinTmp = LongArray(size); blurLinDst = LongArray(size)
        }

        val passes = if (filterType == BrushConfig.SUBLAYER_FILTER_AVERAGING) 2 else 1
        val kr = maxOf(1, safeRadius / 3)

        // ── Direct モード: content を直接ぼかし (リニアライト空間) ────
        if (drawTarget === sampleSource) {
            // 入力: sRGB → リニア変換
            for (ly in 0 until h) for (lx in 0 until w) {
                val px = drawTarget.getPixelAt(x0 + lx, y0 + ly)
                blurSrc[ly * w + lx] = px
                blurLinSrc[ly * w + lx] = PixelOps.pixelToLinear64(px)
            }

            // 分離ボックスブラー (リニア空間)
            System.arraycopy(blurLinSrc, 0, blurLinDst, 0, size)
            repeat(passes) {
                separableBoxBlurPassLinear(blurLinDst, blurLinTmp, w, h, kr)
                System.arraycopy(blurLinTmp, 0, blurLinDst, 0, size)
            }

            // リニア → sRGB 変換
            for (i in 0 until size) {
                blurDst[i] = PixelOps.linear64ToPixel(blurLinDst[i])
            }

            // 円形フォールオフ付きで content に書き戻し
            val r2 = safeRadius * safeRadius
            val fadeStart2 = (safeRadius * 0.7f).let { it * it }
            val fadeRange = r2 - fadeStart2
            for (ly in 0 until h) for (lx in 0 until w) {
                val gdx = (x0 + lx) - cx; val gdy = (y0 + ly) - cy
                val dist2 = gdx * gdx + gdy * gdy
                if (dist2 >= r2) continue

                val idx = ly * w + lx
                val original = blurSrc[idx]
                val blurred = blurDst[idx]

                // フォールオフ: 中心=ぼかし結果、縁=元のピクセル
                val pixel = if (dist2 <= fadeStart2) {
                    blurred
                } else {
                    val t = ((dist2 - fadeStart2) / fadeRange).coerceIn(0f, 1f)
                    PixelOps.lerpColor(blurred, original, t * t)
                }

                val gpx = x0 + lx; val gpy = y0 + ly
                val tx = drawTarget.pixelToTile(gpx)
                val ty = drawTarget.pixelToTile(gpy)
                if (tx < 0 || tx >= drawTarget.tilesX || ty < 0 || ty >= drawTarget.tilesY) continue
                val tile = drawTarget.getOrCreateMutable(tx, ty)
                tile.pixels[(gpy - ty * Tile.SIZE) * Tile.SIZE + (gpx - tx * Tile.SIZE)] = pixel
                dirtyTracker.markDirty(tx, ty)
            }
            return
        }

        // ── Indirect モード: sublayer + content 合成ぼかし (リニアライト空間) ──
        // (Airbrush 等 indirect=true のブラシ用。筆/水彩は上の Direct パスを使用)
        if (blurSubSrc.size < size) {
            blurSubSrc = IntArray(size); blurSubDst = IntArray(size); blurSubTmp = IntArray(size)
        }
        if (blurLinSubSrc.size < size) {
            blurLinSubSrc = LongArray(size); blurLinSubDst = LongArray(size); blurLinSubTmp = LongArray(size)
        }

        for (ly in 0 until h) for (lx in 0 until w) {
            val px = x0 + lx; val py = y0 + ly
            val subPx = drawTarget.getPixelAt(px, py)
            val contPx = sampleSource.getPixelAt(px, py)
            val idx = ly * w + lx
            val comp = PixelOps.blendSrcOver(contPx, subPx)
            blurSrc[idx] = comp
            blurLinSrc[idx] = PixelOps.pixelToLinear64(comp)
            val mask = if (PixelOps.alpha(subPx) >= PixelOps.alpha(contPx)) subPx else contPx
            blurSubSrc[idx] = mask
            blurLinSubSrc[idx] = PixelOps.pixelToLinear64(mask)
        }

        // 分離ボックスブラー (リニア空間): 合成版 (RGB 混色用)
        System.arraycopy(blurLinSrc, 0, blurLinDst, 0, size)
        repeat(passes) {
            separableBoxBlurPassLinear(blurLinDst, blurLinTmp, w, h, kr)
            System.arraycopy(blurLinTmp, 0, blurLinDst, 0, size)
        }
        for (i in 0 until size) blurDst[i] = PixelOps.linear64ToPixel(blurLinDst[i])

        // 分離ボックスブラー (リニア空間): マスク用
        System.arraycopy(blurLinSubSrc, 0, blurLinSubDst, 0, size)
        repeat(passes) {
            separableBoxBlurPassLinear(blurLinSubDst, blurLinSubTmp, w, h, kr)
            System.arraycopy(blurLinSubTmp, 0, blurLinSubDst, 0, size)
        }
        for (i in 0 until size) blurSubDst[i] = PixelOps.linear64ToPixel(blurLinSubDst[i])

        // 円形フォールオフ付きでサブレイヤーに書き戻し
        val r2 = safeRadius * safeRadius
        val fadeStart2 = (safeRadius * 0.7f).let { it * it }
        val fadeRange = r2 - fadeStart2
        for (ly in 0 until h) for (lx in 0 until w) {
            val gdx = (x0 + lx) - cx; val gdy = (y0 + ly) - cy
            val dist2 = gdx * gdx + gdy * gdy
            if (dist2 >= r2) continue

            val idx = ly * w + lx
            val compositeOrig = blurSrc[idx]
            val compositeBlur = blurDst[idx]
            val subOrig = blurSubSrc[idx]
            val subBlur = blurSubDst[idx]

            val bsa: Int
            if (dist2 <= fadeStart2) {
                bsa = PixelOps.alpha(subBlur)
            } else {
                val t = ((dist2 - fadeStart2) / fadeRange).coerceIn(0f, 1f)
                val tSq = t * t
                val aBlur = PixelOps.alpha(subBlur)
                val aOrig = PixelOps.alpha(subOrig)
                bsa = (aBlur + ((aOrig - aBlur) * tSq).toInt()).coerceIn(0, 255)
            }
            if (bsa == 0) continue

            val blendedComposite = if (dist2 <= fadeStart2) {
                compositeBlur
            } else {
                val t = ((dist2 - fadeStart2) / fadeRange).coerceIn(0f, 1f)
                PixelOps.lerpColor(compositeBlur, compositeOrig, t * t)
            }

            val gpx = x0 + lx; val gpy = y0 + ly
            val contPx = sampleSource.getPixelAt(gpx, gpy)
            val subPx = PixelOps.unComposite(blendedComposite, contPx)

            val tx = drawTarget.pixelToTile(gpx)
            val ty = drawTarget.pixelToTile(gpy)
            if (tx < 0 || tx >= drawTarget.tilesX || ty < 0 || ty >= drawTarget.tilesY) continue
            val tile = drawTarget.getOrCreateMutable(tx, ty)
            tile.pixels[(gpy - ty * Tile.SIZE) * Tile.SIZE + (gpx - tx * Tile.SIZE)] = subPx
            dirtyTracker.markDirty(tx, ty)
        }
    }

    /**
     * 分離ボックスブラー 1パス (水平→垂直)。
     * スライディングウィンドウで O(n) — 半径に依存しない。
     */
    private fun separableBoxBlurPass(
        input: IntArray, output: IntArray, w: Int, h: Int, kr: Int,
    ) {
        val d = kr * 2 + 1

        // ── 水平パス: input → output ──
        for (y in 0 until h) {
            var aAcc = 0L; var rAcc = 0L; var gAcc = 0L; var bAcc = 0L
            val row = y * w
            for (x in -kr..kr) {
                val c = input[row + x.coerceIn(0, w - 1)]
                aAcc += PixelOps.alpha(c); rAcc += PixelOps.red(c)
                gAcc += PixelOps.green(c); bAcc += PixelOps.blue(c)
            }
            for (x in 0 until w) {
                output[row + x] = PixelOps.pack(
                    (aAcc / d).toInt(), (rAcc / d).toInt(),
                    (gAcc / d).toInt(), (bAcc / d).toInt(),
                )
                val addX = (x + kr + 1).coerceAtMost(w - 1)
                val remX = (x - kr).coerceAtLeast(0)
                val ac = input[row + addX]; val rc = input[row + remX]
                aAcc += PixelOps.alpha(ac) - PixelOps.alpha(rc)
                rAcc += PixelOps.red(ac) - PixelOps.red(rc)
                gAcc += PixelOps.green(ac) - PixelOps.green(rc)
                bAcc += PixelOps.blue(ac) - PixelOps.blue(rc)
            }
        }

        // ── 垂直パス: output を in-place で上書き ──
        // 垂直は列単位なので temp コピーが必要 → input を temp として再利用
        System.arraycopy(output, 0, input, 0, w * h)
        for (x in 0 until w) {
            var aAcc = 0L; var rAcc = 0L; var gAcc = 0L; var bAcc = 0L
            for (y in -kr..kr) {
                val c = input[y.coerceIn(0, h - 1) * w + x]
                aAcc += PixelOps.alpha(c); rAcc += PixelOps.red(c)
                gAcc += PixelOps.green(c); bAcc += PixelOps.blue(c)
            }
            for (y in 0 until h) {
                output[y * w + x] = PixelOps.pack(
                    (aAcc / d).toInt(), (rAcc / d).toInt(),
                    (gAcc / d).toInt(), (bAcc / d).toInt(),
                )
                val addY = (y + kr + 1).coerceAtMost(h - 1)
                val remY = (y - kr).coerceAtLeast(0)
                val ac = input[addY * w + x]; val rc = input[remY * w + x]
                aAcc += PixelOps.alpha(ac) - PixelOps.alpha(rc)
                rAcc += PixelOps.red(ac) - PixelOps.red(rc)
                gAcc += PixelOps.green(ac) - PixelOps.green(rc)
                bAcc += PixelOps.blue(ac) - PixelOps.blue(rc)
            }
        }
    }

    /**
     * 分離ボックスブラー 1パス (リニアライト空間版)。
     * Long パックレイアウト: A(16) | R(16) | G(16) | B(16)。
     * sRGB 空間での平均化による色の濁りを防止。
     */
    private fun separableBoxBlurPassLinear(
        input: LongArray, output: LongArray, w: Int, h: Int, kr: Int,
    ) {
        val d = kr * 2 + 1

        // ── 水平パス: input → output ──
        for (y in 0 until h) {
            var aAcc = 0L; var rAcc = 0L; var gAcc = 0L; var bAcc = 0L
            val row = y * w
            for (x in -kr..kr) {
                val c = input[row + x.coerceIn(0, w - 1)]
                aAcc += (c ushr 48) and 0xFFFF; rAcc += (c ushr 32) and 0xFFFF
                gAcc += (c ushr 16) and 0xFFFF; bAcc += c and 0xFFFF
            }
            for (x in 0 until w) {
                val oa = (aAcc / d); val or_ = (rAcc / d)
                val og = (gAcc / d); val ob = (bAcc / d)
                output[row + x] = (oa shl 48) or ((or_ and 0xFFFF) shl 32) or
                    ((og and 0xFFFF) shl 16) or (ob and 0xFFFF)
                val addX = (x + kr + 1).coerceAtMost(w - 1)
                val remX = (x - kr).coerceAtLeast(0)
                val ac = input[row + addX]; val rc = input[row + remX]
                aAcc += ((ac ushr 48) and 0xFFFF) - ((rc ushr 48) and 0xFFFF)
                rAcc += ((ac ushr 32) and 0xFFFF) - ((rc ushr 32) and 0xFFFF)
                gAcc += ((ac ushr 16) and 0xFFFF) - ((rc ushr 16) and 0xFFFF)
                bAcc += (ac and 0xFFFF) - (rc and 0xFFFF)
            }
        }

        // ── 垂直パス: output を in-place で上書き (input を temp として再利用) ──
        System.arraycopy(output, 0, input, 0, w * h)
        for (x in 0 until w) {
            var aAcc = 0L; var rAcc = 0L; var gAcc = 0L; var bAcc = 0L
            for (y in -kr..kr) {
                val c = input[y.coerceIn(0, h - 1) * w + x]
                aAcc += (c ushr 48) and 0xFFFF; rAcc += (c ushr 32) and 0xFFFF
                gAcc += (c ushr 16) and 0xFFFF; bAcc += c and 0xFFFF
            }
            for (y in 0 until h) {
                val oa = (aAcc / d); val or_ = (rAcc / d)
                val og = (gAcc / d); val ob = (bAcc / d)
                output[y * w + x] = (oa shl 48) or ((or_ and 0xFFFF) shl 32) or
                    ((og and 0xFFFF) shl 16) or (ob and 0xFFFF)
                val addY = (y + kr + 1).coerceAtMost(h - 1)
                val remY = (y - kr).coerceAtLeast(0)
                val ac = input[addY * w + x]; val rc = input[remY * w + x]
                aAcc += ((ac ushr 48) and 0xFFFF) - ((rc ushr 48) and 0xFFFF)
                rAcc += ((ac ushr 32) and 0xFFFF) - ((rc ushr 32) and 0xFFFF)
                gAcc += ((ac ushr 16) and 0xFFFF) - ((rc ushr 16) and 0xFFFF)
                bAcc += (ac and 0xFFFF) - (rc and 0xFFFF)
            }
        }
    }

    // ── ヘルパー ─────────────────────────────────────────────────────

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    private fun smoothStep(t: Float): Float {
        val c = t.coerceIn(0f, 1f); return c * c * (3f - 2f * c)
    }

    /**
     * 進行方向に対して垂直な縁の濃度を低減。
     * ダブ間の重なりで生じるバンディングを軽減する。
     * ストローク方向を (prevDabX,Y → dabX,Y) から求め、
     * 各マスクピクセルの垂直距離に応じて濃度を減衰。
     */
    private fun applyDirectionalEdgeFade(dab: DabMask, dabX: Float, dabY: Float, radius: Float) {
        if (prevDabX.isNaN() || radius < 1f) return
        val ddx = dabX - prevDabX; val ddy = dabY - prevDabY
        val dirLen = sqrt(ddx * ddx + ddy * ddy)
        if (dirLen < 0.5f) return
        // 垂直方向の単位ベクトル (-dy, dx) / len
        val perpX = -ddy / dirLen; val perpY = ddx / dirLen
        val d = dab.diameter
        val r = radius
        val fadeStart = 0.80f  // 半径の 80% から減衰開始
        val fadeStrength = 0.05f // 最縁で最大 5% 減衰
        for (y in 0 until d) {
            val oy = y - d / 2f + 0.5f
            val ro = y * d
            for (x in 0 until d) {
                val v = dab.data[ro + x]; if (v <= 0) continue
                val ox = x - d / 2f + 0.5f
                // 垂直方向への射影 (絶対値 = 進行方向と直交する距離)
                val perpDist = kotlin.math.abs(perpX * ox + perpY * oy) / r
                if (perpDist <= fadeStart) continue
                val t = smoothStep((perpDist - fadeStart) / (1f - fadeStart))
                dab.data[ro + x] = (v * (1f - fadeStrength * t)).toInt().coerceAtLeast(0)
            }
        }
    }

    /**
     * 筆圧カーブ with intensity (Drawpile DP_ClassicBrushCurve 簡易版)
     * intensity=100 → gamma=0.65 (標準)
     */
    private fun pressureCurve(p: Float, intensity: Int = 100): Float {
        val gamma = if (intensity <= 100) 0.1f + (intensity - 1) / 99f * 0.55f
        else 0.65f + (intensity - 100) / 100f * 1.35f
        return p.coerceIn(0f, 1f).toDouble().pow(gamma.toDouble()).toFloat()
    }

    /**
     * SrcOver 累積で target density に一致するよう per-dab 不透明度を下げる補正。
     *
     * 下限 0.04: effSpacing が極小のとき compensated が 1/255 以下に潰れ、
     *   mask × opacity の大部分が 0 にクランプされてダブが消失する量子化バグを防ぐ。
     *   density=0.4 / s=0.04 → compensated≈0.020 → finalOpacity≈5 (十分な精度)。
     * 上限 1.0: ダブが重ならない間隔では補正不要 (identity)。
     */
    private fun spacingCompensate(density: Float, spacing: Float): Float {
        val s = spacing.coerceIn(0.04f, 1f)
        return (1f - (1f - density).toDouble().pow(s.toDouble()).toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * ブラシ設定。v1 の BrushSettings を Drawpile DP_ClassicBrush に寄せて再設計。
 */
data class BrushConfig(
    val size: Float = 10f,
    val opacity: Float = 1f,
    val density: Float = 0.8f,
    val spacing: Float = 0.1f,
    val hardness: Float = 0.8f,
    val colorPremul: Int = 0xFF000000.toInt(),
    // ── Drawpile 準拠フラグ ──
    val isEraser: Boolean = false,
    val isMarker: Boolean = false,
    val isBlur: Boolean = false,
    /** ぼかし強度 (blur 用: サンプリング半径の倍率) */
    val blurStrength: Float = 1f,
    /** Drawpile paint_mode: true=Indirect(Wash), false=Direct */
    val indirect: Boolean = true,
    // ── 筆圧設定 ──
    val pressureSizeEnabled: Boolean = true,
    val pressureOpacityEnabled: Boolean = false,
    val pressureDensityEnabled: Boolean = false,
    val pressureSizeIntensity: Int = 100,
    val pressureOpacityIntensity: Int = 100,
    val pressureDensityIntensity: Int = 100,
    val minSizeRatio: Float = 0.2f,
    // ── テーパー ──
    val taperEnabled: Boolean = true,
    // ── 水彩 ──
    val waterContent: Float = 0f,
    val colorStretch: Float = 0f,
    // ── AA ──
    val antiAliasing: Float = 1f,
    // ── 手振れ補正 (0=なし, 1=最大) ──
    val stabilizer: Float = 0f,
    // ── サブレイヤーフィルタ (筆/水彩の混色用) ──
    /** SUBLAYER_FILTER_NONE / SUBLAYER_FILTER_AVERAGING / SUBLAYER_FILTER_BOX_BLUR */
    val sublayerFilter: Int = SUBLAYER_FILTER_NONE,
    /** フィルタ半径の倍率 (筆=1.5, 水彩=1.0) */
    val filterRadiusScale: Float = 1f,
    // ── ぼかし筆圧 (Fude/Watercolor 用) ──
    /** 筆圧がこの閾値を下回ると描画色が 0 になりスマッジ専用になる */
    val blurPressureThreshold: Float = 0f,
) {
    companion object {
        const val SUBLAYER_FILTER_NONE = 0
        const val SUBLAYER_FILTER_AVERAGING = 1  // 筆: 平均化フィルタ
        const val SUBLAYER_FILTER_BOX_BLUR = 2   // 水彩: ボックスブラー

        fun fromColor(argb: Int, size: Float = 10f, hardness: Float = 0.8f) =
            BrushConfig(size = size, hardness = hardness,
                colorPremul = PixelOps.premultiply(argb))
    }
}
