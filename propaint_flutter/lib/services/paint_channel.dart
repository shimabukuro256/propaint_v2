import 'package:flutter/services.dart';

/// Kotlin PaintViewModel とのブリッジ。
/// MethodChannel: Flutter → Kotlin (コマンド送信)
/// EventChannel:  Kotlin → Flutter (状態ストリーム)
class PaintChannel {
  static const _method = MethodChannel('com.propaint.app/paint');
  static const _state = EventChannel('com.propaint.app/state');

  /// ネイティブ側からのジェスチャ通知コールバック (undo/redo 等)
  void Function(String gestureType)? onNativeGesture;

  PaintChannel() {
    _method.setMethodCallHandler((call) async {
      if (call.method == 'onNativeGesture') {
        final type = (call.arguments as Map)['type'] as String;
        onNativeGesture?.call(type);
      }
    });
  }

  // ─── Brush 設定 ───────────────────────────────────────

  Future<void> setBrushType(String type) =>
      _method.invokeMethod('setBrushType', {'type': type});

  Future<void> setBrushSize(double value) {
    assert(value > 0, 'Brush size must be > 0');
    return _method.invokeMethod('setBrushSize', {'value': value});
  }

  Future<void> setBrushOpacity(double value) {
    assert(value >= 0.0 && value <= 1.0, 'opacity must be in 0..1');
    return _method.invokeMethod('setBrushOpacity', {'value': value});
  }

  Future<void> setBrushHardness(double value) {
    assert(value >= 0.0 && value <= 1.0, 'hardness must be in 0..1');
    return _method.invokeMethod('setBrushHardness', {'value': value});
  }

  Future<void> setBrushAntiAliasing(double value) {
    assert(value >= 0.0 && value <= 1.0, 'antiAliasing must be in 0..1');
    return _method.invokeMethod('setBrushAntiAliasing', {'value': value});
  }

  Future<void> setBrushDensity(double value) {
    assert(value >= 0.0 && value <= 1.0, 'density must be in 0..1');
    return _method.invokeMethod('setBrushDensity', {'value': value});
  }

  Future<void> setBrushSpacing(double value) {
    assert(value > 0, 'spacing must be > 0');
    return _method.invokeMethod('setBrushSpacing', {'value': value});
  }

  Future<void> setStabilizer(double value) {
    assert(value >= 0.0 && value <= 1.0, 'stabilizer must be in 0..1');
    return _method.invokeMethod('setStabilizer', {'value': value});
  }

  Future<void> setColorStretch(double value) =>
      _method.invokeMethod('setColorStretch', {'value': value});

  Future<void> setWaterContent(double value) =>
      _method.invokeMethod('setWaterContent', {'value': value});

  Future<void> setBlurStrength(double value) =>
      _method.invokeMethod('setBlurStrength', {'value': value});

  Future<void> setBlurPressureThreshold(double value) =>
      _method.invokeMethod('setBlurPressureThreshold', {'value': value});

  // ─── 筆圧トグル ──────────────────────────────────────

  Future<void> togglePressureSize() =>
      _method.invokeMethod('togglePressureSize');

  Future<void> togglePressureOpacity() =>
      _method.invokeMethod('togglePressureOpacity');

  Future<void> togglePressureDensity() =>
      _method.invokeMethod('togglePressureDensity');

  Future<void> togglePressureSelection() =>
      _method.invokeMethod('togglePressureSelection');

  // ─── カラー ───────────────────────────────────────────

  /// ARGB int (例: 0xFF000000 = 黒)
  Future<void> setColor(int argb) =>
      _method.invokeMethod('setColor', {'argb': argb});

  Future<void> commitColor() => _method.invokeMethod('commitColor');

  // ─── Undo / Redo ──────────────────────────────────────

  Future<void> undo() => _method.invokeMethod('undo');
  Future<void> redo() => _method.invokeMethod('redo');

  // ─── レイヤー操作 ─────────────────────────────────────

  Future<void> addLayer() => _method.invokeMethod('addLayer');

