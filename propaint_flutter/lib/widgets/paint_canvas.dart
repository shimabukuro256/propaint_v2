import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Android 側の PaintGlSurfaceView を PlatformView として表示する。
///
/// - Hybrid Composition (Flutter 3.x デフォルト) で GL 描画と互換
/// - EagerGestureRecognizer で全タッチイベントを Android View に透過
///   (Flutter のジェスチャーアリーナを経由しないため描画レイテンシなし)
class PaintCanvas extends StatelessWidget {
  const PaintCanvas({super.key});

  static const String viewType = 'paint-gl-canvas';

  @override
  Widget build(BuildContext context) {
    return AndroidView(
      viewType: viewType,
      creationParamsCodec: const StandardMessageCodec(),
      gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
        Factory<EagerGestureRecognizer>(EagerGestureRecognizer.new),
      },
    );
  }
}
