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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge

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
                require(v > 0f) { "BrushSize must be > 0" }
                viewModel.setBrushSize(v)
                result.success(null)
            }
            "setBrushOpacity" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                check(v in 0f..1f) { "opacity must be in 0..1" }
                viewModel.setBrushOpacity(v)
                result.success(null)
            }
            "setBrushHardness" -> {
                val v = call.argument<Double>("value")?.toFloat()
                    ?: return result.error("INVALID_ARG", "value is required", null)
                check(v in 0f..1f) { "hardness must be in 0..1" }
                viewModel.setBrushHardness(v)
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
                require(v > 0f) { "spacing must be > 0" }
                viewModel.setBrushSpacing(v)
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
                viewModel.setLayerOpacity(id, opacity)
                result.success(null)
            }
            "setLayerBlendMode" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                val mode = call.argument<Int>("mode")
                    ?: return result.error("INVALID_ARG", "mode is required", null)
                viewModel.setLayerBlendMode(id, mode)
                result.success(null)
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
                viewModel.moveLayerUp(id)
                result.success(null)
            }
            "moveLayerDown" -> {
                val id = call.argument<Int>("id")
                    ?: return result.error("INVALID_ARG", "id is required", null)
                viewModel.moveLayerDown(id)
                result.success(null)
            }

            "batchMergeLayers" -> {
                @Suppress("UNCHECKED_CAST")
                val ids = call.argument<List<Int>>("ids")
                    ?: return result.error("INVALID_ARG", "ids is required", null)
                launchHeavy(result) { viewModel.batchMergeLayers(ids) }
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

            // ── 状態取得（一括） ──
            "getState" -> result.success(buildStateMap())

            else -> result.notImplemented()
        }
    }

    // ─── EventChannel: 状態の push ────────────────────────────────

    private var stateObserverJob: Job? = null

    @OptIn(FlowPreview::class)
    private fun startStateObserver() {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            // 全 StateFlow を merge し、どれか1つが変化すれば debounce 後に全状態を push
            merge(
                viewModel.brushSize,
                viewModel.brushOpacity,
                viewModel.brushHardness,
                viewModel.brushDensity,
                viewModel.brushSpacing,
                viewModel.brushStabilizer,
                viewModel.colorStretch,
                viewModel.waterContent,
                viewModel.blurStrength,
                viewModel.blurPressureThreshold,
                viewModel.currentBrushType,
                viewModel.pressureSizeEnabled,
                viewModel.pressureOpacityEnabled,
                viewModel.pressureDensityEnabled,
                viewModel.currentColor,
                viewModel.colorHistory,
                viewModel.canUndo,
                viewModel.canRedo,
                viewModel.toolMode,
                viewModel.isDrawing,
                viewModel.layers,
            )
                .debounce(16L)
                .collect {
                    // buildStateMap は重いレイヤースタックでコストがかかるため
                    // Default ディスパッチャで構築し Main で送信
                    val stateMap = withContext(Dispatchers.Default) {
                        buildStateMap()
                    }
                    eventSink?.success(stateMap)
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
