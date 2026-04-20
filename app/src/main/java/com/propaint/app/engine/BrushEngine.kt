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
    var selectionManager: SelectionManager? = null,
) {
    // ── ストローク状態 ──────────────────────────────────────────────

    private var distToNextDab = 0f
    private val pointBuffer = ArrayList<StrokePoint>(512)

    // ストローク開始時のレイヤー ID (レイヤー切替バグ修正)
    var strokeLayerId: Int = -1
        private set

    // ── per-dab ブラー用バッファ (GC 回避のため再利用) ─────────────
    private var blurSrc = IntArray(0)
    private var blurDst = IntArray(0)
    private var blurSubSrc = IntArray(0)  // サブレイヤー単独ブラー用
    private var blurSubDst = IntArray(0)

    // リニアライト空間 IIR ブラー用バッファ (sRGB 空間で平均化すると色が濁るため)
    private var blurLin = LongArray(0)       // IIR 作業用 (インプレース)
    private var blurLinSub = LongArray(0)    // サブレイヤー IIR 作業用

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
    private var stabPointCount = 0

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
        stabPointCount = 0
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

        // ── Lazy Nezumi 的スタビライザー実装 ──
        // stabilizer: 0.0～1.0 で段階的に平滑化
        // 特徴:
        //   1. 柔軟な平滑化（強度に応じて調整）
        //   2. 予測補正で描画遅延を最小化
        //   3. 最小移動距離でポイント圧縮（ジャギー防止）
        val smoothedPoint = if (brush.stabilizer > 0.001f) {
            val stabStrength = brush.stabilizer.coerceIn(0f, 1f)

            // ── 座標の平滑化係数（stabilizer 0.0-1.0）──
            // stabilizer=0.0 → alpha=1.0 (平滑化なし)
            // stabilizer=1.0 → alpha=0.05 (強い平滑化)
            val alpha = 1f - (stabStrength * 0.95f)

            // ── 筆圧の平滑化係数（座標より弱めに）──
            // 筆圧の急激な変化（入りぬき）を保持
            val pressureAlpha = alpha + (1f - alpha) * 0.5f  // 座標より弱い

            if (!stabInitialized) {
                stabX = safePoint.x
                stabY = safePoint.y
                stabPressure = safePoint.pressure
                stabPointCount = 0
                stabInitialized = true
                safePoint
            } else {
                // ── キャッチアップ: 差分が大きいとき素早く追いつく ──
                val dx = safePoint.x - stabX
                val dy = safePoint.y - stabY
                val distSq = dx * dx + dy * dy
                // 差分が大きいときはアルファを大きくしてキャッチアップ
                val catchUpFactor = if (distSq > 100f) {
                    val dist = sqrt(distSq)
                    (1f + (dist - 10f) / 50f).coerceIn(1f, 2f)  // 1.0-2.0倍
                } else 1f

                // ── EMA フィルタで座標と筆圧を平滑化 ──
                val adjustedAlpha = alpha * catchUpFactor
                stabX += (safePoint.x - stabX) * adjustedAlpha.coerceIn(0f, 1f)
                stabY += (safePoint.y - stabY) * adjustedAlpha.coerceIn(0f, 1f)
                stabPressure += (safePoint.pressure - stabPressure) * pressureAlpha
                stabPointCount++

                // ── 入り抜き補正: 自動テーパー ──
                var finalPressure = stabPressure
                if (stabStrength > 0.2f) {
                    // ── 入り: 最初のポイント群で筆圧を 0 から素早く立ち上げる（キャッチアップ強化）──
                    val taperStrength = stabStrength.coerceIn(0f, 1f)
                    val entryPoints = (4f + taperStrength * 16f).toInt()  // 4-20ポイント

                    if (stabPointCount < entryPoints) {
                        val ratio = stabPointCount.toFloat() / entryPoints
                        // より強い非線形カーブ（4乗）で素早く立ち上げる
                        val curve = ratio * ratio * ratio * ratio
                        finalPressure = stabPressure * curve  // 0 から stabPressure へ
                    }

                    // ── 抜き: 筆圧低下を加速して先端を尖らせる ──
                    if (stabPointCount >= entryPoints) {
                        val drop = stabPressure - safePoint.pressure
                        if (drop > 0.01f) {
                            // 低下を加速（stabilizer 高いほど強く）
                            val accel = 2f + taperStrength * 2f  // 2.0-4.0倍
                            finalPressure = (stabPressure - drop * accel).coerceAtLeast(0f)
                            stabPressure = finalPressure
                        }
                    }
                }

                safePoint.copy(x = stabX, y = stabY, pressure = finalPressure.coerceIn(0f, 1f))
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

        // ── サイズ依存の自動間隔調整 ──
        // 基準値: 半径 25px (サイズ 50) での spacing = 0.1f
        // サイズが小さい場合は spacing を詰め、大きい場合は拡大してパフォーマンス維持
        val baseRad = 25f
        val radScale = nomRad / baseRad  // 基準値(25)との比率
        val autoSpacing = when {
            radScale < 0.4f -> {
                // 小ブラシ (半径 < 10px): spacing を詰める (√ でスケール)
                (brush.spacing / sqrt(4f / radScale)).coerceAtLeast(0.02f)
            }
            radScale < 1f -> {
                // 中小ブラシ (10-25px): 線形補間で緩やかに調整
                brush.spacing * radScale
            }
            radScale < 8f -> {
                // 中大ブラシ (25-200px): √ でスケール (緩やか)
                (brush.spacing * sqrt(radScale)).coerceAtMost(0.3f)
            }
            else -> {
                // 大ブラシ (200px以上): パフォーマンス重視で √ スケーリング + 上限緩和
                (brush.spacing * sqrt(radScale * 0.75f)).coerceAtMost(0.6f)
            }
        }

        val spRad = minOf(nomRad, sqrt(nomRad * 30f))
        // 細線 (半径 ≤ 4px) ではサブピクセル間隔を許可しジャギーを抑制
        val minStep = if (nomRad <= 4f) 0.25f else 1f
        val baseStepDist = maxOf(minStep, spRad * 2f * autoSpacing)
        val effSpacing = (spRad / nomRad * autoSpacing).coerceAtLeast(0.001f)
        // 筆圧でサイズが縮小した際に間隔を適応的に縮める
        // nomRad が大きい時に低筆圧で隙間が空く問題を解消
        val adaptiveSpacing = brush.pressureSizeEnabled && nomRad > 10f

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
        var stepDist = baseStepDist

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

                // 筆圧→サイズ (最小: minBrushSizePercent% または直径1px)
                // ぼかし筆圧閾値が設定されている場合、閾値時点のサイズを下限とする
                // (閾値以下でもブラシサイズが極端に縮小しない)
                val pRad = if (brush.pressureSizeEnabled) {
                    val minRad = maxOf(0.5f, nomRad * brush.minBrushSizePercent / 100f)
                    val curve = pressureCurve(pressure, brush.pressureSizeIntensity)
                    val rad = minRad + (nomRad - minRad) * curve
                    if (brush.blurPressureThreshold > 0f && pressure < brush.blurPressureThreshold) {
                        val threshCurve = pressureCurve(brush.blurPressureThreshold, brush.pressureSizeIntensity)
                        val threshRad = minRad + (nomRad - minRad) * threshCurve
                        maxOf(rad, threshRad)
                    } else rad
                } else nomRad

                // 適応的間隔: 筆圧で縮小した実半径に基づいてステップ距離を再計算
                // 大きいブラシで低筆圧時の隙間を防ぐ
                if (adaptiveSpacing) {
                    val ratio = (pRad / nomRad).coerceIn(0.05f, 1f)
                    // ratio が小さいほど間隔を縮める (sqrt で緩やかに遷移)
                    val adaptFactor = sqrt(ratio)
                    stepDist = maxOf(minStep, baseStepDist * adaptFactor)
                }

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
                // 適応間隔時は実効 spacing も再計算して濃度補正を維持
                val curEffSpacing = if (adaptiveSpacing) {
                    val ratio = (pRad / nomRad).coerceIn(0.05f, 1f)
                    (sqrt(ratio) * effSpacing).coerceAtLeast(0.001f)
                } else effSpacing
                val dabDensity = if (skipCompensation) rawDensity
                    else spacingCompensate(rawDensity, curEffSpacing)
                // Direct モードの Marker は sublayer 合成がないため、
                // brush.opacity をダブ不透明度に直接乗算する。
                val directOpacity = if (brush.isMarker) brush.opacity else 1f
                val finalOpacity = (dabDensity * directOpacity * 255f).toInt().coerceIn(1, 255)

                // ── サブレイヤーフィルタ付きダブ配置 ──────────────────────
                val hasFilter = brush.sublayerFilter != BrushConfig.SUBLAYER_FILTER_NONE
                // filterRadius: 筆圧で変更された実サイズ (pRad) に基づいて計算
                // テーパー適用後のサイズを使用し、筆圧でぼかしもスケーリング
                val filterRadius = maxOf(1, (finalRad * brush.filterRadiusScale).toInt())

                // ダブマスク生成（通常の円形ブラシ）
                // Lazy Nezumi 的補正は点の平滑化と予測で実現
                val dab = DabMaskGenerator.createDab(px, py, finalRad * 2f, brush.hardness, brush.antiAliasing)
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
        if (dab.diameter <= 0) {
            PaintDebug.assertFail("dab diameter must be > 0, got ${dab.diameter}")
            return
        }
        if (dab.data.size != dab.diameter * dab.diameter) {
            PaintDebug.assertFail("dab data size mismatch: expected ${dab.diameter * dab.diameter}, got ${dab.data.size}")
            return
        }
        if (opacity !in 0..255) {
            PaintDebug.assertFail("opacity out of range: $opacity")
            return
        }

        if (dab.scale < 1f) {
            // ── スケーリングされた大ブラシ: 実サイズで適用 ──
            applyScaledDabToSurface(surface, dab, color, opacity, blendMode)
            return
        }

        val dr = dab.left + dab.diameter; val db = dab.top + dab.diameter
        val tx0 = maxOf(0, surface.pixelToTile(dab.left))
        val ty0 = maxOf(0, surface.pixelToTile(dab.top))
        val tx1 = minOf(surface.tilesX - 1, surface.pixelToTile(dr - 1))
        val ty1 = minOf(surface.tilesY - 1, surface.pixelToTile(db - 1))

        // 選択範囲がある場合は、バウンディングボックスをクリップ（ピクセル座標で比較）
        val sm = selectionManager
        val selBounds = if (sm != null && sm.hasSelection) sm.getBounds() else null
        if (selBounds != null) {
            // ダブのピクセル座標で選択範囲外チェック
            if (dab.left >= selBounds[2] || dr <= selBounds[0] ||
                dab.top >= selBounds[3] || db <= selBounds[1]) {
                return  // 完全に選択範囲外
            }
        }

        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val tile = surface.getOrCreateMutable(tx, ty)
            val ox = tx * Tile.SIZE; val oy = ty * Tile.SIZE

            // 選択範囲チェック: タイルの全ピクセルが選択範囲外でないか確認（ピクセル座標同士）
            if (selBounds != null) {
                if (ox >= selBounds[2] || ox + Tile.SIZE <= selBounds[0] ||
                    oy >= selBounds[3] || oy + Tile.SIZE <= selBounds[1]) {
                    continue  // このタイルは選択範囲外
                }
            }

            PixelOps.applyDabToTile(
                tile.pixels, dab.data, dab.diameter, color, opacity, blendMode,
                maxOf(0, dab.left - ox), maxOf(0, dab.top - oy),
                minOf(Tile.SIZE, dr - ox), minOf(Tile.SIZE, db - oy),
                dab.left - ox, dab.top - oy,
            )
            dirtyTracker.markDirty(tx, ty)
        }
    }

    /**
     * スケーリングされたダブマスクを実サイズで適用。
     * マスクの各ピクセルが 1/scale ピクセル分の領域をカバーする。
     * ニアレストネイバー補間で高速に拡大適用。
     */
    private fun applyScaledDabToSurface(
        surface: TiledSurface, dab: DabMask,
        color: Int, opacity: Int, blendMode: Int,
    ) {
        val invScale = 1f / dab.scale
        val actualDiameter = (dab.diameter * invScale).toInt()
        val dr = dab.left + actualDiameter
        val db = dab.top + actualDiameter
        val tx0 = maxOf(0, surface.pixelToTile(dab.left))
        val ty0 = maxOf(0, surface.pixelToTile(dab.top))
        val tx1 = minOf(surface.tilesX - 1, surface.pixelToTile(dr - 1))
        val ty1 = minOf(surface.tilesY - 1, surface.pixelToTile(db - 1))

        // 選択範囲がある場合は、バウンディングボックスをクリップ
        val sm = selectionManager
        val selBounds = if (sm != null && sm.hasSelection) sm.getBounds() else null
        if (selBounds != null) {
            // 選択範囲外のダブは描画しない（ピクセル座標同士で比較）
            if (dab.left >= selBounds[2] || dr <= selBounds[0] ||
                dab.top >= selBounds[3] || db <= selBounds[1]) {
                return  // 完全に選択範囲外
            }
        }

        val ca = PixelOps.alpha(color); val cr = PixelOps.red(color)
        val cg = PixelOps.green(color); val cb = PixelOps.blue(color)
        val minDa = if (blendMode == PixelOps.BLEND_ERASE) 1 else maxOf(1,
            if (cr > 0) (127 + cr) / cr else 0,
            if (cg > 0) (127 + cg) / cg else 0,
            if (cb > 0) (127 + cb) / cb else 0,
        )

        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            // 選択範囲チェック: タイルのピクセル範囲で比較
            if (selBounds != null) {
                val ox = tx * Tile.SIZE; val oy = ty * Tile.SIZE
                if (ox >= selBounds[2] || ox + Tile.SIZE <= selBounds[0] ||
                    oy >= selBounds[3] || oy + Tile.SIZE <= selBounds[1]) {
                    continue  // このタイルは選択範囲外
                }
            }

            val tile = surface.getOrCreateMutable(tx, ty)
            val ox = tx * Tile.SIZE; val oy = ty * Tile.SIZE
            val clipL = maxOf(0, dab.left - ox); val clipT = maxOf(0, dab.top - oy)
            val clipR = minOf(Tile.SIZE, dr - ox); val clipB = minOf(Tile.SIZE, db - oy)

            for (py in clipT until clipB) {
                val tOff = py * Tile.SIZE
                // マスク上の Y 座標 (ニアレストネイバー)
                val my = ((py + oy - dab.top) * dab.scale).toInt()
                    .coerceIn(0, dab.diameter - 1)
                val mOff = my * dab.diameter
                for (px in clipL until clipR) {
                    val mx = ((px + ox - dab.left) * dab.scale).toInt()
                        .coerceIn(0, dab.diameter - 1)
                    val mv = dab.data[mOff + mx]; if (mv <= 0) continue
                    val da = PixelOps.div255(mv * opacity); if (da < minDa) continue
                    val di = tOff + px; val dst = tile.pixels[di]

                    when (blendMode) {
                        PixelOps.BLEND_ERASE -> {
                            tile.pixels[di] = PixelOps.blendErase(dst, PixelOps.div255(da * ca))
                        }
                        PixelOps.BLEND_MARKER -> {
                            val sa = PixelOps.div255(ca * da); val sr = PixelOps.div255(cr * da)
                            val sg = PixelOps.div255(cg * da); val sb = PixelOps.div255(cb * da)
                            val inv = 255 - sa
                            tile.pixels[di] = PixelOps.pack(
                                maxOf(sa, PixelOps.alpha(dst)),
                                sr + PixelOps.div255(PixelOps.red(dst) * inv),
                                sg + PixelOps.div255(PixelOps.green(dst) * inv),
                                sb + PixelOps.div255(PixelOps.blue(dst) * inv))
                        }
                        else -> {
                            val sa = PixelOps.div255(ca * da); val sr = PixelOps.div255(cr * da)
                            val sg = PixelOps.div255(cg * da); val sb = PixelOps.div255(cb * da)
                            val inv = 255 - sa
                            tile.pixels[di] = PixelOps.pack(
                                sa + PixelOps.div255(PixelOps.alpha(dst) * inv),
                                sr + PixelOps.div255(PixelOps.red(dst) * inv),
                                sg + PixelOps.div255(PixelOps.green(dst) * inv),
                                sb + PixelOps.div255(PixelOps.blue(dst) * inv))
                        }
                    }
                }
            }
            dirtyTracker.markDirty(tx, ty)
        }
    }

    // ── 局所ぼかし (筆/水彩 per-dab フィルタ) ────────────────────────

    /**
     * ダブ配置後にダブ周辺を局所ぼかしし、既存色とストローク色を混色する。
     *
     * ■ Direct モード (drawTarget === sampleSource):
     *   content を直接読み取り → IIR ガウス近似ぼかし → 円形フォールオフ付きで content に書き戻し。
     *
     * ■ Indirect モード (drawTarget !== sampleSource):
     *   sublayer + content の合成を IIR ぼかし、sublayer に書き戻す従来方式。
     *
     * 重み付き IIR フィルタによるガウス近似 (Young & van Vliet 簡易版):
     *   前進パス: y[i] = α * x[i] + (1-α) * y[i-1]
     *   後退パス: y[i] = α * y[i] + (1-α) * y[i+1]
     * 水平→垂直の分離型で O(n) per pixel。半径に依存しない一定コスト。
     *
     * - AVERAGING (筆): α=小 (0.08-0.15), 4パス → ほぼ均一な平均化。強い混色。
     * - BOX_BLUR (水彩): α=中 (0.20-0.40), 2パス → ガウスブラー風。にじみ効果。
     */
    private fun blurAreaOnSurface(
        drawTarget: TiledSurface, sampleSource: TiledSurface,
        cx: Int, cy: Int, radius: Int, filterType: Int,
    ) {
        val safeRadius = radius.coerceIn(1, MemoryConfig.maxBlurRadius)
        var x0 = maxOf(0, cx - safeRadius)
        var y0 = maxOf(0, cy - safeRadius)
        var x1 = minOf(drawTarget.width - 1, cx + safeRadius)
        var y1 = minOf(drawTarget.height - 1, cy + safeRadius)

        val sm = selectionManager
        val selBounds = if (sm != null && sm.hasSelection) sm.getBounds() else null
        if (selBounds != null) {
            x0 = maxOf(x0, selBounds[0])
            y0 = maxOf(y0, selBounds[1])
            x1 = minOf(x1, selBounds[2] - 1)
            y1 = minOf(y1, selBounds[3] - 1)
        }
        if (x0 > x1 || y0 > y1) return

        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val size = w * h

        val maxRadiusSq = (MemoryConfig.maxBlurRadius * 2 + 1)
        val maxAreaSizeDirect = maxRadiusSq * maxRadiusSq
        val maxSize = if (drawTarget !== sampleSource) {
            (maxAreaSizeDirect * 0.8).toInt()
        } else maxAreaSizeDirect

        if (size > maxSize) {
            PaintDebug.d(PaintDebug.Perf) {
                "[blurArea] skipped: size=$size > maxSize=$maxSize (w=$w h=$h)"
            }
            return
        }

        // バッファ再利用 (GC 回避)
        try {
            if (blurSrc.size < size) {
                blurSrc = IntArray(size); blurDst = IntArray(size)
            }
            if (blurLin.size < size) blurLin = LongArray(size)
        } catch (e: OutOfMemoryError) {
            PaintDebug.d(PaintDebug.Perf) {
                "[blurArea] OOM: failed to allocate buffers for size=$size"
            }
            return
        }

        // IIR パラメータ: フィルタ種別 × サイズによる段階的調整
        // α が小さいほど強いぼかし (= 強い混色)
        // スペーシング処理と同じ基準値（baseRad=25）で段階的にスケーリング
        val baseRad = 25f
        val radScale = safeRadius.toFloat() / baseRad
        val iirAlpha: Float
        val iirPasses: Int

        if (filterType == BrushConfig.SUBLAYER_FILTER_AVERAGING) {
            // 筆: ほぼ平均になるような強い混色
            val baseAlpha = 0.06f + 0.06f * (5f / maxOf(5f, safeRadius.toFloat()))
            iirAlpha = when {
                radScale < 0.4f -> {
                    // 小ブラシ (半径 < 10px): 混色を弱くする
                    baseAlpha.coerceIn(0.02f, 0.04f)
                }
                radScale < 1f -> {
                    // 中小ブラシ (10-25px): 緩やかに混色増加
                    baseAlpha.coerceIn(0.04f, 0.06f)
                }
                radScale < 8f -> {
                    // 中大ブラシ (25-200px): バランス型
                    baseAlpha.coerceIn(0.06f, 0.10f)
                }
                else -> {
                    // 大ブラシ (200px以上): 強い混色で一体感を出す
                    baseAlpha.coerceIn(0.08f, 0.12f)
                }
            }
            iirPasses = 1
        } else {
            // 水彩: ガウスブラー風のにじみ
            val baseAlpha = 0.15f + 0.15f * (8f / maxOf(8f, safeRadius.toFloat()))
            iirAlpha = when {
                radScale < 0.4f -> {
                    // 小ブラシ (半径 < 10px): 弱いにじみ
                    baseAlpha.coerceIn(0.08f, 0.10f)
                }
                radScale < 1f -> {
                    // 中小ブラシ (10-25px): 緩やかなにじみ
                    baseAlpha.coerceIn(0.10f, 0.11f)
                }
                radScale < 8f -> {
                    // 中大ブラシ (25-200px): バランス型
                    baseAlpha.coerceIn(0.09f, 0.13f)
                }
                else -> {
                    // 大ブラシ (200px以上): 広いにじみ
                    baseAlpha.coerceIn(0.10f, 0.15f)
                }
            }
            iirPasses = 1
        }

        // ── Direct モード: content を直接ぼかし (16bit sRGB 空間) ────
        if (drawTarget === sampleSource) {
            for (ly in 0 until h) for (lx in 0 until w) {
                val gx = x0 + lx; val gy = y0 + ly
                var px = drawTarget.getPixelAt(gx, gy)

                // 選択範囲チェック: 部分選択ピクセルのアルファを減衰
                if (sm != null) {
                    val maskVal = sm.getMaskValue(gx, gy)
                    if (maskVal == 0) {
                        px = 0  // 完全非選択: 透明
                    } else if (maskVal < 255) {
                        // 部分選択: アルファを減衰
                        val fadeAlpha = maskVal / 255f
                        val origAlpha = PixelOps.alpha(px)
                        val newAlpha = (origAlpha * fadeAlpha).toInt().coerceIn(0, 255)
                        px = PixelOps.pack(newAlpha, PixelOps.red(px), PixelOps.green(px), PixelOps.blue(px))
                    }
                }

                blurSrc[ly * w + lx] = px
                blurLin[ly * w + lx] = pixelToWide64(px)
            }

            // IIR ガウス近似ぼかし (16bit sRGB 空間、インプレース)
            repeat(iirPasses) {
                iirGaussPass64(blurLin, w, h, iirAlpha)
            }

            // 16bit → 8bit 変換
            for (i in 0 until size) blurDst[i] = wide64ToPixel(blurLin[i])

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

                val pixel = if (dist2 <= fadeStart2) blurred
                    else {
                        val t = ((dist2 - fadeStart2) / fadeRange).coerceIn(0f, 1f)
                        lerpSrgb(blurred, original, t * t)
                    }

                val gpx = x0 + lx; val gpy = y0 + ly

                // 選択範囲チェック: 完全非選択はスキップ
                if (sm != null && sm.getMaskValue(gpx, gpy) == 0) continue

                val tx = drawTarget.pixelToTile(gpx)
                val ty = drawTarget.pixelToTile(gpy)
                if (tx < 0 || tx >= drawTarget.tilesX || ty < 0 || ty >= drawTarget.tilesY) continue
                val tile = drawTarget.getOrCreateMutable(tx, ty)
                tile.pixels[(gpy - ty * Tile.SIZE) * Tile.SIZE + (gpx - tx * Tile.SIZE)] = pixel
                dirtyTracker.markDirty(tx, ty)
            }
            return
        }

        // ── Indirect モード: sublayer + content 合成ぼかし (16bit sRGB 空間) ──
        try {
            if (blurSubSrc.size < size) {
                blurSubSrc = IntArray(size); blurSubDst = IntArray(size)
            }
            if (blurLinSub.size < size) blurLinSub = LongArray(size)
        } catch (e: OutOfMemoryError) {
            PaintDebug.d(PaintDebug.Perf) {
                "[blurArea] OOM in Indirect mode: size=$size"
            }
            return
        }

        for (ly in 0 until h) for (lx in 0 until w) {
            val px = x0 + lx; val py = y0 + ly
            var subPx = drawTarget.getPixelAt(px, py)
            var contPx = sampleSource.getPixelAt(px, py)
            val idx = ly * w + lx

            // 選択範囲外のピクセルを処理: maskValue に応じてアルファを減衰（完全透明ではなく）
            if (sm != null) {
                val maskVal = sm.getMaskValue(px, py)
                if (maskVal < 255) {
                    // maskVal = 0 (非選択) → α = 0、maskVal = 254 → α ≈ 1%
                    // 境界のアンチエイリアスをスムーズに保つ
                    val fadeAlpha = maskVal / 255f
                    val origAlpha = PixelOps.alpha(subPx)
                    val newAlpha = (origAlpha * fadeAlpha).toInt().coerceIn(0, 255)
                    subPx = PixelOps.pack(newAlpha, PixelOps.red(subPx), PixelOps.green(subPx), PixelOps.blue(subPx))
                    contPx = 0  // 背景は消す
                    PaintDebug.d(PaintDebug.Brush) { "[blurAreaOnSurface] selection fade at ($px,$py) maskVal=$maskVal origA=$origAlpha newA=$newAlpha" }
                }
            }

            val comp = PixelOps.blendSrcOver(contPx, subPx)
            blurSrc[idx] = comp
            blurLin[idx] = pixelToWide64(comp)
            val mask = if (PixelOps.alpha(subPx) >= PixelOps.alpha(contPx)) subPx else contPx
            blurSubSrc[idx] = mask
            blurLinSub[idx] = pixelToWide64(mask)
        }

        // IIR ガウス近似ぼかし (16bit sRGB 空間、インプレース): 合成版 (RGB 混色用)
        repeat(iirPasses) { iirGaussPass64(blurLin, w, h, iirAlpha) }
        for (i in 0 until size) blurDst[i] = wide64ToPixel(blurLin[i])

        // IIR ガウス近似ぼかし (16bit sRGB 空間、インプレース): マスク用
        repeat(iirPasses) { iirGaussPass64(blurLinSub, w, h, iirAlpha) }
        for (i in 0 until size) blurSubDst[i] = wide64ToPixel(blurLinSub[i])

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
                bsa = (PixelOps.alpha(subBlur) + ((PixelOps.alpha(subOrig) - PixelOps.alpha(subBlur)) * tSq).toInt()).coerceIn(0, 255)
            }
            if (bsa == 0) continue

            val blendedComposite = if (dist2 <= fadeStart2) compositeBlur
                else {
                    val t = ((dist2 - fadeStart2) / fadeRange).coerceIn(0f, 1f)
                    PixelOps.lerpColor(compositeBlur, compositeOrig, t * t)
                }

            val gpx = x0 + lx; val gpy = y0 + ly
            if (sm != null && sm.getMaskValue(gpx, gpy) < 255) continue

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
     * premultiplied ARGB8 → 16bit 拡張 Long パック。
     * ガンマ変換なし。各チャネルを ×257 で 0..255 → 0..65535 に拡張。
     * パックレイアウト: A(16) | R(16) | G(16) | B(16)
     */
    private fun pixelToWide64(pixel: Int): Long {
        if (pixel == 0) return 0L
        val a = ((pixel ushr 24) and 0xFF).toLong() * 257L
        val r = ((pixel ushr 16) and 0xFF).toLong() * 257L
        val g = ((pixel ushr 8) and 0xFF).toLong() * 257L
        val b = (pixel and 0xFF).toLong() * 257L
        return (a shl 48) or (r shl 32) or (g shl 16) or b
    }

    /**
     * 16bit Long パック → premultiplied ARGB8。
     * 各チャネルを /257 で 0..65535 → 0..255 に戻す。
     */
    private fun wide64ToPixel(v: Long): Int {
        val a = (((v ushr 48) and 0xFFFF).toInt() + 128) / 257
        val r = (((v ushr 32) and 0xFFFF).toInt() + 128) / 257
        val g = (((v ushr 16) and 0xFFFF).toInt() + 128) / 257
        val b = ((v and 0xFFFF).toInt() + 128) / 257
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * sRGB 空間の premultiplied ARGB8 線形補間 (フォールオフ用)。
     * ガンマ変換なし、整数演算のみ。
     */
    private fun lerpSrgb(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a; if (t >= 1f) return b
        val it = (t * 256f + 0.5f).toInt().coerceIn(0, 256)
        val ot = 256 - it
        val ca = ((a ushr 24) and 0xFF) * ot + ((b ushr 24) and 0xFF) * it
        val cr = ((a ushr 16) and 0xFF) * ot + ((b ushr 16) and 0xFF) * it
        val cg = ((a ushr 8) and 0xFF) * ot + ((b ushr 8) and 0xFF) * it
        val cb = (a and 0xFF) * ot + (b and 0xFF) * it
        return ((ca ushr 8) shl 24) or ((cr ushr 8) shl 16) or ((cg ushr 8) shl 8) or (cb ushr 8)
    }

    /**
     * 重み付き IIR フィルタによるガウス近似 1パス (16bit パック Long、分離型)。
     *
     * 各行/列に対して前進パスと後退パスを適用:
     *   前進: y[i] = α * x[i] + (1-α) * y[i-1]
     *   後退: y[i] = α * y[i] + (1-α) * y[i+1]
     *
     * 16bit 固定小数点精度。ガンマ変換不要で高速。
     * Long パックレイアウト: A(16) | R(16) | G(16) | B(16)
     */
    private fun iirGaussPass64(
        data: LongArray, w: Int, h: Int, alpha: Float,
    ) {
        val a16 = (alpha * 65536f + 0.5f).toInt().coerceIn(1, 65536)
        val b16 = 65536 - a16
        val aL = a16.toLong()
        val bL = b16.toLong()

        // ── 水平パス: 各行に前進→後退 ──
        for (y in 0 until h) {
            val row = y * w

            // 前進パス: left → right
            var prevA = (data[row] ushr 48) and 0xFFFF
            var prevR = (data[row] ushr 32) and 0xFFFF
            var prevG = (data[row] ushr 16) and 0xFFFF
            var prevB = data[row] and 0xFFFF
            for (x in 1 until w) {
                val c = data[row + x]
                val ca = (c ushr 48) and 0xFFFF; val cr = (c ushr 32) and 0xFFFF
                val cg = (c ushr 16) and 0xFFFF; val cb = c and 0xFFFF
                prevA = ((aL * ca + bL * prevA) ushr 16) and 0xFFFF
                prevR = ((aL * cr + bL * prevR) ushr 16) and 0xFFFF
                prevG = ((aL * cg + bL * prevG) ushr 16) and 0xFFFF
                prevB = ((aL * cb + bL * prevB) ushr 16) and 0xFFFF
                data[row + x] = (prevA shl 48) or (prevR shl 32) or (prevG shl 16) or prevB
            }

            // 後退パス: right → left
            prevA = (data[row + w - 1] ushr 48) and 0xFFFF
            prevR = (data[row + w - 1] ushr 32) and 0xFFFF
            prevG = (data[row + w - 1] ushr 16) and 0xFFFF
            prevB = data[row + w - 1] and 0xFFFF
            for (x in w - 2 downTo 0) {
                val c = data[row + x]
                val ca = (c ushr 48) and 0xFFFF; val cr = (c ushr 32) and 0xFFFF
                val cg = (c ushr 16) and 0xFFFF; val cb = c and 0xFFFF
                prevA = ((aL * ca + bL * prevA) ushr 16) and 0xFFFF
                prevR = ((aL * cr + bL * prevR) ushr 16) and 0xFFFF
                prevG = ((aL * cg + bL * prevG) ushr 16) and 0xFFFF
                prevB = ((aL * cb + bL * prevB) ushr 16) and 0xFFFF
                data[row + x] = (prevA shl 48) or (prevR shl 32) or (prevG shl 16) or prevB
            }
        }

        // ── 垂直パス: 各列に前進→後退 ──
        for (x in 0 until w) {
            // 前進パス: top → bottom
            var prevA = (data[x] ushr 48) and 0xFFFF
            var prevR = (data[x] ushr 32) and 0xFFFF
            var prevG = (data[x] ushr 16) and 0xFFFF
            var prevB = data[x] and 0xFFFF
            for (y in 1 until h) {
                val idx = y * w + x
                val c = data[idx]
                val ca = (c ushr 48) and 0xFFFF; val cr = (c ushr 32) and 0xFFFF
                val cg = (c ushr 16) and 0xFFFF; val cb = c and 0xFFFF
                prevA = ((aL * ca + bL * prevA) ushr 16) and 0xFFFF
                prevR = ((aL * cr + bL * prevR) ushr 16) and 0xFFFF
                prevG = ((aL * cg + bL * prevG) ushr 16) and 0xFFFF
                prevB = ((aL * cb + bL * prevB) ushr 16) and 0xFFFF
                data[idx] = (prevA shl 48) or (prevR shl 32) or (prevG shl 16) or prevB
            }

            // 後退パス: bottom → top
            val lastIdx = (h - 1) * w + x
            prevA = (data[lastIdx] ushr 48) and 0xFFFF
            prevR = (data[lastIdx] ushr 32) and 0xFFFF
            prevG = (data[lastIdx] ushr 16) and 0xFFFF
            prevB = data[lastIdx] and 0xFFFF
            for (y in h - 2 downTo 0) {
                val idx = y * w + x
                val c = data[idx]
                val ca = (c ushr 48) and 0xFFFF; val cr = (c ushr 32) and 0xFFFF
                val cg = (c ushr 16) and 0xFFFF; val cb = c and 0xFFFF
                prevA = ((aL * ca + bL * prevA) ushr 16) and 0xFFFF
                prevR = ((aL * cr + bL * prevR) ushr 16) and 0xFFFF
                prevG = ((aL * cg + bL * prevG) ushr 16) and 0xFFFF
                prevB = ((aL * cb + bL * prevB) ushr 16) and 0xFFFF
                data[idx] = (prevA shl 48) or (prevR shl 32) or (prevG shl 16) or prevB
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
        // スケーリングされたマスクではマスク座標系での半径を使う
        val r = radius * dab.scale
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
    /** 最小ブラシサイズ (1～100 パーセント、デフォルト 20%) */
    val minBrushSizePercent: Int = 20,
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
