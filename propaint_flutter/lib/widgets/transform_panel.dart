import 'package:flutter/material.dart';

import '../services/paint_channel.dart';
import '../theme/app_colors.dart';

/// 選択範囲作成時に画面下部にフロート表示される横長の変形ツールバー。
/// 自由変形（ピクセルコピー変形）、90°回転、反転、150%/50% スケールを提供する。
///
/// selectedLayerIds.length >= 2 の場合は複数レイヤー一括変形を優先する。
class SelectionTransformBar extends StatelessWidget {
  final PaintChannel channel;
  final List<int> selectedLayerIds;
  final bool hasSelection;
  final Function(Map<String, int>)? onPixelCopyStarted;

  const SelectionTransformBar({
    super.key,
    required this.channel,
    this.selectedLayerIds = const [],
    this.hasSelection = false,
    this.onPixelCopyStarted,
  });

  bool get _isMulti => selectedLayerIds.length >= 2;

  Future<void> _startFreeTransform(BuildContext context) async {
    if (!hasSelection) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('選択範囲がありません'),
          duration: Duration(seconds: 1),
        ),
      );
      return;
    }
    final layerIds = _isMulti ? selectedLayerIds.toList() : null;
    final bounds = await channel.startPixelCopy(layerIds: layerIds);
    if (onPixelCopyStarted != null && bounds.isNotEmpty) {
      onPixelCopyStarted!(bounds.cast<String, int>());
    }
  }

  void _flipH() {
    if (_isMulti) {
      channel.applyMultiLayerSimpleTransform(layerIds: selectedLayerIds, operation: 'flipH');
    } else {
      channel.flipLayerH();
    }
  }

  void _flipV() {
    if (_isMulti) {
      channel.applyMultiLayerSimpleTransform(layerIds: selectedLayerIds, operation: 'flipV');
    } else {
      channel.flipLayerV();
    }
  }

  void _rotate90CW() {
    if (_isMulti) {
      channel.applyMultiLayerSimpleTransform(layerIds: selectedLayerIds, operation: 'rotate90CW');
    } else {
      channel.rotateLayer90CW();
    }
  }

  void _scale(double s) {
    if (_isMulti) {
      channel.applyPreviewTransform(layerIds: selectedLayerIds, scaleX: s, scaleY: s);
    } else {
      channel.transformLayer(scaleX: s, scaleY: s);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: C.card,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: C.accent.withValues(alpha: 0.4), width: 1),
        boxShadow: const [
          BoxShadow(color: Colors.black54, blurRadius: 16, offset: Offset(0, 4)),
        ],
      ),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_isMulti) ...[
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: C.accent.withValues(alpha: 0.2),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                '${selectedLayerIds.length}レイヤー',
                style: TextStyle(color: C.accent, fontSize: 11, fontWeight: FontWeight.w600),
              ),
            ),
            const SizedBox(width: 8),
          ],
          _BarButton(
            icon: Icons.open_with_rounded,
            label: '自由変形',
            emphasize: hasSelection,
            onTap: () => _startFreeTransform(context),
          ),
          _separator(),
          _BarButton(
            icon: Icons.zoom_in_rounded,
            label: '150%',
            onTap: () => _scale(1.5),
          ),
          _BarButton(
            icon: Icons.zoom_out_rounded,
            label: '50%',
            onTap: () => _scale(0.5),
          ),
          _separator(),
          _BarButton(
            icon: Icons.rotate_90_degrees_cw_rounded,
            label: '90°',
            onTap: _rotate90CW,
          ),
          _separator(),
          _BarButton(
            icon: Icons.flip_rounded,
            label: '左右',
            onTap: _flipH,
          ),
          _BarButton(
            icon: Icons.flip_rounded,
            label: '上下',
            rotateIcon: true,
            onTap: _flipV,
          ),
        ],
      ),
    );
  }

  Widget _separator() => Container(
        width: 1,
        height: 22,
        margin: const EdgeInsets.symmetric(horizontal: 4),
        color: C.border,
      );
}

class _BarButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final bool rotateIcon;
  final bool emphasize;

  const _BarButton({
    required this.icon,
    required this.label,
    required this.onTap,
    this.rotateIcon = false,
    this.emphasize = false,
  });

  @override
  Widget build(BuildContext context) {
    final bg = emphasize ? C.accent.withValues(alpha: 0.18) : Colors.transparent;
    final color = emphasize ? C.accent : C.textPrimary;
    return Material(
      color: bg,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Transform.rotate(
                angle: rotateIcon ? 1.5708 : 0,
                child: Icon(icon, size: 18, color: color),
              ),
              const SizedBox(height: 2),
              Text(label, style: TextStyle(color: color, fontSize: 10)),
            ],
          ),
        ),
      ),
    );
  }
}
