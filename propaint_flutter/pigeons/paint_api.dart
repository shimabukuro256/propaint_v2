// Pigeon 定義ファイル (Flutter ↔ Kotlin 型安全 API)
//
// 生成コマンド (CWD: propaint_flutter/):
//   dart run pigeon --input pigeons/paint_api.dart
// または scripts/gen_pigeon.ps1
//
// 生成先:
//   Dart   -> propaint_flutter/lib/services/paint_api.g.dart
//   Kotlin -> app/src/main/java/com/propaint/app/flutter/pigeon/PaintApi.g.kt
//
// 進捗・段階分けは docs/PIGEON_MIGRATION.md を参照。

import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/services/paint_api.g.dart',
  dartOptions: DartOptions(),
  kotlinOut:
      '../app/src/main/java/com/propaint/app/flutter/pigeon/PaintApi.g.kt',
  kotlinOptions: KotlinOptions(package: 'com.propaint.app.flutter.pigeon'),
  dartPackageName: 'propaint_flutter',
))

/// 選択モード（Pigeon enum）。Kotlin 側 `com.propaint.app.engine.SelectionMode` と
/// 定数名 1:1 対応。Pigeon がコンパイル時検査するため、不正値は投げられない。
enum SelectionMode {
  replace,
  add,
  subtract,
  intersect,
}

/// ツールモード（Pigeon enum）。Kotlin 側 `com.propaint.app.viewmodel.ToolMode` と
/// 1:1 対応。パッケージが異なるため同名 `ToolMode` で衝突しない。
enum ToolMode {
  draw,
  eyedropper,
  selectMagnet,
  selectMagicWand,
  selectPen,
  transform,
  pixelCopy,
  shapeLine,
  shapeRect,
  shapeEllipse,
  floodFill,
  gradient,
  text,
}

/// ビュー変換状態（`getViewTransform` の戻り値）。
/// Dart 側は `paint_channel.getViewTransform()` 内で `Map<String, dynamic>` に
/// 変換して既存呼び出し元（`PaintState.viewTransform` 等）との互換を維持。
class ViewTransform {
  ViewTransform({
    required this.zoom,
    required this.panX,
    required this.panY,
    required this.rotation,
    required this.surfaceWidth,
    required this.surfaceHeight,
    required this.docWidth,
    required this.docHeight,
  });
  double zoom;
  double panX;
  double panY;
  double rotation;
  int surfaceWidth;
  int surfaceHeight;
  int docWidth;
  int docHeight;
}

/// ピクセルコピー開始時の矩形境界（`startPixelCopy` の戻り値）。
/// Kotlin 側は Rect ベース、Dart 側は `Map<String, int>` 互換レイヤー経由で利用。
class PixelCopyBounds {
  PixelCopyBounds({
    required this.left,
    required this.top,
    required this.right,
    required this.bottom,
  });
  int left;
  int top;
  int right;
  int bottom;
}

/// レイヤー 1 件のスナップショット（`PaintState.layers` の要素）。
/// Kotlin 側 `UiLayer` と 1:1 対応（17 フィールド）。
class LayerInfo {
  LayerInfo({
    required this.id,
    required this.name,
    required this.opacity,
    required this.blendMode,
    required this.isVisible,
    required this.isLocked,
    required this.isClipToBelow,
    required this.isActive,
    required this.isAlphaLocked,
    required this.hasMask,
    required this.isMaskEnabled,
    required this.isEditingMask,
    required this.groupId,
    required this.isTextLayer,
    required this.isGroup,
    required this.depth,
    required this.isExpanded,
  });
  int id;
  String name;
  double opacity;
  int blendMode;
  bool isVisible;
  bool isLocked;
  bool isClipToBelow;
  bool isActive;
  bool isAlphaLocked;
  bool hasMask;
  bool isMaskEnabled;
  bool isEditingMask;
  int groupId;
  bool isTextLayer;
  bool isGroup;
  int depth;
  bool isExpanded;
}

