import 'package:flutter/material.dart';

import '../models/paint_state.dart';
import '../services/paint_channel.dart';
import '../theme/app_colors.dart';

/// 選択ペン・選択消しパネル
class SelectionToolPanel extends StatefulWidget {
  final PaintState state;
  final PaintChannel channel;
  final VoidCallback onClose;

  const SelectionToolPanel({
    super.key,
    required this.state,
    required this.channel,
    required this.onClose,
  });

  @override
  State<SelectionToolPanel> createState() => _SelectionToolPanelState();
}

class _SelectionToolPanelState extends State<SelectionToolPanel> {
  late int _brushSize = widget.state.brushSize.toInt();
  late double _pressure = 1.0;
  bool _isAdding = true; // true: 選択ペン (追加), false: 選択消し (削除)

  @override
  void initState() {
    super.initState();
    _brushSize = widget.state.brushSize.toInt();
  }

  @override
  void didUpdateWidget(SelectionToolPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state.brushSize != widget.state.brushSize) {
      _brushSize = widget.state.brushSize.toInt();
    }
  }

  /// キャンバス中央でテスト用の選択ペイント
  void _paintSelection() {
    // TODO: キャンバスの実際のサイズを取得して中央座標を計算
    // 暫定的に固定座標を使用
    const cx = 512;
    const cy = 512;

    if (_isAdding) {
      widget.channel.paintSelectionAdd(cx, cy, _brushSize, pressure: _pressure);
    } else {
      widget.channel.paintSelectionErase(cx, cy, _brushSize, pressure: _pressure);
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
                onPressed: widget.onClose,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
              ),
            ],
          ),
          const SizedBox(height: 12),

          // ──────────────────── モード切替 ────────────────────
          Row(
            children: [
              Expanded(
                child: SegmentedButton<bool>(
                  segments: const [
                    ButtonSegment(label: Text('追加'), value: true),
                    ButtonSegment(label: Text('削除'), value: false),
                  ],
                  selected: {_isAdding},
                  onSelectionChanged: (val) {
                    setState(() => _isAdding = val.first);
                  },
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),

          // ──────────────────── ブラシサイズ ────────────────────
          Row(
            children: [
              const Text('サイズ:', style: TextStyle(fontSize: 12)),
              const SizedBox(width: 8),
              Expanded(
                child: Slider(
                  value: _brushSize.toDouble(),
                  min: 1,
                  max: 200,
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

          // ──────────────────── 筆圧 ────────────────────
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
