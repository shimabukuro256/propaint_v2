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

  Future<void> setBrushMinSizePercent(int percent) {
    assert(percent >= 1 && percent <= 100, 'minSizePercent must be in 1..100');
    return _method.invokeMethod('setBrushMinSizePercent', {'percent': percent});
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

  Future<void> reorderLayerGroup(int fromGroupId, int toGroupId) =>
      _method.invokeMethod('reorderLayerGroup', {'fromGroupId': fromGroupId, 'toGroupId': toGroupId});

  /// フォルダとレイヤーが混在している場合の統一的な並び替え
  /// fromId: ドラッグ対象のレイヤーID（正数）またはグループID（正数）
  /// toId: ドロップ対象のレイヤーID（正数）またはグループID（正数）
  Future<void> reorderDisplayItem(int fromId, int toId) =>
      _method.invokeMethod('reorderDisplayItem', {'fromId': fromId, 'toId': toId});

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

  Future<void> batchMoveLayersUp(List<int> ids) =>
      _method.invokeMethod('batchMoveLayersUp', {'ids': ids});

  Future<void> batchMoveLayersDown(List<int> ids) =>
      _method.invokeMethod('batchMoveLayersDown', {'ids': ids});

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

  /// 選択モード設定 (Replace, Add, Subtract, Intersect)
  Future<void> setSelectionMode(String mode) =>
      _method.invokeMethod('setSelectionMode', {'mode': mode});

  // ─── 選択ツール ───────────────────────────────────────

  Future<void> selectRect(int left, int top, int right, int bottom) =>
      _method.invokeMethod('selectRect', {'left': left, 'top': top, 'right': right, 'bottom': bottom});

  Future<void> selectEllipse(int left, int top, int right, int bottom) =>
      _method.invokeMethod('selectEllipse', {'left': left, 'top': top, 'right': right, 'bottom': bottom});

  Future<void> selectByColor(int x, int y, {int tolerance = 32, bool contiguous = true}) =>
      _method.invokeMethod('selectByColor', {'x': x, 'y': y, 'tolerance': tolerance, 'contiguous': contiguous});

  /// マグネット選択: エッジ吸着で自動トレース
  /// @param x, y: スタート座標
  /// @param tolerance: エッジ検出の許容値
  /// @param maxDistance: トレース距離限界
  Future<void> selectMagnet(int x, int y, {int tolerance = 32, int maxDistance = 256}) =>
      _method.invokeMethod('selectMagnet', {'x': x, 'y': y, 'tolerance': tolerance, 'maxDistance': maxDistance});

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

  // ─── 選択範囲操作 ─────────────────────────────────────

  Future<void> deleteSelection() => _method.invokeMethod('deleteSelection');
  Future<void> fillSelection(int color) => _method.invokeMethod('fillSelection', {'color': color});
  Future<void> copySelection() => _method.invokeMethod('copySelection');
  Future<void> cutSelection() => _method.invokeMethod('cutSelection');
  Future<void> moveSelection(int dx, int dy) => _method.invokeMethod('moveSelection', {'dx': dx, 'dy': dy});

  /// 選択範囲をぼかす
  /// @param radius: ぼかし半径（ピクセル）
  Future<void> featherSelection(int radius) => _method.invokeMethod('featherSelection', {'radius': radius});

  /// 選択範囲を拡張/縮小
  /// @param amount: 正=拡張, 負=縮小（ピクセル）
  Future<void> expandSelection(int amount) => _method.invokeMethod('expandSelection', {'amount': amount});

  /// マグネット選択をキャンセル
  Future<void> cancelMagnetSelection() => _method.invokeMethod('cancelMagnetSelection');

  /// マグネット選択を確定（確定ボタン押下時）
  Future<void> finalizeMagnetSelection() => _method.invokeMethod('finalizeMagnetSelection');

  // ─── ピクセルコピー変形（Word/Excel風）───────────────────

  /// 選択範囲のピクセルをコピーし、バウンディングボックスを返す
  /// @return {left, top, right, bottom} の境界情報
  Future<Map<String, dynamic>> startPixelCopy() async {
    final result = await _method.invokeMethod<Map>('startPixelCopy');
    return Map<String, dynamic>.from(result ?? {});
  }

  /// ピクセルコピー変形を確定
  /// @param x, y: 左上位置
  /// @param scaleX, scaleY: スケール倍率
  /// @param rotation: 回転角度（度数法）
  Future<void> applyPixelCopy({
    required int x,
    required int y,
    double scaleX = 1.0,
    double scaleY = 1.0,
    double rotation = 0.0,
  }) => _method.invokeMethod('applyPixelCopy', {
    'x': x, 'y': y, 'scaleX': scaleX, 'scaleY': scaleY, 'rotation': rotation,
  });

  /// ピクセルコピー変形をキャンセル
  Future<void> cancelPixelCopy() => _method.invokeMethod('cancelPixelCopy');

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

  /// ディストート（パースペクティブ変形）
  /// corners: [tlX, tlY, trX, trY, brX, brY, blX, blY]
  Future<void> distortLayer(List<double> corners) =>
      _method.invokeMethod('distortLayer', {'corners': corners});

  /// メッシュワープ変形
  Future<void> meshWarpLayer(int gridW, int gridH, List<double> nodes) =>
      _method.invokeMethod('meshWarpLayer', {'gridW': gridW, 'gridH': gridH, 'nodes': nodes});

  // ─── 複数レイヤー一括変形 ───────────────────────────────

  /// 複数レイヤーに自由変形を一括適用（プレビュー確定時）
  Future<void> applyPreviewTransform({
    required List<int> layerIds,
    double scaleX = 1, double scaleY = 1,
    double angleDeg = 0,
    double translateX = 0, double translateY = 0,
  }) => _method.invokeMethod('applyPreviewTransform', {
    'layerIds': layerIds,
    'scaleX': scaleX, 'scaleY': scaleY, 'angleDeg': angleDeg,
    'translateX': translateX, 'translateY': translateY,
  });

  /// 複数レイヤーに反転・回転を一括適用
  Future<void> applyMultiLayerSimpleTransform({
    required List<int> layerIds,
    required String operation,
  }) => _method.invokeMethod('applyMultiLayerSimpleTransform', {
    'layerIds': layerIds, 'operation': operation,
  });

  /// リキファイ開始 (Undo スナップショット)
  Future<void> beginLiquify() => _method.invokeMethod('beginLiquify');

  /// リキファイ適用 (mode: 0=Push, 1=TwirlCW, 2=TwirlCCW, 3=Pinch, 4=Expand)
  Future<void> liquifyLayer(double cx, double cy, double radius,
      double dirX, double dirY, {double pressure = 1.0, int mode = 0}) =>
      _method.invokeMethod('liquifyLayer', {
        'cx': cx, 'cy': cy, 'radius': radius,
        'dirX': dirX, 'dirY': dirY, 'pressure': pressure, 'mode': mode,
      });

  /// リキファイ終了
  Future<void> endLiquify() => _method.invokeMethod('endLiquify');

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

  // ─── ピクセル移動機能（将来実装向け対応） ───────────────────

  /// 複数レイヤーの一時的なオフセットを設定（ピクセル移動中）
  Future<void> setLayersOffset(List<int> layerIds, double offsetX, double offsetY) =>
      _method.invokeMethod('setLayersOffset', {
        'layerIds': layerIds,
        'offsetX': offsetX,
        'offsetY': offsetY,
      });

  /// 複数レイヤーのオフセットをリセット
  Future<void> resetLayersOffset(List<int> layerIds) =>
      _method.invokeMethod('resetLayersOffset', {'layerIds': layerIds});

  /// 複数レイヤーのピクセル移動を確定（オフセットを実際のピクセルに適用）
  Future<void> commitPixelMovement(List<int> layerIds) =>
      _method.invokeMethod('commitPixelMovement', {'layerIds': layerIds});

  /// フォルダ内のすべての子レイヤーIDを取得
  Future<List<int>> getLayersInGroup(int groupId) async {
    final result = await _method.invokeMethod<List>('getLayersInGroup', {'groupId': groupId});
    return (result ?? []).cast<int>();
  }

  /// フォルダ内のすべての子レイヤーIDを再帰的に取得
  Future<List<int>> getLayersInGroupRecursive(int groupId) async {
    final result =
        await _method.invokeMethod<List>('getLayersInGroupRecursive', {'groupId': groupId});
    return (result ?? []).cast<int>();
  }

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
