import 'package:flutter/material.dart';

import '../models/paint_state.dart';
import '../services/paint_channel.dart';
import '../theme/app_colors.dart';
import 'panel_card.dart';

/// 選択ツールパネル
class SelectionPanel extends StatelessWidget {
  final PaintState state;
  final PaintChannel channel;
  final VoidCallback? onClose;

  const SelectionPanel({super.key, required this.state, required this.channel, this.onClose});

  @override
  Widget build(BuildContext context) {
    final hasSelection = state.hasSelection;
    final toolMode = state.toolMode;

    return PanelCard(
      width: 260,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // ヘッダー（閉じるボタン付き）
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 10, 6, 6),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text('選択', style: TextStyle(color: C.textPrimary, fontSize: 14, fontWeight: FontWeight.w600)),
                SizedBox(
                  width: 28,
                  height: 28,
                  child: IconButton(
                    icon: const Icon(Icons.close, size: 16, color: C.iconDefault),
                    onPressed: onClose,
                    padding: EdgeInsets.zero,
                  ),
                ),
              ],
            ),
          ),

          // 選択ツール切替
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            child: Wrap(
              spacing: 4,
              runSpacing: 4,
              children: [
                _ToolChip(
                  icon: Icons.crop_square_rounded,
                  label: '矩形',
                  isActive: toolMode == 'SelectRect',
                  onTap: () => channel.setToolMode('SelectRect'),
                ),
                _ToolChip(
                  icon: Icons.circle_outlined,
                  label: '楕円',
                  isActive: toolMode == 'SelectEllipse',
                  onTap: () => channel.setToolMode('SelectEllipse'),
                ),
                _ToolChip(
                  icon: Icons.gesture_rounded,
                  label: 'なげなわ',
                  isActive: toolMode == 'SelectLasso',
                  onTap: () => channel.setToolMode('SelectLasso'),
                ),
                _ToolChip(
                  icon: Icons.auto_fix_high_rounded,
                  label: '自動選択',
                  isActive: toolMode == 'SelectMagicWand',
                  onTap: () => channel.setToolMode('SelectMagicWand'),
                ),
              ],
            ),
          ),

          const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

          // 選択操作ボタン
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            child: Wrap(
              spacing: 4,
              runSpacing: 4,
              children: [
                _ActionChip(label: '全選択', onTap: channel.selectAll),
                _ActionChip(
                  label: '選択解除',
                  onTap: hasSelection ? channel.clearSelection : null,
                ),
                _ActionChip(
                  label: '反転',
                  onTap: hasSelection ? channel.invertSelection : null,
                ),
              ],
            ),
          ),

          const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

          // 編集操作ボタン
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            child: Wrap(
              spacing: 4,
              runSpacing: 4,
              children: [
                _ActionChip(
                  label: 'コピー',
                  onTap: hasSelection ? channel.copySelection : null,
                ),
                _ActionChip(
                  label: '切り取り',
                  onTap: hasSelection ? channel.cutSelection : null,
                ),
                _ActionChip(
                  label: '削除',
                  onTap: hasSelection ? channel.deleteSelection : null,
                ),
                _ActionChip(
                  label: '塗りつぶし',
                  onTap: hasSelection ? () => channel.fillSelection(state.currentColor) : null,
                ),
              ],
            ),
          ),

        ],
      ),
    );
  }
}

class _ToolChip extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool isActive;
  final VoidCallback onTap;

  const _ToolChip({required this.icon, required this.label, required this.isActive, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: isActive ? C.accentDim : C.surface,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 16, color: isActive ? C.accent : C.iconDefault),
              const SizedBox(width: 4),
              Text(label, style: TextStyle(color: isActive ? C.accent : C.textPrimary, fontSize: 12)),
            ],
          ),
        ),
      ),
    );
  }
}

class _ActionChip extends StatelessWidget {
  final String label;
  final VoidCallback? onTap;

  const _ActionChip({required this.label, this.onTap});

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    return Material(
      color: C.surface,
      borderRadius: BorderRadius.circular(6),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(6),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          child: Text(label, style: TextStyle(color: enabled ? C.textPrimary : C.disabled, fontSize: 12)),
        ),
      ),
    );
  }
}
