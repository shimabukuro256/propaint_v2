import 'dart:async';

import 'package:flutter/material.dart';

import 'models/paint_state.dart';
import 'services/paint_channel.dart';
import 'theme/app_colors.dart';
import 'widgets/paint_canvas.dart';
import 'widgets/toolbar.dart';
import 'widgets/brush_panel.dart';
import 'widgets/color_picker_panel.dart';
import 'widgets/layer_panel.dart';
import 'widgets/side_quick_bar.dart';

void main() => runApp(const ProPaintApp());

class ProPaintApp extends StatelessWidget {
  const ProPaintApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ProPaint',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true).copyWith(
        scaffoldBackgroundColor: C.bg,
        sliderTheme: SliderThemeData(
          activeTrackColor: C.accent,
          thumbColor: C.accent,
          inactiveTrackColor: C.border,
          overlayColor: C.accent.withAlpha(40),
          trackHeight: 3,
          thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 7),
        ),
      ),
      home: const PaintScaffold(),
    );
  }
}

/// 開いているパネル
enum PanelType { none, brush, color, layer, menu }

class PaintScaffold extends StatefulWidget {
  const PaintScaffold({super.key});

  @override
  State<PaintScaffold> createState() => _PaintScaffoldState();
}

class _PaintScaffoldState extends State<PaintScaffold> {
  final _channel = PaintChannel();
  PaintState _state = const PaintState();
  StreamSubscription<Map<String, dynamic>>? _stateSub;
  PanelType _openPanel = PanelType.none;

  // スポイトトグル用: トグル前のブラシ種別を保存
  String? _savedBrushType;

  // ジェスチャ通知のオーバーレイ
  String? _gestureOverlayText;
  Timer? _gestureOverlayTimer;

  @override
  void initState() {
    super.initState();
    _stateSub = _channel.stateStream.listen(_onStateUpdate);
    _channel.getState().then(_onStateUpdate);

    // ネイティブからのジェスチャ通知を受信
    _channel.onNativeGesture = _onNativeGesture;
  }

  @override
  void dispose() {
    _stateSub?.cancel();
    _gestureOverlayTimer?.cancel();
    super.dispose();
  }

  void _onStateUpdate(Map<String, dynamic> m) {
    setState(() => _state = _state.copyWithMap(m));
  }

  void _togglePanel(PanelType panel) {
    setState(() => _openPanel = _openPanel == panel ? PanelType.none : panel);
  }

  void _closePanel() {
    setState(() => _openPanel = PanelType.none);
  }

  // ── スポイトトグル ──────────────────────────────────────

  void _onEyedropperToggle() {
    if (_state.toolMode == 'Eyedropper') {
      // OFF: 元のブラシに復帰
      _channel.deactivateEyedropper();
      if (_savedBrushType != null) {
        _channel.setBrushType(_savedBrushType!);
        _savedBrushType = null;
      }
    } else {
      // ON: 現在のブラシを保存してスポイト発動
      _savedBrushType = _state.brushType;
      _channel.activateEyedropper();
    }
  }

  // ── ジェスチャ通知 ────────────────────────────────────────

