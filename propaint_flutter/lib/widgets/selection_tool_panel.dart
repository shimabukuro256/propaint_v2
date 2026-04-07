import 'package:flutter/material.dart';

import '../models/paint_state.dart';
import '../services/paint_channel.dart';
import '../theme/app_colors.dart';

/// 統合選択ツールパネル（ペン・なげなわ・自動選択）
class SelectionToolPanel extends StatefulWidget {
  final PaintState state;
  final PaintChannel channel;
  final VoidCallback onClose;
  final Function(Map<String, int>)? onPixelCopyStarted;

  const SelectionToolPanel({
    super.key,
    required this.state,
    required this.channel,
    required this.onClose,
    this.onPixelCopyStarted,
  });

  @override
  State<SelectionToolPanel> createState() => _SelectionToolPanelState();
}

class _SelectionToolPanelState extends State<SelectionToolPanel> {
  late int _brushSize = widget.state.brushSize.toInt();
  late double _pressure = 1.0;
  bool _pressureEnabled = false;
  bool _isAddMode = true;  // true: Add, false: Subtract

  @override
  void initState() {
    super.initState();
    _brushSize = widget.state.brushSize.toInt();
    // 初期選択モード：Add
    _isAddMode = true;
    widget.channel.setSelectionMode('Add');
    // initState では toolMode を変更しない（ツールバーが既に設定済み）
  }

