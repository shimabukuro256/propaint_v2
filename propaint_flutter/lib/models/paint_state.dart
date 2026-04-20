/// Kotlin PaintViewModel のミラー状態。
/// EventChannel から受け取った Map をパースして保持する。
class PaintState {
  final String brushType;
  final double brushSize;
  final double brushOpacity;
  final double brushHardness;
  final double brushAntiAliasing;
  final double brushDensity;
  final double brushSpacing;
  final double stabilizer;
  final int minBrushSizePercent;
  final double colorStretch;
  final double waterContent;
  final double blurStrength;
  final double blurPressureThreshold;
  final bool pressureSizeEnabled;
  final bool pressureOpacityEnabled;
  final bool pressureDensityEnabled;
  final int currentColor; // ARGB int
  final List<int> colorHistory; // ARGB int list
  final bool canUndo;
  final bool canRedo;
  final String toolMode;
  final bool isDrawing;
  final bool hasSelection;
  final Set<int> selectedLayerIds;
  final List<LayerInfo> layers;
  /// GL キャンバスのビュー変換。PixelCopyOverlay の doc→screen 変換用。
  /// キー: zoom, panX, panY, rotation, surfaceWidth, surfaceHeight, docWidth, docHeight
  final Map<String, dynamic>? viewTransform;

  const PaintState({
    this.brushType = 'Pencil',
    this.brushSize = 10.0,
    this.brushOpacity = 1.0,
    this.brushHardness = 1.0,
    this.brushAntiAliasing = 1.0,
    this.brushDensity = 1.0,
    this.brushSpacing = 0.06,
    this.stabilizer = 0.0,
    this.minBrushSizePercent = 20,
    this.colorStretch = 0.0,
    this.waterContent = 0.0,
    this.blurStrength = 0.05,
    this.blurPressureThreshold = 0.0,
    this.pressureSizeEnabled = true,
    this.pressureOpacityEnabled = false,
    this.pressureDensityEnabled = false,
    this.currentColor = 0xFF000000,
    this.colorHistory = const [],
    this.canUndo = false,
    this.canRedo = false,
    this.toolMode = 'Draw',
    this.isDrawing = false,
    this.hasSelection = false,
    this.selectedLayerIds = const {},
    this.layers = const [],
    this.viewTransform,
  });

  PaintState copyWithMap(Map<String, dynamic> m) {
    return PaintState(
      brushType: m['brushType'] as String? ?? brushType,
      brushSize: (m['brushSize'] as num?)?.toDouble() ?? brushSize,
      brushOpacity: (m['brushOpacity'] as num?)?.toDouble() ?? brushOpacity,
      brushHardness: (m['brushHardness'] as num?)?.toDouble() ?? brushHardness,
      brushAntiAliasing: (m['brushAntiAliasing'] as num?)?.toDouble() ?? brushAntiAliasing,
      brushDensity: (m['brushDensity'] as num?)?.toDouble() ?? brushDensity,
      brushSpacing: (m['brushSpacing'] as num?)?.toDouble() ?? brushSpacing,
      stabilizer: (m['stabilizer'] as num?)?.toDouble() ?? stabilizer,
      minBrushSizePercent: (m['minBrushSizePercent'] as num?)?.toInt() ?? minBrushSizePercent,
      colorStretch: (m['colorStretch'] as num?)?.toDouble() ?? colorStretch,
      waterContent: (m['waterContent'] as num?)?.toDouble() ?? waterContent,
      blurStrength: (m['blurStrength'] as num?)?.toDouble() ?? blurStrength,
      blurPressureThreshold: (m['blurPressureThreshold'] as num?)?.toDouble() ?? blurPressureThreshold,
      pressureSizeEnabled: m['pressureSizeEnabled'] as bool? ?? pressureSizeEnabled,
      pressureOpacityEnabled: m['pressureOpacityEnabled'] as bool? ?? pressureOpacityEnabled,
      pressureDensityEnabled: m['pressureDensityEnabled'] as bool? ?? pressureDensityEnabled,
      currentColor: (m['currentColor'] as num?)?.toInt() ?? currentColor,
      colorHistory: m['colorHistory'] != null
          ? (m['colorHistory'] as List).map((e) => (e as num).toInt()).toList()
          : colorHistory,
      canUndo: m['canUndo'] as bool? ?? canUndo,
      canRedo: m['canRedo'] as bool? ?? canRedo,
      toolMode: m['toolMode'] as String? ?? toolMode,
      isDrawing: m['isDrawing'] as bool? ?? isDrawing,
      hasSelection: m['hasSelection'] as bool? ?? hasSelection,
      selectedLayerIds: m['selectedLayerIds'] != null
          ? (m['selectedLayerIds'] as List).map((e) => (e as num).toInt()).toSet()
          : selectedLayerIds,
      layers: m['layers'] != null
          ? (m['layers'] as List).map((e) => LayerInfo.fromMap(Map<String, dynamic>.from(e as Map))).toList()
          : layers,
      viewTransform: m.containsKey('viewTransform')
          ? (m['viewTransform'] == null
              ? null
              : Map<String, dynamic>.from(m['viewTransform'] as Map))
          : viewTransform,
    );
  }
}

