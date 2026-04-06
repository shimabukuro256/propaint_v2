import 'package:flutter/material.dart';

import '../main.dart';
import '../models/paint_state.dart';
import '../services/paint_channel.dart';
import '../theme/app_colors.dart';

class PaintToolbar extends StatelessWidget {
  final PaintState state;
  final PaintChannel channel;
  final PanelType openPanel;
  final ValueChanged<PanelType> onTogglePanel;
  final VoidCallback? onMenuTap;
  final VoidCallback? onHomeTap;

  const PaintToolbar({
    super.key,
    required this.state,
    required this.channel,
    required this.openPanel,
    required this.onTogglePanel,
    this.onMenuTap,
    this.onHomeTap,
  });

  @override
  Widget build(BuildContext context) {
    final currentColor = Color(state.currentColor);

    return Container(
      height: 48,
      decoration: BoxDecoration(
        color: C.toolbar,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: C.border.withAlpha(80), width: 0.5),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          // ギャラリーに戻る
          _ToolbarIcon(
            icon: Icons.home_rounded,
            onPressed: onHomeTap,
            tooltip: 'ギャラリーに戻る',
          ),

          // メニュー (保存/エクスポート)
          _ToolbarIcon(
            icon: Icons.menu_rounded,
            onPressed: onMenuTap,
            tooltip: 'メニュー',
          ),

          _separator(),

          // Undo
          _ToolbarIcon(
            icon: Icons.undo_rounded,
            onPressed: state.canUndo ? channel.undo : null,
            tooltip: '元に戻す',
          ),
          // Redo
          _ToolbarIcon(
            icon: Icons.redo_rounded,
            onPressed: state.canRedo ? channel.redo : null,
            tooltip: 'やり直す',
          ),

          _separator(),

          // ブラシクイック切替: 鉛筆, 水彩筆, エアブラシ, マーカー, 消しゴム
          ..._buildBrushIcons(),

          _separator(),

          // カラースウォッチ
          GestureDetector(
            onTap: () => onTogglePanel(PanelType.color),
            child: Container(
              width: 30,
              height: 30,
              decoration: BoxDecoration(
                color: currentColor,
                shape: BoxShape.circle,
                border: Border.all(
                  color: openPanel == PanelType.color ? C.accent : Colors.white54,
                  width: openPanel == PanelType.color ? 2 : 1.5,
                ),
              ),
            ),
          ),

          const SizedBox(width: 4),

          // スポイト
          _ToolbarIcon(
            icon: Icons.colorize_rounded,
            onPressed: () {
              if (state.toolMode == 'Eyedropper') {
                channel.deactivateEyedropper();
              } else {
                channel.activateEyedropper();
              }
            },
            isActive: state.toolMode == 'Eyedropper',
            tooltip: 'スポイト',
          ),

          _separator(),

          // ビューリセット
          _ToolbarIcon(
            icon: Icons.center_focus_strong_rounded,
            onPressed: channel.resetView,
            tooltip: 'ビューリセット',
          ),

          // レイヤー
          _ToolbarIcon(
            icon: Icons.layers_rounded,
            onPressed: () => onTogglePanel(PanelType.layer),
            isActive: openPanel == PanelType.layer,
            tooltip: 'レイヤー',
          ),

          _separator(),

          // 選択ペン
          _ToolbarIcon(
            icon: Icons.edit_rounded,
            onPressed: () {
              channel.setToolMode('SelectPen');
              onTogglePanel(PanelType.selectionTool);
            },
            isActive: state.toolMode == 'SelectPen' || openPanel == PanelType.selectionTool,
            tooltip: '選択ペン',
          ),

          // 選択消しペン
          _ToolbarIcon(
            icon: Icons.cleaning_services_rounded,
            onPressed: () {
              channel.setToolMode('SelectEraser');
              onTogglePanel(PanelType.selectionTool);
            },
            isActive: state.toolMode == 'SelectEraser',
            tooltip: '選択消しペン',
          ),

          // 矩形選択
          _ToolbarIcon(
            icon: Icons.crop_square_rounded,
            onPressed: () {
              channel.setToolMode('SelectRect');
              onTogglePanel(PanelType.selection);
            },
            isActive: state.toolMode == 'SelectRect',
            tooltip: '矩形選択',
          ),

          // 楕円選択
          _ToolbarIcon(
            icon: Icons.circle_outlined,
            onPressed: () {
              channel.setToolMode('SelectEllipse');
              onTogglePanel(PanelType.selection);
            },
            isActive: state.toolMode == 'SelectEllipse',
            tooltip: '楕円選択',
          ),

          // なげなわ選択
          _ToolbarIcon(
            icon: Icons.gesture_rounded,
            onPressed: () {
              channel.setToolMode('SelectLasso');
              onTogglePanel(PanelType.selection);
            },
            isActive: state.toolMode == 'SelectLasso',
            tooltip: 'なげなわ選択',
          ),

          // 自動選択
          _ToolbarIcon(
            icon: Icons.auto_fix_high_rounded,
            onPressed: () {
              channel.setToolMode('SelectMagicWand');
              onTogglePanel(PanelType.selection);
            },
            isActive: state.toolMode == 'SelectMagicWand',
            tooltip: '自動選択',
          ),

          // 図形・テキスト
          _ToolbarIcon(
            icon: Icons.shape_line_rounded,
            onPressed: () => onTogglePanel(PanelType.shapeText),
            isActive: openPanel == PanelType.shapeText,
            tooltip: '図形・テキスト',
          ),

          // 変形
          _ToolbarIcon(
            icon: Icons.transform_rounded,
            onPressed: () => onTogglePanel(PanelType.transform),
            isActive: openPanel == PanelType.transform,
            tooltip: '変形',
          ),

          // フィルター
          _ToolbarIcon(
            icon: Icons.auto_awesome_rounded,
            onPressed: () => onTogglePanel(PanelType.filter),
            isActive: openPanel == PanelType.filter,
            tooltip: 'フィルター',
          ),
        ],
      ),
    );
  }

  List<Widget> _buildBrushIcons() {
    const brushDefs = <(String, IconData, String)>[
      ('Pencil', Icons.edit_rounded, '鉛筆'),
      ('Fude', Icons.brush_rounded, '筆'),
      ('Watercolor', Icons.water_drop_rounded, '水彩'),
      ('Airbrush', Icons.blur_on_rounded, 'エアブラシ'),
      ('Marker', Icons.highlight_rounded, 'マーカー'),
      ('Eraser', Icons.auto_fix_high_rounded, '消しゴム'),
    ];

    // 選択範囲関連ツールが有効か判定
    final isSelectionToolActive = const [
      'SelectRect', 'SelectEllipse', 'SelectLasso', 'SelectMagicWand',
      'SelectPen', 'SelectEraser'
    ].contains(state.toolMode);

    return brushDefs.map((def) {
      final (key, icon, tooltip) = def;
      final isActive = state.brushType == key && !isSelectionToolActive;
      return _BrushToolIcon(
        icon: icon,
        isActive: isActive,
        tooltip: tooltip,
        onTap: () {
          channel.setBrushType(key);
          // 選択範囲ツール active 時は Draw に切り替える（選択範囲は保持）
          if (isSelectionToolActive) {
            channel.setToolMode('Draw');
          }
        },
        onLongPress: isSelectionToolActive ? null : () {
          channel.setBrushType(key);
          onTogglePanel(PanelType.brush);
        },
      );
    }).toList();
  }

  Widget _separator() => Container(
        width: 1,
        height: 24,
        margin: const EdgeInsets.symmetric(horizontal: 4),
        color: C.border,
      );
}

