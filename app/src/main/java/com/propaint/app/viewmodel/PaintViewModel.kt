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
    val isGroup: Boolean = false,  // レイヤーグループ（フォルダ）フラグ
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
    SelectMagnet, SelectMagicWand,
    SelectPen,
    Transform,
    PixelCopy,
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
    private val _minBrushSizePercent = MutableStateFlow(20)
    val minBrushSizePercent: StateFlow<Int> = _minBrushSizePercent.asStateFlow()
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

    // ストローク中のブラシ設定キャッシュ (ACTION_DOWN で構築、MOVE 中は再利用)
    private var cachedStrokeBrush: BrushConfig? = null

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
    fun setBrushMinSizePercent(p: Int) { _minBrushSizePercent.value = p.coerceIn(1, 100); persistCurrentBrush() }
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
            minBrushSizePercent = _minBrushSizePercent.value,
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

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            PaintDebug.d(PaintDebug.Input) { "[Touch] ACTION_DOWN at (${event.x}, ${event.y}), toolMode=${_toolMode.value.name}" }
        }

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

        // 自動選択 (MagicWand): タップ位置で色選択
        if (_toolMode.value == ToolMode.SelectMagicWand) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val (dx, dy) = screenToDoc(event.x, event.y)
                selectByColor(dx.toInt(), dy.toInt())
            }
            return true
        }

        // マグネット選択 (SelectMagnet): タップでアンカーポイント設定、エッジ自動追跡
        if (_toolMode.value == ToolMode.SelectMagnet) {
            val activeLayer = doc.getActiveLayer()
            if (activeLayer == null) {
                PaintDebug.d(PaintDebug.Input) { "[Magnet] no active layer" }
                return true
            }
            val surface = activeLayer.content
            if (surface == null) {
                PaintDebug.d(PaintDebug.Input) { "[Magnet] active layer has no surface" }
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    val (dx, dy) = screenToDoc(event.x, event.y)
                    val tapX = dx.toInt()
                    val tapY = dy.toInt()

                    // 開始点がまだ設定されていない：最初のタップ
                    if (_magnetStart == null) {
                        _magnetStart = tapX to tapY
                        _magnetPoints.add(tapX to tapY)
                        PaintDebug.d(PaintDebug.Input) { "[Magnet] start anchor set at ($tapX, $tapY)" }
                    } else {
                        // 終点が開始点に近い（40px以内）か判定 → 自動確定
                        val start = _magnetStart!!
                        val distToStart = hypot((tapX - start.first).toDouble(), (tapY - start.second).toDouble())

                        if (distToStart < 40.0 && _magnetPoints.size >= 2) {
                            // パスを開始点に閉じて確定
                            val lastPoint = _magnetPoints.lastOrNull()
                            if (lastPoint != null) {
                                _magnetPath.addAll(traceMagnetPath(surface, lastPoint.first, lastPoint.second, start.first, start.second))
                            }
                            _magnetPath.add(start)  // 閉じるために開始点を追加

                            // 選択を確定
                            selectLasso(_magnetPath.toList())
                            _magnetStart = null
                            _magnetPoints.clear()
                            _magnetPath.clear()
                            PaintDebug.d(PaintDebug.Input) { "[Magnet] selection finalized (auto-closed) with ${_magnetPoints.size} anchors, dist=$distToStart" }
                        } else {
                            // 新規アンカーポイント追加 + 前のアンカーから現在位置へエッジ追跡
                            val lastPoint = _magnetPoints.lastOrNull()
                            if (lastPoint != null) {
                                _magnetPath.addAll(traceMagnetPath(surface, lastPoint.first, lastPoint.second, tapX, tapY))
                            }
                            _magnetPoints.add(tapX to tapY)
                            val distFromStart = hypot((tapX - start.first).toDouble(), (tapY - start.second).toDouble())
                            PaintDebug.d(PaintDebug.Input) { "[Magnet] anchor added at ($tapX, $tapY), anchors=${_magnetPoints.size}, distFromStart=$distFromStart" }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // リアルタイムプレビュー：最後のアンカーから現在位置への仮パス
                    // (現在の実装では省略。必要に応じてプレビューレンダリングを追加)
                }
                MotionEvent.ACTION_CANCEL -> {
                    // キャンセル
                    _magnetStart = null
                    _magnetPoints.clear()
                    _magnetPath.clear()
                    PaintDebug.d(PaintDebug.Input) { "[Magnet] cancelled" }
                }
            }
            return true
        }

        // 選択ペン: ブラシサイズでマスクをペイント
        if (_toolMode.value == ToolMode.SelectPen) {
            val isAdd = true
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
                cachedStrokeBrush = brush
                doc.beginStroke(brush)
                _isDrawing.value = true
                processDrawPoints(event, doc, brush)
            }
            MotionEvent.ACTION_MOVE -> {
                updateCursor(event.x, event.y, event.pressure)
                if (_isDrawing.value) {
                    val brush = cachedStrokeBrush ?: return true
                    processDrawPoints(event, doc, brush)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hideCursor()
                if (event.actionMasked == MotionEvent.ACTION_UP) stylusIsDown = false
                if (_isDrawing.value) {
                    val brush = cachedStrokeBrush ?: buildBrushConfig()
                    doc.endStroke(brush)
                    cachedStrokeBrush = null
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
    /** なげなわ選択のポイント蓄積 */
    private val _lassoPoints = mutableListOf<Pair<Int, Int>>()

    /** マグネット選択: 開始アンカーポイント */
    private var _magnetStart: Pair<Int, Int>? = null
    /** マグネット選択: 蓄積アンカーポイント */
    private val _magnetPoints = mutableListOf<Pair<Int, Int>>()
    /** マグネット選択: 確定済みパス */
    private val _magnetPath = mutableListOf<Pair<Int, Int>>()

    /** 図形ツールのドラッグ開始座標 (ドキュメント座標) */
    private var _shapeDragStart: Pair<Float, Float>? = null

    // ─────────────────────────────────────────
    // マグネット選択のヘルパー関数
    // ─────────────────────────────────────────

    /** 指定座標のエッジ強度を計算（輝度勾配） */
    private fun edgeStrength(surface: TiledSurface, x: Int, y: Int): Float {
        fun lum(px: Int, py: Int): Float {
            val p = PixelOps.unpremultiply(surface.getPixelAt(px, py))
            return PixelOps.red(p) * 0.299f + PixelOps.green(p) * 0.587f + PixelOps.blue(p) * 0.114f
        }
        val gx = lum(x + 1, y) - lum(x - 1, y)
        val gy = lum(x, y + 1) - lum(x, y - 1)
        return sqrt(gx * gx + gy * gy)
    }

    /** A→B のベクトルに垂直な方向でエッジが強い点を探す */
    private fun findBestEdgePoint(
        surface: TiledSurface,
        cx: Int, cy: Int,
        ax: Int, ay: Int, bx: Int, by: Int,
        width: Int = 5
    ): Pair<Int, Int> {
        // A→B のベクトル
        val dx = (bx - ax).toFloat()
        val dy = (by - ay).toFloat()
        val len = hypot(dx, dy).coerceAtLeast(1f)
        // 法線ベクトル（垂直方向）
        val nx = (-dy / len).toInt()
        val ny = (dx / len).toInt()

        var bestStr = -1f
        var bestX = cx
        var bestY = cy

        for (d in -width..width) {
            val px = cx + nx * d
            val py = cy + ny * d
            val str = edgeStrength(surface, px, py)
            if (str > bestStr) {
                bestStr = str
                bestX = px
                bestY = py
            }
        }
        return bestX to bestY
    }

    /** A→B の経路を Bresenham + エッジ追跡で生成 */
    private fun traceMagnetPath(
        surface: TiledSurface,
        ax: Int, ay: Int, bx: Int, by: Int,
        snappingWidth: Int = 5
    ): List<Pair<Int, Int>> {
        val points = mutableListOf<Pair<Int, Int>>()

        var x = ax
        var y = ay
        val dx = abs(bx - ax)
        val dy = abs(by - ay)
        val sx = if (ax < bx) 1 else -1
        val sy = if (ay < by) 1 else -1
        var err = dx - dy

        while (true) {
            // 垂直方向にエッジ探索
            val snapped = findBestEdgePoint(surface, x, y, ax, ay, bx, by, snappingWidth)
            points.add(snapped)

            if (x == bx && y == by) break

            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                x += sx
            }
            if (e2 < dx) {
                err += dx
                y += sy
            }
        }

        return points
    }

    /** マグネット選択をキャンセル */
    fun cancelMagnetSelection() {
        _magnetStart = null
        _magnetPoints.clear()
        _magnetPath.clear()
        PaintDebug.d(PaintDebug.Input) { "[Magnet] selection cancelled via button" }
    }

    /** 選択ペン/消しペン: ブラシサイズの円で選択マスクをペイント */
    private fun paintSelectionMask(event: MotionEvent, doc: CanvasDocument, isAdd: Boolean) {
        val radius = max(1f, _brushSize.value / 2f)
        // 筆圧適用が有効な場合は デバイス筆圧を使用、無効な場合は 1.0 (100%)
        val usePressure = _pressureSelectionEnabled.value

        for (h in 0 until event.historySize) {
            val (dx, dy) = screenToDoc(event.getHistoricalX(h), event.getHistoricalY(h))
            val pressure = if (usePressure) event.getHistoricalPressure(h).coerceIn(0f, 1f) else 1f
            doc.selectionManager.paintCircle(dx.toInt(), dy.toInt(), radius.toInt(), isAdd, pressure)
        }
        val (dx, dy) = screenToDoc(event.x, event.y)
        val pressure = if (usePressure) event.pressure.coerceIn(0f, 1f) else 1f
        doc.selectionManager.paintCircle(dx.toInt(), dy.toInt(), radius.toInt(), isAdd, pressure)
    }

    private fun handleMultiTouch(event: MotionEvent) {
        if (!isMultiTouch && _isDrawing.value) {
            // 最初の指で始まった不要ストロークを終了し、即座に undo で取り消す
            val brush = cachedStrokeBrush ?: buildBrushConfig()
            _document?.endStroke(brush); cachedStrokeBrush = null; _isDrawing.value = false
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
        if (fromIndex !in doc.layers.indices || toIndex !in doc.layers.indices) return
        if (fromIndex == toIndex) return
        doc.moveLayer(fromIndex, toIndex)
        updateLayerState()
    }

    fun reorderLayerGroup(fromGroupId: Int, toGroupId: Int) {
        val doc = _document ?: return
        doc.reorderLayerGroup(fromGroupId, toGroupId)
        updateLayerState()
    }

    /// フォルダとレイヤーが混在している場合の統一的な並び替え
    /// fromId/toId: レイヤーID またはグループID（グループは負数）
    fun reorderDisplayItem(fromId: Int, toId: Int) {
        val doc = _document ?: return

        // グループID（負数）を正に変換
        val actualFromId = if (fromId < 0) -fromId else fromId
        val actualToId = if (toId < 0) -toId else toId

        // fromId と toId のアイテムを取得
        val fromLayer = doc.layers.find { it.id == actualFromId }
        val fromGroup = doc.layerGroups[actualFromId]
        val fromItem = fromLayer ?: (fromGroup?.let { it as Any })
        val fromDisplayOrder = fromLayer?.displayOrder ?: fromGroup?.displayOrder ?: return

        val toLayer = doc.layers.find { it.id == actualToId }
        val toGroup = doc.layerGroups[actualToId]
        val toItem = toLayer ?: (toGroup?.let { it as Any })
        val toDisplayOrder = toLayer?.displayOrder ?: toGroup?.displayOrder ?: return

        if (fromDisplayOrder == toDisplayOrder) return

        // 移動方向を判定して、途中のアイテムを シフトして順序を保持
        val allItems = (doc.layers.map { it as Any } + doc.layerGroups.values.map { it as Any })
            .sortedBy {
                when (it) {
                    is Layer -> it.displayOrder
                    is LayerGroupInfo -> it.displayOrder
                    else -> 0
                }
            }

        if (fromDisplayOrder < toDisplayOrder) {
            // fromId が上にある（displayOrder が小さい）→ 下に移動
            // fromId の次から toId までを1つ上にシフト
            for (item in allItems) {
                val order = when (item) {
                    is Layer -> item.displayOrder
                    is LayerGroupInfo -> item.displayOrder
                    else -> continue
                }
                if (order > fromDisplayOrder && order <= toDisplayOrder) {
                    when (item) {
                        is Layer -> item.displayOrder -= 1
                        is LayerGroupInfo -> item.displayOrder -= 1
                    }
                }
            }
            // fromId を toId と同じ位置に移動
            when (fromItem) {
                is Layer -> fromItem.displayOrder = toDisplayOrder - 1
                is LayerGroupInfo -> fromItem.displayOrder = toDisplayOrder - 1
            }
        } else {
            // fromId が下にある（displayOrder が大きい）→ 上に移動
            // toId から fromId の前までを1つ下にシフト
            for (item in allItems) {
                val order = when (item) {
                    is Layer -> item.displayOrder
                    is LayerGroupInfo -> item.displayOrder
                    else -> continue
                }
                if (order >= toDisplayOrder && order < fromDisplayOrder) {
                    when (item) {
                        is Layer -> item.displayOrder += 1
                        is LayerGroupInfo -> item.displayOrder += 1
                    }
                }
            }
            // fromId を toId と同じ位置に移動
            when (fromItem) {
                is Layer -> fromItem.displayOrder = toDisplayOrder + 1
                is LayerGroupInfo -> fromItem.displayOrder = toDisplayOrder + 1
            }
        }

        // displayOrder 変更に加えて、レイヤーリスト内の実際の順序も更新
        // (rebuildCompositeTile が正しい順序で合成されるようにするため)
        if (fromLayer != null && toLayer != null) {
            val fromIdx = doc.layers.indexOfFirst { it.id == actualFromId }
            val toIdx = doc.layers.indexOfFirst { it.id == actualToId }
            if (fromIdx >= 0 && toIdx >= 0) {
                doc.moveLayer(fromIdx, toIdx)
            }
        }

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
    fun batchMoveLayersUp(ids: List<Int>) {
        val doc = _document ?: return
        // 上に移動するときは高インデックス順に処理してインデックスずれを防ぐ
        val sorted = ids.mapNotNull { id ->
            val idx = doc.layers.indexOfFirst { it.id == id }
            if (idx >= 0) Pair(id, idx) else null
        }.sortedByDescending { it.second }
        for ((_, idx) in sorted) {
            if (idx < doc.layers.size - 1) doc.moveLayer(idx, idx + 1)
        }
        updateLayerState()
    }
    fun batchMoveLayersDown(ids: List<Int>) {
        val doc = _document ?: return
        // 下に移動するときは低インデックス順に処理してインデックスずれを防ぐ
        val sorted = ids.mapNotNull { id ->
            val idx = doc.layers.indexOfFirst { it.id == id }
            if (idx >= 0) Pair(id, idx) else null
        }.sortedBy { it.second }
        for ((_, idx) in sorted) {
            if (idx > 0) doc.moveLayer(idx, idx - 1)
        }
        updateLayerState()
    }

    // ── ピクセル移動機能対応 ──
    /** 複数レイヤーの一時的なオフセットを設定（ピクセル移動中に使用） */
    fun setLayersOffset(ids: List<Int>, offsetX: Float, offsetY: Float) {
        val doc = _document ?: return
        for (id in ids) {
            val layer = doc.layers.find { it.id == id } ?: continue
            layer.offsetX = offsetX
            layer.offsetY = offsetY
        }
        // ピクセル移動プレビューを描画させるため全タイル再合成
        doc.dirtyTracker.markFullRebuild()
        updateLayerState()
    }

    /** 複数レイヤーのオフセットをリセット */
    fun resetLayersOffset(ids: List<Int>) {
        val doc = _document ?: return
        for (id in ids) {
            val layer = doc.layers.find { it.id == id } ?: continue
            layer.offsetX = 0f
            layer.offsetY = 0f
        }
        // ピクセル移動プレビューをリセットして描画
        doc.dirtyTracker.markFullRebuild()
        updateLayerState()
    }

    /** フォルダ内のすべての子レイヤーIDを取得（フォルダ選択時の一括処理用） */
    fun getLayersInGroup(groupId: Int): List<Int> {
        val doc = _document ?: return emptyList()
        return doc.layers.filter { it.groupId == groupId }.map { it.id }
    }

    /** フォルダ内のすべての子レイヤーIDを取得（現在はgetLayersInGroupと同一） */
    fun getLayersInGroupRecursive(groupId: Int): List<Int> {
        // 将来的にネストされたフォルダに対応する際は、ここを拡張
        return getLayersInGroup(groupId)
    }

    /** 複数レイヤーのピクセル移動を確定（オフセットを実際のピクセルに適用） */
    fun commitPixelMovement(ids: List<Int>) {
        val doc = _document ?: return

        for (id in ids) {
            val layer = doc.layers.find { it.id == id } ?: continue
            val offsetX = layer.offsetX.toInt()
            val offsetY = layer.offsetY.toInt()

            // オフセットをピクセルデータに適用
            if (offsetX != 0 || offsetY != 0) {
                translateLayerContent(id, offsetX, offsetY)
            }

            // オフセットをリセット
            layer.offsetX = 0f
            layer.offsetY = 0f
        }

        updateLayerState()
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
        // groupId が負数（Flutter 側でのグループID表現）の場合は正数に変換
        val actualGroupId = if (groupId < 0) -groupId else groupId
        _document?.setLayerGroup(layerId, actualGroupId); updateLayerState()
    }
    fun setGroupVisibility(groupId: Int, visible: Boolean) {
        _document?.setGroupVisibility(groupId, visible); updateLayerState()
    }
    fun setGroupOpacity(groupId: Int, opacity: Float) {
        _document?.setGroupOpacity(groupId, opacity); updateLayerState()
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
        // 重い処理を Default ディスパッチャで非同期実行（メインスレッド非ブロック）
        viewModelScope.launch(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            val uiLayers = mutableListOf<UiLayer>()

            // ── レイヤーとグループを displayOrder で統一的にソート ──
            data class DisplayItem(val displayOrder: Int, val uiLayer: UiLayer)
            val displayItems = mutableListOf<DisplayItem>()

            // グループを変換
            for ((gId, group) in doc.layerGroups) {
                displayItems.add(DisplayItem(
                    displayOrder = group.displayOrder,
                    uiLayer = UiLayer(
                        id = -gId,  // グループは負の ID で区別
                        name = group.name,
                        opacity = 1f,
                        blendMode = 0,
                        isVisible = group.isVisible,
                        isLocked = false,
                        isClipToBelow = false,
                        isActive = false,
                        isGroup = true  // グループフラグをセット
                    )
                ))
            }

            // レイヤーを変換
            for (layer in doc.layers) {
                displayItems.add(DisplayItem(
                    displayOrder = layer.displayOrder,
                    uiLayer = UiLayer(
                        id = layer.id,
                        name = layer.name,
                        opacity = layer.opacity,
                        blendMode = layer.blendMode,
                        isVisible = layer.isVisible,
                        isLocked = layer.isLocked,
                        isClipToBelow = layer.isClipToBelow,
                        isActive = layer.id == doc.activeLayerId,
                        isAlphaLocked = layer.isAlphaLocked,
                        hasMask = layer.mask != null,
                        isMaskEnabled = layer.isMaskEnabled,
                        isEditingMask = layer.isEditingMask,
                        groupId = layer.groupId,
                        isTextLayer = layer.textConfig != null,
                        isGroup = false
                    )
                ))
            }

            // displayOrder でソートして、UiLayer を抽出
            uiLayers.addAll(displayItems.sortedBy { it.displayOrder }.map { it.uiLayer })

            // 処理時間をログに出力
            val elapsedTime = System.currentTimeMillis() - startTime
            if (elapsedTime > 16) {  // フレーム時間（60fps = 16ms）超過時に警告
                PaintDebug.d(PaintDebug.Perf) {
                    "[updateLayerState] layers=${doc.layers.size} groups=${doc.layerGroups.size} time=${elapsedTime}ms"
                }
            }

            // UI スレッドで結果を反映
            withContext(Dispatchers.Main) {
                _layers.value = uiLayers
            }
        }
    }

    private fun updateUndoState() {
        val doc = _document ?: return
        // Undo状態の更新もメインスレッド非ブロック
        viewModelScope.launch(Dispatchers.Main) {
            _canUndo.value = doc.canUndo
            _canRedo.value = doc.canRedo
        }
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

    fun selectMagnet(x: Int, y: Int, tolerance: Int = 32, maxDistance: Int = 256) {
        val doc = _document ?: return
        val layer = doc.getActiveLayer() ?: return
        doc.selectionManager.selectMagnet(layer.content, x, y, tolerance, maxDistance, _selectionMode.value)
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

    // ── 選択範囲操作 ──────────────────────────────────────────────

    fun deleteSelection() {
        val doc = _document ?: return
        doc.deleteSelection(doc.activeLayerId)
        updateUndoState()
    }

    fun fillSelection(color: Int) {
        val doc = _document ?: return
        doc.fillSelection(doc.activeLayerId, color)
        updateUndoState()
    }

    fun copySelection() {
        val doc = _document ?: return
        doc.copySelection(doc.activeLayerId)
        updateUndoState()
        updateLayerState()
    }

    fun cutSelection() {
        val doc = _document ?: return
        doc.cutSelection(doc.activeLayerId)
        updateUndoState()
        updateLayerState()
    }

    fun moveSelection(dx: Int, dy: Int) {
        val doc = _document ?: return
        doc.moveSelection(doc.activeLayerId, dx, dy)
        _hasSelection.value = doc.selectionManager.hasSelection
        pushSelMaskToRenderer()
        updateUndoState()
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

    fun distortActiveLayer(corners: FloatArray) {
        val doc = _document ?: return
        doc.distortLayer(doc.activeLayerId, corners)
        updateUndoState()
    }

    fun meshWarpActiveLayer(gridW: Int, gridH: Int, nodes: FloatArray) {
        val doc = _document ?: return
        doc.meshWarpLayer(doc.activeLayerId, gridW, gridH, nodes)
        updateUndoState()
    }

    fun beginLiquify() { _document?.beginLiquify(_document?.activeLayerId ?: return) }

    fun liquifyActiveLayer(cx: Float, cy: Float, radius: Float,
                           dirX: Float, dirY: Float, pressure: Float, mode: Int) {
        val doc = _document ?: return
        doc.liquifyLayer(doc.activeLayerId, cx, cy, radius, dirX, dirY, pressure, mode)
    }

    fun endLiquify() { updateUndoState() }

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

    fun setToolMode(mode: ToolMode) {
        _toolMode.value = mode
        PaintDebug.d(PaintDebug.Input) { "[Tool] mode changed to ${mode.name}" }
    }

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

    // ─── ピクセルコピー変形（Word/Excel風） ────────────────────

    fun startPixelCopy(): Map<String, Int> {
        val doc = _document ?: return emptyMap()
        val bounds = doc.startPixelCopy(doc.activeLayerId)
        return mapOf(
            "left" to bounds.left,
            "top" to bounds.top,
            "right" to bounds.right,
            "bottom" to bounds.bottom,
        )
    }

    fun applyPixelCopy(x: Int, y: Int, scaleX: Float, scaleY: Float, rotation: Float) {
        val doc = _document ?: return
        doc.applyPixelCopy(doc.activeLayerId, x, y, scaleX, scaleY, rotation)
        hasUnsavedChanges = true; updateUndoState()
    }

    fun cancelPixelCopy() {
        val doc = _document ?: return
        doc.cancelPixelCopy()
    }
}