/// Kotlin PaintViewModel 全状態のスナップショット。
/// Session 11 導入時点では既存 EventChannel (`buildStateMap`) と並走。
/// Flutter 側 `PaintState` (Dart モデル) へは `paint_channel.dart` で Map 互換変換。
class PaintState {
  PaintState({
    required this.brushType,
    required this.brushSize,
    required this.brushOpacity,
    required this.brushHardness,
    required this.brushAntiAliasing,
    required this.brushDensity,
    required this.brushSpacing,
    required this.stabilizer,
    required this.minBrushSizePercent,
    required this.colorStretch,
    required this.waterContent,
    required this.blurStrength,
    required this.blurPressureThreshold,
    required this.pressureSizeEnabled,
    required this.pressureOpacityEnabled,
    required this.pressureDensityEnabled,
    required this.pressureSelectionEnabled,
    required this.currentColor,
    required this.colorHistory,
    required this.canUndo,
    required this.canRedo,
    required this.toolMode,
    required this.isDrawing,
    required this.hasSelection,
    required this.selectedLayerIds,
    required this.layers,
    this.viewTransform,
    required this.deviceRamMb,
    required this.memoryTier,
    required this.maxCanvasSize,
    required this.maxLayers,
    required this.maxBrushSize,
    required this.maxBlurRadius,
    required this.maxUndoEntries,
  });
  String brushType;
  double brushSize;
  double brushOpacity;
  double brushHardness;
  double brushAntiAliasing;
  double brushDensity;
  double brushSpacing;
  double stabilizer;
  int minBrushSizePercent;
  double colorStretch;
  double waterContent;
  double blurStrength;
  double blurPressureThreshold;
  bool pressureSizeEnabled;
  bool pressureOpacityEnabled;
  bool pressureDensityEnabled;
  bool pressureSelectionEnabled;
  int currentColor;
  List<int> colorHistory;
  bool canUndo;
  bool canRedo;
  ToolMode toolMode;
  bool isDrawing;
  bool hasSelection;
  /// Pigeon は `Set` 非対応のため `List` で表現。重複なしで Kotlin 側から送出。
  List<int> selectedLayerIds;
  List<LayerInfo> layers;
  ViewTransform? viewTransform;
  int deviceRamMb;
  String memoryTier;
  int maxCanvasSize;
  int maxLayers;
  int maxBrushSize;
  int maxBlurRadius;
  int maxUndoEntries;
}

/// Flutter → Kotlin コマンドAPI。
///
/// 移行済みセッション:
/// - Session 1: setBrushSize (試験移行)
/// - Session 2: Brush 設定系 (20 メソッド)
/// - Session 3: Color + History + Layer CRUD (18 メソッド)
/// - Session 4: Batch Layer + Multi Selection (9 メソッド)
/// - Session 5: Layer Group (12 メソッド)
/// - Session 6: Selection Tool + Selection Ops (19 メソッド)
/// - Session 7: Transform + Pixel Copy + Pixel Movement (17 メソッド + PixelCopyBounds)
/// - Session 8: Filter (13 メソッド、@async で Default ディスパッチャにオフロード)
/// - Session 9: Mask + Shape/Fill/Text + Save/Export/Import (13 メソッド、saveProject のみ @async)
/// - Session 10: View + Tool Mode (5 メソッド、ToolMode enum + ViewTransform データクラス化)
/// - Session 11: State Stream FlutterApi (getState + PaintFlutterApi、既存 EventChannel と並走)
@HostApi()
abstract class PaintHostApi {
  // ─────────────────────────────────────────────────────
  // Session 1: 試験移行
  // ─────────────────────────────────────────────────────

  /// ブラシサイズ設定 (value > 0)。
  void setBrushSize(double value);

  // ─────────────────────────────────────────────────────
  // Session 2: Brush 設定系
  // ─────────────────────────────────────────────────────

  /// ブラシ種別設定。type は Kotlin BrushType の name と一致する必要あり。
  /// 不正値は FlutterError("INVALID_ARG")。
  void setBrushType(String type);

  /// 不透明度 (0.0..1.0)。
  void setBrushOpacity(double value);