  @override
  void didUpdateWidget(SelectionToolPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state.brushSize != widget.state.brushSize) {
      _brushSize = widget.state.brushSize.toInt();
    }
  }

  // ─────────────────────────────────────────
  // Tool Tab Item
  // ─────────────────────────────────────────
  Widget _buildToolTab({
    required String toolMode,
    required IconData icon,
    required String label,
  }) {
    final isActive = widget.state.toolMode == toolMode;
    return Tooltip(
      message: label,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: () => widget.channel.setToolMode(toolMode),
          borderRadius: BorderRadius.circular(8),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            decoration: BoxDecoration(
              color: isActive ? C.accent.withAlpha(200) : Colors.transparent,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(
                color: isActive ? C.accent : C.border.withAlpha(100),
                width: 1,
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, size: 16, color: isActive ? Colors.white : C.iconDefault),
                const SizedBox(width: 4),
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 11,
                    color: isActive ? Colors.white : C.textSecondary,
                    fontWeight: isActive ? FontWeight.bold : FontWeight.normal,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // ─────────────────────────────────────────
  // Mode Button (Add/Subtract for Pen/Lasso)
  // ─────────────────────────────────────────
  Widget _buildModeButton({
    required bool isAdd,
    required IconData icon,
    required String label,
  }) {
    final isActive = (isAdd && _isAddMode) || (!isAdd && !_isAddMode);
    return Tooltip(
      message: label,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: () {
            setState(() => _isAddMode = isAdd);
            final mode = isAdd ? 'Add' : 'Subtract';
            widget.channel.setSelectionMode(mode);
          },
          borderRadius: BorderRadius.circular(8),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            decoration: BoxDecoration(
              color: isActive ? C.accent.withAlpha(200) : Colors.transparent,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(
                color: isActive ? C.accent : C.border.withAlpha(100),
                width: 1,
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, size: 16, color: isActive ? Colors.white : C.iconDefault),
                const SizedBox(width: 4),
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 11,
                    color: isActive ? Colors.white : C.textSecondary,
                    fontWeight: isActive ? FontWeight.bold : FontWeight.normal,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // ─────────────────────────────────────────
  // Action Button
  // ─────────────────────────────────────────
  Widget _buildActionButton({
    required IconData icon,
    required String label,
    required VoidCallback onPressed,
  }) {
    return Tooltip(
      message: label,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onPressed,
          borderRadius: BorderRadius.circular(8),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            decoration: BoxDecoration(
              color: Colors.transparent,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: C.border.withAlpha(100), width: 1),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, size: 16, color: C.iconDefault),
                const SizedBox(width: 4),
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 11,
                    color: C.textSecondary,
                    fontWeight: FontWeight.normal,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _onDelete() {
    widget.channel.deleteSelection();
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('選択範囲を削除しました')),
    );
  }

  void _onClear() {
    widget.channel.clearSelection();
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('選択を解除しました')),
    );
  }

  Future<void> _onCopyAndTransform() async {
    if (!widget.state.hasSelection) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('選択範囲がありません')),
      );
      return;
    }
    final bounds = await widget.channel.startPixelCopy();
    if (mounted && widget.onPixelCopyStarted != null) {
      widget.onPixelCopyStarted!(bounds.cast<String, int>());
    }
  }

  void _onSelectAll() => widget.channel.selectAll();

  void _onInvert() => widget.channel.invertSelection();

  void _onCut() => widget.channel.cutSelection();

  void _onFill() => widget.channel.fillSelection(widget.state.currentColor);

  @override
  Widget build(BuildContext context) {
    final isPenMode = widget.state.toolMode == 'SelectPen';
    final isMagnetMode = widget.state.toolMode == 'SelectMagnet';
    final isMagicWandMode = widget.state.toolMode == 'SelectMagicWand';
    final showModeButtons = isPenMode || isMagnetMode || isMagicWandMode;
    final hasSelection = widget.state.hasSelection;

    return Container(
      width: 320,
      decoration: BoxDecoration(
        color: C.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: C.border.withAlpha(80), width: 0.5),
      ),
      padding: const EdgeInsets.all(12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ──────── ヘッダー ────────
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text('選択',
                  style: TextStyle(fontWeight: FontWeight.bold)),
              IconButton(
                icon: const Icon(Icons.close, size: 18),
                onPressed: () {
                  widget.channel.setToolMode('Draw');
                  widget.onClose();
                },
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
              ),
            ],
          ),
          const SizedBox(height: 12),

          // ──────── ツール選択タブ ────────
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _buildToolTab(
                  toolMode: 'SelectPen',
                  icon: Icons.edit_rounded,
                  label: 'ペン',
                ),
                const SizedBox(width: 4),
                _buildToolTab(
                  toolMode: 'SelectMagnet',
                  icon: Icons.linear_scale_rounded,
                  label: 'マグネット',
                ),
                const SizedBox(width: 4),
                _buildToolTab(
                  toolMode: 'SelectMagicWand',
                  icon: Icons.auto_fix_high_rounded,
                  label: '自動選択',
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),

          // ──────── 選択モード（ペン/マグネット/自動選択） ────────
          if (showModeButtons) ...[
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _buildModeButton(
                    isAdd: true,
                    icon: Icons.add_circle_outline,
                    label: 'Add',
                  ),
                  const SizedBox(width: 4),
                  _buildModeButton(
                    isAdd: false,
                    icon: Icons.remove_circle_outline,
                    label: 'Subtract',
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
          ],

          // ──────── ペン設定（ペンモードのみ） ────────
          if (isPenMode) ...[
            Row(
              children: [
                const Text('サイズ:', style: TextStyle(fontSize: 12)),
                const SizedBox(width: 8),
                Expanded(
                  child: Slider(
                    value: _brushSize.toDouble().clamp(1, 512),
                    min: 1,
                    max: 512,
                    onChanged: (val) {
                      setState(() => _brushSize = val.toInt());
                      widget.channel.setBrushSize(_brushSize.toDouble());
                    },
                  ),
                ),
                SizedBox(
                  width: 40,
                  child: Text(_brushSize.toString(),
                      textAlign: TextAlign.right, style: const TextStyle(fontSize: 12)),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text('筆圧適用:', style: TextStyle(fontSize: 12)),
                Switch(
                  value: _pressureEnabled,
                  onChanged: (val) {
                    setState(() => _pressureEnabled = val);
                    widget.channel.togglePressureSelection();
                  },
                ),
              ],
            ),
            if (_pressureEnabled) ...[
              const SizedBox(height: 8),
              Row(
                children: [
                  const Text('筆圧:', style: TextStyle(fontSize: 12)),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Slider(
                      value: _pressure,
                      min: 0.0,
                      max: 1.0,
                      onChanged: (val) {
                        setState(() => _pressure = val);
                      },
                    ),
                  ),
                  SizedBox(
                    width: 40,
                    child: Text('${(_pressure * 100).toInt()}%',
                        textAlign: TextAlign.right, style: const TextStyle(fontSize: 12)),
                  ),
                ],
              ),
            ],
            const SizedBox(height: 12),
          ],

          // ──────── マグネット選択モード用：キャンセルボタンのみ ────────
          if (isMagnetMode) ...[
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _buildActionButton(
                    icon: Icons.close,
                    label: 'キャンセル',
                    onPressed: () => widget.channel.cancelMagnetSelection(),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
          ],

          // ──────── その他モード用：標準アクションボタン ────────
          if (!isMagnetMode) ...[
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _buildActionButton(
                    icon: Icons.delete_sweep,
                    label: 'Delete',
                    onPressed: _onDelete,
                  ),
                  const SizedBox(width: 4),
                  _buildActionButton(
                    icon: Icons.content_copy,
                    label: 'Copy',
                    onPressed: _onCopyAndTransform,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),

            // ──────── 選択操作 ────────
            Wrap(
              spacing: 4,
              runSpacing: 4,
              children: [
                _buildActionButton(
                  icon: Icons.select_all,
                  label: '全選択',
                  onPressed: _onSelectAll,
                ),
                _buildActionButton(
                  icon: Icons.flip,
                  label: '反転',
                  onPressed: hasSelection ? _onInvert : () {},
                ),
                _buildActionButton(
                  icon: Icons.assignment_returned,
                  label: '選択解除',
                  onPressed: hasSelection ? _onClear : () {},
                ),
                _buildActionButton(
                  icon: Icons.content_cut,
                  label: '切り取り',
                  onPressed: hasSelection ? _onCut : () {},
                ),
                _buildActionButton(
                  icon: Icons.format_color_fill,
                  label: '塗りつぶし',
                  onPressed: hasSelection ? _onFill : () {},
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}
