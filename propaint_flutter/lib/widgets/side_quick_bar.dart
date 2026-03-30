import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/app_colors.dart';

/// 左サイドのブラシサイズ・不透明度クイック調整バー + モディファイアボタン
class SideQuickBar extends StatelessWidget {
  final double brushSize;
  final double brushOpacity;
  final ValueChanged<double> onSizeChanged;
  final ValueChanged<double> onOpacityChanged;
  final VoidCallback? onEyedropperToggle;
  final bool isEyedropperActive;

  const SideQuickBar({
    super.key,
    required this.brushSize,
    required this.brushOpacity,
    required this.onSizeChanged,
    required this.onOpacityChanged,
    this.onEyedropperToggle,
    this.isEyedropperActive = false,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 44,
      padding: const EdgeInsets.symmetric(vertical: 10),
      decoration: BoxDecoration(
        color: C.toolbar,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: C.border.withAlpha(80), width: 0.5),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // サイズ
          Text(
            '${brushSize.round()}',
            style: const TextStyle(
              color: C.textPrimary,
              fontSize: 11,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
          const SizedBox(height: 2),
          const Icon(Icons.circle, size: 8, color: C.textSecondary),
          _VerticalSlider(
            value: brushSize,
            min: 1,
            max: 500,
            height: 110,
            curve: 2.5,
            onChanged: onSizeChanged,
          ),

          const SizedBox(height: 12),

          // 不透明度
          Text(
            '${(brushOpacity * 100).round()}%',
            style: const TextStyle(
              color: C.textPrimary,
              fontSize: 11,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
          const SizedBox(height: 2),
          const Icon(Icons.opacity_rounded, size: 12, color: C.textSecondary),
          _VerticalSlider(
            value: brushOpacity,
            min: 0,
            max: 1,
            height: 110,
            curve: 1.5,
            onChanged: onOpacityChanged,
          ),

          // モディファイアボタン
          const SizedBox(height: 10),
          Container(
            width: 28,
            height: 1,
            color: C.border.withAlpha(100),
          ),
          const SizedBox(height: 8),

          // スポイトトグル
          _ToggleButton(
            icon: Icons.colorize_rounded,
            tooltip: 'スポイト',
            size: 40,
            isActive: isEyedropperActive,
            onTap: onEyedropperToggle,
          ),
        ],
      ),
    );
  }
}

/// トグル式ボタン: タップで ON/OFF 切替
class _ToggleButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final double size;
  final bool isActive;
  final VoidCallback? onTap;

  const _ToggleButton({
    required this.icon,
    required this.tooltip,
    this.size = 32,
    this.isActive = false,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 100),
          width: size,
          height: size,
          decoration: BoxDecoration(
            color: isActive ? C.accent.withAlpha(40) : Colors.transparent,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: isActive ? C.accent : C.border.withAlpha(80),
              width: 1,
            ),
          ),
          child: Icon(
            icon,
            size: 16,
            color: isActive ? C.accent : C.textSecondary,
          ),
        ),
      ),
    );
  }
}

class _VerticalSlider extends StatelessWidget {
  final double value;
  final double min;
  final double max;
  final double height;
  final double curve;
  final ValueChanged<double> onChanged;

  const _VerticalSlider({
    required this.value,
    required this.min,
    required this.max,
    required this.height,
    this.curve = 1.0,
    required this.onChanged,
  });

  double _toSlider(double v) {
    final t = ((v - min) / (max - min)).clamp(0.0, 1.0);
    return math.pow(t, 1.0 / curve).toDouble();
  }

  double _fromSlider(double s) {
    final t = math.pow(s.clamp(0.0, 1.0), curve).toDouble();
    return min + (max - min) * t;
  }

  @override
  Widget build(BuildContext context) {
    final sliderVal = curve == 1.0 ? value.clamp(min, max) : _toSlider(value.clamp(min, max));
    final sMin = curve == 1.0 ? min : 0.0;
    final sMax = curve == 1.0 ? max : 1.0;

    return SizedBox(
      height: height,
      width: 36,
      child: RotatedBox(
        quarterTurns: 3, // 縦方向に回転 (下が min, 上が max)
        child: SliderTheme(
          data: SliderTheme.of(context).copyWith(
            trackHeight: 3,
            thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 7),
            overlayShape: const RoundSliderOverlayShape(overlayRadius: 14),
            activeTrackColor: C.accent,
            thumbColor: C.accent,
            inactiveTrackColor: C.border,
          ),
          child: Slider(
            value: sliderVal,
            min: sMin,
            max: sMax,
            onChanged: (v) => onChanged(curve == 1.0 ? v : _fromSlider(v)),
          ),
        ),
      ),
    );
  }
}