  Future<void> removeLayer(int id) =>
      _method.invokeMethod('removeLayer', {'id': id});

  Future<void> selectLayer(int id) =>
      _method.invokeMethod('selectLayer', {'id': id});

  Future<void> setLayerVisibility(int id, bool visible) =>
      _method.invokeMethod('setLayerVisibility', {'id': id, 'visible': visible});

  Future<void> setLayerOpacity(int id, double opacity) =>
      _method.invokeMethod('setLayerOpacity', {'id': id, 'opacity': opacity});

  Future<void> setLayerBlendMode(int id, int mode) =>
      _method.invokeMethod('setLayerBlendMode', {'id': id, 'mode': mode});

  Future<void> setLayerClip(int id, bool clip) =>
      _method.invokeMethod('setLayerClip', {'id': id, 'clip': clip});

  Future<void> setLayerLocked(int id, bool locked) =>
      _method.invokeMethod('setLayerLocked', {'id': id, 'locked': locked});

  Future<void> setAlphaLocked(int id, bool locked) =>
      _method.invokeMethod('setAlphaLocked', {'id': id, 'locked': locked});

  Future<void> reorderLayer(int fromIndex, int toIndex) =>
      _method.invokeMethod('reorderLayer', {'fromIndex': fromIndex, 'toIndex': toIndex});

  Future<void> duplicateLayer(int id) =>
      _method.invokeMethod('duplicateLayer', {'id': id});

  Future<void> mergeDown(int id) =>
      _method.invokeMethod('mergeDown', {'id': id});

  Future<void> batchMergeLayers(List<int> ids) =>
      _method.invokeMethod('batchMergeLayers', {'ids': ids});

  // ─── レイヤーグループ（フォルダ）──────────────────

  Future<void> createLayerGroup(String name) =>
      _method.invokeMethod('createLayerGroup', {'name': name});

  Future<void> deleteLayerGroup(int groupId) =>
      _method.invokeMethod('deleteLayerGroup', {'groupId': groupId});

  Future<void> setLayerGroup(int layerId, int groupId) =>
      _method.invokeMethod('setLayerGroup', {'layerId': layerId, 'groupId': groupId});

  Future<void> setGroupVisibility(int groupId, bool visible) =>
      _method.invokeMethod('setGroupVisibility', {'groupId': groupId, 'visible': visible});

  Future<void> setGroupOpacity(int groupId, double opacity) =>
      _method.invokeMethod('setGroupOpacity', {'groupId': groupId, 'opacity': opacity});

  // ─── 保存/エクスポート ────────────────────────────────

  Future<void> saveProject() => _method.invokeMethod('saveProject');

  /// 保存してギャラリーに戻る
  Future<void> returnToGallery() => _method.invokeMethod('returnToGallery');

  /// ネイティブ側に Activity Result Launcher を起動してもらう
  Future<void> requestExport(String format) =>
      _method.invokeMethod('requestExport', {'format': format});

  Future<void> requestImport(String type) =>
      _method.invokeMethod('requestImport', {'type': type});

  Future<void> moveLayerUp(int id) =>
      _method.invokeMethod('moveLayerUp', {'id': id});

  Future<void> moveLayerDown(int id) =>
      _method.invokeMethod('moveLayerDown', {'id': id});

  // ─── ブラシ設定のエクスポート/インポート/リセット ──────

  /// 全ブラシ設定を JSON 文字列としてエクスポート
  Future<String> exportBrushSettings() async {
    final result = await _method.invokeMethod<String>('exportBrushSettings');
    return result ?? '{}';
  }

  /// JSON 文字列からブラシ設定をインポート
  Future<bool> importBrushSettings(String json) async {
    final result = await _method.invokeMethod<bool>('importBrushSettings', {'json': json});
    return result ?? false;
  }

  /// 全ブラシ設定をデフォルトに戻す
  Future<void> resetBrushToDefaults() =>
      _method.invokeMethod('resetBrushToDefaults');

