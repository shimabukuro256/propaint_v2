package com.propaint.app.flutter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import com.propaint.app.engine.MemoryConfig
import com.propaint.app.engine.PaintDebug
import com.propaint.app.flutter.pigeon.FlutterError
import com.propaint.app.flutter.pigeon.PaintHostApi
import com.propaint.app.viewmodel.BrushType
import com.propaint.app.viewmodel.PaintViewModel
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Flutter UI をホストする Activity。
 */
class PaintFlutterActivity : FlutterActivity(), ViewModelStoreOwner {

    private val vmStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = vmStore

    private lateinit var viewModel: PaintViewModel

    // Final: Kotlin → Flutter 通知用 FlutterApi。onStateChanged で状態スナップショットを push。
    private var paintFlutterApi: com.propaint.app.flutter.pigeon.PaintFlutterApi? = null

    // Final: State observer ジョブ（StateFlow 変更 → PaintFlutterApi.onStateChanged）。
    private var stateObserverJob: Job? = null

    // Final: 同一 PaintState 連続送信を抑制（Pigeon 生成 data class の equals を利用）。
    private var lastSentPaintState: com.propaint.app.flutter.pigeon.PaintState? = null

    // エクスポート種別を onActivityResult で区別するためのリクエストコード
    // Activity 再生成時も SavedInstanceState で保持する
    private var pendingExportFormat: String? = null

    /** onPause で保存済みなら onStop での重複保存を抑制 */
    private var savedOnPause = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Activity 再生成時に pendingExportFormat を復元
        pendingExportFormat = savedInstanceState?.getString(KEY_PENDING_FORMAT)

