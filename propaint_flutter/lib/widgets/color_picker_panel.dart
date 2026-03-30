import 'package:flutter/material.dart';

import '../models/paint_state.dart';
import '../services/paint_channel.dart';
import '../theme/app_colors.dart';
import 'panel_card.dart';

int _colorToArgb(Color c) =>
    (0xFF << 24) |
    ((c.r * 255).round().clamp(0, 255) << 16) |
    ((c.g * 255).round().clamp(0, 255) << 8) |
    (c.b * 255).round().clamp(0, 255);

int _r(Color c) => (c.r * 255).round().clamp(0, 255);
int _g(Color c) => (c.g * 255).round().clamp(0, 255);
int _b(Color c) => (c.b * 255).round().clamp(0, 255);

class ColorPickerPanel extends StatefulWidget {
  final PaintState state;
  final PaintChannel channel;

  const ColorPickerPanel({super.key, required this.state, required this.channel});

  @override
  State<ColorPickerPanel> createState() => _ColorPickerPanelState();
}

class _ColorPickerPanelState extends State<ColorPickerPanel> {
  late double _hue;
  late double _saturation;
  late double _value;

  @override
  void initState() {
    super.initState();
    _syncFromState();
  }

  @override
  void didUpdateWidget(ColorPickerPanel old) {
    super.didUpdateWidget(old);
    if (old.state.currentColor != widget.state.currentColor) {
      _syncFromState();
    }
  }

  void _syncFromState() {
    final c = Color(widget.state.currentColor);
    final hsv = HSVColor.fromColor(c);
    _hue = hsv.hue;
    _saturation = hsv.saturation;
    _value = hsv.value;
  }

  void _commitColor() {
    final c = HSVColor.fromAHSV(1, _hue, _saturation, _value).toColor();
    final argb = _colorToArgb(c);
    widget.channel.setColor(argb);
    widget.channel.commitColor();
  }

  void _updateSV(Offset local, Size size) {
    setState(() {
      _saturation = (local.dx / size.width).clamp(0, 1);
      _value = 1.0 - (local.dy / size.height).clamp(0, 1);
    });
    final c = HSVColor.fromAHSV(1, _hue, _saturation, _value).toColor();
    final argb = _colorToArgb(c);
    widget.channel.setColor(argb);
  }

  void _updateHue(double dx, double width) {
    setState(() => _hue = (dx / width).clamp(0, 1) * 360);
    final c = HSVColor.fromAHSV(1, _hue, _saturation, _value).toColor();
    final argb = _colorToArgb(c);
    widget.channel.setColor(argb);
  }