  /// 硬さ (0.0..1.0)。
  void setBrushHardness(double value);

  /// アンチエイリアス (0.0..1.0)。
  void setBrushAntiAliasing(double value);

  /// 濃度 (範囲検証なし、BrushEngine 側で clamp)。
  void setBrushDensity(double value);

  /// ダブ間隔 (> 0)。
  void setBrushSpacing(double value);

  /// 最小サイズ (1..100 %)。
  void setBrushMinSizePercent(int percent);

  /// 手振れ補正強度。
  void setStabilizer(double value);

  /// 混色ストレッチ。
  void setColorStretch(double value);

  /// 水分量 (混色ブラシ)。
  void setWaterContent(double value);

  /// ぼかし強度。
  void setBlurStrength(double value);

  /// ぼかし発動の筆圧閾値。
  void setBlurPressureThreshold(double value);

  /// 筆圧 → サイズ連動トグル。
  void togglePressureSize();

  /// 筆圧 → 不透明度連動トグル。
  void togglePressureOpacity();

  /// 筆圧 → 濃度連動トグル。
  void togglePressureDensity();

  /// 筆圧 → 選択範囲連動トグル。
  void togglePressureSelection();

  /// 全ブラシ設定を JSON 文字列としてエクスポート。
  String exportBrushSettings();

  /// JSON からブラシ設定をインポート。成功/失敗を返す。
  bool importBrushSettings(String json);

  /// 全ブラシ設定をデフォルトに戻す。
  void resetBrushToDefaults();

  // ─────────────────────────────────────────────────────
  // Session 3: Color + History + Layer CRUD
  // ─────────────────────────────────────────────────────

  /// カラー設定。argb は ARGB (0xFFRRGGBB)。Dart int → Kotlin Long マッピング。
  void setColor(int argb);

  /// 現在色をヒストリに追加。
  void commitColor();

  /// Undo / Redo。
  void undo();
  void redo();

  /// 新規レイヤー追加。
  void addLayer();

  /// レイヤー削除。
  void removeLayer(int id);

  /// アクティブレイヤー切替。
  void selectLayer(int id);

  /// 可視/不可視切替。
  void setLayerVisibility(int id, bool visible);

  /// 不透明度 (0.0..1.0)。
  void setLayerOpacity(int id, double opacity);

  /// ブレンドモード (インデックス)。
  void setLayerBlendMode(int id, int mode);

  /// 下のレイヤーへのクリップトグル。
  void setLayerClip(int id, bool clip);

  /// レイヤーロック (編集禁止)。
  void setLayerLocked(int id, bool locked);

  /// α ロック (透明部への描画禁止)。
  void setAlphaLocked(int id, bool locked);

  /// レイヤー並び替え。insertAfter=true で toId の後ろに挿入。
  void reorderLayer(int fromId, int toId, bool insertAfter);

  /// レイヤー複製。
  void duplicateLayer(int id);

  /// 下のレイヤーと結合。
  void mergeDown(int id);

  /// 1段上に移動 / 1段下に移動。
  void moveLayerUp(int id);
  void moveLayerDown(int id);

  // ─────────────────────────────────────────────────────
  // Session 4: Batch Layer + Multi Selection
  // ─────────────────────────────────────────────────────

  /// 複数レイヤーを下方向へ連続マージ。
  void batchMergeLayers(List<int> ids);

  /// 複数レイヤーの可視一括設定。
  void batchSetVisibility(List<int> ids, bool visible);

  /// 複数レイヤーの不透明度一括設定 (0.0..1.0)。
  void batchSetOpacity(List<int> ids, double opacity);

  /// 複数レイヤーのブレンドモード一括設定。
  void batchSetBlendMode(List<int> ids, int mode);

  /// 複数レイヤーを 1 段上/下に一括移動。
  void batchMoveLayersUp(List<int> ids);
  void batchMoveLayersDown(List<int> ids);

  /// 複数選択の置き換え (Single Source of Truth: Kotlin)。
  void setMultiSelection(List<int> ids);

  /// 複数選択のクリア。
  void clearMultiSelection();

