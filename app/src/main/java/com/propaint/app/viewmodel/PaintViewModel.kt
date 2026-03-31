package com.propaint.app.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.propaint.app.engine.*
import com.propaint.app.engine.MemoryConfig
import com.propaint.app.gallery.GalleryRepository
import com.propaint.app.gl.CanvasRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.*

data class UiLayer(
    val id: Int, val name: String, val opacity: Float, val blendMode: Int,
    val isVisible: Boolean, val isLocked: Boolean,
    val isClipToBelow: Boolean, val isActive: Boolean,
    val isAlphaLocked: Boolean = false,
    val hasMask: Boolean = false,
    val isMaskEnabled: Boolean = false,
    val isEditingMask: Boolean = false,
    val groupId: Int = 0,
    val isTextLayer: Boolean = false,
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

enum class ToolMode {
    Draw, Eyedropper,
    SelectRect, SelectEllipse, SelectLasso, SelectMagicWand,
    SelectPen, SelectEraser,
    Transform,
    ShapeLine, ShapeRect, ShapeEllipse,
    FloodFill, Gradient,
    Text,
}

class PaintViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("brush_settings", Context.MODE_PRIVATE)
    val galleryRepo = GalleryRepository(application)

    private var _document: CanvasDocument? = null
    val document: CanvasDocument? get() = _document
    val renderer = CanvasRenderer()

    /** 現在編集中のプロジェクトID (null = ギャラリーから未オープン) */
    var currentProjectId: String? = null; private set

    // ── 自動保存 (60秒間隔) ────────────────────────────────────────
    private var autoSaveJob: Job? = null
    /** ストローク完了後に true にし、自動保存で false に戻す。未変更時は保存をスキップ */
    private var hasUnsavedChanges = false
    /** 保存処理の排他制御。自動保存とライフサイクル保存の競合を防止 */
    private val saveMutex = Mutex()

    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            while (isActive) {
                delay(MemoryConfig.autoSaveIntervalMs)
                if (hasUnsavedChanges && !_isDrawing.value) {
                    withContext(Dispatchers.IO) { saveCurrentProject() }
                    hasUnsavedChanges = false
                    PaintDebug.d(PaintDebug.Perf) { "[AutoSave] saved project=${currentProjectId}" }
                }
            }
        }
    }

    private fun stopAutoSave() { autoSaveJob?.cancel(); autoSaveJob = null }

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
    private val _brushAntiAliasing = MutableStateFlow(1f)
    val brushAntiAliasing: StateFlow<Float> = _brushAntiAliasing.asStateFlow()
    private val _blurPressureThreshold = MutableStateFlow(0f)
    val blurPressureThreshold: StateFlow<Float> = _blurPressureThreshold.asStateFlow()
    private val _pressureSizeEnabled = MutableStateFlow(true)
    val pressureSizeEnabled: StateFlow<Boolean> = _pressureSizeEnabled.asStateFlow()
    private val _pressureOpacityEnabled = MutableStateFlow(false)
    val pressureOpacityEnabled: StateFlow<Boolean> = _pressureOpacityEnabled.asStateFlow()
    private val _pressureDensityEnabled = MutableStateFlow(false)
    val pressureDensityEnabled: StateFlow<Boolean> = _pressureDensityEnabled.asStateFlow()
    private val _pressureSelectionEnabled = MutableStateFlow(false)
    val pressureSelectionEnabled: StateFlow<Boolean> = _pressureSelectionEnabled.asStateFlow()

    private val _currentColor = MutableStateFlow(Color.Black)
    val currentColor: StateFlow<Color> = _currentColor.asStateFlow()

    companion object {
        private const val MAX_COLOR_HISTORY = 32
        private const val PREF_KEY_COLOR_HISTORY = "color_history"
        private const val PREF_KEY_LAST_COLOR = "last_color"
        private val DEFAULT_PALETTE = listOf(
            Color.Black, Color.White, Color.Red, Color.Blue,
            Color.Green, Color.Yellow, Color(0xFFFF6600), Color(0xFF9900FF),
            Color(0xFF00CCCC), Color(0xFFFF69B4), Color(0xFF8B4513), Color(0xFF808080),
        )

        // ジェスチャ定数
        private const val TAP_SLOP = 20f
        private const val TAP_TIMEOUT_MS = 300L
        private const val PALM_SIZE_THRESHOLD = 0.2f
        private const val INERTIA_FRICTION = 0.8f
        private const val INERTIA_MIN_VELOCITY = 0.5f
    }

    val colorHistory = MutableStateFlow(loadColorHistory())

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private val _isDrawing = MutableStateFlow(false)
    val isDrawing: StateFlow<Boolean> = _isDrawing.asStateFlow()

    // ── ブラシカーソル ────────────────────────────────────────────────
    data class CursorState(val x: Float, val y: Float, val radius: Float, val visible: Boolean)
    private val _cursorState = MutableStateFlow(CursorState(0f, 0f, 0f, false))
    val cursorState: StateFlow<CursorState> = _cursorState.asStateFlow()

    private var lastCursorScreenX = 0f
    private var lastCursorScreenY = 0f
    private var lastCursorPressure = 1f

    private fun updateCursor(screenX: Float, screenY: Float, pressure: Float) {
        lastCursorScreenX = screenX; lastCursorScreenY = screenY; lastCursorPressure = pressure
        val doc = _document ?: return
        val sw = renderer.surfaceWidth.toFloat(); val sh = renderer.surfaceHeight.toFloat()
        if (sw <= 0 || sh <= 0) return
        val bs = min(sw / doc.width, sh / doc.height); val fs = bs * _zoom
        val nomRad = _brushSize.value / 2f
        val rad = if (_pressureSizeEnabled.value) {
            val minRad = 0.5f
            minRad + (nomRad - minRad) * pressure.coerceIn(0f, 1f)
        } else nomRad
        val screenRadius = rad * fs
        _cursorState.value = CursorState(screenX, screenY, screenRadius, true)
        // GL レンダラーにもカーソル状態を反映（Flutter PlatformView でも円が表示される）
        renderer.cursorX = screenX
        renderer.cursorY = screenY
        renderer.cursorRadius = screenRadius
        renderer.cursorVisible = true
    }

    /** ブラシサイズ変更時にカーソルを再計算 (ホバー中のみ) */
    private fun refreshCursorSize() {
        if (_cursorState.value.visible) {
            updateCursor(lastCursorScreenX, lastCursorScreenY, lastCursorPressure)
        }
    }

    private fun hideCursor() {
        _cursorState.value = _cursorState.value.copy(visible = false)
        renderer.cursorVisible = false
    }

    // View transform
    private var _zoom = 1f; private var _panX = 0f; private var _panY = 0f; private var _rotation = 0f
    private var gestureStartDist = 0f; private var gestureStartZoom = 1f
    private var gestureStartAngle = 0f; private var gestureStartRotation = 0f
    private var gestureStartPanX = 0f; private var gestureStartPanY = 0f
    private var gestureStartMidX = 0f; private var gestureStartMidY = 0f
    private var isMultiTouch = false

    // マルチタップ検出 (2本指=Undo, 3本指=Redo)
    private var multiTouchDownTime = 0L
    private var multiTouchMaxFingers = 0
    private var multiTouchTotalMovement = 0f
    private var gestureCommittedToPinch = false
    private var multiTouchLastMidX = 0f
    private var multiTouchLastMidY = 0f

    // パームリジェクション
    private var stylusIsDown = false

    // 慣性アニメーション
    private var velocityTracker: VelocityTracker? = null
    private var inertiaJob: Job? = null

    /** ジェスチャ通知コールバック (undo/redo 等の通知を Flutter に送るため) */
    var onGestureEvent: ((String) -> Unit)? = null

    /** エクスポート要求コールバック (Activity の ResultLauncher を起動するため) */
    var onExportRequest: ((String) -> Unit)? = null
    /** インポート要求コールバック */
    var onImportRequest: ((String) -> Unit)? = null
    /** ギャラリーに戻る要求コールバック */
    var onReturnToGallery: (() -> Unit)? = null

    // ブラシ設定マップ (種別ごとに全パラメータ保持)
    data class BrushState(
        val size: Float, val opacity: Float, val hardness: Float,
        val density: Float, val spacing: Float, val stabilizer: Float,
        val colorStretch: Float, val waterContent: Float, val blurStrength: Float,
        val blurPressureThreshold: Float = 0f, val antiAliasing: Float = 1f,
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
                    antiAliasing = prefs.getFloat("${prefix}antiAliasing", 1f),
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
            _brushAntiAliasing.value = saved.antiAliasing
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

    /** 現在のブラシ種別の設定を SharedPreferences に永続化 (300ms debounce) */
    private var persistJob: Job? = null
    private fun persistCurrentBrush() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(300L) // スライダードラッグ中の連続書き込みを回避
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
                .putFloat("${prefix}antiAliasing", _brushAntiAliasing.value)
                .putBoolean("${prefix}pressureSize", _pressureSizeEnabled.value)
                .putBoolean("${prefix}pressureOpacity", _pressureOpacityEnabled.value)
                .putBoolean("${prefix}pressureDensity", _pressureDensityEnabled.value)
                .apply()
        }
    }

    /** ブラシ切替時など、即座に永続化が必要な場合 */
    private fun persistCurrentBrushImmediate() {
        persistJob?.cancel()
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
            .putFloat("${prefix}antiAliasing", _brushAntiAliasing.value)
            .putBoolean("${prefix}pressureSize", _pressureSizeEnabled.value)
            .putBoolean("${prefix}pressureOpacity", _pressureOpacityEnabled.value)
            .putBoolean("${prefix}pressureDensity", _pressureDensityEnabled.value)
            .apply()
    }

    // ── 初期化 ──────────────────────────────────────────────────────

    fun initCanvas(width: Int, height: Int) {
        val doc = CanvasDocument(width, height)
        _document = doc; renderer.document = doc; updateLayerState()
        // 名前なしでもプロジェクトを自動作成して保存可能にする
        ensureProjectId(width, height)
        startAutoSave()
    }

    /** ギャラリーから新規プロジェクトを作成して開く */
    fun openNewProject(name: String, width: Int, height: Int) {
        saveCurrentProject()
        val uniqueName = galleryRepo.generateUniqueName(name)
        val id = galleryRepo.createProject(uniqueName, width, height)
        val doc = galleryRepo.loadProject(id)
        if (doc != null) {
            _document = doc; renderer.document = doc; currentProjectId = id
            updateLayerState(); updateUndoState()
            resetView()
            hasUnsavedChanges = false; startAutoSave()
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
            hasUnsavedChanges = false; startAutoSave()
        }
    }

    /**
     * currentProjectId が未設定の場合、自動で新規プロジェクトを作成して ID を付与する。
     * これにより名前なしキャンバスでも自動保存・ライフサイクル保存が機能する。
     * synchronized で多重呼び出しによる重複作成を防止。
     */
    @Synchronized
    private fun ensureProjectId(width: Int, height: Int) {
        if (currentProjectId != null) return
        val doc = _document ?: return
        val name = galleryRepo.generateUniqueName("無題")
        val id = galleryRepo.createProject(name, width, height)
        // createProject は空の CanvasDocument を作成するが、
        // 既に initCanvas で作成済みの doc を使いたいので上書き保存する
        galleryRepo.saveProject(id, doc)
        currentProjectId = id
        PaintDebug.d(PaintDebug.Layer) { "[ViewModel] auto-created project id=$id name=$name" }
    }

    /** 現在のプロジェクトを保存（Mutex で排他制御） */
    fun saveCurrentProject() {
        val doc = _document ?: return
        // currentProjectId が null なら自動でプロジェクトを作成
        if (currentProjectId == null) {
            ensureProjectId(doc.width, doc.height)
        }
        val id = currentProjectId ?: return
        // saveMutex.tryLock で二重実行を防止（ブロッキング回避）
        if (!saveMutex.tryLock()) {
            PaintDebug.d(PaintDebug.Perf) { "[Save] skipped — another save in progress" }
            return
        }
        try {
            galleryRepo.saveProject(id, doc)
        } finally {
            saveMutex.unlock()
        }
    }

    /** ギャラリーに戻る前に保存してドキュメントをクリア */
    fun closeProject() {
        stopAutoSave()
        saveCurrentProject()
        _document = null; renderer.document = null; currentProjectId = null
        _layers.value = emptyList()
        hasUnsavedChanges = false
    }

    // ── ブラシ操作 ──────────────────────────────────────────────────

    fun setBrushType(type: BrushType) {
        // 現在の設定を保存
        brushStateMap[_brushType.value] = BrushState(
            _brushSize.value, _brushOpacity.value, _brushHardness.value,
            _brushDensity.value, _brushSpacing.value, _brushStabilizer.value,
            _colorStretch.value, _waterContent.value, _blurStrength.value,
            _blurPressureThreshold.value, _brushAntiAliasing.value,
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
            _brushAntiAliasing.value = saved.antiAliasing
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
        persistCurrentBrushImmediate()
        refreshCursorSize()
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

    fun setBrushSize(v: Float) { _brushSize.value = v.coerceIn(1f, 2000f); persistCurrentBrush(); refreshCursorSize() }
    fun setBrushOpacity(v: Float) { _brushOpacity.value = v.coerceIn(0.01f, 1f); persistCurrentBrush() }
    fun setBrushHardness(v: Float) { _brushHardness.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun setBrushDensity(v: Float) { _brushDensity.value = v.coerceIn(0.01f, 1f); persistCurrentBrush() }
    fun setBrushSpacing(v: Float) { _brushSpacing.value = v.coerceIn(0.01f, 2f); persistCurrentBrush() }
    fun setColorStretch(v: Float) { _colorStretch.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun setWaterContent(v: Float) { _waterContent.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun setBlurStrength(v: Float) { _blurStrength.value = v.coerceIn(0.05f, 1f); persistCurrentBrush() }
    fun setStabilizer(v: Float) { _brushStabilizer.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun setBrushAntiAliasing(v: Float) { _brushAntiAliasing.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun setBlurPressureThreshold(v: Float) { _blurPressureThreshold.value = v.coerceIn(0f, 1f); persistCurrentBrush() }
    fun togglePressureSize() { _pressureSizeEnabled.value = !_pressureSizeEnabled.value; persistCurrentBrush(); refreshCursorSize() }
    fun togglePressureOpacity() { _pressureOpacityEnabled.value = !_pressureOpacityEnabled.value; persistCurrentBrush() }
    fun togglePressureDensity() { _pressureDensityEnabled.value = !_pressureDensityEnabled.value; persistCurrentBrush() }
    fun togglePressureSelection() { _pressureSelectionEnabled.value = !_pressureSelectionEnabled.value; persistCurrentBrush() }
    /** 現在の描画色を変更する (履歴には追加しない。ドラッグ中等のリアルタイム更新用) */
    fun setColor(color: Color) {
        _currentColor.value = color
    }

    /**
     * 現在色を履歴に確定登録する。
     * カラーピッカーの指離し・スポイト確定・履歴クリック等、
     * ユーザーの意図的な色選択が完了した時点で呼ぶ。
     */
    fun commitColorToHistory(color: Color = _currentColor.value) {
        _currentColor.value = color
        val hist = colorHistory.value.toMutableList()
        hist.remove(color); hist.add(0, color)
        while (hist.size > MAX_COLOR_HISTORY) hist.removeLast()
        colorHistory.value = hist
        persistColorHistory()
    }

    private fun colorToArgbInt(c: Color): Int =
        (0xFF shl 24) or
        ((c.red * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
        ((c.green * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
        (c.blue * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun argbIntToColor(argb: Int): Color = Color(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
    )

    private fun loadColorHistory(): List<Color> {
        val saved = prefs.getString(PREF_KEY_COLOR_HISTORY, null) ?: return DEFAULT_PALETTE
        val colors = saved.split(",").mapNotNull { s ->
            s.trim().toLongOrNull()?.let { argbIntToColor(it.toInt()) }
        }
        if (colors.isEmpty()) return DEFAULT_PALETTE

        // 最終選択色を復元
        prefs.getLong(PREF_KEY_LAST_COLOR, -1L).let {
            if (it >= 0) _currentColor.value = argbIntToColor(it.toInt())
        }
        return colors.take(MAX_COLOR_HISTORY)
    }

    private fun persistColorHistory() {
        val hist = colorHistory.value
        val csv = hist.joinToString(",") { colorToArgbInt(it).toLong().and(0xFFFFFFFFL).toString() }
        prefs.edit()
            .putString(PREF_KEY_COLOR_HISTORY, csv)
            .putLong(PREF_KEY_LAST_COLOR, colorToArgbInt(_currentColor.value).toLong().and(0xFFFFFFFFL))
            .apply()
    }

    fun activateEyedropper() { _toolMode.value = ToolMode.Eyedropper }
    fun deactivateEyedropper() { _toolMode.value = ToolMode.Draw }

    // ── ブラシ設定のエクスポート/インポート (JSON 一括) ──────────────

    /** 全ブラシ設定を JSON 文字列としてエクスポート */
    fun exportBrushSettings(): String {
        // 現在のブラシ状態をマップに反映
        brushStateMap[_brushType.value] = BrushState(
            _brushSize.value, _brushOpacity.value, _brushHardness.value,
            _brushDensity.value, _brushSpacing.value, _brushStabilizer.value,
            _colorStretch.value, _waterContent.value, _blurStrength.value,
            _blurPressureThreshold.value, _brushAntiAliasing.value,
            _pressureSizeEnabled.value, _pressureOpacityEnabled.value,
            _pressureDensityEnabled.value,
        )
        val sb = StringBuilder()
        sb.append("{")
        val entries = BrushType.entries.mapNotNull { type ->
            val s = brushStateMap[type] ?: return@mapNotNull null
            """
            "${type.name}": {
                "size": ${s.size}, "opacity": ${s.opacity}, "hardness": ${s.hardness},
                "density": ${s.density}, "spacing": ${s.spacing}, "stabilizer": ${s.stabilizer},
                "colorStretch": ${s.colorStretch}, "waterContent": ${s.waterContent},
                "blurStrength": ${s.blurStrength}, "blurPressureThreshold": ${s.blurPressureThreshold},
                "antiAliasing": ${s.antiAliasing},
                "pressureSize": ${s.pressureSize}, "pressureOpacity": ${s.pressureOpacity},
                "pressureDensity": ${s.pressureDensity}
            }""".trimIndent()
        }
        sb.append(entries.joinToString(","))
        sb.append("}")
        return sb.toString()
    }

    /** JSON 文字列からブラシ設定を一括インポート */
    fun importBrushSettings(json: String): Boolean {
        return try {
            val org = org.json.JSONObject(json)
            for (type in BrushType.entries) {
                if (!org.has(type.name)) continue
                val obj = org.getJSONObject(type.name)
                brushStateMap[type] = BrushState(
                    size = obj.optDouble("size", 10.0).toFloat(),
                    opacity = obj.optDouble("opacity", 1.0).toFloat(),
                    hardness = obj.optDouble("hardness", 0.8).toFloat(),
                    density = obj.optDouble("density", 0.8).toFloat(),
                    spacing = obj.optDouble("spacing", 0.1).toFloat(),
                    stabilizer = obj.optDouble("stabilizer", 0.3).toFloat(),
                    colorStretch = obj.optDouble("colorStretch", 0.0).toFloat(),
                    waterContent = obj.optDouble("waterContent", 0.0).toFloat(),
                    blurStrength = obj.optDouble("blurStrength", 0.5).toFloat(),
                    blurPressureThreshold = obj.optDouble("blurPressureThreshold", 0.0).toFloat(),
                    antiAliasing = obj.optDouble("antiAliasing", 1.0).toFloat(),
                    pressureSize = obj.optBoolean("pressureSize", true),
                    pressureOpacity = obj.optBoolean("pressureOpacity", false),
                    pressureDensity = obj.optBoolean("pressureDensity", false),
                )
            }
            // 現在のブラシ種別の設定を反映
            val saved = brushStateMap[_brushType.value]
            if (saved != null) {
                _brushSize.value = saved.size; _brushOpacity.value = saved.opacity
                _brushHardness.value = saved.hardness; _brushDensity.value = saved.density
                _brushSpacing.value = saved.spacing; _brushStabilizer.value = saved.stabilizer
                _colorStretch.value = saved.colorStretch; _waterContent.value = saved.waterContent
                _blurStrength.value = saved.blurStrength; _blurPressureThreshold.value = saved.blurPressureThreshold
                _brushAntiAliasing.value = saved.antiAliasing
                _pressureSizeEnabled.value = saved.pressureSize
                _pressureOpacityEnabled.value = saved.pressureOpacity
                _pressureDensityEnabled.value = saved.pressureDensity
            }
            // 全ブラシ種別を永続化
            for (type in BrushType.entries) {
                val s = brushStateMap[type] ?: continue
                val prefix = "brush_${type.name}_"
                prefs.edit()
                    .putFloat("${prefix}size", s.size)
                    .putFloat("${prefix}opacity", s.opacity)
                    .putFloat("${prefix}hardness", s.hardness)
                    .putFloat("${prefix}density", s.density)
                    .putFloat("${prefix}spacing", s.spacing)
                    .putFloat("${prefix}stabilizer", s.stabilizer)
                    .putFloat("${prefix}colorStretch", s.colorStretch)
                    .putFloat("${prefix}waterContent", s.waterContent)
                    .putFloat("${prefix}blurStrength", s.blurStrength)
                    .putFloat("${prefix}blurPressureThreshold", s.blurPressureThreshold)
                    .putBoolean("${prefix}pressureSize", s.pressureSize)
                    .putBoolean("${prefix}pressureOpacity", s.pressureOpacity)
                    .putBoolean("${prefix}pressureDensity", s.pressureDensity)
                    .apply()
            }
            true
        } catch (e: Exception) {
            PaintDebug.d(PaintDebug.Brush) { "[importBrushSettings] failed: ${e.message}" }
            false
        }
    }

    /** 全ブラシ設定をデフォルトに戻す */
    fun resetBrushToDefaults() {
        brushStateMap.clear()
        applyDefaults(_brushType.value)
        // SharedPreferences から全ブラシ設定を削除
        val editor = prefs.edit()
        for (type in BrushType.entries) {
            val prefix = "brush_${type.name}_"
            editor.remove("${prefix}size").remove("${prefix}opacity")
                .remove("${prefix}hardness").remove("${prefix}density")
                .remove("${prefix}spacing").remove("${prefix}stabilizer")
                .remove("${prefix}colorStretch").remove("${prefix}waterContent")
                .remove("${prefix}blurStrength").remove("${prefix}blurPressureThreshold")
                .remove("${prefix}pressureSize").remove("${prefix}pressureOpacity")
                .remove("${prefix}pressureDensity")
        }
        editor.apply()
    }

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

        // ブラシ種別ごとのパラメータ
        val isBlur: Boolean
        val blurStr: Float
        val indirect: Boolean
        val waterCont: Float
        val colStretch: Float
        var subFilter = BrushConfig.SUBLAYER_FILTER_NONE
        var blurPressThreshold = 0f
        var filterScale = 1f

        when (type) {
            BrushType.Fude -> {
                // 筆: content に直接描画+ぼかし (AVERAGING フィルタ)。
                // indirect=false: sublayer→merge の二重適用を防ぎ安定した混色を実現。
                // filterRadiusScale=1.5: 水彩より強い混色効果。ブラシサイズと連動しなくなるので1に設定。
                isBlur = false; blurStr = 0f
                indirect = false; waterCont = 0f; colStretch = _colorStretch.value
                subFilter = BrushConfig.SUBLAYER_FILTER_AVERAGING
                blurPressThreshold = _blurPressureThreshold.value
                filterScale = 1.0f
            }
            BrushType.Watercolor -> {
                // 水彩: content に直接描画+ぼかし (BOX_BLUR フィルタ)。
                // indirect=false: 筆と同様に安定した混色を実現。
                isBlur = false; blurStr = 0f
                indirect = false; waterCont = _waterContent.value; colStretch = _colorStretch.value
                subFilter = BrushConfig.SUBLAYER_FILTER_BOX_BLUR
                blurPressThreshold = _blurPressureThreshold.value
            }
            BrushType.Blur -> {
                isBlur = true; blurStr = _blurStrength.value
                indirect = false; waterCont = 0f; colStretch = 0f
            }
            BrushType.Airbrush -> {
                isBlur = false; blurStr = 0f
                indirect = true; waterCont = 0f; colStretch = 0f
            }
            BrushType.Eraser -> {
                isBlur = false; blurStr = 0f
                indirect = false; waterCont = 0f; colStretch = 0f
            }
            BrushType.Marker -> {
                isBlur = false; blurStr = 0f
                indirect = false; waterCont = 0f; colStretch = 0f
            }
            else -> { // Pencil
                isBlur = false; blurStr = 0f
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
            blurStrength = blurStr,
            indirect = indirect,
            pressureSizeEnabled = _pressureSizeEnabled.value,
            pressureOpacityEnabled = if (opacitySupported) _pressureOpacityEnabled.value else false,
            pressureDensityEnabled = if (densitySupported) _pressureDensityEnabled.value else false,
            taperEnabled = type != BrushType.Eraser,
            waterContent = waterCont,
            colorStretch = colStretch,
            antiAliasing = _brushAntiAliasing.value,
            stabilizer = _brushStabilizer.value,
            sublayerFilter = subFilter,
            filterRadiusScale = filterScale,
            blurPressureThreshold = blurPressThreshold,
        )
    }

    // ── ホバー入力 (スタイラス) ────────────────────────────────────────

    fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                updateCursor(event.x, event.y, 1f)
            }
            MotionEvent.ACTION_HOVER_EXIT -> hideCursor()
        }
        return true
    }

    // ── タッチ入力 ──────────────────────────────────────────────────

    fun onTouchEvent(event: MotionEvent): Boolean {
        val doc = _document ?: return false

        // 慣性アニメーション中に新しいタッチが来たらキャンセル
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            inertiaJob?.cancel()
        }

        // パームリジェクション: スタイラス使用中の大きいタッチを拒否
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stylusIsDown = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        }
        if (stylusIsDown && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            PaintDebug.d(PaintDebug.Input) { "[PalmReject] finger during stylus, rejected" }
            return false
        }
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && event.getSize(0) > PALM_SIZE_THRESHOLD) {
            PaintDebug.d(PaintDebug.Input) { "[PalmReject] size=${event.getSize(0)} rejected" }
            return false
        }

        if (event.pointerCount >= 2) { hideCursor(); handleMultiTouch(event); return true }

        if (isMultiTouch && event.actionMasked == MotionEvent.ACTION_MOVE) return true
        if (isMultiTouch && event.actionMasked == MotionEvent.ACTION_UP) { isMultiTouch = false; return true }

        // スタイラス消しゴム端の自動検出
        val isEraserTip = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

        // 選択ツール (SelectRect / SelectEllipse): ドラッグで領域確定
        if (_toolMode.value == ToolMode.SelectRect || _toolMode.value == ToolMode.SelectEllipse) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // スクリーン座標で保持（MOVE でリアルタイム矩形更新するため）
                    _selectionDragStart = event.x to event.y
                    renderer.selDragRect = floatArrayOf(event.x, event.y, event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val start = _selectionDragStart ?: return true
                    val (sx, sy) = start
                    renderer.selDragRect = floatArrayOf(
                        min(sx, event.x), min(sy, event.y),
                        max(sx, event.x), max(sy, event.y)
                    )
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    renderer.selDragRect = null
                    val start = _selectionDragStart
                    if (start != null && event.actionMasked == MotionEvent.ACTION_UP) {
                        val (sx, sy) = start
                        val (docSX, docSY) = screenToDoc(sx, sy)
                        val (docEX, docEY) = screenToDoc(event.x, event.y)
                        val l = min(docSX, docEX).toInt(); val t = min(docSY, docEY).toInt()
                        val r = max(docSX, docEX).toInt(); val b = max(docSY, docEY).toInt()
                        if (_toolMode.value == ToolMode.SelectRect) selectRect(l, t, r, b)
                        else selectEllipse(l, t, r, b)
                    }
                    _selectionDragStart = null
                }
            }
            return true
        }

        // 自動選択 (MagicWand): タップ位置で色選択
        if (_toolMode.value == ToolMode.SelectMagicWand) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val (dx, dy) = screenToDoc(event.x, event.y)
                selectByColor(dx.toInt(), dy.toInt())
            }
            return true
        }

        // 選択ペン / 選択消しペン: ブラシサイズでマスクをペイント
        if (_toolMode.value == ToolMode.SelectPen || _toolMode.value == ToolMode.SelectEraser) {
            val isAdd = _toolMode.value == ToolMode.SelectPen
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    doc.selectionManager.ensureMask()
                    paintSelectionMask(event, doc, isAdd)
                }
                MotionEvent.ACTION_MOVE -> {
                    paintSelectionMask(event, doc, isAdd)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    paintSelectionMask(event, doc, isAdd)
                    _hasSelection.value = doc.selectionManager.hasSelection
                    pushSelMaskToRenderer()
                }
            }
            return true
        }

        // 図形ツール (ShapeLine / ShapeRect / ShapeEllipse): ドラッグで図形確定
        if (_toolMode.value == ToolMode.ShapeLine ||
            _toolMode.value == ToolMode.ShapeRect ||
            _toolMode.value == ToolMode.ShapeEllipse) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val (dx, dy) = screenToDoc(event.x, event.y)
                    _shapeDragStart = dx to dy
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val start = _shapeDragStart
                    if (start != null && event.actionMasked == MotionEvent.ACTION_UP) {
                        val (sx, sy) = start
                        val (ex, ey) = screenToDoc(event.x, event.y)
                        val l = min(sx, ex).toInt(); val t = min(sy, ey).toInt()
                        val r = max(sx, ex).toInt(); val b = max(sy, ey).toInt()
                        val typeStr = when (_toolMode.value) {
                            ToolMode.ShapeLine -> "line"
                            ToolMode.ShapeRect -> "rect"
                            else -> "ellipse"
                        }
                        drawShape(typeStr, l, t, r, b, fill = false, thickness = 3f)
                    }
                    _shapeDragStart = null
                }
            }
            return true
        }

        // 塗りつぶし (FloodFill): タップ位置で塗りつぶし
        if (_toolMode.value == ToolMode.FloodFill) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val (dx, dy) = screenToDoc(event.x, event.y)
                floodFill(dx.toInt(), dy.toInt())
            }
            return true
        }

        // スポイトモード: タッチしている間連続サンプリング
        if (_toolMode.value == ToolMode.Eyedropper) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    updateCursor(event.x, event.y, event.pressure)
                    val (dx, dy) = screenToDoc(event.x, event.y)
                    val sampled = doc.eyedropperAt(dx.toInt(), dy.toInt())
                    val up = PixelOps.unpremultiply(sampled)
                    // commitColorToHistory は UP 時にのみ実行 (MOVE 中はプレビュー)
                    val c = Color(PixelOps.red(up) / 255f, PixelOps.green(up) / 255f, PixelOps.blue(up) / 255f)
                    _currentColor.value = c
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 最終位置でサンプリングして履歴にコミット
                    val (dx, dy) = screenToDoc(event.x, event.y)
                    val sampled = doc.eyedropperAt(dx.toInt(), dy.toInt())
                    val up = PixelOps.unpremultiply(sampled)
                    commitColorToHistory(Color(PixelOps.red(up) / 255f, PixelOps.green(up) / 255f, PixelOps.blue(up) / 255f))
                    _toolMode.value = ToolMode.Draw
                    hideCursor()
                }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isMultiTouch = false
                updateCursor(event.x, event.y, event.pressure)
                var brush = buildBrushConfig()
                if (isEraserTip) brush = brush.copy(isEraser = true, indirect = false, taperEnabled = false)
                doc.beginStroke(brush)
                _isDrawing.value = true
                processDrawPoints(event, doc, brush)
            }
            MotionEvent.ACTION_MOVE -> {
                updateCursor(event.x, event.y, event.pressure)
                if (_isDrawing.value) {
                    var brush = buildBrushConfig()
                    if (isEraserTip) brush = brush.copy(isEraser = true, indirect = false, taperEnabled = false)
                    processDrawPoints(event, doc, brush)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hideCursor()
                if (event.actionMasked == MotionEvent.ACTION_UP) stylusIsDown = false
                if (_isDrawing.value) {
                    var brush = buildBrushConfig()
                    if (isEraserTip) brush = brush.copy(isEraser = true, indirect = false, taperEnabled = false)
                    doc.endStroke(brush)
                    _isDrawing.value = false
                    hasUnsavedChanges = true
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

    // マルチタッチ開始時に不要ストロークが始まっていた場合の追加 undo 回数
    private var multiTouchAccidentalStroke = false
    // タップアクション発火済みフラグ（二重発火防止）
    private var tapActionFired = false

    /** 選択ツールのドラッグ開始座標 (スクリーン座標) */
    private var _selectionDragStart: Pair<Float, Float>? = null
    /** 図形ツールのドラッグ開始座標 (ドキュメント座標) */
    private var _shapeDragStart: Pair<Float, Float>? = null

    /** 選択ペン/消しペン: ブラシサイズの円で選択マスクをペイント */
    private fun paintSelectionMask(event: MotionEvent, doc: CanvasDocument, isAdd: Boolean) {
        val radius = max(1f, _brushSize.value / 2f)
        // 筆圧適用が有効な場合は デバイス筆圧を使用、無効な場合は 1.0 (100%)
        val usePressure = _pressureSelectionEnabled.value
        val historySize = event.historySize
        val hasHistory = historySize > 0

        for (h in 0 until historySize) {
            val (dx, dy) = screenToDoc(event.getHistoricalX(h), event.getHistoricalY(h))
            val basePressure = if (usePressure) event.getHistoricalPressure(h).coerceIn(0.05f, 1f) else 1f
            // 筆圧 0 の場合は最小値 0.05 を使用（デバイス未対応時）
            val pressure = if (basePressure == 0f) 0.05f else basePressure
            // 入り: 最初のストロークほど弱める、抜き: 最後のストロークほど弱める
            val tapering = if (hasHistory) {
                val ratio = h.toFloat() / historySize
                // ハンニング窓でテーパー: 入りと抜きを自然に
                val taper = (1f - kotlin.math.cos(ratio * Math.PI.toFloat())) / 2f
                taper
            } else {
                1f
            }
            val taperedPressure = pressure * tapering
            doc.selectionManager.paintCircle(dx.toInt(), dy.toInt(), radius.toInt(), isAdd, taperedPressure)
        }
        val (dx, dy) = screenToDoc(event.x, event.y)
        val basePressure = if (usePressure) event.pressure.coerceIn(0.05f, 1f) else 1f
        val pressure = if (basePressure == 0f) 0.05f else basePressure
        doc.selectionManager.paintCircle(dx.toInt(), dy.toInt(), radius.toInt(), isAdd, pressure)
    }

    private fun handleMultiTouch(event: MotionEvent) {
        if (!isMultiTouch && _isDrawing.value) {
            // 最初の指で始まった不要ストロークを終了し、即座に undo で取り消す
            _document?.endStroke(buildBrushConfig()); _isDrawing.value = false
            _document?.undo(); updateUndoState()
            multiTouchAccidentalStroke = true
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

                // タップ検出: 指が増えたらタイマーとムーブメントをリセット
                multiTouchDownTime = SystemClock.uptimeMillis()
                multiTouchMaxFingers = maxOf(multiTouchMaxFingers, event.pointerCount)
                multiTouchTotalMovement = 0f
                gestureCommittedToPinch = false
                tapActionFired = false
                multiTouchLastMidX = midX; multiTouchLastMidY = midY

                // VelocityTracker 初期化
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                multiTouchMaxFingers = maxOf(multiTouchMaxFingers, event.pointerCount)

                // 移動量を累積してタップ/ピンチを判定
                val moveDx = midX - multiTouchLastMidX
                val moveDy = midY - multiTouchLastMidY
                multiTouchTotalMovement += sqrt(moveDx * moveDx + moveDy * moveDy)
                multiTouchLastMidX = midX; multiTouchLastMidY = midY

                // 3本指は手の微動が大きいので閾値を緩くする
                val currentSlop = if (multiTouchMaxFingers >= 3) TAP_SLOP * 2.5f else TAP_SLOP
                if (!gestureCommittedToPinch && multiTouchTotalMovement > currentSlop) {
                    gestureCommittedToPinch = true
                }

                // ピンチ/ズーム/回転/パン (タップと判定されない場合のみ)
                if (gestureCommittedToPinch && gestureStartDist > 10f) {
                    _zoom = (gestureStartZoom * dist / gestureStartDist).coerceIn(0.1f, 30f)
                    _rotation = gestureStartRotation + (angle - gestureStartAngle)
                    _panX = gestureStartPanX + (midX - gestureStartMidX)
                    _panY = gestureStartPanY + (midY - gestureStartMidY)
                    renderer.pendingTransform.set(floatArrayOf(_zoom, _panX, _panY, _rotation))
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val elapsed = SystemClock.uptimeMillis() - multiTouchDownTime
                // 3本指は配置に時間がかかるので猶予を長く
                val currentTimeout = if (multiTouchMaxFingers >= 3) TAP_TIMEOUT_MS * 2 else TAP_TIMEOUT_MS

                // タップ判定（二重発火防止）
                if (!tapActionFired && !gestureCommittedToPinch && elapsed < currentTimeout) {
                    tapActionFired = true
                    when (multiTouchMaxFingers) {
                        2 -> {
                            PaintDebug.d(PaintDebug.Input) {
                                "[MultiTap] fingers=2 movement=${multiTouchTotalMovement} elapsed=${elapsed}ms action=undo"
                            }
                            undo()
                            onGestureEvent?.invoke("undo")
                        }
                        3 -> {
                            PaintDebug.d(PaintDebug.Input) {
                                "[MultiTap] fingers=3 movement=${multiTouchTotalMovement} elapsed=${elapsed}ms action=redo"
                            }
                            redo()
                            onGestureEvent?.invoke("redo")
                        }
                    }
                } else if (gestureCommittedToPinch && !tapActionFired) {
                    // 慣性開始（ピンチ操作の場合のみ）
                    startInertia()
                }

                // リセット (全指が離れた場合)
                if ((event.pointerCount <= 2 && event.actionMasked == MotionEvent.ACTION_POINTER_UP) ||
                    event.actionMasked == MotionEvent.ACTION_UP) {
                    multiTouchMaxFingers = 0
                    multiTouchAccidentalStroke = false
                    tapActionFired = false
                    velocityTracker?.recycle(); velocityTracker = null
                }
            }
        }
    }

    private fun startInertia() {
        val vt = velocityTracker ?: return
        vt.computeCurrentVelocity(1000) // pixels/sec
        var vx = vt.xVelocity
        var vy = vt.yVelocity

        // NaN/Infinity 防御
        if (vx.isNaN() || vx.isInfinite()) vx = 0f
        if (vy.isNaN() || vy.isInfinite()) vy = 0f

        // 最大速度を制限
        val maxV = 2000f
        vx = vx.coerceIn(-maxV, maxV)
        vy = vy.coerceIn(-maxV, maxV)

        // 速度が小さすぎたら慣性なし
        if (abs(vx) < 50f && abs(vy) < 50f) return

        PaintDebug.d(PaintDebug.Perf) { "[Inertia] start vx=$vx vy=$vy" }

        inertiaJob?.cancel()
        inertiaJob = viewModelScope.launch {
            val dt = 0.016f // ~60fps
            while (abs(vx) > INERTIA_MIN_VELOCITY || abs(vy) > INERTIA_MIN_VELOCITY) {
                _panX += vx * dt
                _panY += vy * dt
                vx *= INERTIA_FRICTION
                vy *= INERTIA_FRICTION
                renderer.pendingTransform.set(floatArrayOf(_zoom, _panX, _panY, _rotation))
                delay(16L)
            }
        }
    }

    // ── レイヤー操作 ────────────────────────────────────────────────

    fun addLayer() {
        val doc = _document ?: return
        // アクティブレイヤーの1つ上に挿入
        val activeIdx = doc.layers.indexOfFirst { it.id == doc.activeLayerId }
        val insertAt = if (activeIdx >= 0) activeIdx + 1 else doc.layers.size
        val newLayer = doc.addLayer("レイヤー ${doc.nextLayerNameIndex}", insertAt)
        doc.setActiveLayer(newLayer.id)
        hasUnsavedChanges = true; updateLayerState()
    }
    fun removeLayer(id: Int) { _document?.removeLayer(id); hasUnsavedChanges = true; updateLayerState() }
    fun selectLayer(id: Int) { _document?.setActiveLayer(id); updateLayerState() }
    fun setLayerVisibility(id: Int, v: Boolean) { _document?.setLayerVisibility(id, v); updateLayerState() }
    fun setLayerOpacity(id: Int, v: Float) { _document?.setLayerOpacity(id, v); updateLayerState() }
    fun setLayerBlendMode(id: Int, m: Int) { _document?.setLayerBlendMode(id, m); updateLayerState() }
    fun setLayerClip(id: Int, clip: Boolean) { _document?.setLayerClipToBelow(id, clip); updateLayerState() }
    fun setLayerLocked(id: Int, locked: Boolean) { _document?.setLayerLocked(id, locked); updateLayerState() }
    fun setAlphaLocked(id: Int, locked: Boolean) {
        val doc = _document ?: return
        val layer = doc.layers.find { it.id == id } ?: return
        layer.isAlphaLocked = locked
        updateLayerState()
    }
    fun reorderLayer(fromIndex: Int, toIndex: Int) {
        val doc = _document ?: return
        require(fromIndex in doc.layers.indices) { "fromIndex out of range: $fromIndex" }
        require(toIndex in doc.layers.indices) { "toIndex out of range: $toIndex" }
        if (fromIndex == toIndex) return
        doc.moveLayer(fromIndex, toIndex)
        updateLayerState()
    }
    fun clearActiveLayer() { _document?.let { it.clearLayer(it.activeLayerId) }; hasUnsavedChanges = true; updateLayerState() }
    fun duplicateLayer(id: Int) { _document?.duplicateLayer(id); updateLayerState() }
    fun mergeDown(id: Int) { _document?.mergeDown(id); updateLayerState() }
    fun batchMergeLayers(ids: List<Int>) { _document?.batchMergeLayers(ids); updateLayerState() }
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

    // ── レイヤーグループ ──────────────────────────────────────────────
    fun createLayerGroup(name: String) {
        _document?.createLayerGroup(name); updateLayerState()
    }
    fun deleteLayerGroup(groupId: Int) {
        _document?.deleteLayerGroup(groupId); updateLayerState()
    }
    fun setLayerGroup(layerId: Int, groupId: Int) {
        _document?.setLayerGroup(layerId, groupId); updateLayerState()
    }
    fun setGroupVisibility(groupId: Int, visible: Boolean) {
        _document?.setGroupVisibility(groupId, visible); updateUndoState()
    }
    fun setGroupOpacity(groupId: Int, opacity: Float) {
        _document?.setGroupOpacity(groupId, opacity); updateUndoState()
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

    fun undo() { _document?.undo(); hasUnsavedChanges = true; updateUndoState() }
    fun redo() { _document?.redo(); hasUnsavedChanges = true; updateUndoState() }

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
                it.isVisible, it.isLocked, it.isClipToBelow, it.id == doc.activeLayerId,
                it.isAlphaLocked,
                hasMask = it.mask != null,
                isMaskEnabled = it.isMaskEnabled,
                isEditingMask = it.isEditingMask,
                groupId = it.groupId,
                isTextLayer = it.textConfig != null)
        }
    }

    private fun updateUndoState() {
        val doc = _document ?: return
        _canUndo.value = doc.canUndo; _canRedo.value = doc.canRedo
    }

    // ── 選択ツール ──────────────────────────────────────────────

    /** 選択状態 */
    private val _hasSelection = MutableStateFlow(false)
    val hasSelection: StateFlow<Boolean> = _hasSelection.asStateFlow()

    private val _selectionMode = MutableStateFlow(com.propaint.app.engine.SelectionMode.Replace)
    val selectionMode: StateFlow<com.propaint.app.engine.SelectionMode> = _selectionMode.asStateFlow()

    fun setSelectionMode(mode: com.propaint.app.engine.SelectionMode) { _selectionMode.value = mode }

    /** 選択マスクを GL レンダラーに送る */
    private fun pushSelMaskToRenderer() {
        val doc = _document ?: return
        val mask = doc.selectionManager.mask
        renderer.pendingSelMask.set(mask?.copyOf())
        renderer.selMaskDirty = true
    }

    fun selectRect(left: Int, top: Int, right: Int, bottom: Int) {
        val doc = _document ?: return
        doc.selectionManager.selectRect(left, top, right, bottom, _selectionMode.value)
        _hasSelection.value = doc.selectionManager.hasSelection
        pushSelMaskToRenderer()
    }

    fun selectEllipse(left: Int, top: Int, right: Int, bottom: Int) {
        val doc = _document ?: return
        doc.selectionManager.selectEllipse(left, top, right, bottom, _selectionMode.value)
        _hasSelection.value = doc.selectionManager.hasSelection
        pushSelMaskToRenderer()
    }

    fun selectLasso(points: List<Pair<Int, Int>>) {
        val doc = _document ?: return
        doc.selectionManager.selectLasso(points, _selectionMode.value)
        _hasSelection.value = doc.selectionManager.hasSelection
        pushSelMaskToRenderer()
    }

    fun selectByColor(x: Int, y: Int, tolerance: Int = 32, contiguous: Boolean = true) {
        val doc = _document ?: return
        val layer = doc.getActiveLayer() ?: return
        doc.selectionManager.selectByColor(layer.content, x, y, tolerance, contiguous, _selectionMode.value)
        _hasSelection.value = doc.selectionManager.hasSelection
        pushSelMaskToRenderer()
    }

    fun invertSelection() {
        val doc = _document ?: return
        doc.selectionManager.invertSelection()
        _hasSelection.value = doc.selectionManager.hasSelection
        pushSelMaskToRenderer()
    }

    fun clearSelection() {
        val doc = _document ?: return
        doc.selectionManager.clearSelection()
        _hasSelection.value = false
        pushSelMaskToRenderer()
    }

    fun selectAll() {
        val doc = _document ?: return
        doc.selectionManager.selectAll()
        _hasSelection.value = true
        pushSelMaskToRenderer()
    }

    fun expandSelection(amount: Int) {
        _document?.selectionManager?.expandContract(amount)
        pushSelMaskToRenderer()
    }
    fun featherSelection(radius: Int) {
        _document?.selectionManager?.feather(radius)
        pushSelMaskToRenderer()
    }

    fun paintSelectionAdd(cx: Int, cy: Int, radius: Int, pressure: Float = 1f) {
        val doc = _document ?: return
        doc.selectionManager.ensureMask()
        doc.selectionManager.paintCircle(cx, cy, radius, isAdd = true, pressure = pressure)
        _hasSelection.value = doc.selectionManager.hasSelection
        pushSelMaskToRenderer()
    }

    fun paintSelectionErase(cx: Int, cy: Int, radius: Int, pressure: Float = 1f) {
        val doc = _document ?: return
        doc.selectionManager.ensureMask()
        doc.selectionManager.paintCircle(cx, cy, radius, isAdd = false, pressure = pressure)
        _hasSelection.value = doc.selectionManager.hasSelection
        pushSelMaskToRenderer()
    }

    // ── 変形ツール ──────────────────────────────────────────────

    fun transformActiveLayer(scaleX: Float, scaleY: Float, angleDeg: Float, tx: Float, ty: Float) {
        val doc = _document ?: return
        val cx = doc.width / 2f; val cy = doc.height / 2f
        doc.transformLayer(doc.activeLayerId, cx, cy, scaleX, scaleY, angleDeg, tx, ty)
        updateUndoState()
    }

    fun flipActiveLayerH() { _document?.flipLayerH(_document?.activeLayerId ?: return); updateUndoState() }
    fun flipActiveLayerV() { _document?.flipLayerV(_document?.activeLayerId ?: return); updateUndoState() }
    fun rotateActiveLayer90CW() { _document?.rotateLayer90CW(_document?.activeLayerId ?: return); updateUndoState() }

    // ── レイヤーマスク ──────────────────────────────────────────

    fun addMaskToActiveLayer(fillWhite: Boolean = true) {
        val doc = _document ?: return
        doc.addLayerMask(doc.activeLayerId, fillWhite)
        updateLayerState()
    }

    fun removeMaskFromActiveLayer() {
        val doc = _document ?: return
        doc.removeLayerMask(doc.activeLayerId)
        updateLayerState()
    }

    fun toggleMaskEnabled() {
        val doc = _document ?: return
        doc.toggleMaskEnabled(doc.activeLayerId)
        updateLayerState()
    }

    fun toggleEditMask() {
        val doc = _document ?: return
        doc.toggleEditMask(doc.activeLayerId)
        updateLayerState()
    }

    fun addMaskFromSelection() {
        val doc = _document ?: return
        doc.addMaskFromSelection(doc.activeLayerId)
        updateLayerState()
    }

    // ── レイヤーグループ ────────────────────────────────────────

    fun createGroup(name: String) {
        val doc = _document ?: return
        doc.createLayerGroup(name)
    }

    fun deleteGroup(groupId: Int) {
        val doc = _document ?: return
        doc.deleteLayerGroup(groupId)
        updateLayerState()
    }

    fun setActiveLayerGroup(groupId: Int) {
        val doc = _document ?: return
        doc.setLayerGroup(doc.activeLayerId, groupId)
        updateLayerState()
    }

    // ── 図形・塗りつぶし ────────────────────────────────────────

    fun setToolMode(mode: ToolMode) { _toolMode.value = mode }

    fun drawShape(shapeType: String, left: Int, top: Int, right: Int, bottom: Int, fill: Boolean, thickness: Float = 1f) {
        val doc = _document ?: return
        val colorInt = colorToArgbInt(_currentColor.value)
        val premul = com.propaint.app.engine.PixelOps.premultiply(colorInt)
        doc.drawShape(doc.activeLayerId, shapeType, left, top, right, bottom, premul, fill, thickness)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun floodFill(x: Int, y: Int, tolerance: Int = 0) {
        val doc = _document ?: return
        val colorInt = colorToArgbInt(_currentColor.value)
        val premul = com.propaint.app.engine.PixelOps.premultiply(colorInt)
        doc.floodFill(doc.activeLayerId, x, y, premul, tolerance)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun drawGradient(startX: Float, startY: Float, endX: Float, endY: Float,
                     startColor: Int, endColor: Int, type: com.propaint.app.engine.ShapeRenderer.GradientType) {
        val doc = _document ?: return
        doc.drawGradient(doc.activeLayerId, startX, startY, endX, endY, startColor, endColor, type)
        hasUnsavedChanges = true; updateUndoState()
    }

    // ── テキストレイヤー ────────────────────────────────────────

    fun addTextLayer(text: String, fontSize: Float = 48f, x: Float = 0f, y: Float = 0f,
                     bold: Boolean = false, italic: Boolean = false, vertical: Boolean = false) {
        val doc = _document ?: return
        val colorInt = colorToArgbInt(_currentColor.value)
        val premul = com.propaint.app.engine.PixelOps.premultiply(colorInt)
        val config = com.propaint.app.engine.TextRenderer.TextConfig(
            text = text, fontSize = fontSize, color = premul,
            isBold = bold, isItalic = italic, isVertical = vertical,
            x = x, y = y
        )
        doc.addTextLayer(config)
        hasUnsavedChanges = true; updateLayerState(); updateUndoState()
    }

    fun updateTextLayer(layerId: Int, text: String, fontSize: Float = 48f, x: Float = 0f, y: Float = 0f,
                        bold: Boolean = false, italic: Boolean = false, vertical: Boolean = false) {
        val doc = _document ?: return
        val colorInt = colorToArgbInt(_currentColor.value)
        val premul = com.propaint.app.engine.PixelOps.premultiply(colorInt)
        val config = com.propaint.app.engine.TextRenderer.TextConfig(
            text = text, fontSize = fontSize, color = premul,
            isBold = bold, isItalic = italic, isVertical = vertical,
            x = x, y = y
        )
        doc.updateTextLayer(layerId, config)
        hasUnsavedChanges = true; updateUndoState()
    }

    // ── 追加フィルター ──────────────────────────────────────────

    fun applyToneCurve(masterPoints: List<Pair<Int, Int>>? = null,
                       redPoints: List<Pair<Int, Int>>? = null,
                       greenPoints: List<Pair<Int, Int>>? = null,
                       bluePoints: List<Pair<Int, Int>>? = null) {
        val doc = _document ?: return
        val master = masterPoints?.let { com.propaint.app.engine.FilterOps.buildCurveLut(it) }
        val red = redPoints?.let { com.propaint.app.engine.FilterOps.buildCurveLut(it) }
        val green = greenPoints?.let { com.propaint.app.engine.FilterOps.buildCurveLut(it) }
        val blue = bluePoints?.let { com.propaint.app.engine.FilterOps.buildCurveLut(it) }
        doc.applyToneCurve(doc.activeLayerId, master, red, green, blue)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun applyLevels(inBlack: Int = 0, inWhite: Int = 255, gamma: Float = 1f,
                    outBlack: Int = 0, outWhite: Int = 255) {
        val doc = _document ?: return
        doc.applyLevels(doc.activeLayerId, inBlack, inWhite, gamma, outBlack, outWhite)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun applyColorBalance(cyanRed: Int, magentaGreen: Int, yellowBlue: Int) {
        val doc = _document ?: return
        doc.applyColorBalance(doc.activeLayerId, cyanRed, magentaGreen, yellowBlue)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun applyUnsharpMask(radius: Int = 1, amount: Float = 1f, threshold: Int = 0) {
        val doc = _document ?: return
        doc.applyUnsharpMask(doc.activeLayerId, radius, amount, threshold)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun applyMosaic(blockSize: Int) {
        val doc = _document ?: return
        doc.applyMosaic(doc.activeLayerId, blockSize)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun applyNoise(amount: Int, monochrome: Boolean = true) {
        val doc = _document ?: return
        doc.applyNoise(doc.activeLayerId, amount, monochrome)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun applyPosterize(levels: Int) {
        val doc = _document ?: return
        doc.applyPosterize(doc.activeLayerId, levels)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun applyThreshold(threshold: Int) {
        val doc = _document ?: return
        doc.applyThreshold(doc.activeLayerId, threshold)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun applyGradientMap(gradientLut: IntArray) {
        val doc = _document ?: return
        doc.applyGradientMap(doc.activeLayerId, gradientLut)
        hasUnsavedChanges = true; updateUndoState()
    }
}
