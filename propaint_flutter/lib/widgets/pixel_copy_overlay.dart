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
  final double _rotation = 0.0;

  // ドラッグ開始時の位置・サイズ
  late double _dragStartX;
  late double _dragStartY;
  late double _dragStartWidth;
  late double _dragStartHeight;

  // ドラッグ中のハンドル位置
  String? _activeHandle; // null, 'center', 'tl', 'tr', 'br', 'bl', 't', 'r', 'b', 'l'

  @override
  void initState() {
    super.initState();
    _x = widget.left.toDouble();
    _y = widget.top.toDouble();
    _width = widget.width.toDouble();
    _height = widget.height.toDouble();
  }

  void _startDrag(String handle, Offset offset) {
    setState(() {
      _activeHandle = handle;
      _dragStartX = _x;
      _dragStartY = _y;
      _dragStartWidth = _width;
      _dragStartHeight = _height;
    });
  }

  void _updateDrag(Offset delta) {
    if (_activeHandle == null) return;

    setState(() {
      if (_activeHandle == 'center') {
        // 中央をドラッグ: 移動
        _x = _dragStartX + delta.dx;
        _y = _dragStartY + delta.dy;
      } else {
        // ハンドルをドラッグ: スケール変更
        // 簡略実装：水平・垂直のみ対応
        double newWidth = _dragStartWidth;
        double newHeight = _dragStartHeight;

        if (_activeHandle!.contains('l')) {
          newWidth = _dragStartWidth - delta.dx;
          if (newWidth < 20) newWidth = 20;
        } else if (_activeHandle!.contains('r')) {
          newWidth = _dragStartWidth + delta.dx;
          if (newWidth < 20) newWidth = 20;
        }

        if (_activeHandle!.contains('t')) {
          newHeight = _dragStartHeight - delta.dy;
          if (newHeight < 20) newHeight = 20;
        } else if (_activeHandle!.contains('b')) {
          newHeight = _dragStartHeight + delta.dy;
          if (newHeight < 20) newHeight = 20;
        }

        _scaleX = newWidth / _dragStartWidth;
        _scaleY = newHeight / _dragStartHeight;
        _width = newWidth;
        _height = newHeight;
      }
    });
  }

  void _endDrag() {
    setState(() => _activeHandle = null);
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
            onPanStart: (details) => _startDrag('center', Offset.zero),
            onPanUpdate: (details) => _updateDrag(details.delta),
            onPanEnd: (_) => _endDrag(),
            child: MouseRegion(
              cursor: SystemMouseCursors.move,
              child: Container(
                decoration: BoxDecoration(
                  color: Colors.transparent,
                  border: Border.all(color: C.accent, width: 2, style: BorderStyle.solid),
                ),
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
    ];
  }

  Widget _buildHandle(String handle, double left, double top) {
    final isActive = _activeHandle == handle;

    return Positioned(
      left: left,
      top: top,
      child: GestureDetector(
        onPanStart: (_) => _startDrag(handle, Offset.zero),
        onPanUpdate: (details) => _updateDrag(details.delta),
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
    final paint = Paint()
      ..color = const Color(0xFF00FF00).withAlpha(200)
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke;

    final rect = Rect.fromLTWH(x, y, width, height);
    canvas.drawRect(rect, paint);

    // 中央に座標表示
    final textPainter = TextPainter(
      text: TextSpan(
        text: '${x.toInt()}, ${y.toInt()}',
        style: const TextStyle(color: Color(0xFF00FF00), fontSize: 12),
      ),
      textDirection: TextDirection.ltr,
    );
    textPainter.layout();
    textPainter.paint(canvas, Offset(x + 4, y + 4));
  }

  @override
  bool shouldRepaint(_PixelCopyPainter oldDelegate) => true;
}