  // ─── ツールモード ─────────────────────────────────────

  Future<void> activateEyedropper() =>
      _method.invokeMethod('activateEyedropper');

  Future<void> deactivateEyedropper() =>
      _method.invokeMethod('deactivateEyedropper');

  Future<void> setToolMode(String mode) =>
      _method.invokeMethod('setToolMode', {'mode': mode});

  // ─── 選択ツール ───────────────────────────────────────

  Future<void> selectRect(int left, int top, int right, int bottom) =>
      _method.invokeMethod('selectRect', {'left': left, 'top': top, 'right': right, 'bottom': bottom});

  Future<void> selectEllipse(int left, int top, int right, int bottom) =>
      _method.invokeMethod('selectEllipse', {'left': left, 'top': top, 'right': right, 'bottom': bottom});

  Future<void> selectByColor(int x, int y, {int tolerance = 32, bool contiguous = true}) =>
      _method.invokeMethod('selectByColor', {'x': x, 'y': y, 'tolerance': tolerance, 'contiguous': contiguous});

  Future<void> selectAll() => _method.invokeMethod('selectAll');
  Future<void> clearSelection() => _method.invokeMethod('clearSelection');
  Future<void> invertSelection() => _method.invokeMethod('invertSelection');

  /// 選択ペン: 円形ブラシで選択範囲を追加
  /// @param cx, cy: 中心座標
  /// @param radius: ブラシ半径（ピクセル）
  /// @param pressure: 筆圧 (0.0～1.0)
  Future<void> paintSelectionAdd(int cx, int cy, int radius, {double pressure = 1.0}) =>
      _method.invokeMethod('paintSelectionAdd', {
        'cx': cx, 'cy': cy, 'radius': radius, 'pressure': pressure,
      });

  /// 選択消し: 円形ブラシで選択範囲を削除
  /// @param cx, cy: 中心座標
  /// @param radius: ブラシ半径（ピクセル）
  /// @param pressure: 筆圧 (0.0～1.0)
  Future<void> paintSelectionErase(int cx, int cy, int radius, {double pressure = 1.0}) =>
      _method.invokeMethod('paintSelectionErase', {
        'cx': cx, 'cy': cy, 'radius': radius, 'pressure': pressure,
      });

  // ─── 変形ツール ───────────────────────────────────────

  Future<void> flipLayerH() => _method.invokeMethod('flipLayerH');
  Future<void> flipLayerV() => _method.invokeMethod('flipLayerV');
  Future<void> rotateLayer90CW() => _method.invokeMethod('rotateLayer90CW');

  Future<void> transformLayer({
    double scaleX = 1, double scaleY = 1, double angle = 0,
    double translateX = 0, double translateY = 0,
  }) => _method.invokeMethod('transformLayer', {
    'scaleX': scaleX, 'scaleY': scaleY, 'angle': angle,
    'translateX': translateX, 'translateY': translateY,
  });

  // ─── レイヤーマスク ───────────────────────────────────

  Future<void> addLayerMask({bool fillWhite = true}) =>
      _method.invokeMethod('addLayerMask', {'fillWhite': fillWhite});

  Future<void> removeLayerMask() => _method.invokeMethod('removeLayerMask');
  Future<void> toggleMaskEnabled() => _method.invokeMethod('toggleMaskEnabled');
  Future<void> toggleEditMask() => _method.invokeMethod('toggleEditMask');
  Future<void> addMaskFromSelection() => _method.invokeMethod('addMaskFromSelection');

  // ─── 図形・塗りつぶし ─────────────────────────────────

  Future<void> drawShape(String type, int left, int top, int right, int bottom,
      {bool fill = true, double thickness = 1}) =>
      _method.invokeMethod('drawShape', {
        'type': type, 'left': left, 'top': top, 'right': right, 'bottom': bottom,
        'fill': fill, 'thickness': thickness,
      });

  Future<void> floodFill(int x, int y, {int tolerance = 0}) =>
      _method.invokeMethod('floodFill', {'x': x, 'y': y, 'tolerance': tolerance});

