package com.propaint.app.engine

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ブラシエンジン: Drawpile brush_engine.c 準拠の完全書き直し。
 *
 * v1/旧v2 の致命的バグを修正:
 *  ・ぼかしツールに描画色が乗る → blur は smudge=1.0 でキャンバス色のみ使用
 *  ・Fude/Watercolor が混色しない → smudge サンプリングを CPU タイルから実行
 *  ・全ブラシが同じ描画パスを通る → Direct/Indirect を種別ごとに区別
 *
 * Drawpile のダブ色決定フロー:
 *   1. update_classic_smudge(): resmudge 距離ごとにキャンバスから色サンプリング
 *   2. blend_classic_color():   smudge_color × smudge + brush_color × (1-smudge)
 *   3. 合成した色でダブマスクをタイルに適用
 */
class BrushEngine(
    private val dirtyTracker: DirtyTileTracker,
) {
    // ── ストローク状態 ──────────────────────────────────────────────

    private var distToNextDab = 0f
    private val pointBuffer = ArrayList<StrokePoint>(512)

    // Drawpile の smudge_color: キャンバスからサンプリングした色 (premultiplied)
    private var smudgeColor: Int = 0
    // resmudge: ダブ何個ごとに再サンプリングするか (Drawpile cb->resmudge)
    private var smudgeDabCount: Int = 0

    // ストローク開始時のレイヤー ID (レイヤー切替バグ修正)
    var strokeLayerId: Int = -1
        private set

    // ── per-dab ブラー用バッファ (GC 回避のため再利用) ─────────────
    private var blurSrc = IntArray(0)
    private var blurTmp = IntArray(0)
    private var blurDst = IntArray(0)

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
        smudgeColor = 0
        smudgeDabCount = 0
        strokeLayerId = layerId
        stabInitialized = false
        PaintDebug.d(PaintDebug.Brush) { "[beginStroke] layerId=$layerId" }
    }

    /**
     * ストロークにポイントを追加し、ダブを配置する。
     *
     * @param drawTarget  描画先サーフェス (Indirect→sublayer, Direct→layer.content)
     * @param sampleSource smudge/blur サンプリング元 (常に layer.content)
     *                     ※ sublayer ではなく本体レイヤーから読む (Drawpile 準拠)
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
        smudgeColor = 0; smudgeDabCount = 0
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

        // resmudge 間隔 (Drawpile cb->resmudge: 0=毎ダブ)
        val resmudge = brush.resmudge

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
                val pRad = if (brush.pressureSizeEnabled) {
                    val minRad = 0.5f
                    minRad + (nomRad - minRad) * pressureCurve(pressure, brush.pressureSizeIntensity)
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

                // ── Smudge サンプリング (Drawpile update_classic_smudge) ────
                // smudge > 0 のブラシ: resmudge 間隔でキャンバスから色を取得
                if (brush.smudge > 0f) {
                    smudgeDabCount++
                    if (smudgeDabCount > resmudge) {
                        val sampledColor = sampleSource.sampleColorAt(
                            px.toInt(), py.toInt(),
                            maxOf(2, (finalRad * 0.8f).toInt()),
                        )
                        // smudge_color を更新 (Drawpile: 新サンプルで漸進的に置換)
                        if (smudgeColor == 0) {
                            smudgeColor = sampledColor
                        } else {
                            smudgeColor = PixelOps.lerpColor(smudgeColor, sampledColor, brush.smudge)
                        }
                        smudgeDabCount = 0
                    }
                }

                // ── ぼかし筆圧判定 ──────────────────────────────────────────
                val belowBlurThreshold = brush.blurPressureThreshold > 0f &&
                    pressure < brush.blurPressureThreshold

                // ── ダブ色決定 (Drawpile blend_classic_color) ────────────────
                val dabColor: Int = when {
                    // Blur: smudge=1.0 → キャンバス色のみ (描画色を一切使わない)
                    brush.isBlur -> {
                        sampleSource.sampleColorAt(
                            px.toInt(), py.toInt(),
                            maxOf(2, (finalRad * brush.blurStrength).toInt()),
                        )
                    }
                    // ぼかし筆圧閾値以下 + smudge: スマッジ色のみ (着色なし)
                    belowBlurThreshold && brush.smudge > 0f && smudgeColor != 0 -> smudgeColor
                    // ぼかし筆圧閾値以下 + smudge未初期化: 描画スキップ
                    belowBlurThreshold -> 0
                    // Smudge系 (Fude/Watercolor): キャンバス色と描画色を混合
                    brush.smudge > 0f && smudgeColor != 0 -> {
                        PixelOps.lerpColor(brush.colorPremul, smudgeColor, brush.smudge)
                    }
                    // 通常ブラシ: 描画色そのまま
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
                val filterRadius = maxOf(1, nomRad.toInt())

                if (belowBlurThreshold && hasFilter) {
                    // 閾値以下: ダブを描画せず、既存サブレイヤーをぼかすのみ
                    blurAreaOnSurface(drawTarget, sampleSource, px.toInt(), py.toInt(),
                        filterRadius, brush.sublayerFilter)
                } else {
                    // ダブマスク生成 & タイルに合成
                    val dab = DabMaskGenerator.createDab(px, py, finalRad * 2f, brush.hardness)
                    if (dab != null) {
                        applyDabToSurface(drawTarget, dab, dabColor, finalOpacity, blendId)
                    }
                    // ダブ配置後にフィルタ適用 (筆/水彩の混色効果)
                    if (hasFilter) {
                        blurAreaOnSurface(drawTarget, sampleSource, px.toInt(), py.toInt(),
                            filterRadius, brush.sublayerFilter)
                    }
                }

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

    // ── サブレイヤー局所ぼかし (筆/水彩 per-dab フィルタ) ────────────

    /**
     * サブレイヤー+レイヤー内容の合成をぼかし、結果をサブレイヤーに書き戻す。
     *
     * ぼかし入力: sublayer SrcOver content (キャンバス既存色+ストローク色)
     * ぼかし出力: サブレイヤーのみに書き戻し
     *
     * endStroke で sublayer が content に SrcOver 合成されるため、
     * サブレイヤーに書く値は「ぼかし結果そのもの」とする。
     * (sublayer の alpha が高い領域では content は透過で隠れる)
     *
     * O(n) per pixel の分離ボックスブラー + 円形フォールオフ。
     *
     * - AVERAGING: 2パス (三角カーネル近似 = 滑らかな混色)
     * - BOX_BLUR: 1パス (にじみ効果)
     */
    private fun blurAreaOnSurface(
        sublayer: TiledSurface, content: TiledSurface,
        cx: Int, cy: Int, radius: Int, filterType: Int,
    ) {
        val safeRadius = radius.coerceIn(1, 60)
        val x0 = maxOf(0, cx - safeRadius)
        val y0 = maxOf(0, cy - safeRadius)
        val x1 = minOf(sublayer.width - 1, cx + safeRadius)
        val y1 = minOf(sublayer.height - 1, cy + safeRadius)
        if (x0 > x1 || y0 > y1) return

        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val size = w * h

        // バッファ再利用 (GC 回避)
        if (blurSrc.size < size) {
            blurSrc = IntArray(size); blurTmp = IntArray(size); blurDst = IntArray(size)
        }

        // ぼかし入力: サブレイヤー SrcOver レイヤー内容 (合成)
        // → キャンバスに既に描かれたピクセルも混色対象に入る
        for (ly in 0 until h) for (lx in 0 until w) {
            val px = x0 + lx; val py = y0 + ly
            val subPx = sublayer.getPixelAt(px, py)
            val contPx = content.getPixelAt(px, py)
            blurSrc[ly * w + lx] = PixelOps.blendSrcOver(contPx, subPx)
        }

        // パス数: 筆=2パス (三角カーネル近似), 水彩=1パス
        val passes = if (filterType == BrushConfig.SUBLAYER_FILTER_AVERAGING) 2 else 1
        val kr = maxOf(1, safeRadius / 3)

        // 分離ボックスブラー
        System.arraycopy(blurSrc, 0, blurDst, 0, size)
        repeat(passes) {
            separableBoxBlurPass(blurDst, blurTmp, w, h, kr)
            System.arraycopy(blurTmp, 0, blurDst, 0, size)
        }

        // 円形フォールオフ付きでサブレイヤーに書き戻し
        // ※ 逆合成: ぼかし結果 (合成値) からコンテンツ寄与分を差し引き、
        //   endStroke の SrcOver で二重適用にならないようにする
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

            // フォールオフ: 中心=ぼかし結果、縁=合成元
            val blended = if (dist2 <= fadeStart2) {
                compositeBlur
            } else {
                val t = ((dist2 - fadeStart2) / fadeRange).coerceIn(0f, 1f)
                PixelOps.lerpColor(compositeBlur, compositeOrig, t * t)
            }

            val gpx = x0 + lx; val gpy = y0 + ly

            // 逆合成: (subPx SrcOver contPx) = blended となる subPx を求める
            val contPx = content.getPixelAt(gpx, gpy)
            val subPx = PixelOps.unComposite(blended, contPx)

            val tx = sublayer.pixelToTile(gpx)
            val ty = sublayer.pixelToTile(gpy)
            if (tx < 0 || tx >= sublayer.tilesX || ty < 0 || ty >= sublayer.tilesY) continue
            val tile = sublayer.getOrCreateMutable(tx, ty)
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
    /** Smudge 量 (0=描画色のみ, 1=キャンバス色のみ)。Drawpile cb->smudge.max */
    val smudge: Float = 0f,
    /** 何ダブごとに再サンプリングするか。Drawpile cb->resmudge。0=毎ダブ */
    val resmudge: Int = 0,
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
