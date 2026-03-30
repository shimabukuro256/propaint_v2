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

  // ─── ツールモード ─────────────────────────────────────

  Future<void> activateEyedropper() =>
      _method.invokeMethod('activateEyedropper');

  Future<void> deactivateEyedropper() =>
      _method.invokeMethod('deactivateEyedropper');

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
}