  /// 単一レイヤーの選択トグル。
  void toggleLayerSelection(int id);

  // ─────────────────────────────────────────────────────
  // Session 5: Layer Group (フォルダ)
  // ─────────────────────────────────────────────────────

  /// 新規フォルダ作成。
  void createLayerGroup(String name);

  /// フォルダ削除。
  void deleteLayerGroup(int groupId);

  /// レイヤーの所属グループ設定 (groupId=0 でルート)。
  void setLayerGroup(int layerId, int groupId);

  /// 複数レイヤーを一括でフォルダに移動。
  void batchSetLayerGroup(List<int> layerIds, int groupId);

  /// 複数レイヤーを指定位置に相対移動。
  void batchMoveLayersRelative(List<int> layerIds, int targetId, bool insertAfter);

  /// グループの可視一括設定。
  void setGroupVisibility(int groupId, bool visible);

  /// グループの不透明度一括設定 (0.0..1.0)。
  void setGroupOpacity(int groupId, double opacity);

  /// フォルダ展開/折畳状態。
  void setFolderExpanded(int folderId, bool expanded);

  /// フォルダ並び替え。
  void reorderLayerGroup(int fromGroupId, int toGroupId, bool insertAfter);

  /// フォルダとレイヤー混在時の統一並び替え。
  void reorderDisplayItem(int fromId, int toId);

  /// フォルダ内のレイヤー ID 取得（直下のみ）。
  List<int> getLayersInGroup(int groupId);

  /// フォルダ内のレイヤー ID 取得（再帰）。
  List<int> getLayersInGroupRecursive(int groupId);

  // ─────────────────────────────────────────────────────
  // Session 6: Selection Tool + Selection Ops
  // ─────────────────────────────────────────────────────

  /// 矩形選択。
  void selectRect(int left, int top, int right, int bottom);

  /// 楕円選択。
  void selectEllipse(int left, int top, int right, int bottom);

  /// カラー範囲選択（自動塗りつぶし風）。
  void selectByColor(int x, int y, int tolerance, bool contiguous);

  /// マグネット選択（エッジ吸着トレース）。
  void selectMagnet(int x, int y, int tolerance, int maxDistance);

  /// マグネット選択のキャンセル。
  void cancelMagnetSelection();

  /// マグネット選択の確定。
  void finalizeMagnetSelection();

  /// 全範囲選択。
  void selectAll();

  /// 選択解除。
  void clearSelection();

  /// 選択範囲反転。
  void invertSelection();

  /// 選択ペン（円形ブラシで選択範囲を追加）。
  void paintSelectionAdd(int cx, int cy, int radius, double pressure);

  /// 選択消し（円形ブラシで選択範囲を削除）。
  void paintSelectionErase(int cx, int cy, int radius, double pressure);

  /// 選択範囲のピクセル削除。
  void deleteSelection();

  /// 選択範囲の塗りつぶし。
  void fillSelection(int color);

  /// 選択範囲のコピー / 切り取り。
  void copySelection();
  void cutSelection();

  /// 選択範囲の平行移動（選択マスクのみ／ピクセルは動かさない）。
  void moveSelection(int dx, int dy);

  /// 選択範囲をぼかす（半径ピクセル）。
  void featherSelection(int radius);

  /// 選択範囲を拡張/縮小（正=拡張、負=縮小、ピクセル）。
  void expandSelection(int amount);

  /// 選択モード設定（Pigeon enum）。
  void setSelectionMode(SelectionMode mode);

  // ─────────────────────────────────────────────────────
  // Session 7: Transform + Pixel Copy + Pixel Movement
  // ─────────────────────────────────────────────────────

  /// 水平反転。
  void flipLayerH();

  /// 垂直反転。
  void flipLayerV();

  /// 時計回り 90° 回転。
  void rotateLayer90CW();

  /// アクティブレイヤー変形 (スケール/回転/平行移動)。
  void transformLayer(
    double scaleX,
    double scaleY,
    double angle,
    double translateX,
    double translateY,
  );

