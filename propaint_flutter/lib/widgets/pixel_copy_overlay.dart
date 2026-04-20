import 'dart:math' as math;
import 'package:flutter/material.dart';

import '../services/paint_channel.dart';
import '../theme/app_colors.dart';

/// ピクセルコピー変形オーバーレイ（Word/Excel風）
/// 選択範囲のピクセルをコピーし、ハンドル付きの矩形で移動・スケール変形できる
class PixelCopyOverlay extends StatefulWidget {
  final int left;
  final int top;
  final int width;
  final int height;
  final PaintChannel channel;
  final VoidCallback onClose;

  const PixelCopyOverlay({
    super.key,
    required this.left,
    required this.top,
    required this.width,
    required this.height,
    required this.channel,
    required this.onClose,
  });

  @override
  State<PixelCopyOverlay> createState() => _PixelCopyOverlayState();
}

class _PixelCopyOverlayState extends State<PixelCopyOverlay> {
  late double _x;
  late double _y;
  late double _width;
  late double _height;
  late double _scaleX = 1.0;
  late double _scaleY = 1.0;
  double _rotation = 0.0; // ラジアン

  // ドラッグ開始時の位置・サイズ
  late double _dragStartX;
  late double _dragStartY;
  late double _dragStartWidth;
  late double _dragStartHeight;
  double _dragStartRotation = 0.0;
  double _initialDragAngle = 0.0; // ドラッグ開始時の角度
  Offset? _rotationCenter;

  // ドラッグ中のハンドル位置
  String? _activeHandle; // null, 'center', 'tl', 'tr', 'br', 'bl', 't', 'r', 'b', 'l', 'rotate'

  // Overlay 外側の Transform（main.dart で付与）を通した doc 座標変換で
  // globalPosition を doc 座標に戻すための Stack 参照。
  final GlobalKey _stackKey = GlobalKey();

  /// globalPosition (logical screen 座標) を Overlay 内部の doc 座標に変換。
  /// main.dart の Transform 効果をキャンセルする役割。
  Offset _globalToDoc(Offset globalPosition) {
    final rb = _stackKey.currentContext?.findRenderObject() as RenderBox?;
    if (rb == null) return globalPosition;
    return rb.globalToLocal(globalPosition);
  }

  @override
  void initState() {
    super.initState();
    _x = widget.left.toDouble();
    _y = widget.top.toDouble();
    _width = widget.width.toDouble();
    _height = widget.height.toDouble();
  }

  void _startDrag(String handle, Offset globalPosition) {
    setState(() {
      _activeHandle = handle;
      _dragStartX = _x;
      _dragStartY = _y;
      _dragStartWidth = _width;
      _dragStartHeight = _height;
      if (handle == 'rotate') {
        _dragStartRotation = _rotation;
        // doc 座標で回転中心を保持（globalPosition も毎回 doc 座標に変換して比較）
        _rotationCenter = Offset(_x + _width / 2, _y + _height / 2);
        _initialDragAngle = (_globalToDoc(globalPosition) - _rotationCenter!).direction;
      }
    });
  }

  void _updateDrag(Offset delta, [Offset? globalPosition]) {
    if (_activeHandle == null) return;

    setState(() {
      if (_activeHandle == 'center') {
        // 中央をドラッグ: 移動
        _x = _dragStartX + delta.dx;
        _y = _dragStartY + delta.dy;
      } else if (_activeHandle == 'rotate' && _rotationCenter != null && globalPosition != null) {
        // 回転ハンドル: globalPosition を doc 座標に戻してから中心との角度を算出
        final center = _rotationCenter!;
        final currentAngle = (_globalToDoc(globalPosition) - center).direction;
        final angleDelta = currentAngle - _initialDragAngle;
        _rotation = _dragStartRotation + angleDelta;
      } else {
        // ハンドルをドラッグ: スケール変更
        double newWidth = _dragStartWidth;
        double newHeight = _dragStartHeight;
        double newX = _dragStartX;
        double newY = _dragStartY;

        if (_activeHandle!.contains('l')) {
          newWidth = _dragStartWidth - delta.dx;
          if (newWidth < 20) newWidth = 20;
          newX = _dragStartX + (_dragStartWidth - newWidth);
        } else if (_activeHandle!.contains('r')) {
          newWidth = _dragStartWidth + delta.dx;
          if (newWidth < 20) newWidth = 20;
        }

        if (_activeHandle!.contains('t')) {
          newHeight = _dragStartHeight - delta.dy;
          if (newHeight < 20) newHeight = 20;
          newY = _dragStartY + (_dragStartHeight - newHeight);
        } else if (_activeHandle!.contains('b')) {
          newHeight = _dragStartHeight + delta.dy;
          if (newHeight < 20) newHeight = 20;
        }

        _x = newX;
        _y = newY;
        _scaleX = newWidth / widget.width.toDouble();
        _scaleY = newHeight / widget.height.toDouble();
        _width = newWidth;
        _height = newHeight;
      }
    });
  }