class LayerInfo {
  final int id;
  final String name;
  final double opacity;
  final int blendMode;
  final bool isVisible;
  final bool isLocked;
  final bool isClipToBelow;
  final bool isActive;
  final bool isAlphaLocked;
  final bool hasMask;
  final bool isMaskEnabled;
  final bool isEditingMask;
  final int groupId;
  final bool isTextLayer;
  final bool isGroup;
  final int depth;  // ツリー深度（インデント用）
  final bool isExpanded;  // フォルダ展開状態

  const LayerInfo({
    required this.id,
    required this.name,
    this.opacity = 1.0,
    this.blendMode = 0,
    this.isVisible = true,
    this.isLocked = false,
    this.isClipToBelow = false,
    this.isActive = false,
    this.isAlphaLocked = false,
    this.hasMask = false,
    this.isMaskEnabled = false,
    this.isEditingMask = false,
    this.groupId = 0,
    this.isTextLayer = false,
    this.isGroup = false,
    this.depth = 0,
    this.isExpanded = true,
  });

  factory LayerInfo.fromMap(Map<String, dynamic> m) => LayerInfo(
        id: (m['id'] as num).toInt(),
        name: m['name'] as String? ?? '',
        opacity: (m['opacity'] as num?)?.toDouble() ?? 1.0,
        blendMode: (m['blendMode'] as num?)?.toInt() ?? 0,
        isVisible: m['isVisible'] as bool? ?? true,
        isLocked: m['isLocked'] as bool? ?? false,
        isClipToBelow: m['isClipToBelow'] as bool? ?? false,
        isActive: m['isActive'] as bool? ?? false,
        isAlphaLocked: m['isAlphaLocked'] as bool? ?? false,
        hasMask: m['hasMask'] as bool? ?? false,
        isMaskEnabled: m['isMaskEnabled'] as bool? ?? false,
        isEditingMask: m['isEditingMask'] as bool? ?? false,
        groupId: (m['groupId'] as num?)?.toInt() ?? 0,
        isTextLayer: m['isTextLayer'] as bool? ?? false,
        isGroup: m['isGroup'] as bool? ?? false,
        depth: (m['depth'] as num?)?.toInt() ?? 0,
        isExpanded: m['isExpanded'] as bool? ?? true,
      );
}

/// ブラシ種別の表示名と機能フラグ
class BrushTypeInfo {
  final String key;
  final String displayName;
  final bool supportsOpacity;
  final bool supportsDensity;
  final bool hasColorStretch;
  final bool hasWaterContent;
  final bool hasBlurStrength;
  final bool hasBlurPressureThreshold;

  const BrushTypeInfo(
    this.key,
    this.displayName, {
    this.supportsOpacity = true,
    this.supportsDensity = true,
    this.hasColorStretch = false,
    this.hasWaterContent = false,
    this.hasBlurStrength = false,
    this.hasBlurPressureThreshold = false,
  });
}

const kBrushTypes = [
  BrushTypeInfo('Pencil', '鉛筆'),
  BrushTypeInfo('Fude', '筆', supportsOpacity: false, hasColorStretch: true, hasBlurPressureThreshold: true),
  BrushTypeInfo('Watercolor', '水彩筆', supportsOpacity: false, hasColorStretch: true, hasWaterContent: true, hasBlurPressureThreshold: true),
  BrushTypeInfo('Airbrush', 'エアブラシ', supportsOpacity: false),
  BrushTypeInfo('Marker', 'マーカー', supportsDensity: false),
  BrushTypeInfo('Eraser', '消しゴム'),
  BrushTypeInfo('Blur', 'ぼかし', supportsOpacity: false, hasBlurStrength: true),
];

BrushTypeInfo brushTypeInfoFor(String key) =>
    kBrushTypes.firstWhere((b) => b.key == key, orElse: () => kBrushTypes.first);

/// ブレンドモード名
const kBlendModeNames = [
  '通常', '乗算', 'スクリーン', 'オーバーレイ',
  '焼き込み', 'スクリーン(明)', 'カラー ダッジ', 'カラー バーン',
  'ハード ライト', 'ソフト ライト', '差分', '除外',
  '加算', '減算', 'リニア バーン', 'リニア ライト',
  'ビビッド ライト', 'ピン ライト', '色相', '彩度',
  'カラー', '明度',
];