  /// 歪み変形（4 隅指定、corners は 8 要素: x0,y0,x1,y1,x2,y2,x3,y3）。
  void distortLayer(List<double> corners);

  /// メッシュワープ変形。nodes は gridW*gridH*2 要素 (x,y ペア)。
  void meshWarpLayer(int gridW, int gridH, List<double> nodes);

  /// 複数レイヤーの一括プレビュー変形。
  void applyPreviewTransform(
    List<int> layerIds,
    double scaleX,
    double scaleY,
    double angleDeg,
    double translateX,
    double translateY,
  );

  /// 複数レイヤーの単純変形（flipH / flipV / rotate90CW 等、operation は Kotlin 側で解釈）。
  void applyMultiLayerSimpleTransform(List<int> layerIds, String operation);

  /// リクイファイ（指先ツール）開始。
  void beginLiquify();

  /// リクイファイ適用 (mode=0: push, 1: twirlCW, 2: twirlCCW, 3: pinch, 4: expand, 5: reconstruct)。
  void liquifyLayer(
    double cx,
    double cy,
    double radius,
    double dirX,
    double dirY,
    double pressure,
    int mode,
  );

  /// リクイファイ終了。
  void endLiquify();

  // ── ピクセルコピー（浮遊選択層） ──

  /// ピクセルコピー開始。layerIds=null でアクティブレイヤーを対象。
  /// 戻り値はコピー対象の矩形境界。
  PixelCopyBounds startPixelCopy(List<int>? layerIds);

  /// ピクセルコピー適用 (位置・スケール・回転を反映して確定)。
  void applyPixelCopy(
    int x,
    int y,
    double scaleX,
    double scaleY,
    double rotation,
  );

  /// ピクセルコピー取消。
  void cancelPixelCopy();

  // ── ピクセル移動 ──

  /// 複数レイヤーの描画内容を同時平行移動（未確定状態）。
  void setLayersOffset(List<int> layerIds, double offsetX, double offsetY);

  /// 未確定のオフセットをリセット。
  void resetLayersOffset(List<int> layerIds);

  /// 未確定のオフセットを確定（Undo スナップショット記録）。
  void commitPixelMovement(List<int> layerIds);

  // ─────────────────────────────────────────────────────
  // Session 8: Filter (全ピクセル走査のため @async で Default ディスパッチャへ)
  // ─────────────────────────────────────────────────────

  /// アンシャープマスク。radius: ぼかし半径、amount: 適用量、threshold: 差分閾値。
  @async
  void applyUnsharpMask(int radius, double amount, int threshold);

  /// モザイク。blockSize: ピクセルブロックの一辺。
  @async
  void applyMosaic(int blockSize);

  /// ノイズ。amount: 強度、monochrome: モノクロノイズ。
  @async
  void applyNoise(int amount, bool monochrome);

  /// ポスタライズ。levels: 階調数。
  @async
  void applyPosterize(int levels);

  /// 2 値化。threshold: 0..255 の閾値。
  @async
  void applyThreshold(int threshold);

  /// 色反転（アルファは保持）。
  @async
  void applyInvertColors();

  /// モーションブラー。angleDeg: 角度（度）、distance: ピクセル距離。
  @async
  void applyMotionBlur(double angleDeg, int distance);

  /// 2 点指定の線形グラデーション。color は ARGB int。
  @async
  void applyLinearGradient(
    double startX,
    double startY,
    double endX,
    double endY,
    int startColor,
    int endColor,
  );

  /// 角度指定の線形グラデーション（キャンバス全体）。
  @async
  void applyLinearGradientAngle(double angleDeg, int startColor, int endColor);

  /// 中心・半径指定の円形グラデーション。
  @async
  void applyRadialGradient(
    double centerX,
    double centerY,
    double radius,
    int startColor,
    int endColor,
  );

  /// キャンバス中心を起点とする円形グラデーション。
  @async
  void applyRadialGradientCenter(int startColor, int endColor);

  /// レベル補正。inBlack/inWhite: 入力範囲、gamma: 中間トーン、outBlack/outWhite: 出力範囲。
  @async
  void applyLevels(
    int inBlack,
    int inWhite,
    double gamma,
    int outBlack,
    int outWhite,
  );

