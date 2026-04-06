import 'package:flutter/material.dart';

import '../models/paint_state.dart';
import '../services/paint_channel.dart';
import '../theme/app_colors.dart';

/// 選択ペン・選択消しパネル
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
  bool _isAdding = true; // true: 選択ペン (追加), false: 選択消し (削除)
  bool _pressureEnabled = false; // 筆圧適用オンオフ
  String _activeMode = 'add'; // 選択モード: add, subtract, rect, clear, copy

  @override
  void initState() {
    super.initState();
    _brushSize = widget.state.brushSize.toInt();
    // パネル開時に選択ペンツールに切り替え
    widget.channel.setToolMode('SelectPen');
  }

  @override
  void didUpdateWidget(SelectionToolPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state.brushSize != widget.state.brushSize) {
      _brushSize = widget.state.brushSize.toInt();
    }
  }

  /// アクション実行: Feather（ぼかし）
  void _onFeather() {
    widget.channel.featherSelection(5); // デフォルト半径 5px
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('選択範囲をぼかしました')),
    );
  }

  /// アクション実行: Move（移動）
  void _onMove() {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('選択範囲内のピクセルをドラッグで移動します')),
    );
  }

  /// アクション実行: Delete（削除）
  void _onDelete() {
    widget.channel.deleteSelection();
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('選択範囲を削除しました')),
    );
  }

  /// アクションボタンを構築（確定不要な操作向け）
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

  /// モードボタンを構築
  Widget _buildModeButton({
    required String mode,
    required IconData icon,
    required String label,
    required VoidCallback onPressed,
  }) {
    final isActive = _activeMode == mode;
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

  /// ピクセルコピー変形を開始
  Future<void> _startPixelCopy() async {
    // 選択範囲がなければスナックバー表示
    if (!widget.state.hasSelection) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('選択範囲がありません')),
      );
      return;
    }

    // startPixelCopy を呼び出して境界情報を取得
    final bounds = await widget.channel.startPixelCopy();
    if (mounted && widget.onPixelCopyStarted != null) {
      widget.onPixelCopyStarted!(bounds.cast<String, int>());
    }
  }

  /// キャンバス中央でテスト用の選択ペイント
  void _paintSelection() {
    // キャンバスサイズの推定：レイヤーがあれば最初のレイヤーから推測
    // ない場合は一般的なキャンバスサイズ 2048x2048 を仮定
    const defaultCanvasSize = 2048;

    // 中央座標を計算（実際のキャンバス座標系）
    final cx = defaultCanvasSize ~/ 2;  // 1024
    final cy = defaultCanvasSize ~/ 2;

    // 筆圧適用が有効な場合のみ _pressure を使用、無効な場合は 1.0 (100%)
    final effectivePressure = _pressureEnabled ? _pressure : 1.0;

    if (_isAdding) {
      widget.channel.paintSelectionAdd(cx, cy, _brushSize, pressure: effectivePressure);
    } else {
      widget.channel.paintSelectionErase(cx, cy, _brushSize, pressure: effectivePressure);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 280,
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
          // ──────────────────── ヘッダ ────────────────────
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text('選択ペン・消し',
                  style: TextStyle(fontWeight: FontWeight.bold)),
              IconButton(
                icon: const Icon(Icons.close, size: 18),
                onPressed: () {
                  // パネル閉時に描画モードに戻す
                  widget.channel.setToolMode('Draw');
                  widget.onClose();
                },
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
              ),
            ],
          ),
          const SizedBox(height: 12),

          // ──────────────────── アイコン列 ────────────────────
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                // Add mode
                _buildModeButton(
                  mode: 'add',
                  icon: Icons.add_circle_outline,
                  label: 'Add',
                  onPressed: () {
                    setState(() {
                      _activeMode = 'add';
                      _isAdding = true;
                    });
                    widget.channel.setToolMode('SelectPen');
                  },
                ),
                const SizedBox(width: 4),

                // Subtract mode
                _buildModeButton(
                  mode: 'subtract',
                  icon: Icons.remove_circle_outline,
                  label: 'Sub',
                  onPressed: () {
                    setState(() {
                      _activeMode = 'subtract';
                      _isAdding = false;
                    });
                    widget.channel.setToolMode('SelectEraser');
                  },
                ),
                const SizedBox(width: 4),

                // Rectangle selection
                _buildModeButton(
                  mode: 'rect',
                  icon: Icons.crop_square,
                  label: 'Rect',
                  onPressed: () {
                    setState(() => _activeMode = 'rect');
                    widget.channel.setToolMode('SelectRect');
                  },
                ),
                const SizedBox(width: 4),

                // Clear selection
                _buildModeButton(
                  mode: 'clear',
                  icon: Icons.clear,
                  label: 'Clear',
                  onPressed: () {
                    setState(() => _activeMode = 'clear');
                    widget.channel.clearSelection();
                  },
                ),
                const SizedBox(width: 4),

                // Copy & Transform
                _buildModeButton(
                  mode: 'copy',
                  icon: Icons.content_copy,
                  label: 'Copy',
                  onPressed: () {
                    setState(() => _activeMode = 'copy');
                    _startPixelCopy();
                  },
                ),
                const SizedBox(width: 4),

                // Feather
                _buildActionButton(
                  icon: Icons.blur_on,
                  label: 'Feather',
                  onPressed: _onFeather,
                ),
                const SizedBox(width: 4),

                // Move
                _buildActionButton(
                  icon: Icons.pan_tool,
                  label: 'Move',
                  onPressed: _onMove,
                ),
                const SizedBox(width: 4),

                // Delete
                _buildActionButton(
                  icon: Icons.delete_sweep,
                  label: 'Delete',
                  onPressed: _onDelete,
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),

          // ──────────────────── ブラシサイズ ────────────────────
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

          // ──────────────────── 筆圧適用オンオフ ────────────────────
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
          const SizedBox(height: 8),

          // ──────────────────── 筆圧値スライダー ────────────────────
          if (_pressureEnabled)
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
          const SizedBox(height: 12),

          // ──────────────────── テスト描画ボタン ────────────────────
          Center(
            child: ElevatedButton.icon(
              onPressed: _paintSelection,
              icon: const Icon(Icons.brush, size: 16),
              label: Text(_isAdding ? '選択追加 (テスト)' : '選択削除 (テスト)'),
              style: ElevatedButton.styleFrom(
                backgroundColor: _isAdding ? C.accent : Colors.red.shade700,
              ),
            ),
          ),
          const SizedBox(height: 8),

          // ヒント
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: C.border.withAlpha(30),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Text(
              '※ テスト描画：キャンバス中央に選択ペン/消しを描画します。実際のドラッグ描画はキャンバスで行います。',
              style: TextStyle(fontSize: 11, color: Colors.white70),
            ),
          ),
        ],
      ),
    );
  }
}
