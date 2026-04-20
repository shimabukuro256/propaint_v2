import 'dart:async';

import 'paint_api.g.dart';
import 'paint_flutter_api_impl.dart';

export 'paint_api.g.dart' show SelectionMode, ToolMode;

/// Kotlin PaintViewModel とのブリッジ。
/// Flutter → Kotlin コマンド、Kotlin → Flutter 状態/通知のすべてを Pigeon で扱う。
class PaintChannel {
  /// Flutter → Kotlin コマンド送信用の HostApi。Final セッションで MethodChannel を完全廃止。
  final PaintHostApi _pigeon = PaintHostApi();

  /// Kotlin → Flutter の状態ストリーム（broadcast）。`PaintFlutterApi.onStateChanged`
  /// の PaintState を `Map<String, dynamic>` に変換して流す（既存購読側の互換維持）。
  final StreamController<Map<String, dynamic>> _stateController =
      StreamController<Map<String, dynamic>>.broadcast();

  /// ネイティブ側からのジェスチャ通知コールバック (undo/redo 等)
  void Function(String gestureType)? onNativeGesture;

  /// ネイティブ側からのエラーメッセージ通知コールバック
  void Function(String message)? onErrorMessage;

  PaintChannel() {
    // Kotlin → Flutter の状態・通知配信を Pigeon FlutterApi で受け取る。
    PaintFlutterApi.setUp(PaintFlutterApiImpl(
      stateChangedHandler: (state) {
        if (!_stateController.isClosed) {
          _stateController.add(_paintStateToMap(state));
        }
      },
      nativeGestureHandler: (g) => onNativeGesture?.call(g),
      errorMessageHandler: (m) => onErrorMessage?.call(m),
    ));
  }

  // ─── Brush 設定 (Pigeon 経由 — Session 2) ────────────

  Future<void> setBrushType(String type) => _pigeon.setBrushType(type);

  Future<void> setBrushSize(double value) {
    assert(value > 0, 'Brush size must be > 0');
    return _pigeon.setBrushSize(value);
  }

  Future<void> setBrushOpacity(double value) {
    assert(value >= 0.0 && value <= 1.0, 'opacity must be in 0..1');
    return _pigeon.setBrushOpacity(value);
  }

  Future<void> setBrushHardness(double value) {
    assert(value >= 0.0 && value <= 1.0, 'hardness must be in 0..1');
    return _pigeon.setBrushHardness(value);
  }

  Future<void> setBrushAntiAliasing(double value) {
    assert(value >= 0.0 && value <= 1.0, 'antiAliasing must be in 0..1');
    return _pigeon.setBrushAntiAliasing(value);
  }

  Future<void> setBrushDensity(double value) {
    assert(value >= 0.0 && value <= 1.0, 'density must be in 0..1');
    return _pigeon.setBrushDensity(value);
  }

  Future<void> setBrushSpacing(double value) {
    assert(value > 0, 'spacing must be > 0');
    return _pigeon.setBrushSpacing(value);
  }

  Future<void> setBrushMinSizePercent(int percent) {
    assert(percent >= 1 && percent <= 100, 'minSizePercent must be in 1..100');
    return _pigeon.setBrushMinSizePercent(percent);
  }

  Future<void> setStabilizer(double value) {
    assert(value >= 0.0 && value <= 1.0, 'stabilizer must be in 0..1');
    return _pigeon.setStabilizer(value);
  }

  Future<void> setColorStretch(double value) => _pigeon.setColorStretch(value);

  Future<void> setWaterContent(double value) => _pigeon.setWaterContent(value);

  Future<void> setBlurStrength(double value) => _pigeon.setBlurStrength(value);

  Future<void> setBlurPressureThreshold(double value) =>
      _pigeon.setBlurPressureThreshold(value);

  // ─── 筆圧トグル (Pigeon) ─────────────────────────────

  Future<void> togglePressureSize() => _pigeon.togglePressureSize();
  Future<void> togglePressureOpacity() => _pigeon.togglePressureOpacity();
  Future<void> togglePressureDensity() => _pigeon.togglePressureDensity();
  Future<void> togglePressureSelection() => _pigeon.togglePressureSelection();