  /// カラーバランス。cyanRed/magentaGreen/yellowBlue: それぞれ -100..100。
  @async
  void applyColorBalance(int cyanRed, int magentaGreen, int yellowBlue);

  // ─────────────────────────────────────────────────────
  // Session 9: Mask + Shape/Fill/Text + Save/Export/Import
  // ─────────────────────────────────────────────────────

  /// レイヤーマスク追加。fillWhite=true で白塗り（全表示）、false で黒塗り（全隠し）。
  void addLayerMask(bool fillWhite);

  /// レイヤーマスク削除。
  void removeLayerMask();

  /// マスクの有効/無効を切替。
  void toggleMaskEnabled();

  /// マスク編集モードの切替（マスク側にペイントするか）。
  void toggleEditMask();

  /// 現在の選択範囲からマスクを生成。
  void addMaskFromSelection();

  /// 図形描画。shapeType: "rect" | "ellipse" | "line" 等、
  /// fill=true でベタ塗り、false で輪郭のみ（thickness は輪郭線幅）。
  void drawShape(
    String shapeType,
    int left,
    int top,
    int right,
    int bottom,
    bool fill,
    double thickness,
  );

  /// 塗りつぶしツール（バケツ）。tolerance: 色差許容値（0..255）。
  void floodFill(int x, int y, int tolerance);

  /// 塗りつぶしツール既定の許容値設定（0..255）。
  void setFloodFillTolerance(int tolerance);

  /// テキストレイヤー追加。bold/italic/vertical はそれぞれ太字/斜体/縦書き。
  void addTextLayer(
    String text,
    double fontSize,
    double x,
    double y,
    bool bold,
    bool italic,
    bool vertical,
  );

  /// プロジェクト保存（ファイル IO のため @async）。
  @async
  void saveProject();

  /// 保存してギャラリーに戻る（非同期保存は Kotlin 側でスケジュール）。
  void returnToGallery();

  /// エクスポート要求（"png" / "jpeg" / "psd" / "project"）。ネイティブ側で Activity Result Launcher を起動。
  void requestExport(String format);

  /// インポート要求（"image" / "project" 等）。ネイティブ側で Picker を起動。
  void requestImport(String type);

  // ─────────────────────────────────────────────────────
  // Session 10: View + Tool Mode
  // ─────────────────────────────────────────────────────

  /// スポイトツールを有効化（ToolMode を Eyedropper に遷移）。
  void activateEyedropper();

  /// スポイトツールを解除（ToolMode を Draw に戻す）。
  void deactivateEyedropper();

  /// ツールモード設定（Pigeon enum で型安全）。
  void setToolMode(ToolMode mode);

  /// ビュー（zoom / pan / rotation）をデフォルト位置にリセット。
  void resetView();

  /// 現在のビュー変換状態を取得。
  ViewTransform getViewTransform();

  // ─────────────────────────────────────────────────────
  // Session 11: State Stream (FlutterApi と並走)
  // ─────────────────────────────────────────────────────

  /// 現在の全状態を同期取得（初期化やリカバリ用）。
  /// 継続的な差分配信は `PaintFlutterApi.onStateChanged` 経由（Session 11 では未配線、
  /// 既存 EventChannel が送信を担当）。
  PaintState getState();
}

/// Kotlin → Flutter 通知 API（Pigeon @FlutterApi）。Final セッションで全配信をここに統一。
@FlutterApi()
abstract class PaintFlutterApi {
  /// ViewModel StateFlow 変更時に送られる状態スナップショット。
  /// Kotlin 側は直近送信分と equals 比較して差分がある場合のみ emit。
  void onStateChanged(PaintState state);

  /// ネイティブ側ジェスチャ通知（例: 2 本指タップ → "undo" / "redo"）。
  void onNativeGesture(String gesture);

  /// ネイティブ側エラー表示要求（viewModel.errorMessage 経由）。
  void onErrorMessage(String message);
}
