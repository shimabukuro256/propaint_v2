import 'package:flutter/material.dart';

import '../services/paint_channel.dart';
import '../theme/app_colors.dart';
import 'panel_card.dart';

/// フィルターパネル（既存の HSL/明るさコントラスト/ブラーに加え、新フィルターを追加）
class FilterPanel extends StatefulWidget {
  final PaintChannel channel;

  const FilterPanel({super.key, required this.channel});

  @override
  State<FilterPanel> createState() => _FilterPanelState();
}

class _FilterPanelState extends State<FilterPanel> {
  // アンシャープマスク
  int _usmRadius = 1;
  double _usmAmount = 1.0;
  int _usmThreshold = 0;

  // モザイク
  int _mosaicSize = 10;

  // ノイズ
  int _noiseAmount = 20;
  bool _noiseMono = true;

  // ポスタリゼーション
  int _posterizeLevels = 4;

  // 二値化
  int _threshold = 128;

  // レベル補正
  int _levelsInBlack = 0;
  int _levelsInWhite = 255;
  double _levelsGamma = 1.0;

  // カラーバランス
  int _cbCyanRed = 0;
  int _cbMagentaGreen = 0;
  int _cbYellowBlue = 0;

  @override
  Widget build(BuildContext context) {
    return PanelCard(
      width: 300,
      maxHeight: 520,
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(14, 10, 14, 6),
              child: Text('フィルター', style: TextStyle(color: C.textPrimary, fontSize: 14, fontWeight: FontWeight.w600)),
            ),

            // ── アンシャープマスク ──
            _FilterSection(
              title: 'シャープ (USM)',
              onApply: () => widget.channel.applyUnsharpMask(
                radius: _usmRadius, amount: _usmAmount, threshold: _usmThreshold,
              ),
              children: [
                PanelSlider(
                  label: '半径', value: '$_usmRadius',
                  current: _usmRadius.toDouble(), min: 1, max: 20,
                  onChanged: (v) => setState(() => _usmRadius = v.round()),
                ),
                PanelSlider(
                  label: '量', value: _usmAmount.toStringAsFixed(1),
                  current: _usmAmount, min: 0.1, max: 5.0,
                  onChanged: (v) => setState(() => _usmAmount = v),
                ),
                PanelSlider(
                  label: '閾値', value: '$_usmThreshold',
                  current: _usmThreshold.toDouble(), min: 0, max: 255,
                  onChanged: (v) => setState(() => _usmThreshold = v.round()),
                ),
              ],
            ),

            const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

            // ── モザイク ──
            _FilterSection(
              title: 'モザイク',
              onApply: () => widget.channel.applyMosaic(_mosaicSize),
              children: [
                PanelSlider(
                  label: 'サイズ', value: '$_mosaicSize',
                  current: _mosaicSize.toDouble(), min: 2, max: 100, curve: 2.0,
                  onChanged: (v) => setState(() => _mosaicSize = v.round()),
                ),
              ],
            ),

            const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

            // ── ノイズ ──
            _FilterSection(
              title: 'ノイズ追加',
              onApply: () => widget.channel.applyNoise(_noiseAmount, monochrome: _noiseMono),
              children: [
                PanelSlider(
                  label: '量', value: '$_noiseAmount',
                  current: _noiseAmount.toDouble(), min: 1, max: 100,
                  onChanged: (v) => setState(() => _noiseAmount = v.round()),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Row(
                    children: [
                      const SizedBox(width: 72, child: Text('種類', style: TextStyle(color: C.textSecondary, fontSize: 12))),
                      _MiniToggle(label: 'モノ', isActive: _noiseMono, onTap: () => setState(() => _noiseMono = true)),
                      const SizedBox(width: 4),
                      _MiniToggle(label: 'カラー', isActive: !_noiseMono, onTap: () => setState(() => _noiseMono = false)),
                    ],
                  ),
                ),
              ],
            ),

            const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

            // ── ポスタリゼーション ──
            _FilterSection(
              title: 'ポスタリゼーション',
              onApply: () => widget.channel.applyPosterize(_posterizeLevels),
              children: [
                PanelSlider(
                  label: '階調数', value: '$_posterizeLevels',
                  current: _posterizeLevels.toDouble(), min: 2, max: 32,
                  onChanged: (v) => setState(() => _posterizeLevels = v.round()),
                ),
              ],
            ),

            const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

            // ── 二値化 ──
            _FilterSection(
              title: '二値化',
              onApply: () => widget.channel.applyThreshold(_threshold),
              children: [
                PanelSlider(
                  label: '閾値', value: '$_threshold',
                  current: _threshold.toDouble(), min: 0, max: 255,
                  onChanged: (v) => setState(() => _threshold = v.round()),
                ),
              ],
            ),

            const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

            // ── レベル補正 ──
            _FilterSection(
              title: 'レベル補正',
              onApply: () => widget.channel.applyLevels(
                inBlack: _levelsInBlack, inWhite: _levelsInWhite, gamma: _levelsGamma,
              ),
              children: [
                PanelSlider(
                  label: '黒入力', value: '$_levelsInBlack',
                  current: _levelsInBlack.toDouble(), min: 0, max: 254,
                  onChanged: (v) => setState(() => _levelsInBlack = v.round()),
                ),
                PanelSlider(
                  label: '白入力', value: '$_levelsInWhite',
                  current: _levelsInWhite.toDouble(), min: 1, max: 255,
                  onChanged: (v) => setState(() => _levelsInWhite = v.round()),
                ),
                PanelSlider(
                  label: 'ガンマ', value: _levelsGamma.toStringAsFixed(2),
                  current: _levelsGamma, min: 0.1, max: 3.0,
                  onChanged: (v) => setState(() => _levelsGamma = v),
                ),
              ],
            ),

            const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

            // ── カラーバランス ──
            _FilterSection(
              title: 'カラーバランス',
              onApply: () => widget.channel.applyColorBalance(
                cyanRed: _cbCyanRed, magentaGreen: _cbMagentaGreen, yellowBlue: _cbYellowBlue,
              ),
              children: [
                PanelSlider(
                  label: 'Cyan-Red', value: '$_cbCyanRed',
                  current: _cbCyanRed.toDouble(), min: -100, max: 100,
                  onChanged: (v) => setState(() => _cbCyanRed = v.round()),
                ),
                PanelSlider(
                  label: 'Mg-Green', value: '$_cbMagentaGreen',
                  current: _cbMagentaGreen.toDouble(), min: -100, max: 100,
                  onChanged: (v) => setState(() => _cbMagentaGreen = v.round()),
                ),
                PanelSlider(
                  label: 'Yw-Blue', value: '$_cbYellowBlue',
                  current: _cbYellowBlue.toDouble(), min: -100, max: 100,
                  onChanged: (v) => setState(() => _cbYellowBlue = v.round()),
                ),
              ],
            ),

            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}

/// フィルターセクション: タイトル + スライダー群 + 適用ボタン
class _FilterSection extends StatelessWidget {
  final String title;
  final VoidCallback onApply;
  final List<Widget> children;

  const _FilterSection({required this.title, required this.onApply, required this.children});

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(14, 8, 10, 2),
          child: Row(
            children: [
              Expanded(child: Text(title, style: const TextStyle(color: C.textSecondary, fontSize: 12, fontWeight: FontWeight.w600))),
              SizedBox(
                height: 26,
                child: TextButton(
                  onPressed: onApply,
                  style: TextButton.styleFrom(
                    foregroundColor: C.accent,
                    padding: const EdgeInsets.symmetric(horizontal: 10),
                    textStyle: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
                  ),
                  child: const Text('適用'),
                ),
              ),
            ],
          ),
        ),
        ...children,
        const SizedBox(height: 4),
      ],
    );
  }
}

class _MiniToggle extends StatelessWidget {
  final String label;
  final bool isActive;
  final VoidCallback onTap;

  const _MiniToggle({required this.label, required this.isActive, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: isActive ? C.accentDim : C.surface,
      borderRadius: BorderRadius.circular(6),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(6),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: Text(label, style: TextStyle(color: isActive ? C.accent : C.textPrimary, fontSize: 11)),
        ),
      ),
    );
  }
}
