import 'package:flutter/material.dart';

import '../services/paint_channel.dart';
import '../theme/app_colors.dart';
import 'panel_card.dart';

/// 塗りつぶし（バケツ）プロパティパネル。
/// しきい値（tolerance 0..255）のスライダを表示。
/// ツールバーからトグルで開閉する。
class FloodFillPanel extends StatefulWidget {
  final PaintChannel channel;
  final int tolerance;
  final ValueChanged<int> onToleranceChanged;
  final VoidCallback onClose;

  const FloodFillPanel({
    super.key,
    required this.channel,
    required this.tolerance,
    required this.onToleranceChanged,
    required this.onClose,
  });

  @override
  State<FloodFillPanel> createState() => _FloodFillPanelState();
}

class _FloodFillPanelState extends State<FloodFillPanel> {
  late int _tolerance = widget.tolerance;

  @override
  Widget build(BuildContext context) {
    return PanelCard(
      width: 280,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 10, 8, 6),
            child: Row(
              children: [
                const Icon(Icons.format_color_fill_rounded, size: 16, color: C.accent),
                const SizedBox(width: 8),
                const Text('塗りつぶし', style: TextStyle(color: C.textPrimary, fontSize: 14, fontWeight: FontWeight.w600)),
                const Spacer(),
                IconButton(
                  onPressed: widget.onClose,
                  icon: const Icon(Icons.close_rounded, size: 18, color: C.textSecondary),
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(minWidth: 32, minHeight: 32),
                ),
              ],
            ),
          ),

          const Padding(
            padding: EdgeInsets.fromLTRB(14, 0, 14, 6),
            child: Text(
              'キャンバスをタップで塗りつぶし。\nしきい値が大きいほど広範囲を塗ります。',
              style: TextStyle(color: C.textSecondary, fontSize: 11),
            ),
          ),

          PanelSlider(
            label: 'しきい値',
            value: '$_tolerance',
            current: _tolerance.toDouble(),
            min: 0,
            max: 255,
            divisions: 255,
            onChanged: (v) {
              final iv = v.round();
              setState(() => _tolerance = iv);
              widget.onToleranceChanged(iv);
            },
          ),

          const SizedBox(height: 6),

          Padding(
            padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
            child: SizedBox(
              width: double.infinity,
              height: 32,
              child: OutlinedButton.icon(
                onPressed: () {
                  widget.channel.setToolMode('Draw');
                  widget.onClose();
                },
                icon: const Icon(Icons.edit_rounded, size: 16),
                label: const Text('描画モードに戻る', style: TextStyle(fontSize: 12)),
                style: OutlinedButton.styleFrom(
                  foregroundColor: C.textPrimary,
                  side: const BorderSide(color: C.border),
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