  // ─── テキストレイヤー ─────────────────────────────────

  Future<void> addTextLayer(String text, {
    double fontSize = 48, double x = 0, double y = 0,
    bool bold = false, bool italic = false, bool vertical = false,
  }) => _method.invokeMethod('addTextLayer', {
    'text': text, 'fontSize': fontSize, 'x': x, 'y': y,
    'bold': bold, 'italic': italic, 'vertical': vertical,
  });

  // ─── 追加フィルター ───────────────────────────────────

  Future<void> applyUnsharpMask({int radius = 1, double amount = 1, int threshold = 0}) =>
      _method.invokeMethod('applyUnsharpMask', {'radius': radius, 'amount': amount, 'threshold': threshold});

  Future<void> applyMosaic(int blockSize) =>
      _method.invokeMethod('applyMosaic', {'blockSize': blockSize});

  Future<void> applyNoise(int amount, {bool monochrome = true}) =>
      _method.invokeMethod('applyNoise', {'amount': amount, 'monochrome': monochrome});

  Future<void> applyPosterize(int levels) =>
      _method.invokeMethod('applyPosterize', {'levels': levels});

  Future<void> applyThreshold(int threshold) =>
      _method.invokeMethod('applyThreshold', {'threshold': threshold});

  Future<void> applyLevels({int inBlack = 0, int inWhite = 255, double gamma = 1, int outBlack = 0, int outWhite = 255}) =>
      _method.invokeMethod('applyLevels', {
        'inBlack': inBlack, 'inWhite': inWhite, 'gamma': gamma, 'outBlack': outBlack, 'outWhite': outWhite,
      });

  Future<void> applyColorBalance({int cyanRed = 0, int magentaGreen = 0, int yellowBlue = 0}) =>
      _method.invokeMethod('applyColorBalance', {
        'cyanRed': cyanRed, 'magentaGreen': magentaGreen, 'yellowBlue': yellowBlue,
      });

  // ─── ビュー操作 ───────────────────────────────────────

  Future<void> resetView() => _method.invokeMethod('resetView');

  // ─── 状態取得 ─────────────────────────────────────────

  /// 現在の全状態を一括取得
  Future<Map<String, dynamic>> getState() async {
    final result = await _method.invokeMethod<Map>('getState');
    return Map<String, dynamic>.from(result ?? {});
  }

  /// Kotlin 側から push される状態変更ストリーム
  Stream<Map<String, dynamic>> get stateStream => _state
      .receiveBroadcastStream()
      .map((e) => Map<String, dynamic>.from(e as Map));

  // ─── デバイスメモリ情報ヘルパー ──────────────────────────

  /// デバイスメモリ情報を取得（state map から抽出）
  static MemoryInfo parseMemoryInfo(Map<String, dynamic> state) {
    return MemoryInfo(
      deviceRamMb: state['deviceRamMb'] as int? ?? 0,
      memoryTier: state['memoryTier'] as String? ?? 'unknown',
      maxCanvasSize: state['maxCanvasSize'] as int? ?? 4096,
      maxLayers: state['maxLayers'] as int? ?? 16,
      maxBrushSize: state['maxBrushSize'] as int? ?? 512,
      maxBlurRadius: state['maxBlurRadius'] as int? ?? 512,
      maxUndoEntries: state['maxUndoEntries'] as int? ?? 50,
    );
  }
}

/// デバイスの RAM に基づくメモリ制限情報
class MemoryInfo {
  final int deviceRamMb;
  final String memoryTier;
  final int maxCanvasSize;
  final int maxLayers;
  final int maxBrushSize;
  final int maxBlurRadius;
  final int maxUndoEntries;

  const MemoryInfo({
    required this.deviceRamMb,
    required this.memoryTier,
    required this.maxCanvasSize,
    required this.maxLayers,
    required this.maxBrushSize,
    required this.maxBlurRadius,
    required this.maxUndoEntries,
  });
}
