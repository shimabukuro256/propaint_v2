package com.propaint.app.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.MotionEvent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import com.propaint.app.engine.*
import com.propaint.app.gallery.GalleryRepository
import com.propaint.app.gl.CanvasRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.*

data class UiLayer(
    val id: Int, val name: String, val opacity: Float, val blendMode: Int,
    val isVisible: Boolean, val isLocked: Boolean,
    val isClipToBelow: Boolean, val isActive: Boolean,
)

enum class BrushType(
    val displayName: String,
    val supportsOpacity: Boolean = true,
    val supportsDensity: Boolean = true,
) {
    Pencil("鉛筆"), Fude("筆", supportsOpacity = false),
    Watercolor("水彩筆", supportsOpacity = false), Airbrush("エアブラシ", supportsOpacity = false),
    Marker("マーカー", supportsDensity = false), Eraser("消しゴム"),
    Blur("ぼかし", supportsOpacity = false),
}

enum class ToolMode { Draw, Eyedropper }

class PaintViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("brush_settings", Context.MODE_PRIVATE)
    val galleryRepo = GalleryRepository(application)

    private var _document: CanvasDocument? = null
    val document: CanvasDocument? get() = _document
    val renderer = CanvasRenderer()

    /** 現在編集中のプロジェクトID (null = ギャラリーから未オープン) */
    var currentProjectId: String? = null; private set

    // ── UI State ────────────────────────────────────────────────────

    private val _layers = MutableStateFlow<List<UiLayer>>(emptyList())
    val layers: StateFlow<List<UiLayer>> = _layers.asStateFlow()

    private val _brushType = MutableStateFlow(BrushType.Pencil)
    val currentBrushType: StateFlow<BrushType> = _brushType.asStateFlow()

    private val _toolMode = MutableStateFlow(ToolMode.Draw)
    val toolMode: StateFlow<ToolMode> = _toolMode.asStateFlow()

    private val _brushSize = MutableStateFlow(10f)
    val brushSize: StateFlow<Float> = _brushSize.asStateFlow()
    private val _brushOpacity = MutableStateFlow(1f)
    val brushOpacity: StateFlow<Float> = _brushOpacity.asStateFlow()
    private val _brushHardness = MutableStateFlow(0.8f)
    val brushHardness: StateFlow<Float> = _brushHardness.asStateFlow()
    private val _brushDensity = MutableStateFlow(0.8f)
    val brushDensity: StateFlow<Float> = _brushDensity.asStateFlow()
    private val _brushSpacing = MutableStateFlow(0.1f)
    val brushSpacing: StateFlow<Float> = _brushSpacing.asStateFlow()
    private val _brushStabilizer = MutableStateFlow(0.3f)
    val brushStabilizer: StateFlow<Float> = _brushStabilizer.asStateFlow()
    private val _colorStretch = MutableStateFlow(0.5f)
    val colorStretch: StateFlow<Float> = _colorStretch.asStateFlow()
    private val _waterContent = MutableStateFlow(0f)
    val waterContent: StateFlow<Float> = _waterContent.asStateFlow()
    private val _blurStrength = MutableStateFlow(0.5f)
    val blurStrength: StateFlow<Float> = _blurStrength.asStateFlow()
    private val _blurPressureThreshold = MutableStateFlow(0f)
    val blurPressureThreshold: StateFlow<Float> = _blurPressureThreshold.asStateFlow()
    private val _pressureSizeEnabled = MutableStateFlow(true)
    val pressureSizeEnabled: StateFlow<Boolean> = _pressureSizeEnabled.asStateFlow()
    private val _pressureOpacityEnabled = MutableStateFlow(false)
    val pressureOpacityEnabled: StateFlow<Boolean> = _pressureOpacityEnabled.asStateFlow()
    private val _pressureDensityEnabled = MutableStateFlow(false)
    val pressureDensityEnabled: StateFlow<Boolean> = _pressureDensityEnabled.asStateFlow()

    private val _currentColor = MutableStateFlow(Color.Black)
    val currentColor: StateFlow<Color> = _currentColor.asStateFlow()
    val colorHistory = MutableStateFlow(listOf(
        Color.Black, Color.White, Color.Red, Color.Blue,
        Color.Green, Color.Yellow, Color(0xFFFF6600), Color(0xFF9900FF),
        Color(0xFF00CCCC), Color(0xFFFF69B4), Color(0xFF8B4513), Color(0xFF808080),
    ))

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private val _isDrawing = MutableStateFlow(false)
    val isDrawing: StateFlow<Boolean> = _isDrawing.asStateFlow()

    // View transform
    private var _zoom = 1f; private var _panX = 0f; private var _panY = 0f; private var _rotation = 0f
    private var gestureStartDist = 0f; private var gestureStartZoom = 1f
    private var gestureStartAngle = 0f; private var gestureStartRotation = 0f
    private var gestureStartPanX = 0f; private var gestureStartPanY = 0f
    private var gestureStartMidX = 0f; private var gestureStartMidY = 0f
    private var isMultiTouch = false

    // ブラシ設定マップ (種別ごとに全パラメータ保持)
    data class BrushState(
        val size: Float, val opacity: Float, val hardness: Float,
        val density: Float, val spacing: Float, val stabilizer: Float,
        val colorStretch: Float, val waterContent: Float, val blurStrength: Float,
        val blurPressureThreshold: Float = 0f,
        val pressureSize: Boolean, val pressureOpacity: Boolean,
        val pressureDensity: Boolean,
    )
    private val brushStateMap = HashMap<BrushType, BrushState>()

    init {
        // SharedPreferences からブラシ設定を復元
        for (type in BrushType.entries) {
            val prefix = "brush_${type.name}_"
            if (prefs.contains("${prefix}size")) {
                brushStateMap[type] = BrushState(
                    size = prefs.getFloat("${prefix}size", 10f),
                    opacity = prefs.getFloat("${prefix}opacity", 1f),
                    hardness = prefs.getFloat("${prefix}hardness", 0.8f),
                    density = prefs.getFloat("${prefix}density", 0.8f),
                    spacing = prefs.getFloat("${prefix}spacing", 0.1f),
                    stabilizer = prefs.getFloat("${prefix}stabilizer", 0.3f),
                    colorStretch = prefs.getFloat("${prefix}colorStretch", 0f),
                    waterContent = prefs.getFloat("${prefix}waterContent", 0f),
                    blurStrength = prefs.getFloat("${prefix}blurStrength", 0.5f),
                    blurPressureThreshold = prefs.getFloat("${prefix}blurPressureThreshold", 0f),
                    pressureSize = prefs.getBoolean("${prefix}pressureSize", true),
                    pressureOpacity = prefs.getBoolean("${prefix}pressureOpacity", false),
                    pressureDensity = prefs.getBoolean("${prefix}pressureDensity", false),
                )
            }
        }
        // 保存済みの現在ブラシ種別を復元
        val savedType = prefs.getString("current_brush_type", null)
        if (savedType != null) {
            val type = BrushType.entries.find { it.name == savedType }
            if (type != null) _brushType.value = type
        }
        // 現在の種別の設定を反映
        val saved = brushStateMap[_brushType.value]
        if (saved != null) {
            _brushSize.value = saved.size; _brushOpacity.value = saved.opacity
            _brushHardness.value = saved.hardness; _brushDensity.value = saved.density
            _brushSpacing.value = saved.spacing; _brushStabilizer.value = saved.stabilizer
            _colorStretch.value = saved.colorStretch; _waterContent.value = saved.waterContent
            _blurStrength.value = saved.blurStrength; _blurPressureThreshold.value = saved.blurPressureThreshold
            _pressureSizeEnabled.value = saved.pressureSize
            _pressureOpacityEnabled.value = saved.pressureOpacity
            _pressureDensityEnabled.value = saved.pressureDensity
        }
        // 不透明度非対応ブラシは常に 1.0 / 筆圧不透明度 off
        if (!_brushType.value.supportsOpacity) {
            _brushOpacity.value = 1f
            _pressureOpacityEnabled.value = false
        }
        // 濃度非対応ブラシは常に 1.0 / 筆圧濃度 off
        if (!_brushType.value.supportsDensity) {
            _brushDensity.value = 1f
            _pressureDensityEnabled.value = false
        }
    }

    /** 現在のブラシ種別の設定を SharedPreferences に永続化 */
    private fun persistCurrentBrush() {
        val type = _brushType.value
        val prefix = "brush_${type.name}_"
        prefs.edit()
            .putString("current_brush_type", type.name)
            .putFloat("${prefix}size", _brushSize.value)
            .putFloat("${prefix}opacity", _brushOpacity.value)
            .putFloat("${prefix}hardness", _brushHardness.value)
            .putFloat("${prefix}density", _brushDensity.value)
            .putFloat("${prefix}spacing", _brushSpacing.value)
            .putFloat("${prefix}stabilizer", _brushStabilizer.value)
            .putFloat("${prefix}colorStretch", _colorStretch.value)
            .putFloat("${prefix}waterContent", _waterContent.value)
            .putFloat("${prefix}blurStrength", _blurStrength.value)
            .putFloat("${prefix}blurPressureThreshold", _blurPressureThreshold.value)
            .putBoolean("${prefix}pressureSize", _pressureSizeEnabled.value)
            .putBoolean("${prefix}pressureOpacity", _pressureOpacityEnabled.value)
            .putBoolean("${prefix}pressureDensity", _pressureDensityEnabled.value)
            .apply()
    }

    // ── 初期化 ──────────────────────────────────────────────────────

    fun initCanvas(width: Int, height: Int) {
        val doc = CanvasDocument(width, height)
        _document = doc; renderer.document = doc; updateLayerState()
    }

    /** ギャラリーから新規プロジェクトを作成して開く */
    fun openNewProject(name: String, width: Int, height: Int) {
        saveCurrentProject()
        val id = galleryRepo.createProject(name, width, height)
        val doc = galleryRepo.loadProject(id)
        if (doc != null) {
            _document = doc; renderer.document = doc; currentProjectId = id
            updateLayerState(); updateUndoState()
            resetView()
        }
    }

    /** ギャラリーから既存プロジェクトを開く */
    fun openProject(id: String) {
        saveCurrentProject()
        val doc = galleryRepo.loadProject(id)
        if (doc != null) {
            _document = doc; renderer.document = doc; currentProjectId = id
            updateLayerState(); updateUndoState()
            resetView()
        }
    }

    /** 現在のプロジェクトを保存 */
    fun saveCurrentProject() {
        val id = currentProjectId ?: return
        val doc = _document ?: return
        galleryRepo.saveProject(id, doc)
    }

    /** ギャラリーに戻る前に保存してドキュメントをクリア */
    fun closeProject() {
        saveCurrentProject()
        _document = null; renderer.document = null; currentProjectId = null
        _layers.value = emptyList()
    }

    // ── ブラシ操作 ──────────────────────────────────────────────────

    fun setBrushType(type: BrushType) {
        // 現在の設定を保存
        brushStateMap[_brushType.value] = BrushState(
            _brushSize.value, _brushOpacity.value, _brushHardness.value,
            _brushDensity.value, _brushSpacing.value, _brushStabilizer.value,
            _colorStretch.value, _waterContent.value, _blurStrength.value,
            _blurPressureThreshold.value,
            _pressureSizeEnabled.value, _pressureOpacityEnabled.value,
            _pressureDensityEnabled.value,
        )
        _brushType.value = type
        // 保存済みから復元、なければデフォルト
        val saved = brushStateMap[type]
        if (saved != null) {
            _brushSize.value = saved.size; _brushOpacity.value = saved.opacity
            _brushHardness.value = saved.hardness; _brushDensity.value = saved.density
            _brushSpacing.value = saved.spacing; _brushStabilizer.value = saved.stabilizer
            _colorStretch.value = saved.colorStretch; _waterContent.value = saved.waterContent
            _blurStrength.value = saved.blurStrength; _blurPressureThreshold.value = saved.blurPressureThreshold
            _pressureSizeEnabled.value = saved.pressureSize
            _pressureOpacityEnabled.value = saved.pressureOpacity
            _pressureDensityEnabled.value = saved.pressureDensity
        } else applyDefaults(type)
        // 不透明度非対応ブラシは常に 1.0 / 筆圧不透明度 off
        if (!type.supportsOpacity) {
            _brushOpacity.value = 1f
            _pressureOpacityEnabled.value = false
        }
        // 濃度非対応ブラシは常に 1.0 / 筆圧濃度 off
        if (!type.supportsDensity) {
            _brushDensity.value = 1f
            _pressureDensityEnabled.value = false
        }
        persistCurrentBrush()
    }

    private fun applyDefaults(type: BrushType) {
        _colorStretch.value = 0f; _waterContent.value = 0f; _blurPressureThreshold.value = 0f
        when (type) {
            BrushType.Pencil -> {
                _brushSize.value = 100f; _brushOpacity.value = 1f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.06f
                _pressureSizeEnabled.value = true
                _pressureOpacityEnabled.value = true
                _pressureDensityEnabled.value = true
            }
            BrushType.Eraser -> {
                _brushSize.value = 30f; _brushOpacity.value = 1f; _brushHardness.value = 0.8f
                _brushDensity.value = 1f; _brushSpacing.value = 0.05f
                _pressureSizeEnabled.value = true
                _pressureOpacityEnabled.value = false
                _pressureDensityEnabled.value = false
            }
            BrushType.Airbrush -> {
                _brushSize.value = 60f; _brushOpacity.value = 1f; _brushHardness.value = 0f
                _brushDensity.value = 0.4f; _brushSpacing.value = 0.08f
                _pressureSizeEnabled.value = false
                _pressureOpacityEnabled.value = false
                _pressureDensityEnabled.value = true
            }
            BrushType.Fude, BrushType.Watercolor, BrushType.Blur -> {
                _brushSize.value = 100f; _brushOpacity.value = 1f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.06f
                _pressureSizeEnabled.value = true
                _pressureOpacityEnabled.value = false
                _pressureDensityEnabled.value = true
            }
            BrushType.Marker -> {
                _brushSize.value = 100f; _brushOpacity.value = 0.5f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.06f
                _pressureSizeEnabled.value = true
                _pressureOpacityEnabled.value = false
                _pressureDensityEnabled.value = false
            }
            else -> {
                _brushSize.value = 100f; _brushOpacity.value = 1f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.06f
                _pressureSizeEnabled.value = true
                _pressureOpacityEnabled.value = true
                _pressureDensityEnabled.value = true
            }
        }
    }

    fun setBrushSize(v: Float) { _brushSize.value = v.coerceIn(1f, 2000f); persistCurrentBrush() }
    fun setBrushOpacity(v: Float) { _brushOpacity.value = v.coerceIn(0.01f, 1f); persistCurrentBrush() }
    fun setBrushHardness(v: Float) { _brushHardness.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun setBrushDensity(v: Float) { _brushDensity.value = v.coerceIn(0.01f, 1f); persistCurrentBrush() }
    fun setBrushSpacing(v: Float) { _brushSpacing.value = v.coerceIn(0.01f, 2f); persistCurrentBrush() }
    fun setColorStretch(v: Float) { _colorStretch.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun setWaterContent(v: Float) { _waterContent.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun setBlurStrength(v: Float) { _blurStrength.value = v.coerceIn(0.05f, 1f); persistCurrentBrush() }
    fun setStabilizer(v: Float) { _brushStabilizer.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun setBlurPressureThreshold(v: Float) { _blurPressureThreshold.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun togglePressureSize() { _pressureSizeEnabled.value = !_pressureSizeEnabled.value; persistCurrentBrush() }
    fun togglePressureOpacity() { _pressureOpacityEnabled.value = !_pressureOpacityEnabled.value; persistCurrentBrush() }
    fun togglePressureDensity() { _pressureDensityEnabled.value = !_pressureDensityEnabled.value; persistCurrentBrush() }
    fun setColor(color: Color) {
        _currentColor.value = color
        val hist = colorHistory.value.toMutableList()
        hist.remove(color); hist.add(0, color)
        if (hist.size > 20) hist.removeLast()
        colorHistory.value = hist
    }

    fun activateEyedropper() { _toolMode.value = ToolMode.Eyedropper }
    fun deactivateEyedropper() { _toolMode.value = ToolMode.Draw }

    /** BrushConfig を構築 */
    private fun buildBrushConfig(): BrushConfig {
        val c = _currentColor.value
        val argb = (0xFF shl 24) or
            ((c.red * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((c.green * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (c.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
        val type = _brushType.value
        val opacitySupported = type.supportsOpacity
        val densitySupported = type.supportsDensity

        // ブラシ種別ごとの smudge / blur / indirect パラメータ
        val smudge: Float
        val resmudge: Int
        val isBlur: Boolean
        val blurStr: Float
        val indirect: Boolean
        val waterCont: Float
        val colStretch: Float
        var subFilter = BrushConfig.SUBLAYER_FILTER_NONE
        var blurPressThreshold = 0f

        when (type) {
            BrushType.Fude -> {
                // 筆: キャンバス色と描画色を混合 (smudge=0.6)
                smudge = 0.6f; resmudge = 0; isBlur = false; blurStr = 0f
                indirect = true; waterCont = 0f; colStretch = _colorStretch.value
                subFilter = BrushConfig.SUBLAYER_FILTER_AVERAGING
                blurPressThreshold = _blurPressureThreshold.value
            }
            BrushType.Watercolor -> {
                // 水彩: 高い混色率 + 水分効果
                smudge = 0.4f; resmudge = 0; isBlur = false; blurStr = 0f
                indirect = true; waterCont = _waterContent.value; colStretch = _colorStretch.value
                subFilter = BrushConfig.SUBLAYER_FILTER_BOX_BLUR
                blurPressThreshold = _blurPressureThreshold.value
            }
            BrushType.Blur -> {
                // ぼかし: smudge=1.0 でキャンバス色のみ使用
                smudge = 1f; resmudge = 0; isBlur = true; blurStr = _blurStrength.value
                indirect = false; waterCont = 0f; colStretch = 0f
            }
            BrushType.Airbrush -> {
                smudge = 0f; resmudge = 0; isBlur = false; blurStr = 0f
                indirect = true; waterCont = 0f; colStretch = 0f
            }
            BrushType.Eraser -> {
                smudge = 0f; resmudge = 0; isBlur = false; blurStr = 0f
                indirect = false; waterCont = 0f; colStretch = 0f
            }
            BrushType.Marker -> {
                smudge = 0f; resmudge = 0; isBlur = false; blurStr = 0f
                indirect = false; waterCont = 0f; colStretch = 0f
            }
            else -> { // Pencil
                smudge = 0f; resmudge = 0; isBlur = false; blurStr = 0f
                indirect = true; waterCont = 0f; colStretch = 0f
            }
        }

        return BrushConfig(
            size = _brushSize.value,
            opacity = if (opacitySupported) _brushOpacity.value else 1f,
            density = if (densitySupported) _brushDensity.value else 1f,
            spacing = _brushSpacing.value,
            hardness = _brushHardness.value,
            colorPremul = PixelOps.premultiply(argb),
            isEraser = type == BrushType.Eraser,
            isMarker = type == BrushType.Marker,
            isBlur = isBlur,
            smudge = smudge,
            resmudge = resmudge,
            blurStrength = blurStr,
            indirect = indirect,
            pressureSizeEnabled = _pressureSizeEnabled.value,
            pressureOpacityEnabled = if (opacitySupported) _pressureOpacityEnabled.value else false,
            pressureDensityEnabled = if (densitySupported) _pressureDensityEnabled.value else false,
            taperEnabled = type != BrushType.Eraser,
            waterContent = waterCont,
            colorStretch = colStretch,
            stabilizer = _brushStabilizer.value,
            sublayerFilter = subFilter,
            blurPressureThreshold = blurPressThreshold,
        )
    }

    // ── タッチ入力 ──────────────────────────────────────────────────

    fun onTouchEvent(event: MotionEvent): Boolean {
        val doc = _document ?: return false

        if (event.pointerCount >= 2) { handleMultiTouch(event); return true }

        if (isMultiTouch && event.actionMasked == MotionEvent.ACTION_MOVE) return true
        if (isMultiTouch && event.actionMasked == MotionEvent.ACTION_UP) { isMultiTouch = false; return true }

        // スタイラス消しゴム端の自動検出
        val isEraserTip = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isMultiTouch = false
                if (_toolMode.value == ToolMode.Eyedropper) {
                    val (dx, dy) = screenToDoc(event.x, event.y)
                    val sampled = doc.eyedropperAt(dx.toInt(), dy.toInt())
                    val up = PixelOps.unpremultiply(sampled)
                    setColor(Color(PixelOps.red(up) / 255f, PixelOps.green(up) / 255f, PixelOps.blue(up) / 255f))
                    _toolMode.value = ToolMode.Draw
                    return true
                }
                var brush = buildBrushConfig()
                if (isEraserTip) brush = brush.copy(isEraser = true, indirect = false, taperEnabled = false)
                doc.beginStroke(brush)
                _isDrawing.value = true
                processDrawPoints(event, doc, brush)
            }
            MotionEvent.ACTION_MOVE -> {
                if (_isDrawing.value) {
                    var brush = buildBrushConfig()
                    if (isEraserTip) brush = brush.copy(isEraser = true, indirect = false, taperEnabled = false)
                    processDrawPoints(event, doc, brush)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (_isDrawing.value) {
                    var brush = buildBrushConfig()
                    if (isEraserTip) brush = brush.copy(isEraser = true, indirect = false, taperEnabled = false)
                    doc.endStroke(brush)
                    _isDrawing.value = false
                    updateUndoState()
                }
            }
        }
        return true
    }

    private fun processDrawPoints(event: MotionEvent, doc: CanvasDocument, brush: BrushConfig) {
        for (h in 0 until event.historySize) {
            val (dx, dy) = screenToDoc(event.getHistoricalX(h), event.getHistoricalY(h))
            doc.strokeTo(BrushEngine.StrokePoint(dx, dy, event.getHistoricalPressure(h)), brush)
        }
        val (dx, dy) = screenToDoc(event.x, event.y)
        doc.strokeTo(BrushEngine.StrokePoint(dx, dy, event.pressure), brush)
    }

    private fun screenToDoc(sx: Float, sy: Float): Pair<Float, Float> {
        val doc = _document ?: return sx to sy
        val sw = renderer.surfaceWidth.toFloat(); val sh = renderer.surfaceHeight.toFloat()
        if (sw <= 0 || sh <= 0) return sx to sy
        val bs = min(sw / doc.width, sh / doc.height); val fs = bs * _zoom
        val cx = sw / 2f + _panX; val cy = sh / 2f + _panY
        val dx = sx - cx; val dy = sy - cy
        val cosR = cos(-_rotation.toDouble()).toFloat(); val sinR = sin(-_rotation.toDouble()).toFloat()
        return Pair((dx * cosR - dy * sinR) / fs + doc.width / 2f,
            (dx * sinR + dy * cosR) / fs + doc.height / 2f)
    }

    private fun handleMultiTouch(event: MotionEvent) {
        if (!isMultiTouch && _isDrawing.value) {
            _document?.endStroke(buildBrushConfig()); _isDrawing.value = false
        }
        val x0 = event.getX(0); val y0 = event.getY(0)
        val x1 = event.getX(1); val y1 = event.getY(1)
        val midX = (x0 + x1) / 2f; val midY = (y0 + y1) / 2f
        val dist = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        val angle = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()).toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                isMultiTouch = true; gestureStartDist = dist; gestureStartZoom = _zoom
                gestureStartAngle = angle; gestureStartRotation = _rotation
                gestureStartPanX = _panX; gestureStartPanY = _panY
                gestureStartMidX = midX; gestureStartMidY = midY
            }
            MotionEvent.ACTION_MOVE -> {
                if (isMultiTouch && gestureStartDist > 10f) {
                    _zoom = (gestureStartZoom * dist / gestureStartDist).coerceIn(0.1f, 30f)
                    _rotation = gestureStartRotation + (angle - gestureStartAngle)
                    _panX = gestureStartPanX + (midX - gestureStartMidX)
                    _panY = gestureStartPanY + (midY - gestureStartMidY)
                    renderer.pendingTransform.set(floatArrayOf(_zoom, _panX, _panY, _rotation))
                }
            }
        }
    }

    // ── レイヤー操作 ────────────────────────────────────────────────

    fun addLayer() {
        val doc = _document ?: return
        doc.addLayer("レイヤー ${doc.nextLayerNameIndex}"); updateLayerState()
    }
    fun removeLayer(id: Int) { _document?.removeLayer(id); updateLayerState() }
    fun selectLayer(id: Int) { _document?.setActiveLayer(id); updateLayerState() }
    fun setLayerVisibility(id: Int, v: Boolean) { _document?.setLayerVisibility(id, v); updateLayerState() }
    fun setLayerOpacity(id: Int, v: Float) { _document?.setLayerOpacity(id, v); updateLayerState() }
    fun setLayerBlendMode(id: Int, m: Int) { _document?.setLayerBlendMode(id, m); updateLayerState() }
    fun setLayerClip(id: Int, clip: Boolean) { _document?.setLayerClipToBelow(id, clip); updateLayerState() }
    fun setLayerLocked(id: Int, locked: Boolean) { _document?.setLayerLocked(id, locked); updateLayerState() }
    fun clearActiveLayer() { _document?.let { it.clearLayer(it.activeLayerId) }; updateLayerState() }
    fun duplicateLayer(id: Int) { _document?.duplicateLayer(id); updateLayerState() }
    fun mergeDown(id: Int) { _document?.mergeDown(id); updateLayerState() }
    fun moveLayerUp(id: Int) {
        val doc = _document ?: return
        val idx = doc.layers.indexOfFirst { it.id == id }; if (idx < 0 || idx >= doc.layers.size - 1) return
        doc.moveLayer(idx, idx + 1); updateLayerState()
    }
    fun moveLayerDown(id: Int) {
        val doc = _document ?: return
        val idx = doc.layers.indexOfFirst { it.id == id }; if (idx <= 0) return
        doc.moveLayer(idx, idx - 1); updateLayerState()
    }
    fun translateLayerContent(id: Int, dx: Int, dy: Int) {
        _document?.translateLayerContent(id, dx, dy); updateUndoState()
    }

    // ── フィルター ──────────────────────────────────────────────────

    fun applyHslFilter(hue: Float, sat: Float, lit: Float) {
        val doc = _document ?: return
        doc.applyHslFilter(doc.activeLayerId, hue, sat, lit); updateUndoState()
    }

    fun applyBrightnessContrast(brightness: Float, contrast: Float) {
        val doc = _document ?: return
        doc.applyBrightnessContrast(doc.activeLayerId, brightness, contrast); updateUndoState()
    }

    fun applyBlurFilter(radius: Int, blurType: Int = CanvasDocument.BLUR_GAUSSIAN) {
        val doc = _document ?: return
        doc.applyBlurFilter(doc.activeLayerId, radius, blurType); updateUndoState()
    }

    fun beginBlurPreview() {
        val doc = _document ?: return
        doc.beginFilterPreview(doc.activeLayerId)
    }

    fun updateBlurPreview(radius: Int, blurType: Int) {
        val doc = _document ?: return
        doc.applyBlurPreview(doc.activeLayerId, radius, blurType)
    }

    fun commitBlurPreview() {
        val doc = _document ?: return
        doc.commitFilterPreview(); updateUndoState()
    }

    fun cancelBlurPreview() {
        val doc = _document ?: return
        doc.cancelFilterPreview()
    }

    // ── Undo/Redo ───────────────────────────────────────────────────

    fun undo() { _document?.undo(); updateUndoState() }
    fun redo() { _document?.redo(); updateUndoState() }

    // ── View ────────────────────────────────────────────────────────

    fun resetView() {
        _zoom = 1f; _panX = 0f; _panY = 0f; _rotation = 0f
        renderer.pendingTransform.set(floatArrayOf(_zoom, _panX, _panY, _rotation))
    }

    // ── エクスポート ────────────────────────────────────────────────

    /** プロジェクトファイル保存 (.ppaint) */
    fun saveProject(outputStream: OutputStream) {
        val doc = _document ?: return
        ProjectFile.save(doc, outputStream)
    }

    /** プロジェクトファイル読込 (.ppaint) */
    fun loadProject(inputStream: InputStream): Boolean {
        val doc = ProjectFile.load(inputStream) ?: return false
        _document = doc; renderer.document = doc
        updateLayerState(); updateUndoState()
        return true
    }

    fun exportPng(outputStream: OutputStream) {
        val doc = _document ?: return
        val bmp = getCompositeBitmap(doc)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        bmp.recycle()
    }

    fun exportJpeg(outputStream: OutputStream, quality: Int = 95) {
        val doc = _document ?: return
        val bmp = getCompositeBitmap(doc)
        // JPEG は透明非対応 → 白背景で合成
        val jpegBmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(jpegBmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        bmp.recycle()
        jpegBmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), outputStream)
        jpegBmp.recycle()
    }

    fun exportWebp(outputStream: OutputStream, quality: Int = 90) {
        val doc = _document ?: return
        val bmp = getCompositeBitmap(doc)
        @Suppress("DEPRECATION")
        bmp.compress(Bitmap.CompressFormat.WEBP, quality.coerceIn(1, 100), outputStream)
        bmp.recycle()
    }

    fun exportPsd(outputStream: OutputStream) {
        val doc = _document ?: return
        PsdExporter.export(doc, outputStream)
    }

    private fun getCompositeBitmap(doc: CanvasDocument): Bitmap {
        val pixels = doc.getCompositePixels()
        for (i in pixels.indices) {
            pixels[i] = PixelOps.unpremultiply(pixels[i])
        }
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, doc.width, 0, 0, doc.width, doc.height)
        return bmp
    }

    /** 画像ファイルをレイヤーとしてインポート (PNG/JPEG/WebP) */
    fun importImageAsLayer(inputStream: InputStream, name: String = "インポート"): Boolean {
        val doc = _document ?: return false
        val bmp = BitmapFactory.decodeStream(inputStream) ?: return false
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        doc.importImageAsLayer(name, pixels, w, h)
        updateLayerState(); updateUndoState()
        return true
    }

    /** PSD ファイルをインポート */
    fun importPsd(inputStream: InputStream): Boolean {
        val doc = PsdImporter.import(inputStream) ?: return false
        _document = doc; renderer.document = doc
        updateLayerState(); updateUndoState()
        resetView()
        return true
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private fun updateLayerState() {
        val doc = _document ?: return
        _layers.value = doc.layers.map {
            UiLayer(it.id, it.name, it.opacity, it.blendMode,
                it.isVisible, it.isLocked, it.isClipToBelow, it.id == doc.activeLayerId)
        }
    }

    private fun updateUndoState() {
        val doc = _document ?: return
        _canUndo.value = doc.canUndo; _canRedo.value = doc.canRedo
    }
}