  // ─── カラー (Pigeon 経由 — Session 3) ─────────────────

  /// ARGB int (例: 0xFF000000 = 黒)
  Future<void> setColor(int argb) => _pigeon.setColor(argb);

  Future<void> commitColor() => _pigeon.commitColor();

  // ─── Undo / Redo (Pigeon 経由 — Session 3) ────────────

  Future<void> undo() => _pigeon.undo();
  Future<void> redo() => _pigeon.redo();

  // ─── レイヤー操作 (Pigeon 経由 — Session 3) ────────────

  Future<void> addLayer() => _pigeon.addLayer();

  Future<void> removeLayer(int id) => _pigeon.removeLayer(id);

  Future<void> selectLayer(int id) => _pigeon.selectLayer(id);

  Future<void> setLayerVisibility(int id, bool visible) =>
      _pigeon.setLayerVisibility(id, visible);

  Future<void> setLayerOpacity(int id, double opacity) {
    assert(opacity >= 0.0 && opacity <= 1.0, 'opacity must be in 0..1');
    return _pigeon.setLayerOpacity(id, opacity);
  }

  Future<void> setLayerBlendMode(int id, int mode) =>
      _pigeon.setLayerBlendMode(id, mode);

  Future<void> setLayerClip(int id, bool clip) => _pigeon.setLayerClip(id, clip);

  Future<void> setLayerLocked(int id, bool locked) =>
      _pigeon.setLayerLocked(id, locked);

  Future<void> setAlphaLocked(int id, bool locked) =>
      _pigeon.setAlphaLocked(id, locked);

  Future<void> reorderLayer(int fromId, int toId, {bool insertAfter = false}) =>
      _pigeon.reorderLayer(fromId, toId, insertAfter);

  Future<void> duplicateLayer(int id) => _pigeon.duplicateLayer(id);

  Future<void> mergeDown(int id) => _pigeon.mergeDown(id);

  Future<void> batchMergeLayers(List<int> ids) => _pigeon.batchMergeLayers(ids);

  // ─── 複数レイヤー選択 (Pigeon — Session 4) ──

  Future<void> setMultiSelection(List<int> ids) => _pigeon.setMultiSelection(ids);

  Future<void> clearMultiSelection() => _pigeon.clearMultiSelection();

  Future<void> toggleLayerSelection(int id) => _pigeon.toggleLayerSelection(id);

  // ─── 複数レイヤー一括プロパティ変更 (Pigeon — Session 4) ──

  Future<void> batchSetVisibility(List<int> ids, bool visible) =>
      _pigeon.batchSetVisibility(ids, visible);

  Future<void> batchSetOpacity(List<int> ids, double opacity) {
    assert(opacity >= 0.0 && opacity <= 1.0, 'opacity must be in 0..1');
    return _pigeon.batchSetOpacity(ids, opacity);
  }

  Future<void> batchSetBlendMode(List<int> ids, int mode) =>
      _pigeon.batchSetBlendMode(ids, mode);

  // ─── レイヤーグループ（フォルダ）(Pigeon — Session 5) ──

  Future<void> createLayerGroup(String name) => _pigeon.createLayerGroup(name);

  Future<void> deleteLayerGroup(int groupId) => _pigeon.deleteLayerGroup(groupId);

  Future<void> setLayerGroup(int layerId, int groupId) =>
      _pigeon.setLayerGroup(layerId, groupId);

  /// 複数レイヤーを一括でフォルダに移動（groupId=0 でルートに移動）
  Future<void> batchSetLayerGroup(List<int> layerIds, int groupId) =>
      _pigeon.batchSetLayerGroup(layerIds, groupId);

  /// 複数レイヤーを一括で指定位置に相対移動
  Future<void> batchMoveLayersRelative(List<int> layerIds, int targetId, {bool insertAfter = false}) =>
      _pigeon.batchMoveLayersRelative(layerIds, targetId, insertAfter);

  Future<void> setGroupVisibility(int groupId, bool visible) =>
      _pigeon.setGroupVisibility(groupId, visible);

  Future<void> setGroupOpacity(int groupId, double opacity) {
    assert(opacity >= 0.0 && opacity <= 1.0, 'opacity must be in 0..1');
    return _pigeon.setGroupOpacity(groupId, opacity);
  }

