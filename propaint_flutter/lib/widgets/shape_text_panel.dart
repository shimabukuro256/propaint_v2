import 'package:flutter/material.dart';

import '../models/paint_state.dart';
import '../services/paint_channel.dart';
import '../theme/app_colors.dart';
import 'panel_card.dart';

/// 図形・テキスト・塗りつぶしパネル
class ShapeTextPanel extends StatefulWidget {
  final PaintState state;
  final PaintChannel channel;

  const ShapeTextPanel({super.key, required this.state, required this.channel});

  @override
  State<ShapeTextPanel> createState() => _ShapeTextPanelState();
}

class _ShapeTextPanelState extends State<ShapeTextPanel> {
  final _textController = TextEditingController();
  double _fontSize = 48;
  bool _isBold = false;
  bool _isItalic = false;
  bool _isVertical = false;

  @override
  void dispose() {
    _textController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final toolMode = widget.state.toolMode;

    return PanelCard(
      width: 280,
      maxHeight: 520,
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(14, 10, 14, 6),
              child: Text('図形・テキスト', style: TextStyle(color: C.textPrimary, fontSize: 14, fontWeight: FontWeight.w600)),
            ),

            // ── 図形ツール切替 ──
            const Padding(
              padding: EdgeInsets.fromLTRB(14, 0, 14, 2),
              child: Text('図形ツール', style: TextStyle(color: C.textSecondary, fontSize: 11)),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              child: Wrap(
                spacing: 4,
                runSpacing: 4,
                children: [
                  _ToolChip(
                    icon: Icons.horizontal_rule_rounded,
                    label: '直線',
                    isActive: toolMode == 'ShapeLine',
                    onTap: () => widget.channel.setToolMode('ShapeLine'),
                  ),
                  _ToolChip(
                    icon: Icons.crop_square_rounded,
                    label: '矩形',
                    isActive: toolMode == 'ShapeRect',
                    onTap: () => widget.channel.setToolMode('ShapeRect'),
                  ),
                  _ToolChip(
                    icon: Icons.circle_outlined,
                    label: '楕円',
                    isActive: toolMode == 'ShapeEllipse',
                    onTap: () => widget.channel.setToolMode('ShapeEllipse'),
                  ),
                  _ToolChip(
                    icon: Icons.format_color_fill_rounded,
                    label: '塗りつぶし',
                    isActive: toolMode == 'FloodFill',
                    onTap: () => widget.channel.setToolMode('FloodFill'),
                  ),
                  _ToolChip(
                    icon: Icons.gradient_rounded,
                    label: 'グラデ',
                    isActive: toolMode == 'Gradient',
                    onTap: () => widget.channel.setToolMode('Gradient'),
                  ),
                ],
              ),
            ),

            const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

            // ── テキスト ──
            const Padding(
              padding: EdgeInsets.fromLTRB(14, 8, 14, 4),
              child: Text('テキストレイヤー', style: TextStyle(color: C.textSecondary, fontSize: 11)),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10),
              child: TextField(
                controller: _textController,
                style: const TextStyle(color: C.textPrimary, fontSize: 13),
                maxLines: 3,
                minLines: 1,
                decoration: InputDecoration(
                  hintText: 'テキストを入力...',
                  hintStyle: const TextStyle(color: C.disabled, fontSize: 13),
                  filled: true,
                  fillColor: C.surface,
                  contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8),
                    borderSide: const BorderSide(color: C.border),
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8),
                    borderSide: const BorderSide(color: C.border),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 6),

            // フォントサイズ
            PanelSlider(
              label: 'サイズ',
              value: '${_fontSize.toInt()}px',
              current: _fontSize,
              min: 8,
              max: 200,
              curve: 2.0,
              onChanged: (v) => setState(() => _fontSize = v),
            ),

            // スタイル
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              child: Row(
                children: [
                  _StyleChip(label: 'B', isActive: _isBold, onTap: () => setState(() => _isBold = !_isBold)),
                  const SizedBox(width: 4),
                  _StyleChip(label: 'I', isActive: _isItalic, onTap: () => setState(() => _isItalic = !_isItalic)),
                  const SizedBox(width: 4),
                  _StyleChip(label: '縦', isActive: _isVertical, onTap: () => setState(() => _isVertical = !_isVertical)),
                  const Spacer(),
                  SizedBox(
                    height: 30,
                    child: ElevatedButton(
                      onPressed: _textController.text.isNotEmpty ? () {
                        widget.channel.addTextLayer(
                          _textController.text,
                          fontSize: _fontSize,
                          x: 40,
                          y: 40,
                          bold: _isBold,
                          italic: _isItalic,
                          vertical: _isVertical,
                        );
                      } : null,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: C.accent,
                        foregroundColor: Colors.black,
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        textStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
                      ),
                      child: const Text('追加'),
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 6),

            // 描画モードに戻る
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
              child: SizedBox(
                width: double.infinity,
                height: 32,
                child: OutlinedButton.icon(
                  onPressed: () => widget.channel.setToolMode('Draw'),
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

class _StyleChip extends StatelessWidget {
  final String label;
  final bool isActive;
  final VoidCallback onTap;

  const _StyleChip({required this.label, required this.isActive, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: isActive ? C.accentDim : C.surface,
      borderRadius: BorderRadius.circular(6),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(6),
        child: SizedBox(
          width: 32,
          height: 30,
          child: Center(
            child: Text(
              label,
              style: TextStyle(
                color: isActive ? C.accent : C.textPrimary,
                fontSize: 13,
                fontWeight: isActive ? FontWeight.bold : FontWeight.normal,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