        // デバイス RAM に基づいてメモリ設定を初期化
        MemoryConfig.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_FORMAT, pendingExportFormat)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[PaintViewModel::class.java]

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        if (projectId != null) {
            PaintDebug.d(PaintDebug.Layer) { "[FlutterActivity] openProject id=$projectId" }
            viewModel.openProject(projectId)
        } else {
            PaintDebug.d(PaintDebug.Layer) { "[FlutterActivity] initCanvas 2048x2048 (no project_id)" }
            viewModel.initCanvas(2048, 2048)
        }

        flutterEngine.platformViewsController
            .registry
            .registerViewFactory(VIEW_TYPE, PaintCanvasViewFactory(viewModel))

        // Pigeon 型安全 API (Final: 全 API が Pigeon 経由。MethodChannel / EventChannel は廃止)
        PaintHostApi.setUp(
            flutterEngine.dartExecutor.binaryMessenger,
            object : PaintHostApi {
                // ── Session 1: 試験移行 ──
                override fun setBrushSize(value: Double) {
                    if (value <= 0.0) {
                        throw FlutterError("INVALID_ARG", "BrushSize must be > 0 (was $value)")
                    }
                    viewModel.setBrushSize(value.toFloat())
                }

                // ── Session 2: Brush 設定系 ──
                override fun setBrushType(type: String) {
                    val kind = BrushType.entries.find { it.name == type }
                        ?: throw FlutterError("INVALID_ARG", "Unknown BrushType: $type")
                    viewModel.setBrushType(kind)
                }
                override fun setBrushOpacity(value: Double) {
                    if (value !in 0.0..1.0) {
                        throw FlutterError("INVALID_ARG", "opacity must be in 0..1 (was $value)")
                    }
                    viewModel.setBrushOpacity(value.toFloat())
                }
                override fun setBrushHardness(value: Double) {
                    if (value !in 0.0..1.0) {
                        throw FlutterError("INVALID_ARG", "hardness must be in 0..1 (was $value)")
                    }
                    viewModel.setBrushHardness(value.toFloat())
                }
                override fun setBrushAntiAliasing(value: Double) {
                    if (value !in 0.0..1.0) {
                        throw FlutterError("INVALID_ARG", "antiAliasing must be in 0..1 (was $value)")
                    }
                    viewModel.setBrushAntiAliasing(value.toFloat())
                }
                override fun setBrushDensity(value: Double) {
                    viewModel.setBrushDensity(value.toFloat())
                }
                override fun setBrushSpacing(value: Double) {
                    if (value <= 0.0) {
                        throw FlutterError("INVALID_ARG", "spacing must be > 0 (was $value)")
                    }
                    viewModel.setBrushSpacing(value.toFloat())
                }
                override fun setBrushMinSizePercent(percent: Long) {
                    if (percent !in 1L..100L) {
                        throw FlutterError("INVALID_ARG", "percent must be in 1..100 (was $percent)")
                    }
                    viewModel.setBrushMinSizePercent(percent.toInt())
                }
                override fun setStabilizer(value: Double) {
                    viewModel.setStabilizer(value.toFloat())
                }
                override fun setColorStretch(value: Double) {
                    viewModel.setColorStretch(value.toFloat())
                }
                override fun setWaterContent(value: Double) {
                    viewModel.setWaterContent(value.toFloat())
                }
                override fun setBlurStrength(value: Double) {
                    viewModel.setBlurStrength(value.toFloat())
                }
                override fun setBlurPressureThreshold(value: Double) {
                    viewModel.setBlurPressureThreshold(value.toFloat())
                }

                override fun togglePressureSize() { viewModel.togglePressureSize() }
                override fun togglePressureOpacity() { viewModel.togglePressureOpacity() }
                override fun togglePressureDensity() { viewModel.togglePressureDensity() }
                override fun togglePressureSelection() { viewModel.togglePressureSelection() }

                override fun exportBrushSettings(): String =
                    viewModel.exportBrushSettings()
                override fun importBrushSettings(json: String): Boolean =
                    viewModel.importBrushSettings(json)
                override fun resetBrushToDefaults() {
                    viewModel.resetBrushToDefaults()
                }

                // ── Session 3: Color + History + Layer CRUD ──
                override fun setColor(argb: Long) {
                    val v = argb.toInt()
                    val r = ((v shr 16) and 0xFF) / 255f
                    val g = ((v shr 8) and 0xFF) / 255f
                    val b = (v and 0xFF) / 255f
                    viewModel.setColor(Color(r, g, b))
                }
                override fun commitColor() { viewModel.commitColorToHistory() }
                override fun undo() { viewModel.undo() }
                override fun redo() { viewModel.redo() }

                override fun addLayer() { viewModel.addLayer() }
                override fun removeLayer(id: Long) { viewModel.removeLayer(id.toInt()) }
                override fun selectLayer(id: Long) { viewModel.selectLayer(id.toInt()) }
                override fun setLayerVisibility(id: Long, visible: Boolean) {
                    viewModel.setLayerVisibility(id.toInt(), visible)
                }
                override fun setLayerOpacity(id: Long, opacity: Double) {
                    if (opacity !in 0.0..1.0) {
                        throw FlutterError("INVALID_ARG", "opacity must be in 0..1 (was $opacity)")
                    }
                    viewModel.setLayerOpacity(id.toInt(), opacity.toFloat())
                }
                override fun setLayerBlendMode(id: Long, mode: Long) {
                    viewModel.setLayerBlendMode(id.toInt(), mode.toInt())
                }
                override fun setLayerClip(id: Long, clip: Boolean) {
                    viewModel.setLayerClip(id.toInt(), clip)
                }
                override fun setLayerLocked(id: Long, locked: Boolean) {
                    viewModel.setLayerLocked(id.toInt(), locked)
                }
                override fun setAlphaLocked(id: Long, locked: Boolean) {
                    viewModel.setAlphaLocked(id.toInt(), locked)
                }
                override fun reorderLayer(fromId: Long, toId: Long, insertAfter: Boolean) {
                    viewModel.reorderLayer(fromId.toInt(), toId.toInt(), insertAfter)
                }
                override fun duplicateLayer(id: Long) { viewModel.duplicateLayer(id.toInt()) }
                override fun mergeDown(id: Long) { viewModel.mergeDown(id.toInt()) }
                override fun moveLayerUp(id: Long) { viewModel.moveLayerUp(id.toInt()) }
                override fun moveLayerDown(id: Long) { viewModel.moveLayerDown(id.toInt()) }

                // ── Session 4: Batch Layer + Multi Selection ──
                override fun batchMergeLayers(ids: List<Long>) {
                    viewModel.batchMergeLayers(ids.map { it.toInt() })
                }
                override fun batchSetVisibility(ids: List<Long>, visible: Boolean) {
                    viewModel.batchSetVisibility(ids.map { it.toInt() }, visible)
                }
                override fun batchSetOpacity(ids: List<Long>, opacity: Double) {
                    if (opacity !in 0.0..1.0) {
                        throw FlutterError("INVALID_ARG", "opacity must be in 0..1 (was $opacity)")
                    }
                    viewModel.batchSetOpacity(ids.map { it.toInt() }, opacity.toFloat())
                }
                override fun batchSetBlendMode(ids: List<Long>, mode: Long) {
                    viewModel.batchSetBlendMode(ids.map { it.toInt() }, mode.toInt())
                }
                override fun batchMoveLayersUp(ids: List<Long>) {
                    viewModel.batchMoveLayersUp(ids.map { it.toInt() })
                }
                override fun batchMoveLayersDown(ids: List<Long>) {
                    viewModel.batchMoveLayersDown(ids.map { it.toInt() })
                }
                override fun setMultiSelection(ids: List<Long>) {
                    viewModel.setMultiSelection(ids.map { it.toInt() }.toSet())
                }
                override fun clearMultiSelection() { viewModel.clearMultiSelection() }
                override fun toggleLayerSelection(id: Long) {
                    viewModel.toggleLayerSelection(id.toInt())
                }

                // ── Session 5: Layer Group (フォルダ) ──
                override fun createLayerGroup(name: String) {
                    viewModel.createLayerGroup(name)
                }
                override fun deleteLayerGroup(groupId: Long) {
                    viewModel.deleteLayerGroup(groupId.toInt())
                }
                override fun setLayerGroup(layerId: Long, groupId: Long) {
                    viewModel.setLayerGroup(layerId.toInt(), groupId.toInt())
                }
                override fun batchSetLayerGroup(layerIds: List<Long>, groupId: Long) {
                    viewModel.batchSetLayerGroup(layerIds.map { it.toInt() }, groupId.toInt())
                }
                override fun batchMoveLayersRelative(
                    layerIds: List<Long>,
                    targetId: Long,
                    insertAfter: Boolean,
                ) {
                    viewModel.batchMoveLayersRelative(
                        layerIds.map { it.toInt() }, targetId.toInt(), insertAfter,
                    )
                }
                override fun setGroupVisibility(groupId: Long, visible: Boolean) {
                    viewModel.setGroupVisibility(groupId.toInt(), visible)
                }
                override fun setGroupOpacity(groupId: Long, opacity: Double) {
                    if (opacity !in 0.0..1.0) {
                        throw FlutterError("INVALID_ARG", "opacity must be in 0..1 (was $opacity)")
                    }
                    viewModel.setGroupOpacity(groupId.toInt(), opacity.toFloat())
                }
                override fun setFolderExpanded(folderId: Long, expanded: Boolean) {
                    viewModel.setFolderExpanded(folderId.toInt(), expanded)
                }
                override fun reorderLayerGroup(
                    fromGroupId: Long,
                    toGroupId: Long,
                    insertAfter: Boolean,
                ) {
                    viewModel.reorderLayerGroup(fromGroupId.toInt(), toGroupId.toInt(), insertAfter)
                }
                override fun reorderDisplayItem(fromId: Long, toId: Long) {
                    viewModel.reorderDisplayItem(fromId.toInt(), toId.toInt())
                }
                override fun getLayersInGroup(groupId: Long): List<Long> =
                    viewModel.getLayersInGroup(groupId.toInt()).map { it.toLong() }
                override fun getLayersInGroupRecursive(groupId: Long): List<Long> =
                    viewModel.getLayersInGroupRecursive(groupId.toInt()).map { it.toLong() }

                // ── Session 6: Selection Tool + Selection Ops ──
                override fun selectRect(left: Long, top: Long, right: Long, bottom: Long) {
                    viewModel.selectRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
                }
                override fun selectEllipse(left: Long, top: Long, right: Long, bottom: Long) {
                    viewModel.selectEllipse(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
                }
                override fun selectByColor(x: Long, y: Long, tolerance: Long, contiguous: Boolean) {
                    viewModel.selectByColor(x.toInt(), y.toInt(), tolerance.toInt(), contiguous)
                }
                override fun selectMagnet(x: Long, y: Long, tolerance: Long, maxDistance: Long) {
                    viewModel.selectMagnet(x.toInt(), y.toInt(), tolerance.toInt(), maxDistance.toInt())
                }
                override fun cancelMagnetSelection() { viewModel.cancelMagnetSelection() }
                override fun finalizeMagnetSelection() { viewModel.finalizeMagnetSelection() }
                override fun selectAll() { viewModel.selectAll() }
                override fun clearSelection() { viewModel.clearSelection() }
                override fun invertSelection() { viewModel.invertSelection() }
                override fun paintSelectionAdd(cx: Long, cy: Long, radius: Long, pressure: Double) {
                    viewModel.paintSelectionAdd(cx.toInt(), cy.toInt(), radius.toInt(), pressure.toFloat())
                }
                override fun paintSelectionErase(cx: Long, cy: Long, radius: Long, pressure: Double) {
                    viewModel.paintSelectionErase(cx.toInt(), cy.toInt(), radius.toInt(), pressure.toFloat())
                }
                override fun deleteSelection() { viewModel.deleteSelection() }
                override fun fillSelection(color: Long) { viewModel.fillSelection(color.toInt()) }
                override fun copySelection() { viewModel.copySelection() }
                override fun cutSelection() { viewModel.cutSelection() }
                override fun moveSelection(dx: Long, dy: Long) {
                    viewModel.moveSelection(dx.toInt(), dy.toInt())
                }
                override fun featherSelection(radius: Long) {
                    viewModel.featherSelection(radius.toInt())
                }
                override fun expandSelection(amount: Long) {
                    viewModel.expandSelection(amount.toInt())
                }
                override fun setSelectionMode(mode: com.propaint.app.flutter.pigeon.SelectionMode) {
                    val engineMode = when (mode) {
                        com.propaint.app.flutter.pigeon.SelectionMode.REPLACE ->
                            com.propaint.app.engine.SelectionMode.Replace
                        com.propaint.app.flutter.pigeon.SelectionMode.ADD ->
                            com.propaint.app.engine.SelectionMode.Add
                        com.propaint.app.flutter.pigeon.SelectionMode.SUBTRACT ->
                            com.propaint.app.engine.SelectionMode.Subtract
                        com.propaint.app.flutter.pigeon.SelectionMode.INTERSECT ->
                            com.propaint.app.engine.SelectionMode.Intersect
                    }
                    viewModel.setSelectionMode(engineMode)
                }

                // ── Session 7: Transform ──
                override fun flipLayerH() { viewModel.flipActiveLayerH() }
                override fun flipLayerV() { viewModel.flipActiveLayerV() }
                override fun rotateLayer90CW() { viewModel.rotateActiveLayer90CW() }

                override fun transformLayer(
                    scaleX: Double, scaleY: Double, angle: Double,
                    translateX: Double, translateY: Double,
                ) {
                    viewModel.transformActiveLayer(
                        scaleX.toFloat(), scaleY.toFloat(), angle.toFloat(),
                        translateX.toFloat(), translateY.toFloat(),
                    )
                }

                override fun distortLayer(corners: List<Double>) {
                    if (corners.size < 8) throw FlutterError("INVALID_ARG", "corners must have 8 values", null)
                    val arr = FloatArray(8) { corners[it].toFloat() }
                    viewModel.distortActiveLayer(arr)
                }

                override fun meshWarpLayer(gridW: Long, gridH: Long, nodes: List<Double>) {
                    val gw = gridW.toInt()
                    val gh = gridH.toInt()
                    val arr = FloatArray(nodes.size) { nodes[it].toFloat() }
                    viewModel.meshWarpActiveLayer(gw, gh, arr)
                }

                override fun applyPreviewTransform(
                    layerIds: List<Long>,
                    scaleX: Double, scaleY: Double, angleDeg: Double,
                    translateX: Double, translateY: Double,
                ) {
                    val ids = layerIds.map { it.toInt() }
                    if (ids.isEmpty()) throw FlutterError("INVALID_ARG", "layerIds is empty", null)
                    viewModel.applyMultiLayerTransform(
                        ids, scaleX.toFloat(), scaleY.toFloat(), angleDeg.toFloat(),
                        translateX.toFloat(), translateY.toFloat(),
                    )
                }

                override fun applyMultiLayerSimpleTransform(layerIds: List<Long>, operation: String) {
                    val ids = layerIds.map { it.toInt() }
                    if (ids.isEmpty()) throw FlutterError("INVALID_ARG", "layerIds is empty", null)
                    viewModel.applyMultiLayerSimpleTransform(ids, operation)
                }

                // ── Session 7: Liquify ──
                override fun beginLiquify() { viewModel.beginLiquify() }

                override fun liquifyLayer(
                    cx: Double, cy: Double, radius: Double,
                    dirX: Double, dirY: Double, pressure: Double, mode: Long,
                ) {
                    viewModel.liquifyActiveLayer(
                        cx.toFloat(), cy.toFloat(), radius.toFloat(),
                        dirX.toFloat(), dirY.toFloat(), pressure.toFloat(), mode.toInt(),
                    )
                }

                override fun endLiquify() { viewModel.endLiquify() }

                // ── Session 7: Pixel Copy ──
                override fun startPixelCopy(layerIds: List<Long>?): com.propaint.app.flutter.pigeon.PixelCopyBounds {
                    val ids = layerIds?.map { it.toInt() }
                    val bounds = viewModel.startPixelCopy(ids)
                    return com.propaint.app.flutter.pigeon.PixelCopyBounds(
                        left = (bounds["left"] ?: 0).toLong(),
                        top = (bounds["top"] ?: 0).toLong(),
                        right = (bounds["right"] ?: 0).toLong(),
                        bottom = (bounds["bottom"] ?: 0).toLong(),
                    )
                }

                override fun applyPixelCopy(
                    x: Long, y: Long, scaleX: Double, scaleY: Double, rotation: Double,
                ) {
                    viewModel.applyPixelCopy(
                        x.toInt(), y.toInt(),
                        scaleX.toFloat(), scaleY.toFloat(), rotation.toFloat(),
                    )
                }

                override fun cancelPixelCopy() { viewModel.cancelPixelCopy() }

                // ── Session 7: Pixel Movement ──
                override fun setLayersOffset(layerIds: List<Long>, offsetX: Double, offsetY: Double) {
                    val ids = layerIds.map { it.toInt() }
                    viewModel.setLayersOffset(ids, offsetX.toFloat(), offsetY.toFloat())
                }

                override fun resetLayersOffset(layerIds: List<Long>) {
                    val ids = layerIds.map { it.toInt() }
                    viewModel.resetLayersOffset(ids)
                }

                override fun commitPixelMovement(layerIds: List<Long>) {
                    val ids = layerIds.map { it.toInt() }
                    viewModel.commitPixelMovement(ids)
                }

                // ── Session 8: Filter (全ピクセル走査、Default ディスパッチャへ) ──
                override fun applyUnsharpMask(
                    radius: Long, amount: Double, threshold: Long,
                    callback: (Result<Unit>) -> Unit,
                ) = runFilter(callback) {
                    viewModel.applyUnsharpMask(radius.toInt(), amount.toFloat(), threshold.toInt())
                }

                override fun applyMosaic(blockSize: Long, callback: (Result<Unit>) -> Unit) =
                    runFilter(callback) { viewModel.applyMosaic(blockSize.toInt()) }

                override fun applyNoise(
                    amount: Long, monochrome: Boolean,
                    callback: (Result<Unit>) -> Unit,
                ) = runFilter(callback) { viewModel.applyNoise(amount.toInt(), monochrome) }

                override fun applyPosterize(levels: Long, callback: (Result<Unit>) -> Unit) =
                    runFilter(callback) { viewModel.applyPosterize(levels.toInt()) }

                override fun applyThreshold(threshold: Long, callback: (Result<Unit>) -> Unit) =
                    runFilter(callback) { viewModel.applyThreshold(threshold.toInt()) }

                override fun applyInvertColors(callback: (Result<Unit>) -> Unit) =
                    runFilter(callback) { viewModel.applyInvertColors() }

                override fun applyMotionBlur(
                    angleDeg: Double, distance: Long,
                    callback: (Result<Unit>) -> Unit,
                ) = runFilter(callback) {
                    viewModel.applyMotionBlur(angleDeg.toFloat(), distance.toInt())
                }

                override fun applyLinearGradient(
                    startX: Double, startY: Double, endX: Double, endY: Double,
                    startColor: Long, endColor: Long,
                    callback: (Result<Unit>) -> Unit,
                ) = runFilter(callback) {
                    viewModel.applyLinearGradient(
                        startX.toFloat(), startY.toFloat(),
                        endX.toFloat(), endY.toFloat(),
                        startColor.toInt(), endColor.toInt(),
                    )
                }

                override fun applyLinearGradientAngle(
                    angleDeg: Double, startColor: Long, endColor: Long,
                    callback: (Result<Unit>) -> Unit,
                ) = runFilter(callback) {
                    viewModel.applyLinearGradientAngle(
                        angleDeg.toFloat(), startColor.toInt(), endColor.toInt(),
                    )
                }

                override fun applyRadialGradient(
                    centerX: Double, centerY: Double, radius: Double,
                    startColor: Long, endColor: Long,
                    callback: (Result<Unit>) -> Unit,
                ) = runFilter(callback) {
                    viewModel.applyRadialGradient(
                        centerX.toFloat(), centerY.toFloat(), radius.toFloat(),
                        startColor.toInt(), endColor.toInt(),
                    )
                }

                override fun applyRadialGradientCenter(
                    startColor: Long, endColor: Long,
                    callback: (Result<Unit>) -> Unit,
                ) = runFilter(callback) {
                    viewModel.applyRadialGradientCenter(startColor.toInt(), endColor.toInt())
                }

                override fun applyLevels(
                    inBlack: Long, inWhite: Long, gamma: Double,
                    outBlack: Long, outWhite: Long,
                    callback: (Result<Unit>) -> Unit,
                ) = runFilter(callback) {
                    viewModel.applyLevels(
                        inBlack.toInt(), inWhite.toInt(), gamma.toFloat(),
                        outBlack.toInt(), outWhite.toInt(),
                    )
                }

                override fun applyColorBalance(
                    cyanRed: Long, magentaGreen: Long, yellowBlue: Long,
                    callback: (Result<Unit>) -> Unit,
                ) = runFilter(callback) {
                    viewModel.applyColorBalance(
                        cyanRed.toInt(), magentaGreen.toInt(), yellowBlue.toInt(),
                    )
                }

                // ── Session 9: Mask ──
                override fun addLayerMask(fillWhite: Boolean) {
                    viewModel.addMaskToActiveLayer(fillWhite)
                }
                override fun removeLayerMask() { viewModel.removeMaskFromActiveLayer() }
                override fun toggleMaskEnabled() { viewModel.toggleMaskEnabled() }
                override fun toggleEditMask() { viewModel.toggleEditMask() }
                override fun addMaskFromSelection() { viewModel.addMaskFromSelection() }

                // ── Session 9: Shape / Fill / Text ──
                override fun drawShape(
                    shapeType: String,
                    left: Long, top: Long, right: Long, bottom: Long,
                    fill: Boolean, thickness: Double,
                ) {
                    viewModel.drawShape(
                        shapeType, left.toInt(), top.toInt(), right.toInt(), bottom.toInt(),
                        fill, thickness.toFloat(),
                    )
                }

                override fun floodFill(x: Long, y: Long, tolerance: Long) {
                    viewModel.floodFill(x.toInt(), y.toInt(), tolerance.toInt())
                }

                override fun setFloodFillTolerance(tolerance: Long) {
                    viewModel.setFloodFillTolerance(tolerance.toInt())
                }

                override fun addTextLayer(
                    text: String, fontSize: Double, x: Double, y: Double,
                    bold: Boolean, italic: Boolean, vertical: Boolean,
                ) {
                    viewModel.addTextLayer(
                        text, fontSize.toFloat(), x.toFloat(), y.toFloat(),
                        bold, italic, vertical,
                    )
                }

                // ── Session 9: Save / Export / Import / Gallery ──
                override fun saveProject(callback: (Result<Unit>) -> Unit) =
                    runFilter(callback) { viewModel.saveCurrentProject() }

                override fun returnToGallery() { viewModel.onReturnToGallery?.invoke() }

                override fun requestExport(format: String) {
                    viewModel.onExportRequest?.invoke(format)
                }

                override fun requestImport(type: String) {
                    viewModel.onImportRequest?.invoke(type)
                }

                // ── Session 10: View + Tool Mode ──
                override fun activateEyedropper() { viewModel.activateEyedropper() }
                override fun deactivateEyedropper() { viewModel.deactivateEyedropper() }

                override fun setToolMode(mode: com.propaint.app.flutter.pigeon.ToolMode) {
                    val engineMode = when (mode) {
                        com.propaint.app.flutter.pigeon.ToolMode.DRAW -> com.propaint.app.viewmodel.ToolMode.Draw
                        com.propaint.app.flutter.pigeon.ToolMode.EYEDROPPER -> com.propaint.app.viewmodel.ToolMode.Eyedropper
                        com.propaint.app.flutter.pigeon.ToolMode.SELECT_MAGNET -> com.propaint.app.viewmodel.ToolMode.SelectMagnet
                        com.propaint.app.flutter.pigeon.ToolMode.SELECT_MAGIC_WAND -> com.propaint.app.viewmodel.ToolMode.SelectMagicWand
                        com.propaint.app.flutter.pigeon.ToolMode.SELECT_PEN -> com.propaint.app.viewmodel.ToolMode.SelectPen
                        com.propaint.app.flutter.pigeon.ToolMode.TRANSFORM -> com.propaint.app.viewmodel.ToolMode.Transform
                        com.propaint.app.flutter.pigeon.ToolMode.PIXEL_COPY -> com.propaint.app.viewmodel.ToolMode.PixelCopy
                        com.propaint.app.flutter.pigeon.ToolMode.SHAPE_LINE -> com.propaint.app.viewmodel.ToolMode.ShapeLine
                        com.propaint.app.flutter.pigeon.ToolMode.SHAPE_RECT -> com.propaint.app.viewmodel.ToolMode.ShapeRect
                        com.propaint.app.flutter.pigeon.ToolMode.SHAPE_ELLIPSE -> com.propaint.app.viewmodel.ToolMode.ShapeEllipse
                        com.propaint.app.flutter.pigeon.ToolMode.FLOOD_FILL -> com.propaint.app.viewmodel.ToolMode.FloodFill
                        com.propaint.app.flutter.pigeon.ToolMode.GRADIENT -> com.propaint.app.viewmodel.ToolMode.Gradient
                        com.propaint.app.flutter.pigeon.ToolMode.TEXT -> com.propaint.app.viewmodel.ToolMode.Text
                    }
                    viewModel.setToolMode(engineMode)
                }

                override fun resetView() { viewModel.resetView() }

                override fun getViewTransform(): com.propaint.app.flutter.pigeon.ViewTransform {
                    val map = viewModel.getViewTransform()
                    return com.propaint.app.flutter.pigeon.ViewTransform(
                        zoom = (map["zoom"] as? Double) ?: 1.0,
                        panX = (map["panX"] as? Double) ?: 0.0,
                        panY = (map["panY"] as? Double) ?: 0.0,
                        rotation = (map["rotation"] as? Double) ?: 0.0,
                        surfaceWidth = ((map["surfaceWidth"] as? Int) ?: 0).toLong(),
                        surfaceHeight = ((map["surfaceHeight"] as? Int) ?: 0).toLong(),
                        docWidth = ((map["docWidth"] as? Int) ?: 0).toLong(),
                        docHeight = ((map["docHeight"] as? Int) ?: 0).toLong(),
                    )
                }

                // ── Session 11: State Stream (A 案: 並走) ──
                // 初期化・リカバリ用の同期取得。差分配信は既存 EventChannel を継続使用。
                override fun getState(): com.propaint.app.flutter.pigeon.PaintState = buildPaintState()
            },
        )

        // Final: FlutterApi インスタンスを生成し、状態ストリーム / ジェスチャ / エラー通知を全て配線。
        val api = com.propaint.app.flutter.pigeon.PaintFlutterApi(
            flutterEngine.dartExecutor.binaryMessenger,
        )
        paintFlutterApi = api

        // ジェスチャ通知: ネイティブ → Flutter（例: 2 本指タップ = undo/redo）
        viewModel.onGestureEvent = { gestureType ->
            lifecycleScope.launch(Dispatchers.Main) {
                api.onNativeGesture(gestureType) { /* ignore delivery result */ }
            }
        }

        // エラーメッセージ通知: ネイティブ → Flutter
        lifecycleScope.launch {
            viewModel.errorMessage.collect { message ->
                api.onErrorMessage(message) { /* ignore */ }
            }
        }

        // 状態ストリーム: StateFlow 変更 → PaintFlutterApi.onStateChanged（フルスナップショット）
        startPaintStateObserver()

        // エクスポート/インポート要求コールバック
        viewModel.onExportRequest = { format ->
            runOnUiThread { launchExport(format) }
        }
        viewModel.onImportRequest = { type ->
            runOnUiThread { launchImport(type) }
        }
        // ギャラリーに戻る（保存は非同期で完了後に finish）
        viewModel.onReturnToGallery = {
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.saveCurrentProject()
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    /**
     * Session 8 フィルター専用ヘルパー: Default ディスパッチャで重い処理を実行し、
     * Main スレッドで Pigeon callback を返す。例外は Result.failure として透過。
     */
    private fun runFilter(callback: (Result<Unit>) -> Unit, block: () -> Unit) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                block()
                withContext(Dispatchers.Main) { callback(Result.success(Unit)) }
            } catch (e: Exception) {
                PaintDebug.d(PaintDebug.Layer) { "[Pigeon Filter] error: ${e.message}" }
                withContext(Dispatchers.Main) { callback(Result.failure(e)) }
            }
        }
    }

    /**
     * Final: 旧 `sendDiff` 相当の StateFlow 監視を Pigeon FlutterApi 経由に置換。
     * 差分送信は廃止し、変化検出時に PaintState フルスナップショットを push する
     * （Pigeon 生成 data class の equals により同一状態は再送抑制）。
     */
    @OptIn(FlowPreview::class)
    private fun startPaintStateObserver() {
        stateObserverJob?.cancel()
        stateObserverJob = lifecycleScope.launch {
            // ブラシ設定 (軽量): sample で 32ms 間隔の最新値を発行
            launch {
                combine(
                    viewModel.brushSize,
                    viewModel.brushOpacity,
                    viewModel.brushHardness,
                    viewModel.brushDensity,
                    viewModel.brushSpacing,
                ) { _ -> Unit }
                    .sample(32L)
                    .collect { sendPaintStateSnapshot() }
            }
            launch {
                combine(
                    viewModel.brushStabilizer,
                    viewModel.colorStretch,
                    viewModel.waterContent,
                    viewModel.blurStrength,
                    viewModel.blurPressureThreshold,
                ) { _ -> Unit }
                    .sample(32L)
                    .collect { sendPaintStateSnapshot() }
            }
            // ブラシタイプ・筆圧トグル (変更時のみ)
            launch {
                combine(
                    viewModel.currentBrushType,
                    viewModel.pressureSizeEnabled,
                    viewModel.pressureOpacityEnabled,
                    viewModel.pressureDensityEnabled,
                ) { a, b, c, d -> "$a-$b-$c-$d" }
                    .distinctUntilChanged()
                    .collect { sendPaintStateSnapshot() }
            }
            // StateFlow は既に distinctUntilChanged 相当の挙動のため明示適用しない
            launch { viewModel.currentColor.collect { sendPaintStateSnapshot() } }
            launch { viewModel.colorHistory.collect { sendPaintStateSnapshot() } }
            launch {
                combine(viewModel.canUndo, viewModel.canRedo) { a, b -> Pair(a, b) }
                    .distinctUntilChanged()
                    .collect { sendPaintStateSnapshot() }
            }
            launch {
                combine(viewModel.toolMode, viewModel.isDrawing) { a, b -> Pair(a, b) }
                    .distinctUntilChanged()
                    .collect { sendPaintStateSnapshot() }
            }
            launch { viewModel.hasSelection.collect { sendPaintStateSnapshot() } }
            launch { viewModel.layers.collect { sendPaintStateSnapshot() } }
            launch { viewModel.selectedLayerIds.collect { sendPaintStateSnapshot() } }
            // ビュー変換 (zoom/pan/rotation) — conflate で高頻度ジェスチャを間引く
            launch { viewModel.viewTransformTick.conflate().collect { sendPaintStateSnapshot() } }
        }
    }

    /** Final: PaintState を生成し、直近送信分と差分があれば FlutterApi.onStateChanged で送信。 */
    private suspend fun sendPaintStateSnapshot() {
        val snapshot = withContext(Dispatchers.Default) { buildPaintState() }
        if (snapshot == lastSentPaintState) return
        lastSentPaintState = snapshot
        withContext(Dispatchers.Main) {
            paintFlutterApi?.onStateChanged(snapshot) { /* ignore delivery result */ }
        }
    }

    /**
     * Session 11: Pigeon `PaintState` スナップショット構築。
     * Pigeon Int は Long 表現のため `.toLong()` / `.toInt()` 変換が必要。
     */
    private fun buildPaintState(): com.propaint.app.flutter.pigeon.PaintState {
        val c = viewModel.currentColor.value
        val vt = viewModel.getViewTransform()
        val viewTransformPigeon = com.propaint.app.flutter.pigeon.ViewTransform(
            zoom = (vt["zoom"] as? Double) ?: 1.0,
            panX = (vt["panX"] as? Double) ?: 0.0,
            panY = (vt["panY"] as? Double) ?: 0.0,
            rotation = (vt["rotation"] as? Double) ?: 0.0,
            surfaceWidth = ((vt["surfaceWidth"] as? Int) ?: 0).toLong(),
            surfaceHeight = ((vt["surfaceHeight"] as? Int) ?: 0).toLong(),
            docWidth = ((vt["docWidth"] as? Int) ?: 0).toLong(),
            docHeight = ((vt["docHeight"] as? Int) ?: 0).toLong(),
        )
        return com.propaint.app.flutter.pigeon.PaintState(
            brushType = viewModel.currentBrushType.value.name,
            brushSize = viewModel.brushSize.value.toDouble(),
            brushOpacity = viewModel.brushOpacity.value.toDouble(),
            brushHardness = viewModel.brushHardness.value.toDouble(),
            brushAntiAliasing = viewModel.brushAntiAliasing.value.toDouble(),
            brushDensity = viewModel.brushDensity.value.toDouble(),
            brushSpacing = viewModel.brushSpacing.value.toDouble(),
            stabilizer = viewModel.brushStabilizer.value.toDouble(),
            minBrushSizePercent = viewModel.minBrushSizePercent.value.toLong(),
            colorStretch = viewModel.colorStretch.value.toDouble(),
            waterContent = viewModel.waterContent.value.toDouble(),
            blurStrength = viewModel.blurStrength.value.toDouble(),
            blurPressureThreshold = viewModel.blurPressureThreshold.value.toDouble(),
            pressureSizeEnabled = viewModel.pressureSizeEnabled.value,
            pressureOpacityEnabled = viewModel.pressureOpacityEnabled.value,
            pressureDensityEnabled = viewModel.pressureDensityEnabled.value,
            pressureSelectionEnabled = viewModel.pressureSelectionEnabled.value,
            currentColor = colorToArgbInt(c).toLong(),
            colorHistory = viewModel.colorHistory.value.map { colorToArgbInt(it).toLong() },
            canUndo = viewModel.canUndo.value,
            canRedo = viewModel.canRedo.value,
            toolMode = toolModeToPigeon(viewModel.toolMode.value),
            isDrawing = viewModel.isDrawing.value,
            hasSelection = viewModel.hasSelection.value,
            selectedLayerIds = viewModel.selectedLayerIds.value.map { it.toLong() },
            layers = viewModel.layers.value.map { layer ->
                com.propaint.app.flutter.pigeon.LayerInfo(
                    id = layer.id.toLong(),
                    name = layer.name,
                    opacity = layer.opacity.toDouble(),
                    blendMode = layer.blendMode.toLong(),
                    isVisible = layer.isVisible,
                    isLocked = layer.isLocked,
                    isClipToBelow = layer.isClipToBelow,
                    isActive = layer.isActive,
                    isAlphaLocked = layer.isAlphaLocked,
                    hasMask = layer.hasMask,
                    isMaskEnabled = layer.isMaskEnabled,
                    isEditingMask = layer.isEditingMask,
                    groupId = layer.groupId.toLong(),
                    isTextLayer = layer.isTextLayer,
                    isGroup = layer.isGroup,
                    depth = layer.depth.toLong(),
                    isExpanded = layer.isExpanded,
                )
            },
            viewTransform = viewTransformPigeon,
            deviceRamMb = MemoryConfig.deviceRamMb.toLong(),
            memoryTier = MemoryConfig.tierName,
            maxCanvasSize = MemoryConfig.maxCanvasSize.toLong(),
            maxLayers = MemoryConfig.maxLayers.toLong(),
            maxBrushSize = MemoryConfig.maxDabDiameter.toLong(),
            maxBlurRadius = MemoryConfig.maxBlurRadius.toLong(),
            maxUndoEntries = MemoryConfig.maxUndoEntries.toLong(),
        )
    }

    /** Session 11: engine `ToolMode` → Pigeon `ToolMode` マッピング。Session 10 の逆変換。 */
    private fun toolModeToPigeon(
        mode: com.propaint.app.viewmodel.ToolMode,
    ): com.propaint.app.flutter.pigeon.ToolMode = when (mode) {
        com.propaint.app.viewmodel.ToolMode.Draw -> com.propaint.app.flutter.pigeon.ToolMode.DRAW
        com.propaint.app.viewmodel.ToolMode.Eyedropper -> com.propaint.app.flutter.pigeon.ToolMode.EYEDROPPER
        com.propaint.app.viewmodel.ToolMode.SelectMagnet -> com.propaint.app.flutter.pigeon.ToolMode.SELECT_MAGNET
        com.propaint.app.viewmodel.ToolMode.SelectMagicWand -> com.propaint.app.flutter.pigeon.ToolMode.SELECT_MAGIC_WAND
        com.propaint.app.viewmodel.ToolMode.SelectPen -> com.propaint.app.flutter.pigeon.ToolMode.SELECT_PEN
        com.propaint.app.viewmodel.ToolMode.Transform -> com.propaint.app.flutter.pigeon.ToolMode.TRANSFORM
        com.propaint.app.viewmodel.ToolMode.PixelCopy -> com.propaint.app.flutter.pigeon.ToolMode.PIXEL_COPY
        com.propaint.app.viewmodel.ToolMode.ShapeLine -> com.propaint.app.flutter.pigeon.ToolMode.SHAPE_LINE
        com.propaint.app.viewmodel.ToolMode.ShapeRect -> com.propaint.app.flutter.pigeon.ToolMode.SHAPE_RECT
        com.propaint.app.viewmodel.ToolMode.ShapeEllipse -> com.propaint.app.flutter.pigeon.ToolMode.SHAPE_ELLIPSE
        com.propaint.app.viewmodel.ToolMode.FloodFill -> com.propaint.app.flutter.pigeon.ToolMode.FLOOD_FILL
        com.propaint.app.viewmodel.ToolMode.Gradient -> com.propaint.app.flutter.pigeon.ToolMode.GRADIENT
        com.propaint.app.viewmodel.ToolMode.Text -> com.propaint.app.flutter.pigeon.ToolMode.TEXT
    }

    /** Compose Color → ARGB Int。PaintState.currentColor / colorHistory で使用。 */
    private fun colorToArgbInt(c: Color): Int =
        (0xFF shl 24) or
        ((c.red * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
        ((c.green * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
        (c.blue * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun launchExport(format: String) {
        pendingExportFormat = format
        val mimeType = when (format) {
            "png" -> "image/png"
            "jpeg" -> "image/jpeg"
            "psd" -> "application/octet-stream"
            "project" -> "application/octet-stream"
            else -> "application/octet-stream"
        }
        val ext = when (format) {
            "png" -> "artwork.png"
            "jpeg" -> "artwork.jpg"
            "psd" -> "artwork.psd"
            "project" -> "artwork.ppaint"
            else -> "artwork.bin"
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, ext)
        }
        startActivityForResult(intent, RC_EXPORT)
    }

    private fun launchImport(type: String) {
        pendingExportFormat = type // reuse field for import type
        val mimeType = when (type) {
            "image" -> "image/*"
            "psd" -> "*/*"       // PSD は image/vnd.adobe.photoshop だが端末によっては未対応
            "project" -> "*/*"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = mimeType
        }
        startActivityForResult(intent, RC_IMPORT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            RC_EXPORT -> handleExport(uri)
            RC_IMPORT -> handleImport(uri)
        }
    }

    private fun handleExport(uri: Uri) {
        val format = pendingExportFormat ?: run {
            PaintDebug.d(PaintDebug.Layer) { "[Export] pendingExportFormat is null, skipping export" }
            return
        }
        // 画���エンコード等の重い I/O を IO スレッドで実行 (ANR 防止)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val os = contentResolver.openOutputStream(uri)
                if (os == null) {
                    PaintDebug.d(PaintDebug.Layer) { "[Export] openOutputStream returned null for $uri" }
                    return@launch
                }
                os.use {
                    PaintDebug.d(PaintDebug.Layer) { "[Export] starting $format export" }
                    when (format) {
                        "png" -> viewModel.exportPng(it)
                        "jpeg" -> viewModel.exportJpeg(it)
                        "psd" -> viewModel.exportPsd(it)
                        "project" -> viewModel.saveProject(it)
                    }
                    PaintDebug.d(PaintDebug.Layer) { "[Export] $format export completed" }
                }
                val label = when (format) {
                    "png" -> "PNG"
                    "jpeg" -> "JPEG"
                    "psd" -> "PSD"
                    "project" -> "プロジェクト"
                    else -> format
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PaintFlutterActivity, "$label を保存しました", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                PaintDebug.d(PaintDebug.Layer) { "[Export] $format error: ${e.message}" }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PaintFlutterActivity, "保存に失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleImport(uri: Uri) {
        val type = pendingExportFormat ?: return
        // 画像デコード等の重い I/O を IO スレッドで実行 (ANR 防止)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = contentResolver.openInputStream(uri)?.use { ins ->
                    when (type) {
                        "image" -> viewModel.importImageAsLayer(ins)
                        "psd" -> viewModel.importPsd(ins)
                        "project" -> viewModel.loadProject(ins)
                        else -> false
                    }
                } ?: false
                val label = when (type) {
                    "image" -> "画像をレイヤーとしてインポート"
                    "psd" -> "PSD をインポート"
                    "project" -> "プロジェクトを読み込み"
                    else -> "インポート"
                }
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@PaintFlutterActivity, "${label}しました", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@PaintFlutterActivity, "${label}に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PaintFlutterActivity, "読み込みに失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        // Pigeon ハンドラ解除
        PaintHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger, null)
        stateObserverJob?.cancel()
        stateObserverJob = null
        paintFlutterApi = null
        lastSentPaintState = null
        viewModel.onGestureEvent = null
        viewModel.onExportRequest = null
        viewModel.onImportRequest = null
        viewModel.onReturnToGallery = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onResume() {
        super.onResume()
        // onResume で savedOnPause フラグをリセット
        savedOnPause = false
    }

    override fun onPause() {
        super.onPause()
        // 非同期で保存（UI スレッドをブロックしない → ANR 防止）
        savedOnPause = true
        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.saveCurrentProject()
            PaintDebug.d(PaintDebug.Perf) { "[Lifecycle] onPause save completed" }
        }
    }

    override fun onStop() {
        super.onStop()
        // onPause で既に保存を開始済みなら重複しない
        if (!savedOnPause) {
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.saveCurrentProject()
                PaintDebug.d(PaintDebug.Perf) { "[Lifecycle] onStop save completed" }
            }
        }
    }

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val VIEW_TYPE = "paint-gl-canvas"
        private const val RC_EXPORT = 1001
        private const val RC_IMPORT = 1002
        private const val KEY_PENDING_FORMAT = "pending_export_format"
    }
}