  @override
  Widget build(BuildContext context) {
    final currentColor = HSVColor.fromAHSV(1, _hue, _saturation, _value).toColor();

    return PanelCard(
      width: 300,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // SV 矩形
            _SVRect(
              hue: _hue,
              saturation: _saturation,
              value: _value,
              onUpdate: _updateSV,
              onEnd: _commitColor,
            ),

            const SizedBox(height: 10),

            // Hue スライダー
            _HueBar(
              hue: _hue,
              onUpdate: _updateHue,
              onEnd: _commitColor,
            ),

            const SizedBox(height: 10),

            // 現在色 + Hex
            Row(
              children: [
                Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    color: currentColor,
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: C.border, width: 1),
                  ),
                ),
                const SizedBox(width: 10),
                Text(
                  '#${_r(currentColor).toRadixString(16).padLeft(2, '0')}'
                  '${_g(currentColor).toRadixString(16).padLeft(2, '0')}'
                  '${_b(currentColor).toRadixString(16).padLeft(2, '0')}'
                      .toUpperCase(),
                  style: const TextStyle(
                    color: C.textPrimary,
                    fontSize: 13,
                    fontFamily: 'monospace',
                  ),
                ),
              ],
            ),

            // カラー履歴
            if (widget.state.colorHistory.isNotEmpty) ...[
              const SizedBox(height: 10),
              const Align(
                alignment: Alignment.centerLeft,
                child: Text('カラー履歴', style: TextStyle(color: C.textSecondary, fontSize: 11)),
              ),
              const SizedBox(height: 6),
              _ColorHistory(
                colors: widget.state.colorHistory,
                currentColor: widget.state.currentColor,
                onSelect: (argb) {
                  widget.channel.setColor(argb);
                  final c = Color(argb);
                  final hsv = HSVColor.fromColor(c);
                  setState(() {
                    _hue = hsv.hue;
                    _saturation = hsv.saturation;
                    _value = hsv.value;
                  });
                },
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _SVRect extends StatelessWidget {
  final double hue, saturation, value;
  final void Function(Offset local, Size size) onUpdate;
  final VoidCallback onEnd;

  const _SVRect({
    required this.hue,
    required this.saturation,
    required this.value,
    required this.onUpdate,
    required this.onEnd,
  });

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final w = constraints.maxWidth;
      const h = 160.0;
      return GestureDetector(
        onPanDown: (d) => onUpdate(d.localPosition, Size(w, h)),
        onPanUpdate: (d) => onUpdate(d.localPosition, Size(w, h)),
        onPanEnd: (_) => onEnd(),
        child: SizedBox(
          width: w,
          height: h,
          child: CustomPaint(
            painter: _SVPainter(hue: hue),
            child: Stack(
              children: [
                Positioned(
                  left: saturation * w - 8,
                  top: (1 - value) * h - 8,
                  child: Container(
                    width: 16,
                    height: 16,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.white, width: 2),
                      boxShadow: const [BoxShadow(color: Colors.black54, blurRadius: 3)],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    });
  }
}

class _SVPainter extends CustomPainter {
  final double hue;
  _SVPainter({required this.hue});

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;

    // 色相ベース
    canvas.drawRect(
      rect,
      Paint()..color = HSVColor.fromAHSV(1, hue, 1, 1).toColor(),
    );

    // 白 → 透明 (水平グラデーション = 彩度)
    canvas.drawRect(
      rect,
      Paint()
        ..shader = const LinearGradient(
          colors: [Colors.white, Colors.transparent],
        ).createShader(rect),
    );

    // 透明 → 黒 (垂直グラデーション = 明度)
    canvas.drawRect(
      rect,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Colors.transparent, Colors.black],
        ).createShader(rect),
    );
  }

  @override
  bool shouldRepaint(_SVPainter old) => old.hue != hue;
}

class _HueBar extends StatelessWidget {
  final double hue;
  final void Function(double dx, double width) onUpdate;
  final VoidCallback onEnd;

  const _HueBar({required this.hue, required this.onUpdate, required this.onEnd});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final w = constraints.maxWidth;
      return GestureDetector(
        onPanDown: (d) => onUpdate(d.localPosition.dx, w),
        onPanUpdate: (d) => onUpdate(d.localPosition.dx, w),
        onPanEnd: (_) => onEnd(),
        child: SizedBox(
          width: w,
          height: 24,
          child: CustomPaint(
            painter: _HuePainter(),
            child: Stack(
              children: [
                Positioned(
                  left: (hue / 360) * w - 6,
                  top: 0,
                  child: Container(
                    width: 12,
                    height: 24,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(3),
                      border: Border.all(color: Colors.white, width: 2),
                      boxShadow: const [BoxShadow(color: Colors.black38, blurRadius: 2)],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    });
  }
}

class _HuePainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final colors = List.generate(
      7,
      (i) => HSVColor.fromAHSV(1, i * 60.0, 1, 1).toColor(),
    );
    canvas.drawRRect(
      RRect.fromRectAndRadius(rect, const Radius.circular(4)),
      Paint()
        ..shader = LinearGradient(colors: colors).createShader(rect),
    );
  }

  @override
  bool shouldRepaint(_HuePainter old) => false;
}

class _ColorHistory extends StatelessWidget {
  final List<int> colors;
  final int currentColor;
  final ValueChanged<int> onSelect;

  const _ColorHistory({
    required this.colors,
    required this.currentColor,
    required this.onSelect,
  });

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 4,
      runSpacing: 4,
      children: colors.map((argb) {
        final selected = argb == currentColor;
        return GestureDetector(
          onTap: () => onSelect(argb),
          child: Container(
            width: 26,
            height: 26,
            decoration: BoxDecoration(
              color: Color(argb),
              borderRadius: BorderRadius.circular(4),
              border: Border.all(
                color: selected ? Colors.white : C.border,
                width: selected ? 2 : 1,
              ),
            ),
          ),
        );
      }).toList(),
    );
  }
}
