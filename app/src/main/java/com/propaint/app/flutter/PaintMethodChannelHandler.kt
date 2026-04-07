package com.propaint.app.flutter

import androidx.compose.ui.graphics.Color
import com.propaint.app.engine.MemoryConfig
import com.propaint.app.engine.PaintDebug
import com.propaint.app.viewmodel.BrushType
import com.propaint.app.viewmodel.PaintViewModel
import com.propaint.app.viewmodel.UiLayer
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample

/**
 * Flutter ↔ Kotlin MethodChannel / EventChannel ハンドラ。
 *
 * MethodChannel (com.propaint.app/paint):
 *   Flutter → Kotlin のコマンド（ブラシ設定、レイヤー操作、undo/redo 等）
 *
 * EventChannel (com.propaint.app/state):
 *   Kotlin → Flutter の状態同期ストリーム（StateFlow を debounce して push）
 */
class PaintMethodChannelHandler(
    messenger: BinaryMessenger,
    private val viewModel: PaintViewModel,
) : MethodChannel.MethodCallHandler {

    private val methodChannel = MethodChannel(messenger, CHANNEL_METHOD)
    private val stateEventChannel = EventChannel(messenger, CHANNEL_STATE)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var eventSink: EventChannel.EventSink? = null

    init {
        methodChannel.setMethodCallHandler(this)
        stateEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
                startStateObserver()
            }
            override fun onCancel(arguments: Any?) {
                eventSink = null
                stateObserverJob?.cancel()
            }
        })

        // ジェスチャ通知: ネイティブ→Flutter (2本指tap=undo 等)
        viewModel.onGestureEvent = { gestureType ->
            scope.launch(Dispatchers.Main) {
                methodChannel.invokeMethod("onNativeGesture", mapOf("type" to gestureType))
            }
        }
    }

    // ─── MethodChannel Handler ─────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            dispatch(call, result)
        } catch (e: Exception) {
            PaintDebug.d(PaintDebug.Layer) {
                "[MethodChannel] error method=${call.method} ${e.message}"
            }
            result.error("PAINT_ERROR", e.message, null)
        }
    }

    private fun dispatch(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            // ── ブラシ設定 ──
            "setBrushType" -> {
                val name = call.argument<String>("type")
                    ?: return result.error("INVALID_ARG", "type is required", null)
                val type = BrushType.entries.find { it.name == name }
                    ?: return result.error("INVALID_ARG", "Unknown BrushType: $name", null)
                viewModel.setBrushType(type)
                result.success(null)
            }
            "setBrushSize" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                if (v <= 0f) return result.error("INVALID_ARG", "BrushSize must be > 0", null)
                viewModel.setBrushSize(v)
                result.success(null)
            }
            "setBrushOpacity" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                if (v !in 0f..1f) return result.error("INVALID_ARG", "opacity must be in 0..1", null)
                viewModel.setBrushOpacity(v)
                result.success(null)
            }
            "setBrushHardness" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                if (v !in 0f..1f) return result.error("INVALID_ARG", "hardness must be in 0..1", null)
                viewModel.setBrushHardness(v)
                result.success(null)
            }
            "setBrushAntiAliasing" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                if (v !in 0f..1f) return result.error("INVALID_ARG", "antiAliasing must be in 0..1", null)
                viewModel.setBrushAntiAliasing(v)
                result.success(null)
            }
            "setBrushDensity" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                viewModel.setBrushDensity(v)
                result.success(null)
            }
            "setBrushSpacing" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                if (v <= 0f) return result.error("INVALID_ARG", "spacing must be > 0", null)
                viewModel.setBrushSpacing(v)
                result.success(null)
            }
            "setBrushMinSizePercent" -> {
                val p = call.argument<Int>("percent")
                    ?: return result.error("INVALID_ARG", "percent is required", null)
                if (p !in 1..100) return result.error("INVALID_ARG", "percent must be in 1..100", null)
                viewModel.setBrushMinSizePercent(p)
                result.success(null)
            }
            "setStabilizer" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                viewModel.setStabilizer(v)
                result.success(null)
            }
            "setColorStretch" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                viewModel.setColorStretch(v)
                result.success(null)
            }
            "setWaterContent" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                viewModel.setWaterContent(v)
                result.success(null)
            }
            "setBlurStrength" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                viewModel.setBlurStrength(v)
                result.success(null)
            }
            "setBlurPressureThreshold" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                viewModel.setBlurPressureThreshold(v)
                result.success(null)
            }

            // ── 筆圧トグル ──
            "togglePressureSize" -> { viewModel.togglePressureSize(); result.success(null) }
            "togglePressureOpacity" -> { viewModel.togglePressureOpacity(); result.success(null) }
            "togglePressureDensity" -> { viewModel.togglePressureDensity(); result.success(null) }
            "togglePressureSelection" -> { viewModel.togglePressureSelection(); result.success(null) }

            // ── カラー ──
            "setColor" -> {
                val argb = call.argument<Number>("argb")?.toInt()
                    ?: return result.error("INVALID_ARG", "argb is required", null)
                val r = ((argb shr 16) and 0xFF) / 255f
                val g = ((argb shr 8) and 0xFF) / 255f
                val b = (argb and 0xFF) / 255f
                viewModel.setColor(Color(r, g, b))
                result.success(null)
            }
            "commitColor" -> { viewModel.commitColorToHistory(); result.success(null) }

            // ── Undo / Redo ──
            "undo" -> { viewModel.undo(); result.success(null) }
            "redo" -> { viewModel.redo(); result.success(null) }

            // ── レイヤー操作（重い処理は Default ディスパッチャにオフロード） ──
            "addLayer" -> launchHeavy(result) { viewModel.addLayer() }
            "removeLayer" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                launchHeavy(result) { viewModel.removeLayer(id) }
            }
            "selectLayer" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                viewModel.selectLayer(id)
                result.success(null)
            }
            "setLayerVisibility" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                val visible = call.argument<Boolean>("visible")
                    ?: return result.error("INVALID_ARG", "visible is required", null)
                viewModel.setLayerVisibility(id, visible)
                result.success(null)
            }
            "setLayerOpacity" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                val opacity = call.argument<Double>("opacity")?.toFloat()
                    ?: return result.error("INVALID_ARG", "opacity is required", null)
                launchHeavy(result) { viewModel.setLayerOpacity(id, opacity) }
            }
            "setLayerBlendMode" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                val mode = call.argument<Int>("mode")
                    ?: return result.error("INVALID_ARG", "mode is required", null)
                launchHeavy(result) { viewModel.setLayerBlendMode(id, mode) }
            }
            "setLayerClip" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                val clip = call.argument<Boolean>("clip")
                    ?: return result.error("INVALID_ARG", "clip is required", null)
                viewModel.setLayerClip(id, clip)
                result.success(null)
            }
            "setLayerLocked" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                val locked = call.argument<Boolean>("locked")
                    ?: return result.error("INVALID_ARG", "locked is required", null)
                viewModel.setLayerLocked(id, locked)
                result.success(null)
            }
            "setAlphaLocked" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                val locked = call.argument<Boolean>("locked")
                    ?: return result.error("INVALID_ARG", "locked is required", null)
                viewModel.setAlphaLocked(id, locked)
                result.success(null)
            }
            "reorderLayer" -> {
                val fromIndex = call.argument<Int>("fromIndex")
                    ?: return result.error("INVALID_ARG", "fromIndex is required", null)
                val toIndex = call.argument<Int>("toIndex")
                    ?: return result.error("INVALID_ARG", "toIndex is required", null)
                viewModel.reorderLayer(fromIndex, toIndex)
                result.success(null)
            }
            "duplicateLayer" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                launchHeavy(result) { viewModel.duplicateLayer(id) }
            }
            "mergeDown" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                launchHeavy(result) { viewModel.mergeDown(id) }
            }
            "moveLayerUp" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                launchHeavy(result) { viewModel.moveLayerUp(id) }
            }
            "moveLayerDown" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                launchHeavy(result) { viewModel.moveLayerDown(id) }
            }

            "batchMergeLayers" -> {
                @Suppress("UNCHECKED_CAST")
                val ids = call.argument<List<Int>>("ids")
                    ?: return result.error("INVALID_ARG", "ids is required", null)
                launchHeavy(result) { viewModel.batchMergeLayers(ids) }
            }
            "batchMoveLayersUp" -> {
                @Suppress("UNCHECKED_CAST")
                val ids = call.argument<List<Int>>("ids")
                    ?: return result.error("INVALID_ARG", "ids is required", null)
                launchHeavy(result) { viewModel.batchMoveLayersUp(ids) }
            }
            "batchMoveLayersDown" -> {
                @Suppress("UNCHECKED_CAST")
                val ids = call.argument<List<Int>>("ids")
                    ?: return result.error("INVALID_ARG", "ids is required", null)
                launchHeavy(result) { viewModel.batchMoveLayersDown(ids) }
            }

            // ── レイヤーグループ ──
            "createLayerGroup" -> {
                val name = call.argument<String>("name") ?: "フォルダ"
                launchHeavy(result) { viewModel.createLayerGroup(name) }
            }
            "deleteLayerGroup" -> {
                val groupId = call.argument<Int>("groupId") ?: return result.error("INVALID_ARG", "groupId is required", null)
                launchHeavy(result) { viewModel.deleteLayerGroup(groupId) }
            }
            "setLayerGroup" -> {
                val layerId = call.argument<Int>("layerId") ?: return result.error("INVALID_ARG", "layerId is required", null)
                val groupId = call.argument<Int>("groupId") ?: return result.error("INVALID_ARG", "groupId is required", null)
                launchHeavy(result) { viewModel.setLayerGroup(layerId, groupId) }
            }
            "setGroupVisibility" -> {
                val groupId = call.argument<Int>("groupId") ?: return result.error("INVALID_ARG", "groupId is required", null)
                val visible = call.argument<Boolean>("visible") ?: true
                launchHeavy(result) { viewModel.setGroupVisibility(groupId, visible) }
            }
            "setGroupOpacity" -> {
                val groupId = call.argument<Int>("groupId") ?: return result.error("INVALID_ARG", "groupId is required", null)
                val opacity = call.argument<Double>("opacity")?.toFloat()?.coerceIn(0f, 1f) ?: 1f
                launchHeavy(result) { viewModel.setGroupOpacity(groupId, opacity) }
            }
            "reorderLayerGroup" -> {
                val fromGroupId = call.argument<Int>("fromGroupId") ?: return result.error("INVALID_ARG", "fromGroupId is required", null)
                val toGroupId = call.argument<Int>("toGroupId") ?: return result.error("INVALID_ARG", "toGroupId is required", null)
                launchHeavy(result) { viewModel.reorderLayerGroup(fromGroupId, toGroupId) }
            }
            "reorderDisplayItem" -> {
                val fromId = call.argument<Int>("fromId") ?: return result.error("INVALID_ARG", "fromId is required", null)
                val toId = call.argument<Int>("toId") ?: return result.error("INVALID_ARG", "toId is required", null)
                launchHeavy(result) { viewModel.reorderDisplayItem(fromId, toId) }
            }

            // ── ピクセル移動機能（将来実装向け対応） ──
            "setLayersOffset" -> {
                @Suppress("UNCHECKED_CAST")
                val layerIds = call.argument<List<Int>>("layerIds") ?: return result.error("INVALID_ARG", "layerIds is required", null)
                val offsetX = call.argument<Double>("offsetX")?.toFloat() ?: 0f
                val offsetY = call.argument<Double>("offsetY")?.toFloat() ?: 0f
                launchHeavy(result) { viewModel.setLayersOffset(layerIds, offsetX, offsetY) }
            }
            "resetLayersOffset" -> {
                @Suppress("UNCHECKED_CAST")
                val layerIds = call.argument<List<Int>>("layerIds") ?: return result.error("INVALID_ARG", "layerIds is required", null)
                launchHeavy(result) { viewModel.resetLayersOffset(layerIds) }
            }
            "commitPixelMovement" -> {
                @Suppress("UNCHECKED_CAST")
                val layerIds = call.argument<List<Int>>("layerIds") ?: return result.error("INVALID_ARG", "layerIds is required", null)
                launchHeavy(result) { viewModel.commitPixelMovement(layerIds) }
            }
            "getLayersInGroup" -> {
                val groupId = call.argument<Int>("groupId") ?: return result.error("INVALID_ARG", "groupId is required", null)
                result.success(viewModel.getLayersInGroup(groupId))
            }
            "getLayersInGroupRecursive" -> {
                val groupId = call.argument<Int>("groupId") ?: return result.error("INVALID_ARG", "groupId is required", null)
                result.success(viewModel.getLayersInGroupRecursive(groupId))
            }

            // ── ツールモード ──
            "activateEyedropper" -> { viewModel.activateEyedropper(); result.success(null) }
            "deactivateEyedropper" -> { viewModel.deactivateEyedropper(); result.success(null) }

            // ── ビュー ──
            "resetView" -> { viewModel.resetView(); result.success(null) }

            // ── 保存/ギャラリー遷移 ──
            "returnToGallery" -> {
                viewModel.onReturnToGallery?.invoke()
                result.success(null)
            }
            "saveProject" -> launchHeavy(result) { viewModel.saveCurrentProject() }
            "requestExport" -> {
                val format = call.argument<String>("format") ?: "png"
                viewModel.onExportRequest?.invoke(format)
                result.success(null)
            }
            "requestImport" -> {
                val type = call.argument<String>("type") ?: "image"
                viewModel.onImportRequest?.invoke(type)
                result.success(null)
            }

            // ── ブラシ設定のエクスポート/インポート/リセット ──
            "exportBrushSettings" -> result.success(viewModel.exportBrushSettings())
            "importBrushSettings" -> {
                val json = call.argument<String>("json")
                    ?: return result.error("INVALID_ARG", "json is required", null)
                result.success(viewModel.importBrushSettings(json))
            }
            "resetBrushToDefaults" -> {
                viewModel.resetBrushToDefaults()
                result.success(null)
            }

            // ── 選択ツール ──
            "selectRect" -> {
                val l = call.argument<Int>("left") ?: 0; val t = call.argument<Int>("top") ?: 0
                val r = call.argument<Int>("right") ?: 0; val b = call.argument<Int>("bottom") ?: 0
                viewModel.selectRect(l, t, r, b); result.success(null)
            }
            "selectEllipse" -> {
                val l = call.argument<Int>("left") ?: 0; val t = call.argument<Int>("top") ?: 0
                val r = call.argument<Int>("right") ?: 0; val b = call.argument<Int>("bottom") ?: 0
                viewModel.selectEllipse(l, t, r, b); result.success(null)
            }
            "selectByColor" -> {
                val x = call.argument<Int>("x") ?: 0; val y = call.argument<Int>("y") ?: 0
                val tol = call.argument<Int>("tolerance") ?: 32
                val contiguous = call.argument<Boolean>("contiguous") ?: true
                viewModel.selectByColor(x, y, tol, contiguous); result.success(null)
            }
            "selectAll" -> { viewModel.selectAll(); result.success(null) }
            "clearSelection" -> { viewModel.clearSelection(); result.success(null) }
            "invertSelection" -> { viewModel.invertSelection(); result.success(null) }
            "paintSelectionAdd" -> {
                val cx = call.argument<Int>("cx") ?: 0
                val cy = call.argument<Int>("cy") ?: 0
                val radius = call.argument<Int>("radius") ?: 10
                val pressure = call.argument<Double>("pressure")?.toFloat() ?: 1f
                viewModel.paintSelectionAdd(cx, cy, radius, pressure); result.success(null)
            }
            "paintSelectionErase" -> {
                val cx = call.argument<Int>("cx") ?: 0
                val cy = call.argument<Int>("cy") ?: 0
                val radius = call.argument<Int>("radius") ?: 10
                val pressure = call.argument<Double>("pressure")?.toFloat() ?: 1f
                viewModel.paintSelectionErase(cx, cy, radius, pressure); result.success(null)
            }

            // ── 選択範囲操作 ──
            "deleteSelection" -> { viewModel.deleteSelection(); result.success(null) }
            "fillSelection" -> {
                val color = call.argument<Int>("color") ?: 0xFF000000.toInt()
                viewModel.fillSelection(color); result.success(null)
            }
            "copySelection" -> { viewModel.copySelection(); result.success(null) }
            "cutSelection" -> { viewModel.cutSelection(); result.success(null) }
            "moveSelection" -> {
                val dx = call.argument<Int>("dx") ?: 0
                val dy = call.argument<Int>("dy") ?: 0
                viewModel.moveSelection(dx, dy); result.success(null)
            }
            "featherSelection" -> {
                val radius = call.argument<Int>("radius") ?: 5
                viewModel.featherSelection(radius); result.success(null)
            }
            "expandSelection" -> {
                val amount = call.argument<Int>("amount") ?: 5
                viewModel.expandSelection(amount); result.success(null)
            }
            "cancelMagnetSelection" -> {
                viewModel.cancelMagnetSelection(); result.success(null)
            }

            // ── 変形 ──
            "flipLayerH" -> { viewModel.flipActiveLayerH(); result.success(null) }
            "flipLayerV" -> { viewModel.flipActiveLayerV(); result.success(null) }
            "rotateLayer90CW" -> { viewModel.rotateActiveLayer90CW(); result.success(null) }
            "transformLayer" -> {
                val sx = call.argument<Double>("scaleX")?.toFloat() ?: 1f
                val sy = call.argument<Double>("scaleY")?.toFloat() ?: 1f
                val angle = call.argument<Double>("angle")?.toFloat() ?: 0f
                val tx = call.argument<Double>("translateX")?.toFloat() ?: 0f
                val ty = call.argument<Double>("translateY")?.toFloat() ?: 0f
                launchHeavy(result) { viewModel.transformActiveLayer(sx, sy, angle, tx, ty) }
            }
            "distortLayer" -> {
                val corners = call.argument<List<Double>>("corners")
                if (corners == null || corners.size < 8) { result.error("INVALID", "corners required (8 values)", null); return }
                val arr = FloatArray(8) { corners[it].toFloat() }
                launchHeavy(result) { viewModel.distortActiveLayer(arr) }
            }
            "meshWarpLayer" -> {
                val gridW = call.argument<Int>("gridW") ?: 3
                val gridH = call.argument<Int>("gridH") ?: 3
                val nodesList = call.argument<List<Double>>("nodes")
                if (nodesList == null) { result.error("INVALID", "nodes required", null); return }
                val nodes = FloatArray(nodesList.size) { nodesList[it].toFloat() }
                launchHeavy(result) { viewModel.meshWarpActiveLayer(gridW, gridH, nodes) }
            }
            "beginLiquify" -> { viewModel.beginLiquify(); result.success(null) }
            "liquifyLayer" -> {
                val cx = call.argument<Double>("cx")?.toFloat() ?: 0f
                val cy = call.argument<Double>("cy")?.toFloat() ?: 0f
                val radius = call.argument<Double>("radius")?.toFloat() ?: 50f
                val dirX = call.argument<Double>("dirX")?.toFloat() ?: 0f
                val dirY = call.argument<Double>("dirY")?.toFloat() ?: 0f
                val pressure = call.argument<Double>("pressure")?.toFloat() ?: 1f
                val mode = call.argument<Int>("mode") ?: 0
                viewModel.liquifyActiveLayer(cx, cy, radius, dirX, dirY, pressure, mode); result.success(null)
            }
            "endLiquify" -> { viewModel.endLiquify(); result.success(null) }

            // ── レイヤーマスク ──
            "addLayerMask" -> {
                val fillWhite = call.argument<Boolean>("fillWhite") ?: true
                viewModel.addMaskToActiveLayer(fillWhite); result.success(null)
            }
            "removeLayerMask" -> { viewModel.removeMaskFromActiveLayer(); result.success(null) }
            "toggleMaskEnabled" -> { viewModel.toggleMaskEnabled(); result.success(null) }
            "toggleEditMask" -> { viewModel.toggleEditMask(); result.success(null) }
            "addMaskFromSelection" -> { viewModel.addMaskFromSelection(); result.success(null) }

            // ── 図形・塗りつぶし ──
            "drawShape" -> {
                val type = call.argument<String>("type") ?: "rect"
                val l = call.argument<Int>("left") ?: 0; val t = call.argument<Int>("top") ?: 0
                val r = call.argument<Int>("right") ?: 0; val b = call.argument<Int>("bottom") ?: 0
                val fill = call.argument<Boolean>("fill") ?: true
                val thickness = call.argument<Double>("thickness")?.toFloat() ?: 1f
                viewModel.drawShape(type, l, t, r, b, fill, thickness); result.success(null)
            }
            "floodFill" -> {
                val x = call.argument<Int>("x") ?: 0; val y = call.argument<Int>("y") ?: 0
                val tol = call.argument<Int>("tolerance") ?: 0
                viewModel.floodFill(x, y, tol); result.success(null)
            }

            // ── テキスト ──
            "addTextLayer" -> {
                val text = call.argument<String>("text") ?: ""
                val fontSize = call.argument<Double>("fontSize")?.toFloat() ?: 48f
                val x = call.argument<Double>("x")?.toFloat() ?: 0f
                val y = call.argument<Double>("y")?.toFloat() ?: 0f
                val bold = call.argument<Boolean>("bold") ?: false
                val italic = call.argument<Boolean>("italic") ?: false
                val vertical = call.argument<Boolean>("vertical") ?: false
                viewModel.addTextLayer(text, fontSize, x, y, bold, italic, vertical)
                result.success(null)
            }

            // ── 追加フィルター ──
            "applyUnsharpMask" -> {
                val radius = call.argument<Int>("radius") ?: 1
                val amount = call.argument<Double>("amount")?.toFloat() ?: 1f
                val threshold = call.argument<Int>("threshold") ?: 0
                launchHeavy(result) { viewModel.applyUnsharpMask(radius, amount, threshold) }
            }
            "applyMosaic" -> {
                val blockSize = call.argument<Int>("blockSize") ?: 10
                launchHeavy(result) { viewModel.applyMosaic(blockSize) }
            }
            "applyNoise" -> {
                val amount = call.argument<Int>("amount") ?: 20
                val mono = call.argument<Boolean>("monochrome") ?: true
                launchHeavy(result) { viewModel.applyNoise(amount, mono) }
            }
            "applyPosterize" -> {
                val levels = call.argument<Int>("levels") ?: 4
                launchHeavy(result) { viewModel.applyPosterize(levels) }
            }
            "applyThreshold" -> {
                val threshold = call.argument<Int>("threshold") ?: 128
                launchHeavy(result) { viewModel.applyThreshold(threshold) }
            }
            "applyLevels" -> {
                val inB = call.argument<Int>("inBlack") ?: 0
                val inW = call.argument<Int>("inWhite") ?: 255
                val gamma = call.argument<Double>("gamma")?.toFloat() ?: 1f
                val outB = call.argument<Int>("outBlack") ?: 0
                val outW = call.argument<Int>("outWhite") ?: 255
                launchHeavy(result) { viewModel.applyLevels(inB, inW, gamma, outB, outW) }
            }
            "applyColorBalance" -> {
                val cr = call.argument<Int>("cyanRed") ?: 0
                val mg = call.argument<Int>("magentaGreen") ?: 0
                val yb = call.argument<Int>("yellowBlue") ?: 0
                launchHeavy(result) { viewModel.applyColorBalance(cr, mg, yb) }
            }

            // ── ツールモード ──
            "setToolMode" -> {
                val mode = call.argument<String>("mode") ?: "Draw"
                try {
                    viewModel.setToolMode(com.propaint.app.viewmodel.ToolMode.valueOf(mode))
                    result.success(null)
                } catch (e: IllegalArgumentException) {
                    result.error("INVALID_ARG", "unknown tool mode: $mode", null)
                }
            }

            // ── ピクセルコピー変形 ──
            "startPixelCopy" -> {
                val bounds = viewModel.startPixelCopy()
                result.success(bounds)
            }
            "applyPixelCopy" -> {
                val x = call.argument<Int>("x") ?: 0
                val y = call.argument<Int>("y") ?: 0
                val scaleX = call.argument<Double>("scaleX")?.toFloat() ?: 1f
                val scaleY = call.argument<Double>("scaleY")?.toFloat() ?: 1f
                val rotation = call.argument<Double>("rotation")?.toFloat() ?: 0f
                viewModel.applyPixelCopy(x, y, scaleX, scaleY, rotation)
                result.success(null)
            }
            "cancelPixelCopy" -> {
                viewModel.cancelPixelCopy()
                result.success(null)
            }

            // ── 状態取得（一括） ──
            "getState" -> result.success(buildStateMap())

            else -> result.notImplemented()
        }
    }

    // ─── EventChannel: 状態の push ────────────────────────────────

    private var stateObserverJob: Job? = null

    /** 前回送信した状態マップ。差分検出に使用 */
    private var lastSentState: Map<String, Any?> = emptyMap()

    @OptIn(FlowPreview::class)
    private fun startStateObserver() {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            // ── ブラシ設定 (軽量): sample で一定間隔の最新値を発行 ──
            launch {
                combine(
                    viewModel.brushSize,
                    viewModel.brushOpacity,
                    viewModel.brushHardness,
                    viewModel.brushDensity,
                    viewModel.brushSpacing,
                ) { _ -> "brush" }
                    .sample(32L)
                    .collect { sendDiff() }
            }
            // ── ブラシ追加設定 ──
            launch {
                combine(
                    viewModel.brushStabilizer,
                    viewModel.colorStretch,
                    viewModel.waterContent,
                    viewModel.blurStrength,
                    viewModel.blurPressureThreshold,
                ) { _ -> "brush_ext" }
                    .sample(32L)
                    .collect { sendDiff() }
            }
            // ── ブラシタイプ・筆圧トグル (低頻度: 変更時のみ) ──
            launch {
                combine(
                    viewModel.currentBrushType,
                    viewModel.pressureSizeEnabled,
                    viewModel.pressureOpacityEnabled,
                    viewModel.pressureDensityEnabled,
                ) { a, b, c, d -> "$a-$b-$c-$d" }  // 実際の値を含めることで distinctUntilChanged が変化を検出
                    .distinctUntilChanged()
                    .collect { sendDiff() }
            }
            // ── カラー (変更時のみ) ──
            launch {
                viewModel.currentColor
                    .map { it }
                    .distinctUntilChanged()
                    .collect { sendDiff() }
            }
            // ── カラーヒストリー (変更時のみ) ──
            launch {
                viewModel.colorHistory
                    .map { it }
                    .distinctUntilChanged()
                    .collect { sendDiff() }
            }
            // ── Undo/Redo 状態 (変更時のみ) ──
            launch {
                combine(
                    viewModel.canUndo,
                    viewModel.canRedo,
                ) { a, b -> Pair(a, b) }
                    .distinctUntilChanged()
                    .collect { sendDiff() }
            }
            // ── ツールモード・描画中フラグ (変更時のみ) ──
            launch {
                combine(
                    viewModel.toolMode,
                    viewModel.isDrawing,
                ) { a, b -> Pair(a, b) }
                    .distinctUntilChanged()
                    .collect { sendDiff() }
            }
            // ── 選択状態 (変更時のみ) ──
            launch {
                viewModel.hasSelection
                    .collect { sendDiff() }
            }
            // ── レイヤー (変更時のみ — 重いデータ) ──
            launch {
                viewModel.layers
                    .map { it }
                    .distinctUntilChanged()
                    .collect { sendDiff() }
            }
        }
    }

    /**
     * 現在の状態マップを構築し、前回送信分と差分がある項目のみ送信する。
     * Flutter 側は copyWithMap で部分更新するため、変更のあるキーだけ送れば十分。
     */
    private suspend fun sendDiff() {
        val fullMap = withContext(Dispatchers.Default) { buildStateMap() }
        val diff = mutableMapOf<String, Any?>()
        for ((key, value) in fullMap) {
            if (lastSentState[key] != value) {
                diff[key] = value
            }
        }
        if (diff.isNotEmpty()) {
            lastSentState = fullMap
            withContext(Dispatchers.Main) {
                eventSink?.success(diff)
            }
        }
    }

    // ─── 重い処理のオフロード ──────────────────────────────────────

    /**
     * 重いレイヤー操作を Default ディスパッチャで実行し、
     * 完了後に Main スレッドで result.success を返す。
     */
    private fun launchHeavy(result: MethodChannel.Result, block: suspend () -> Unit) {
        scope.launch(Dispatchers.Default) {
            try {
                block()
                withContext(Dispatchers.Main) { result.success(null) }
            } catch (e: Exception) {
                PaintDebug.d(PaintDebug.Layer) {
                    "[MethodChannel] heavy op error: ${e.message}"
                }
                withContext(Dispatchers.Main) {
                    result.error("PAINT_ERROR", e.message, null)
                }
            }
        }
    }

    // ─── ヘルパー ──────────────────────────────────────────────────

    private fun buildStateMap(): Map<String, Any?> {
        val c = viewModel.currentColor.value
        return mapOf(
            "brushType" to viewModel.currentBrushType.value.name,
            "brushSize" to viewModel.brushSize.value.toDouble(),
            "brushOpacity" to viewModel.brushOpacity.value.toDouble(),
            "brushHardness" to viewModel.brushHardness.value.toDouble(),
            "brushAntiAliasing" to viewModel.brushAntiAliasing.value.toDouble(),
            "brushDensity" to viewModel.brushDensity.value.toDouble(),
            "brushSpacing" to viewModel.brushSpacing.value.toDouble(),
            "stabilizer" to viewModel.brushStabilizer.value.toDouble(),
            "colorStretch" to viewModel.colorStretch.value.toDouble(),
            "waterContent" to viewModel.waterContent.value.toDouble(),
            "blurStrength" to viewModel.blurStrength.value.toDouble(),
            "blurPressureThreshold" to viewModel.blurPressureThreshold.value.toDouble(),
            "pressureSizeEnabled" to viewModel.pressureSizeEnabled.value,
            "pressureOpacityEnabled" to viewModel.pressureOpacityEnabled.value,
            "pressureDensityEnabled" to viewModel.pressureDensityEnabled.value,
            "currentColor" to colorToArgbInt(c),
            "canUndo" to viewModel.canUndo.value,
            "canRedo" to viewModel.canRedo.value,
            "toolMode" to viewModel.toolMode.value.name,
            "isDrawing" to viewModel.isDrawing.value,
            "hasSelection" to viewModel.hasSelection.value,
            "layers" to serializeLayers(viewModel.layers.value),
            "colorHistory" to viewModel.colorHistory.value.map { colorToArgbInt(it) },
            // デバイスメモリ情報
            "deviceRamMb" to MemoryConfig.deviceRamMb,
            "memoryTier" to MemoryConfig.tierName,
            "maxCanvasSize" to MemoryConfig.maxCanvasSize,
            "maxLayers" to MemoryConfig.maxLayers,
            "maxBrushSize" to MemoryConfig.maxDabDiameter,
            "maxBlurRadius" to MemoryConfig.maxBlurRadius,
            "maxUndoEntries" to MemoryConfig.maxUndoEntries,
        )
    }

    private fun serializeLayers(layers: List<UiLayer>): List<Map<String, Any?>> =
        layers.map { layer ->
            mapOf(
                "id" to layer.id,
                "name" to layer.name,
                "opacity" to layer.opacity.toDouble(),
                "blendMode" to layer.blendMode,
                "isVisible" to layer.isVisible,
                "isLocked" to layer.isLocked,
                "isClipToBelow" to layer.isClipToBelow,
                "isActive" to layer.isActive,
                "isAlphaLocked" to layer.isAlphaLocked,
                "hasMask" to layer.hasMask,
                "isMaskEnabled" to layer.isMaskEnabled,
                "isEditingMask" to layer.isEditingMask,
                "groupId" to layer.groupId,
                "isTextLayer" to layer.isTextLayer,
                "isGroup" to layer.isGroup,
            )
        }

    private fun colorToArgbInt(c: Color): Int =
        (0xFF shl 24) or
        ((c.red * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
        ((c.green * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
        (c.blue * 255f + 0.5f).toInt().coerceIn(0, 255)

    fun dispose() {
        scope.cancel()
        methodChannel.setMethodCallHandler(null)
        eventSink = null
    }

    companion object {
        const val CHANNEL_METHOD = "com.propaint.app/paint"
        const val CHANNEL_STATE = "com.propaint.app/state"
    }
}