  Future<void> setFolderExpanded(int folderId, bool expanded) =>
      _pigeon.setFolderExpanded(folderId, expanded);

  Future<void> reorderLayerGroup(int fromGroupId, int toGroupId, {bool insertAfter = false}) =>
      _pigeon.reorderLayerGroup(fromGroupId, toGroupId, insertAfter);

  /// フォルダとレイヤーが混在している場合の統一的な並び替え
  /// fromId: ドラッグ対象のレイヤーID（正数）またはグループID（正数）
  /// toId: ドロップ対象のレイヤーID（正数）またはグループID（正数）
  Future<void> reorderDisplayItem(int fromId, int toId) =>
      _pigeon.reorderDisplayItem(fromId, toId);

  // ─── 保存/エクスポート (Pigeon — Session 9) ───────────

  Future<void> saveProject() => _pigeon.saveProject();

  /// 保存してギャラリーに戻る
  Future<void> returnToGallery() => _pigeon.returnToGallery();

  /// ネイティブ側に Activity Result Launcher を起動してもらう
  Future<void> requestExport(String format) => _pigeon.requestExport(format);

  Future<void> requestImport(String type) => _pigeon.requestImport(type);

  Future<void> moveLayerUp(int id) => _pigeon.moveLayerUp(id);

  Future<void> moveLayerDown(int id) => _pigeon.moveLayerDown(id);

  Future<void> batchMoveLayersUp(List<int> ids) => _pigeon.batchMoveLayersUp(ids);

  Future<void> batchMoveLayersDown(List<int> ids) => _pigeon.batchMoveLayersDown(ids);

  // ─── ブラシ設定の Export/Import/Reset (Pigeon — Session 2) ───

  /// 全ブラシ設定を JSON 文字列としてエクスポート
  Future<String> exportBrushSettings() => _pigeon.exportBrushSettings();

  /// JSON 文字列からブラシ設定をインポート
  Future<bool> importBrushSettings(String json) =>
      _pigeon.importBrushSettings(json);

  /// 全ブラシ設定をデフォルトに戻す
  Future<void> resetBrushToDefaults() => _pigeon.resetBrushToDefaults();

  // ─── ツールモード (Pigeon — Session 10) ───────────────

  Future<void> activateEyedropper() => _pigeon.activateEyedropper();

  Future<void> deactivateEyedropper() => _pigeon.deactivateEyedropper();

  /// 既存呼び出し元の String API 互換を維持しつつ Pigeon ToolMode enum に変換して送る。
  Future<void> setToolMode(String mode) {
    final pigeonMode = _toolModeByName[mode];
    if (pigeonMode == null) {
      throw ArgumentError('unknown tool mode: $mode');
    }
    return _pigeon.setToolMode(pigeonMode);
  }

  static const Map<String, ToolMode> _toolModeByName = {
    'Draw': ToolMode.draw,
    'Eyedropper': ToolMode.eyedropper,
    'SelectMagnet': ToolMode.selectMagnet,
    'SelectMagicWand': ToolMode.selectMagicWand,
    'SelectPen': ToolMode.selectPen,
    'Transform': ToolMode.transform,
    'PixelCopy': ToolMode.pixelCopy,
    'ShapeLine': ToolMode.shapeLine,
    'ShapeRect': ToolMode.shapeRect,
    'ShapeEllipse': ToolMode.shapeEllipse,
    'FloodFill': ToolMode.floodFill,
    'Gradient': ToolMode.gradient,
    'Text': ToolMode.text,
  };

  /// 選択モード設定（Pigeon enum — Session 6）
  Future<void> setSelectionMode(SelectionMode mode) => _pigeon.setSelectionMode(mode);

  // ─── 選択ツール (Pigeon — Session 6) ──────────────────

  Future<void> selectRect(int left, int top, int right, int bottom) =>
      _pigeon.selectRect(left, top, right, bottom);

  Future<void> selectEllipse(int left, int top, int right, int bottom) =>
      _pigeon.selectEllipse(left, top, right, bottom);

  Future<void> selectByColor(int x, int y, {int tolerance = 32, bool contiguous = true}) =>
      _pigeon.selectByColor(x, y, tolerance, contiguous);

