import 'package:flutter/material.dart';

import '../services/paint_channel.dart';
import '../theme/app_colors.dart';
import 'panel_card.dart';

/// 変形ツールパネル
class TransformPanel extends StatelessWidget {
  final PaintChannel channel;

  const TransformPanel({super.key, required this.channel});

  @override
  Widget build(BuildContext context) {
    return PanelCard(
      width: 240,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(14, 10, 14, 6),
            child: Text('変形', style: TextStyle(color: C.textPrimary, fontSize: 14, fontWeight: FontWeight.w600)),
          ),

          // 反転
          const Padding(
            padding: EdgeInsets.fromLTRB(14, 4, 14, 2),
            child: Text('反転', style: TextStyle(color: C.textSecondary, fontSize: 11)),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10),
            child: Row(
              children: [
                Expanded(
                  child: _TransformButton(
                    icon: Icons.flip_rounded,
                    label: '左右',
                    onTap: channel.flipLayerH,
                  ),
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: _TransformButton(
                    icon: Icons.flip_rounded,
                    label: '上下',
                    onTap: channel.flipLayerV,
                    rotateIcon: true,
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 8),

          // 回転
          const Padding(
            padding: EdgeInsets.fromLTRB(14, 0, 14, 2),
            child: Text('回転', style: TextStyle(color: C.textSecondary, fontSize: 11)),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10),
            child: Row(
              children: [
                Expanded(
                  child: _TransformButton(
                    icon: Icons.rotate_90_degrees_cw_rounded,
                    label: '90° 右',
                    onTap: channel.rotateLayer90CW,
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 8),

          // 拡大縮小
          const Padding(
            padding: EdgeInsets.fromLTRB(14, 0, 14, 2),
            child: Text('拡大縮小', style: TextStyle(color: C.textSecondary, fontSize: 11)),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10),
            child: Row(
              children: [
                Expanded(
                  child: _TransformButton(
                    icon: Icons.zoom_in_rounded,
                    label: '150%',
                    onTap: () => channel.transformLayer(scaleX: 1.5, scaleY: 1.5),
                  ),
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: _TransformButton(
                    icon: Icons.zoom_out_rounded,
                    label: '50%',
                    onTap: () => channel.transformLayer(scaleX: 0.5, scaleY: 0.5),
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 8),

          // ディストート・ワープ
          const Padding(
            padding: EdgeInsets.fromLTRB(14, 0, 14, 2),
            child: Text('変形モード', style: TextStyle(color: C.textSecondary, fontSize: 11)),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10),
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: _TransformButton(
                        icon: Icons.open_with_rounded,
                        label: 'ユニフォーム',
                        onTap: () => channel.transformLayer(scaleX: 1.0, scaleY: 1.0),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Row(
                  children: [
                    Expanded(
                      child: _TransformButton(
                        icon: Icons.crop_free_rounded,
                        label: 'リキファイ',
                        onTap: () {
                          channel.setToolMode('Liquify');
                          channel.beginLiquify();
                        },
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),

          const SizedBox(height: 10),
        ],
      ),
    );
  }
}

class _TransformButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final bool rotateIcon;

  const _TransformButton({
    required this.icon,
    required this.label,
    required this.onTap,
    this.rotateIcon = false,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: C.surface,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Transform.rotate(
                angle: rotateIcon ? 1.5708 : 0,
                child: Icon(icon, size: 18, color: C.iconDefault),
              ),
              const SizedBox(width: 6),
              Text(label, style: const TextStyle(color: C.textPrimary, fontSize: 12)),
            ],
          ),
        ),
      ),
    );
  }
}
