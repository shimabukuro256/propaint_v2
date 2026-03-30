import 'package:flutter/material.dart';

import '../models/paint_state.dart';
import '../services/paint_channel.dart';
import '../theme/app_colors.dart';
import 'panel_card.dart';

class BrushPanel extends StatelessWidget {
  final PaintState state;
  final PaintChannel channel;

  const BrushPanel({super.key, required this.state, required this.channel});

  @override
  Widget build(BuildContext context) {
    final info = brushTypeInfoFor(state.brushType);

    return PanelCard(
      width: 310,
      maxHeight: 480,
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            // ブラシ種別グリッド
            _BrushTypeGrid(current: state.brushType, channel: channel),

            const Divider(color: C.border, height: 16, indent: 12, endIndent: 12),

            // サイズ
            PanelSlider(
              label: 'サイズ',
              value: '${state.brushSize.round()}px',
              current: state.brushSize,
              min: 1,
              max: 2000,
              curve: 3.0,
              onChanged: (v) => channel.setBrushSize(v),
            ),

            // 不透明度 (対応ブラシのみ)
            if (info.supportsOpacity)
              PanelSlider(
                label: '不透明度',
                value: '${(state.brushOpacity * 100).round()}%',
                current: state.brushOpacity,
                curve: 1.5,
                onChanged: (v) => channel.setBrushOpacity(v),
              ),

            // 硬さ
            PanelSlider(
              label: '硬さ',
              value: '${(state.brushHardness * 100).round()}%',
              current: state.brushHardness,
              curve: 1.5,
              onChanged: (v) => channel.setBrushHardness(v),
            ),

            // 濃度 (対応ブラシのみ)
            if (info.supportsDensity)
              PanelSlider(
                label: '濃度',
                value: '${(state.brushDensity * 100).round()}%',
                current: state.brushDensity,
                curve: 1.5,
                onChanged: (v) => channel.setBrushDensity(v),
              ),

            // 間隔
            PanelSlider(
              label: '間隔',
              value: '${(state.brushSpacing * 100).round()}%',
              current: state.brushSpacing,
              min: 0.01,
              max: 2.0,
              curve: 2.0,
              onChanged: (v) => channel.setBrushSpacing(v),
            ),

            // 手振れ補正
            PanelSlider(
              label: '手振れ補正',
              value: '${(state.stabilizer * 100).round()}%',
              current: state.stabilizer,
              curve: 1.5,
              onChanged: (v) => channel.setStabilizer(v),
            ),

            // ブラシ固有パラメータ
            if (info.hasColorStretch)
              PanelSlider(
                label: 'カラー伸び',
                value: '${(state.colorStretch * 100).round()}%',
                current: state.colorStretch,
                curve: 1.5,
                onChanged: (v) => channel.setColorStretch(v),
              ),

            if (info.hasWaterContent)
              PanelSlider(
                label: '水分量',
                value: '${(state.waterContent * 100).round()}%',
                current: state.waterContent,
                curve: 1.5,
                onChanged: (v) => channel.setWaterContent(v),
              ),

            if (info.hasBlurStrength)
              PanelSlider(
                label: 'ぼかし強度',
                value: '${(state.blurStrength * 100).round()}%',
                current: state.blurStrength,
                min: 0.05,
                max: 1.0,
                curve: 1.5,
                onChanged: (v) => channel.setBlurStrength(v),
              ),

            if (info.hasBlurPressureThreshold)
              PanelSlider(
                label: 'ぼかし筆圧',
                value: '${(state.blurPressureThreshold * 100).round()}%',
                current: state.blurPressureThreshold,
                curve: 1.5,
                onChanged: (v) => channel.setBlurPressureThreshold(v),
              ),

            const Divider(color: C.border, height: 16, indent: 12, endIndent: 12),

            // 筆圧設定
            _PressureSection(state: state, channel: channel),
          ],
        ),
      ),
    );
  }
}

class _BrushTypeGrid extends StatelessWidget {
  final String current;
  final PaintChannel channel;

  const _BrushTypeGrid({required this.current, required this.channel});

  static const _icons = {
    'Pencil': Icons.edit_rounded,
    'Fude': Icons.brush_rounded,
    'Watercolor': Icons.water_drop_rounded,
    'Airbrush': Icons.air_rounded,
    'Marker': Icons.square_rounded,
    'Eraser': Icons.auto_fix_high_rounded,
    'Blur': Icons.blur_on_rounded,
  };

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10),
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        children: kBrushTypes.map((b) {
          final selected = b.key == current;
          return Material(
            color: selected ? C.accentDim : C.surface,
            borderRadius: BorderRadius.circular(8),
            child: InkWell(
              onTap: () => channel.setBrushType(b.key),
              borderRadius: BorderRadius.circular(8),
              child: Container(
                width: 68,
                padding: const EdgeInsets.symmetric(vertical: 8),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(8),
                  border: selected ? Border.all(color: C.accent, width: 1.5) : null,
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      _icons[b.key] ?? Icons.brush,
                      size: 20,
                      color: selected ? C.accent : C.iconDefault,
                    ),
                    const SizedBox(height: 3),
                    Text(
                      b.displayName,
                      style: TextStyle(
                        fontSize: 10,
                        color: selected ? C.accent : C.textSecondary,
                        fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        }).toList(),
      ),
    );
  }
}

class _PressureSection extends StatelessWidget {
  final PaintState state;
  final PaintChannel channel;

  const _PressureSection({required this.state, required this.channel});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('筆圧', style: TextStyle(color: C.textSecondary, fontSize: 12)),
          const SizedBox(height: 4),
          Row(
            children: [
              _PressureChip(
                label: 'サイズ',
                enabled: state.pressureSizeEnabled,
                onTap: channel.togglePressureSize,
              ),
              const SizedBox(width: 8),
              _PressureChip(
                label: '不透明度',
                enabled: state.pressureOpacityEnabled,
                onTap: channel.togglePressureOpacity,
              ),
              const SizedBox(width: 8),
              _PressureChip(
                label: '濃度',
                enabled: state.pressureDensityEnabled,
                onTap: channel.togglePressureDensity,
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _PressureChip extends StatelessWidget {
  final String label;
  final bool enabled;
  final VoidCallback onTap;

  const _PressureChip({
    required this.label,
    required this.enabled,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: enabled ? C.accentDim : Colors.transparent,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
            color: enabled ? C.accent : C.border,
            width: enabled ? 1.5 : 1,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 11,
            color: enabled ? C.accent : C.textSecondary,
            fontWeight: enabled ? FontWeight.w600 : FontWeight.normal,
          ),
        ),
      ),
    );
  }
}