  /// マグネット選択: エッジ吸着で自動トレース
  /// @param x, y: スタート座標
  /// @param tolerance: エッジ検出の許容値
  /// @param maxDistance: トレース距離限界
  Future<void> selectMagnet(int x, int y, {int tolerance = 32, int maxDistance = 256}) =>
      _pigeon.selectMagnet(x, y, tolerance, maxDistance);

  Future<void> selectAll() => _pigeon.selectAll();
  Future<void> clearSelection() => _pigeon.clearSelection();
  Future<void> invertSelection() => _pigeon.invertSelection();

  /// 選択ペン: 円形ブラシで選択範囲を追加
  /// @param cx, cy: 中心座標
  /// @param radius: ブラシ半径（ピクセル）
  /// @param pressure: 筆圧 (0.0～1.0)
  Future<void> paintSelectionAdd(int cx, int cy, int radius, {double pressure = 1.0}) =>
      _pigeon.paintSelectionAdd(cx, cy, radius, pressure);

  /// 選択消し: 円形ブラシで選択範囲を削除
  /// @param cx, cy: 中心座標
  /// @param radius: ブラシ半径（ピクセル）
  /// @param pressure: 筆圧 (0.0～1.0)
  Future<void> paintSelectionErase(int cx, int cy, int radius, {double pressure = 1.0}) =>
      _pigeon.paintSelectionErase(cx, cy, radius, pressure);

  // ─── 選択範囲操作 (Pigeon — Session 6) ────────────────

  Future<void> deleteSelection() => _pigeon.deleteSelection();
  Future<void> fillSelection(int color) => _pigeon.fillSelection(color);
  Future<void> copySelection() => _pigeon.copySelection();
  Future<void> cutSelection() => _pigeon.cutSelection();
  Future<void> moveSelection(int dx, int dy) => _pigeon.moveSelection(dx, dy);

  /// 選択範囲をぼかす
  /// @param radius: ぼかし半径（ピクセル）
  Future<void> featherSelection(int radius) => _pigeon.featherSelection(radius);

  /// 選択範囲を拡張/縮小
  /// @param amount: 正=拡張, 負=縮小（ピクセル）
  Future<void> expandSelection(int amount) => _pigeon.expandSelection(amount);

  /// マグネット選択をキャンセル
  Future<void> cancelMagnetSelection() => _pigeon.cancelMagnetSelection();

  /// マグネット選択を確定（確定ボタン押下時）
  Future<void> finalizeMagnetSelection() => _pigeon.finalizeMagnetSelection();

  // ─── ピクセルコピー変形（浮遊選択層）(Pigeon — Session 7) ──