/// ブラシツールアイコン: タップで切替、長押しで設定パネル
class _BrushToolIcon extends StatelessWidget {
  final IconData icon;
  final bool isActive;
  final String tooltip;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  const _BrushToolIcon({
    required this.icon,
    required this.isActive,
    required this.tooltip,
    this.onTap,
    this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    return Tooltip(
      message: enabled ? '$tooltip (長押しで設定)' : '$tooltip (選択範囲ツール使用中)',
      waitDuration: const Duration(milliseconds: 500),
      child: Material(
        color: isActive ? C.accentDim : Colors.transparent,
        borderRadius: BorderRadius.circular(8),
        child: InkWell(
          onTap: onTap,
          onLongPress: onLongPress,
          borderRadius: BorderRadius.circular(8),
          child: SizedBox(
            width: 36,
            height: 36,
            child: Icon(
              icon,
              size: 18,
              color: isActive
                  ? C.accent
                  : enabled
                      ? C.iconDefault
                      : C.disabled,
            ),
          ),
        ),
      ),
    );
  }
}

class _ToolbarIcon extends StatelessWidget {
  final IconData icon;
  final VoidCallback? onPressed;
  final bool isActive;
  final String? tooltip;

  const _ToolbarIcon({
    required this.icon,
    this.onPressed,
    this.isActive = false,
    this.tooltip,
  });

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null;
    return Tooltip(
      message: tooltip ?? '',
      waitDuration: const Duration(milliseconds: 500),
      child: Material(
        color: isActive ? C.accentDim : Colors.transparent,
        borderRadius: BorderRadius.circular(8),
        child: InkWell(
          onTap: onPressed,
          borderRadius: BorderRadius.circular(8),
          child: SizedBox(
            width: 38,
            height: 38,
            child: Icon(
              icon,
              size: 20,
              color: isActive
                  ? C.accent
                  : enabled
                      ? C.iconDefault
                      : C.disabled,
            ),
          ),
        ),
      ),
    );
  }
}