  void _endDrag() {
    setState(() {
      _activeHandle = null;
    });
  }

  void _confirmCopy() {
    widget.channel.applyPixelCopy(
      x: _x.toInt(),
      y: _y.toInt(),
      scaleX: _scaleX,
      scaleY: _scaleY,
      rotation: _rotation,
    );
    widget.onClose();
  }

  void _cancelCopy() {
    widget.channel.cancelPixelCopy();
    widget.onClose();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      key: _stackKey,
      children: [
        // 矩形フレーム + ハンドル
        CustomPaint(
          painter: _PixelCopyPainter(
            x: _x,
            y: _y,
            width: _width,
            height: _height,
            rotation: _rotation,
            scaleX: _scaleX,
            scaleY: _scaleY,
            activeHandle: _activeHandle,
          ),
          size: Size.infinite,
        ),

        // 中央エリア: ドラッグで移動
        Positioned(
          left: _x,
          top: _y,
          width: _width,
          height: _height,
          child: GestureDetector(
            onPanStart: (details) => _startDrag('center', details.globalPosition),
            onPanUpdate: (details) => _updateDrag(details.delta, details.globalPosition),
            onPanEnd: (_) => _endDrag(),
            child: MouseRegion(
              cursor: SystemMouseCursors.move,
              child: Container(
                color: Colors.transparent,
              ),
            ),
          ),
        ),

        // ハンドル（8個）
        ..._buildHandles(),

        // 確定・キャンセルボタン
        Positioned(
          left: _x + _width / 2 - 50,
          top: _y + _height + 12,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              ElevatedButton.icon(
                onPressed: _confirmCopy,
                icon: const Icon(Icons.check, size: 16),
                label: const Text('確定'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.green.shade700,
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                ),
              ),
              const SizedBox(width: 8),
              ElevatedButton.icon(
                onPressed: _cancelCopy,
                icon: const Icon(Icons.close, size: 16),
                label: const Text('キャンセル'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.red.shade700,
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  List<Widget> _buildHandles() {
    const handleSize = 12.0;
    const handleMargin = -handleSize / 2;
    const rotateHandleDistance = 30.0; // 回転ハンドルまでの距離

    return [
      // 4角のハンドル
      _buildHandle('tl', _x + handleMargin, _y + handleMargin),
      _buildHandle('tr', _x + _width + handleMargin, _y + handleMargin),
      _buildHandle('br', _x + _width + handleMargin, _y + _height + handleMargin),
      _buildHandle('bl', _x + handleMargin, _y + _height + handleMargin),

      // 4辺のハンドル
      _buildHandle('t', _x + _width / 2 + handleMargin, _y + handleMargin),
      _buildHandle('r', _x + _width + handleMargin, _y + _height / 2 + handleMargin),
      _buildHandle('b', _x + _width / 2 + handleMargin, _y + _height + handleMargin),
      _buildHandle('l', _x + handleMargin, _y + _height / 2 + handleMargin),

      // 回転ハンドル（上部中央から伸びるアンテナ）
      _buildRotateHandle(
        _x + _width / 2 + handleMargin,
        _y - rotateHandleDistance + handleMargin,
        rotateHandleDistance,
      ),
    ];
  }

  Widget _buildHandle(String handle, double left, double top) {
    final isActive = _activeHandle == handle;

    return Positioned(
      left: left,
      top: top,
      child: GestureDetector(
        onPanStart: (details) => _startDrag(handle, details.globalPosition),
        onPanUpdate: (details) => _updateDrag(details.delta, details.globalPosition),
        onPanEnd: (_) => _endDrag(),
        child: Container(
          width: 12,
          height: 12,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: isActive ? Colors.yellow : C.accent,
            border: Border.all(color: Colors.white, width: 1),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withAlpha(100),
                blurRadius: 4,
                offset: const Offset(0, 2),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 回転ハンドル（アンテナスタイル）
  Widget _buildRotateHandle(double left, double top, double lineLength) {
    final isActive = _activeHandle == 'rotate';
    const handleSize = 12.0;

    return Positioned(
      left: left,
      top: top,
      child: GestureDetector(
        onPanStart: (details) => _startDrag('rotate', details.globalPosition),
        onPanUpdate: (details) => _updateDrag(details.delta, details.globalPosition),
        onPanEnd: (_) => _endDrag(),
        child: MouseRegion(
          cursor: SystemMouseCursors.click,
          child: SizedBox(
            width: handleSize,
            height: lineLength + handleSize,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                // 回転ハンドルの円
                Container(
                  width: handleSize,
                  height: handleSize,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: isActive ? Colors.yellow : Colors.orange,
                    border: Border.all(color: Colors.white, width: 1.5),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withAlpha(100),
                        blurRadius: 4,
                        offset: const Offset(0, 2),
                      ),
                    ],
                  ),
                  child: const Icon(Icons.rotate_right, size: 8, color: Colors.white),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// PixelCopy フレームを描画
class _PixelCopyPainter extends CustomPainter {
  final double x;
  final double y;
  final double width;
  final double height;
  final double rotation;
  final double scaleX;
  final double scaleY;
  final String? activeHandle;

  _PixelCopyPainter({
    required this.x,
    required this.y,
    required this.width,
    required this.height,
    required this.rotation,
    required this.scaleX,
    required this.scaleY,
    required this.activeHandle,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(x + width / 2, y + height / 2);

    // 枠線ペイント
    final framePaint = Paint()
      ..color = C.accent
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke;

    // ガイド線ペイント（薄い点線風）
    final guidePaint = Paint()
      ..color = C.accent.withAlpha(100)
      ..strokeWidth = 1
      ..style = PaintingStyle.stroke;

    // 回転ハンドル接続線ペイント
    final rotatePaint = Paint()
      ..color = Colors.orange
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke;

    final rect = Rect.fromLTWH(x, y, width, height);

    // 枠線
    canvas.drawRect(rect, framePaint);

    // ガイド線: 中央十字
    canvas.drawLine(
      Offset(center.dx, y),
      Offset(center.dx, y + height),
      guidePaint,
    );
    canvas.drawLine(
      Offset(x, center.dy),
      Offset(x + width, center.dy),
      guidePaint,
    );

    // ガイド線: 対角線
    canvas.drawLine(Offset(x, y), Offset(x + width, y + height), guidePaint);
    canvas.drawLine(Offset(x + width, y), Offset(x, y + height), guidePaint);

    // 回転ハンドルへの接続線
    const rotateHandleDistance = 30.0;
    canvas.drawLine(
      Offset(center.dx, y),
      Offset(center.dx, y - rotateHandleDistance),
      rotatePaint,
    );

    // 中央に十字マーク
    final centerMarkPaint = Paint()
      ..color = C.accent
      ..strokeWidth = 1.5
      ..style = PaintingStyle.stroke;
    const markSize = 8.0;
    canvas.drawLine(
      Offset(center.dx - markSize, center.dy),
      Offset(center.dx + markSize, center.dy),
      centerMarkPaint,
    );
    canvas.drawLine(
      Offset(center.dx, center.dy - markSize),
      Offset(center.dx, center.dy + markSize),
      centerMarkPaint,
    );

    // 情報表示（左上）
    final infoText = 'X:${x.toInt()} Y:${y.toInt()} W:${width.toInt()} H:${height.toInt()}';
    final rotationDeg = (rotation * 180 / math.pi).toStringAsFixed(1);
    final scaleText = 'Scale: ${(scaleX * 100).toInt()}% x ${(scaleY * 100).toInt()}%';
    final rotText = 'Rot: $rotationDeg°';

    final textPainter = TextPainter(
      text: TextSpan(
        text: '$infoText\n$scaleText  $rotText',
        style: TextStyle(
          color: C.accent,
          fontSize: 11,
          backgroundColor: Colors.black.withAlpha(150),
        ),
      ),
      textDirection: TextDirection.ltr,
    );
    textPainter.layout();
    textPainter.paint(canvas, Offset(x + 4, y - textPainter.height - 4));
  }

  @override
  bool shouldRepaint(_PixelCopyPainter oldDelegate) => true;
}