  /// 選択範囲のピクセルをコピーし、バウンディングボックスを返す。
  /// [layerIds] を指定すると多レイヤー浮遊選択 (Phase 3.5)。
  /// null の場合はアクティブレイヤーのみ。
  /// 既存呼び出し元との互換性のため、戻り値は `Map<String, int>` 相当で返す。
  Future<Map<String, dynamic>> startPixelCopy({List<int>? layerIds}) async {
    final bounds = await _pigeon.startPixelCopy(layerIds);
    return {
      'left': bounds.left,
      'top': bounds.top,
      'right': bounds.right,
      'bottom': bounds.bottom,
    };
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
  }) => _pigeon.applyPixelCopy(x, y, scaleX, scaleY, rotation);

  /// ピクセルコピー変形をキャンセル
  Future<void> cancelPixelCopy() => _pigeon.cancelPixelCopy();

  /// GL キャンバスのビュー変換（zoom/pan/rotation/surface/doc）を取得。
  /// PixelCopyOverlay の doc → screen 変換に使用。
  /// Pigeon の `ViewTransform` を `Map<String, dynamic>` に変換して既存呼び出し元と互換維持。
  Future<Map<String, dynamic>> getViewTransform() async {
    final vt = await _pigeon.getViewTransform();
    return {
      'zoom': vt.zoom,
      'panX': vt.panX,
      'panY': vt.panY,
      'rotation': vt.rotation,
      'surfaceWidth': vt.surfaceWidth,
      'surfaceHeight': vt.surfaceHeight,
      'docWidth': vt.docWidth,
      'docHeight': vt.docHeight,
    };
  }

  // ─── 変形ツール (Pigeon — Session 7) ──────────────────

  Future<void> flipLayerH() => _pigeon.flipLayerH();
  Future<void> flipLayerV() => _pigeon.flipLayerV();
  Future<void> rotateLayer90CW() => _pigeon.rotateLayer90CW();

  Future<void> transformLayer({
    double scaleX = 1, double scaleY = 1, double angle = 0,
    double translateX = 0, double translateY = 0,
  }) => _pigeon.transformLayer(scaleX, scaleY, angle, translateX, translateY);

  /// ディストート（パースペクティブ変形）
  /// corners: [tlX, tlY, trX, trY, brX, brY, blX, blY]
  Future<void> distortLayer(List<double> corners) {
    assert(corners.length >= 8, 'distortLayer: corners must have >= 8 values');
    return _pigeon.distortLayer(corners);
  }

  /// メッシュワープ変形
  Future<void> meshWarpLayer(int gridW, int gridH, List<double> nodes) =>
      _pigeon.meshWarpLayer(gridW, gridH, nodes);

  // ─── 複数レイヤー一括変形 (Pigeon — Session 7) ─────────

  /// 複数レイヤーに自由変形を一括適用（プレビュー確定時）
  Future<void> applyPreviewTransform({
    required List<int> layerIds,
    double scaleX = 1, double scaleY = 1,
    double angleDeg = 0,
    double translateX = 0, double translateY = 0,
  }) => _pigeon.applyPreviewTransform(
        layerIds, scaleX, scaleY, angleDeg, translateX, translateY,
      );

  /// 複数レイヤーに反転・回転を一括適用
  Future<void> applyMultiLayerSimpleTransform({
    required List<int> layerIds,
    required String operation,
  }) => _pigeon.applyMultiLayerSimpleTransform(layerIds, operation);

  /// リキファイ開始 (Undo スナップショット)
  Future<void> beginLiquify() => _pigeon.beginLiquify();

  /// リキファイ適用 (mode: 0=Push, 1=TwirlCW, 2=TwirlCCW, 3=Pinch, 4=Expand)
  Future<void> liquifyLayer(double cx, double cy, double radius,
      double dirX, double dirY, {double pressure = 1.0, int mode = 0}) =>
      _pigeon.liquifyLayer(cx, cy, radius, dirX, dirY, pressure, mode);

  /// リキファイ終了
  Future<void> endLiquify() => _pigeon.endLiquify();

  // ─── レイヤーマスク (Pigeon — Session 9) ──────────────

  Future<void> addLayerMask({bool fillWhite = true}) =>
      _pigeon.addLayerMask(fillWhite);

  Future<void> removeLayerMask() => _pigeon.removeLayerMask();
  Future<void> toggleMaskEnabled() => _pigeon.toggleMaskEnabled();
  Future<void> toggleEditMask() => _pigeon.toggleEditMask();
  Future<void> addMaskFromSelection() => _pigeon.addMaskFromSelection();

  // ─── 図形・塗りつぶし (Pigeon — Session 9) ────────────

  Future<void> drawShape(String type, int left, int top, int right, int bottom,
      {bool fill = true, double thickness = 1}) =>
      _pigeon.drawShape(type, left, top, right, bottom, fill, thickness);

  Future<void> floodFill(int x, int y, {int tolerance = 0}) =>
      _pigeon.floodFill(x, y, tolerance);

  /// 塗りつぶしツール使用時のしきい値設定 (0..255)
  Future<void> setFloodFillTolerance(int tolerance) {
    assert(tolerance >= 0 && tolerance <= 255);
    return _pigeon.setFloodFillTolerance(tolerance);
  }

  // ─── テキストレイヤー (Pigeon — Session 9) ────────────

  Future<void> addTextLayer(String text, {
    double fontSize = 48, double x = 0, double y = 0,
    bool bold = false, bool italic = false, bool vertical = false,
  }) => _pigeon.addTextLayer(text, fontSize, x, y, bold, italic, vertical);

  // ─── 追加フィルター (Pigeon — Session 8, @async) ────────

  Future<void> applyUnsharpMask({int radius = 1, double amount = 1, int threshold = 0}) =>
      _pigeon.applyUnsharpMask(radius, amount, threshold);

  Future<void> applyMosaic(int blockSize) => _pigeon.applyMosaic(blockSize);

  Future<void> applyNoise(int amount, {bool monochrome = true}) =>
      _pigeon.applyNoise(amount, monochrome);

  Future<void> applyPosterize(int levels) => _pigeon.applyPosterize(levels);

  Future<void> applyThreshold(int threshold) => _pigeon.applyThreshold(threshold);

  Future<void> applyInvertColors() => _pigeon.applyInvertColors();

  Future<void> applyMotionBlur({required double angleDeg, required int distance}) =>
      _pigeon.applyMotionBlur(angleDeg, distance);

  Future<void> applyLinearGradient({
    required double startX, required double startY,
    required double endX, required double endY,
    required int startColor, required int endColor,
  }) => _pigeon.applyLinearGradient(startX, startY, endX, endY, startColor, endColor);

  Future<void> applyLinearGradientAngle({
    required double angleDeg,
    required int startColor, required int endColor,
  }) => _pigeon.applyLinearGradientAngle(angleDeg, startColor, endColor);

  Future<void> applyRadialGradient({
    required double centerX, required double centerY,
    required double radius,
    required int startColor, required int endColor,
  }) => _pigeon.applyRadialGradient(centerX, centerY, radius, startColor, endColor);

  Future<void> applyRadialGradientCenter({
    required int startColor, required int endColor,
  }) => _pigeon.applyRadialGradientCenter(startColor, endColor);

  Future<void> applyLevels({int inBlack = 0, int inWhite = 255, double gamma = 1, int outBlack = 0, int outWhite = 255}) =>
      _pigeon.applyLevels(inBlack, inWhite, gamma, outBlack, outWhite);

  Future<void> applyColorBalance({int cyanRed = 0, int magentaGreen = 0, int yellowBlue = 0}) =>
      _pigeon.applyColorBalance(cyanRed, magentaGreen, yellowBlue);

  // ─── ビュー操作 (Pigeon — Session 10) ─────────────────

  Future<void> resetView() => _pigeon.resetView();

  // ─── ピクセル移動機能 (Pigeon — Session 7) ───────────────

  /// 複数レイヤーの一時的なオフセットを設定（ピクセル移動中）
  Future<void> setLayersOffset(List<int> layerIds, double offsetX, double offsetY) =>
      _pigeon.setLayersOffset(layerIds, offsetX, offsetY);

  /// 複数レイヤーのオフセットをリセット
  Future<void> resetLayersOffset(List<int> layerIds) =>
      _pigeon.resetLayersOffset(layerIds);

  /// 複数レイヤーのピクセル移動を確定（オフセットを実際のピクセルに適用）
  Future<void> commitPixelMovement(List<int> layerIds) =>
      _pigeon.commitPixelMovement(layerIds);

  /// フォルダ内のすべての子レイヤーIDを取得
  Future<List<int>> getLayersInGroup(int groupId) =>
      _pigeon.getLayersInGroup(groupId);

  /// フォルダ内のすべての子レイヤーIDを再帰的に取得
  Future<List<int>> getLayersInGroupRecursive(int groupId) =>
      _pigeon.getLayersInGroupRecursive(groupId);

  // ─── 状態取得 (Pigeon — Session 11) ───────────────────

  /// 現在の全状態を一括取得（Pigeon `getState` 経由）。
  /// 既存呼び出し元との互換性のため `Map<String, dynamic>` に変換して返す。
  /// `PaintState.copyWithMap` / `parseMemoryInfo` が読み取るキー構造と同一。
  Future<Map<String, dynamic>> getState() async {
    final state = await _pigeon.getState();
    return _paintStateToMap(state);
  }

  /// Kotlin 側から push される状態変更ストリーム（`PaintFlutterApi.onStateChanged` 経由）。
  /// 受信した Pigeon `PaintState` は `_paintStateToMap` で既存 Map 形式に変換済み。
  Stream<Map<String, dynamic>> get stateStream => _stateController.stream;

  /// Pigeon `PaintState` → 既存 Map 互換形式への変換。
  /// ToolMode enum は String 名（engine 側 PascalCase）に戻す。
  static Map<String, dynamic> _paintStateToMap(PaintState s) {
    return {
      'brushType': s.brushType,
      'brushSize': s.brushSize,
      'brushOpacity': s.brushOpacity,
      'brushHardness': s.brushHardness,
      'brushAntiAliasing': s.brushAntiAliasing,
      'brushDensity': s.brushDensity,
      'brushSpacing': s.brushSpacing,
      'stabilizer': s.stabilizer,
      'minBrushSizePercent': s.minBrushSizePercent,
      'colorStretch': s.colorStretch,
      'waterContent': s.waterContent,
      'blurStrength': s.blurStrength,
      'blurPressureThreshold': s.blurPressureThreshold,
      'pressureSizeEnabled': s.pressureSizeEnabled,
      'pressureOpacityEnabled': s.pressureOpacityEnabled,
      'pressureDensityEnabled': s.pressureDensityEnabled,
      'pressureSelectionEnabled': s.pressureSelectionEnabled,
      'currentColor': s.currentColor,
      'colorHistory': s.colorHistory,
      'canUndo': s.canUndo,
      'canRedo': s.canRedo,
      'toolMode': _toolModeToEngineName(s.toolMode),
      'isDrawing': s.isDrawing,
      'hasSelection': s.hasSelection,
      'selectedLayerIds': s.selectedLayerIds,
      'layers': s.layers.map(_layerInfoToMap).toList(),
      'viewTransform': s.viewTransform == null
          ? null
          : {
              'zoom': s.viewTransform!.zoom,
              'panX': s.viewTransform!.panX,
              'panY': s.viewTransform!.panY,
              'rotation': s.viewTransform!.rotation,
              'surfaceWidth': s.viewTransform!.surfaceWidth,
              'surfaceHeight': s.viewTransform!.surfaceHeight,
              'docWidth': s.viewTransform!.docWidth,
              'docHeight': s.viewTransform!.docHeight,
            },
      'deviceRamMb': s.deviceRamMb,
      'memoryTier': s.memoryTier,
      'maxCanvasSize': s.maxCanvasSize,
      'maxLayers': s.maxLayers,
      'maxBrushSize': s.maxBrushSize,
      'maxBlurRadius': s.maxBlurRadius,
      'maxUndoEntries': s.maxUndoEntries,
    };
  }

  static Map<String, dynamic> _layerInfoToMap(LayerInfo l) => {
        'id': l.id,
        'name': l.name,
        'opacity': l.opacity,
        'blendMode': l.blendMode,
        'isVisible': l.isVisible,
        'isLocked': l.isLocked,
        'isClipToBelow': l.isClipToBelow,
        'isActive': l.isActive,
        'isAlphaLocked': l.isAlphaLocked,
        'hasMask': l.hasMask,
        'isMaskEnabled': l.isMaskEnabled,
        'isEditingMask': l.isEditingMask,
        'groupId': l.groupId,
        'isTextLayer': l.isTextLayer,
        'isGroup': l.isGroup,
        'depth': l.depth,
        'isExpanded': l.isExpanded,
      };

  /// Pigeon `ToolMode` enum → engine 側 `ToolMode` の PascalCase 名。
  /// Session 10 の `_toolModeByName` の逆マッピング。
  static String _toolModeToEngineName(ToolMode m) {
    switch (m) {
      case ToolMode.draw: return 'Draw';
      case ToolMode.eyedropper: return 'Eyedropper';
      case ToolMode.selectMagnet: return 'SelectMagnet';
      case ToolMode.selectMagicWand: return 'SelectMagicWand';
      case ToolMode.selectPen: return 'SelectPen';
      case ToolMode.transform: return 'Transform';
      case ToolMode.pixelCopy: return 'PixelCopy';
      case ToolMode.shapeLine: return 'ShapeLine';
      case ToolMode.shapeRect: return 'ShapeRect';
      case ToolMode.shapeEllipse: return 'ShapeEllipse';
      case ToolMode.floodFill: return 'FloodFill';
      case ToolMode.gradient: return 'Gradient';
      case ToolMode.text: return 'Text';
    }
  }

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
