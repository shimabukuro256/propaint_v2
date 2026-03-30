import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/app_colors.dart';

/// パネル共通のカード外装
class PanelCard extends StatelessWidget {
  final double width;
  final double? maxHeight;
  final Widget child;

  const PanelCard({
    super.key,
    required this.width,
    this.maxHeight,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: width,
      constraints: maxHeight != null ? BoxConstraints(maxHeight: maxHeight!) : null,
      decoration: BoxDecoration(
        color: C.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: C.border.withAlpha(100), width: 0.5),
        boxShadow: const [
          BoxShadow(color: Colors.black54, blurRadius: 16, offset: Offset(0, 4)),
        ],
      ),
      child: child,
    );
  }
}

/// パネル内の labeled slider
///
/// [curve] で非線形マッピングを制御。
///  - 1.0 = リニア
///  - 2.0 = 二乗カーブ (小さい値付近の微調整が容易)
///  - 3.0 = 三乗カーブ (広い範囲のスライダ向け)
class PanelSlider extends StatelessWidget {
  final String label;
  final String value;
  final double current;
  final double min;
  final double max;
  final ValueChanged<double> onChanged;
  final int? divisions;
  final double curve;

  const PanelSlider({
    super.key,
    required this.label,
    required this.value,
    required this.current,
    this.min = 0,
    this.max = 1,
    required this.onChanged,
    this.divisions,
    this.curve = 1.0,
  });

  // 実値 → スライダ位置 (0..1)
  double _toSlider(double v) {
    if (curve == 1.0) return v;
    final t = ((v - min) / (max - min)).clamp(0.0, 1.0);
    return math.pow(t, 1.0 / curve).toDouble();
  }

  // スライダ位置 (0..1) → 実値
  double _fromSlider(double s) {
    if (curve == 1.0) return s;
    final t = math.pow(s.clamp(0.0, 1.0), curve).toDouble();
    return min + (max - min) * t;
  }

  @override
  Widget build(BuildContext context) {
    final sliderValue = curve == 1.0
        ? current.clamp(min, max)
        : _toSlider(current.clamp(min, max));
    final sliderMin = curve == 1.0 ? min : 0.0;
    final sliderMax = curve == 1.0 ? max : 1.0;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      child: Row(
        children: [
          SizedBox(
            width: 72,
            child: Text(label, style: const TextStyle(color: C.textSecondary, fontSize: 12)),
          ),
          Expanded(
            child: SliderTheme(
              data: SliderTheme.of(context).copyWith(
                trackHeight: 2.5,
                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                overlayShape: const RoundSliderOverlayShape(overlayRadius: 14),
              ),
              child: Slider(
                value: sliderValue,
                min: sliderMin,
                max: sliderMax,
                divisions: divisions,
                onChanged: (v) {
                  onChanged(curve == 1.0 ? v : _fromSlider(v));
                },
              ),
            ),
          ),
          SizedBox(
            width: 44,
            child: Text(
              value,
              textAlign: TextAlign.right,
              style: const TextStyle(color: C.textPrimary, fontSize: 12, fontFeatures: [FontFeature.tabularFigures()]),
            ),
          ),
        ],
      ),
    );
  }
}