  void _onNativeGesture(String type) {
    final label = switch (type) {
      'undo' => '取り消し',
      'redo' => 'やり直し',
      _ => null,
    };
    if (label == null) return;

    setState(() => _gestureOverlayText = label);
    _gestureOverlayTimer?.cancel();
    _gestureOverlayTimer = Timer(const Duration(milliseconds: 800), () {
      if (mounted) setState(() => _gestureOverlayText = null);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // GL Canvas — 全画面
          const Positioned.fill(child: PaintCanvas()),

          // パネル開いているときの透明タップでクローズ
          if (_openPanel != PanelType.none)
            Positioned.fill(
              child: GestureDetector(
                behavior: HitTestBehavior.translucent,
                onTap: _closePanel,
                child: const SizedBox.expand(),
              ),
            ),

          // サイドクイックバー (左)
          Positioned(
            left: 8,
            top: 0,
            bottom: 0,
            child: Center(
              child: SideQuickBar(
                brushSize: _state.brushSize,
                brushOpacity: _state.brushOpacity,
                onSizeChanged: (v) => _channel.setBrushSize(v),
                onOpacityChanged: (v) => _channel.setBrushOpacity(v),
                onEyedropperToggle: _onEyedropperToggle,
                isEyedropperActive: _state.toolMode == 'Eyedropper',
              ),
            ),
          ),

          // ツールバー (上部中央)
          Positioned(
            top: 8,
            left: 60,
            right: 8,
            child: Center(
              child: PaintToolbar(
                state: _state,
                channel: _channel,
                openPanel: _openPanel,
                onTogglePanel: _togglePanel,
                onMenuTap: () => _togglePanel(PanelType.menu),
                onHomeTap: () => _channel.returnToGallery(),
              ),
            ),
          ),

          // メニューパネル (保存/エクスポート/インポート)
          if (_openPanel == PanelType.menu)
            Positioned(
              top: 60,
              left: 60,
              child: _MenuPanel(channel: _channel, onClose: _closePanel),
            ),

          // ブラシパネル
          if (_openPanel == PanelType.brush)
            Positioned(
              top: 60,
              left: 60,
              child: BrushPanel(state: _state, channel: _channel),
            ),

          // カラーピッカーパネル
          if (_openPanel == PanelType.color)
            Positioned(
              top: 60,
              left: 60,
              right: 60,
              child: Center(child: ColorPickerPanel(state: _state, channel: _channel)),
            ),

          // レイヤーパネル
          if (_openPanel == PanelType.layer)
            Positioned(
              top: 60,
              right: 8,
              child: LayerPanel(state: _state, channel: _channel),
            ),

          // スポイトモード表示
          if (_state.toolMode == 'Eyedropper')
            Positioned(
              top: 60,
              left: 0,
              right: 0,
              child: Center(
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  decoration: BoxDecoration(
                    color: C.card,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Text(
                    'スポイト: キャンバスをタップ',
                    style: TextStyle(color: C.textPrimary, fontSize: 13),
                  ),
                ),
              ),
            ),

          // ジェスチャ通知オーバーレイ (undo/redo)
          if (_gestureOverlayText != null)
            Positioned(
              bottom: 60,
              left: 0,
              right: 0,
              child: Center(
                child: AnimatedOpacity(
                  opacity: _gestureOverlayText != null ? 1.0 : 0.0,
                  duration: const Duration(milliseconds: 200),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                    decoration: BoxDecoration(
                      color: C.card.withAlpha(220),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      _gestureOverlayText ?? '',
                      style: const TextStyle(
                        color: C.textPrimary,
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// 保存/エクスポート/インポート メニューパネル
class _MenuPanel extends StatelessWidget {
  final PaintChannel channel;
  final VoidCallback onClose;

  const _MenuPanel({required this.channel, required this.onClose});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 220,
      decoration: BoxDecoration(
        color: C.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: C.border.withAlpha(80), width: 0.5),
        boxShadow: [BoxShadow(color: Colors.black38, blurRadius: 12)],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(14, 10, 14, 4),
            child: Text('メニュー', style: TextStyle(color: C.textPrimary, fontSize: 14, fontWeight: FontWeight.w600)),
          ),
          const Divider(color: C.border, height: 1),

          // 保存
          _MenuItem(
            icon: Icons.save_rounded,
            label: 'プロジェクト保存',
            onTap: () { channel.saveProject(); onClose(); },
          ),

          const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

          // エクスポート
          const Padding(
            padding: EdgeInsets.fromLTRB(14, 8, 14, 2),
            child: Text('エクスポート', style: TextStyle(color: C.textSecondary, fontSize: 11)),
          ),
          _MenuItem(
            icon: Icons.image_rounded,
            label: 'PNG で書き出し',
            onTap: () { channel.requestExport('png'); onClose(); },
          ),
          _MenuItem(
            icon: Icons.photo_rounded,
            label: 'JPEG で書き出し',
            onTap: () { channel.requestExport('jpeg'); onClose(); },
          ),
          _MenuItem(
            icon: Icons.layers_rounded,
            label: 'PSD で書き出し',
            onTap: () { channel.requestExport('psd'); onClose(); },
          ),
          _MenuItem(
            icon: Icons.folder_zip_rounded,
            label: 'プロジェクトファイル(.ppaint)',
            onTap: () { channel.requestExport('project'); onClose(); },
          ),

          const Divider(color: C.border, height: 1, indent: 12, endIndent: 12),

          // インポート
          const Padding(
            padding: EdgeInsets.fromLTRB(14, 8, 14, 2),
            child: Text('インポート', style: TextStyle(color: C.textSecondary, fontSize: 11)),
          ),
          _MenuItem(
            icon: Icons.add_photo_alternate_rounded,
            label: '画像をレイヤーとして読み込み',
            onTap: () { channel.requestImport('image'); onClose(); },
          ),
          _MenuItem(
            icon: Icons.layers_rounded,
            label: 'PSD を読み込み',
            onTap: () { channel.requestImport('psd'); onClose(); },
          ),
          _MenuItem(
            icon: Icons.file_open_rounded,
            label: 'プロジェクト読み込み',
            onTap: () { channel.requestImport('project'); onClose(); },
          ),

          const SizedBox(height: 8),
        ],
      ),
    );
  }
}

class _MenuItem extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  const _MenuItem({required this.icon, required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Row(
            children: [
              Icon(icon, size: 18, color: C.iconDefault),
              const SizedBox(width: 10),
              Expanded(
                child: Text(label, style: const TextStyle(color: C.textPrimary, fontSize: 13)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
