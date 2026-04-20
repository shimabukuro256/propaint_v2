import 'package:flutter/material.dart';
import 'dart:async';

import '../models/paint_state.dart';
import '../services/paint_channel.dart';
import '../theme/app_colors.dart';
import 'panel_card.dart';

/// レイヤーパネル（ツリー構造対応）
class LayerPanel extends StatefulWidget {
  final PaintState state;
  final PaintChannel channel;
  final ValueChanged<Set<int>>? onSelectionChanged;

  const LayerPanel({
    super.key,
    required this.state,
    required this.channel,
    this.onSelectionChanged,
  });

  @override
  State<LayerPanel> createState() => _LayerPanelState();
}

class _LayerPanelState extends State<LayerPanel> {
  // ─── 状態 ───────────────────────────────────────────────
  final Set<int> _selectedIds = {};        // 複数選択
  final Set<int> _expandedFolders = {};    // 展開中フォルダ
  int? _expandedLayerId;                   // 詳細展開中レイヤー

  // ドラッグ状態
  int? _draggingId;
  int? _dropTargetId;
  _DropPosition _dropPosition = _DropPosition.none;

  // 各レイヤータイルの GlobalKey（座標計算用）
  final Map<int, GlobalKey> _tileKeys = {};

  // スクロール
  final ScrollController _scrollCtrl = ScrollController();
  Timer? _autoScrollTimer;

  // フォルダホバー展開
  Timer? _hoverTimer;
  int? _hoverFolderId;

  @override
  void dispose() {
    _scrollCtrl.dispose();
    _autoScrollTimer?.cancel();
    _hoverTimer?.cancel();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant LayerPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    // ドラッグ中は状態更新スキップ
    if (_draggingId != null) return;

    // 初回のみフォルダ展開状態を同期
    if (_expandedFolders.isEmpty) {
      final expanded = widget.state.layers
          .where((l) => l.isGroup && l.isExpanded)
          .map((l) => l.id)
          .toSet();
      if (expanded.isNotEmpty) {
        setState(() => _expandedFolders.addAll(expanded));
      }
    }
  }

  // ─── 表示用リスト生成 ────────────────────────────────────
  List<LayerInfo> _buildDisplayList() {
    final result = <LayerInfo>[];
    final layers = widget.state.layers;

    for (final layer in layers) {
      // 親フォルダが折りたたまれている場合はスキップ
      if (layer.depth > 0) {
        final parent = _findParentFolder(layer, layers);
        if (parent != null && !_expandedFolders.contains(parent.id)) {
          continue;
        }
      }
      result.add(layer);
    }
    return result;
  }

  LayerInfo? _findParentFolder(LayerInfo layer, List<LayerInfo> layers) {
    final idx = layers.indexOf(layer);
    for (int i = idx - 1; i >= 0; i--) {
      if (layers[i].isGroup && layers[i].depth < layer.depth) {
        return layers[i];
      }
    }
    return null;
  }

  // ─── 操作ハンドラ ───────────────────────────────────────
  void _onTapLayer(LayerInfo layer) {
    if (layer.isGroup) {
      // フォルダタップ: 選択状態をトグル（展開/折りたたみはアイコンで）
      _toggleSelection(layer.id);
    } else {
      widget.channel.selectLayer(layer.id);
    }
  }

  void _toggleFolder(int folderId) {
    setState(() {
      if (_expandedFolders.contains(folderId)) {
        _expandedFolders.remove(folderId);
      } else {
        _expandedFolders.add(folderId);
      }
    });
    widget.channel.setFolderExpanded(folderId, _expandedFolders.contains(folderId));
  }

  void _toggleSelection(int layerId) {
    setState(() {
      if (_selectedIds.contains(layerId)) {
        _selectedIds.remove(layerId);
      } else {
        _selectedIds.add(layerId);
      }
    });
    widget.onSelectionChanged?.call(Set.from(_selectedIds));
    widget.channel.setMultiSelection(_selectedIds.toList());
  }

  void _clearSelection() {
    setState(() => _selectedIds.clear());
    widget.onSelectionChanged?.call({});
    widget.channel.clearMultiSelection();
  }

  void _toggleDetailExpand(int layerId) {
    setState(() {
      _expandedLayerId = _expandedLayerId == layerId ? null : layerId;
    });
  }

  // ─── ドラッグ&ドロップ ──────────────────────────────────
  void _onDragStart(int layerId) {
    setState(() => _draggingId = layerId);
  }

  void _onDragEnd() {
    setState(() {
      _draggingId = null;
      _dropTargetId = null;
      _dropPosition = _DropPosition.none;
      _hoverFolderId = null;
    });
    _hoverTimer?.cancel();
    _autoScrollTimer?.cancel();
  }

  void _onDragUpdate(int targetId, Offset localPosition, double itemHeight, bool isFolder) {
    if (_draggingId == targetId) return;

    final draggingIsFolder = widget.state.layers
        .any((l) => l.id == _draggingId && l.isGroup);

    _DropPosition pos;
    if (isFolder && !draggingIsFolder) {
      // レイヤー → フォルダ: 中央部分はフォルダ内挿入
      final edgeZone = (itemHeight * 0.25).clamp(8.0, 12.0);
      if (localPosition.dy < edgeZone) {
        pos = _DropPosition.above;
      } else if (localPosition.dy > itemHeight - edgeZone) {
        pos = _DropPosition.below;
      } else {
        pos = _DropPosition.inside;
        _startHoverTimer(targetId);
      }
    } else {
      // フォルダ → フォルダ、レイヤー → レイヤー: 上下のみ
      pos = localPosition.dy < itemHeight / 2
          ? _DropPosition.above
          : _DropPosition.below;
    }

    if (pos != _DropPosition.inside) {
      _cancelHoverTimer();
    }

    setState(() {
      _dropTargetId = targetId;
      _dropPosition = pos;
    });
  }

  void _onDragLeave() {
    _cancelHoverTimer();
  }

  void _onDrop(int targetId) {
    if (_draggingId == null || _draggingId == targetId) return;

    final dragId = _draggingId!;
    final pos = _dropPosition;

    // ドラッグ元とターゲットの情報を取得
    final dragLayer = widget.state.layers.firstWhere(
      (l) => l.id == dragId,
      orElse: () => widget.state.layers.first,
    );
    final targetLayer = widget.state.layers.firstWhere(
      (l) => l.id == targetId,
      orElse: () => widget.state.layers.first,
    );

    // 複数選択時: ドラッグ元が選択中なら選択中の全アイテムを移動
    final isMultiSelect = _selectedIds.contains(dragId) && _selectedIds.length > 1;
    final itemsToMove = isMultiSelect
        ? _selectedIds.where((id) => id != targetId).toList()
        : [dragId];

    // 移動対象をフォルダとレイヤーに分離
    final foldersToMove = <int>[];
    final layersToMove = <int>[];
    for (final id in itemsToMove) {
      final item = widget.state.layers.cast<LayerInfo?>().firstWhere(
        (l) => l?.id == id,
        orElse: () => null,
      );
      if (item == null) continue;
      if (item.isGroup) {
        foldersToMove.add(id);
      } else {
        layersToMove.add(id);
      }
    }

    if (foldersToMove.isEmpty && layersToMove.isEmpty) {
      _onDragEnd();
      return;
    }

    if (pos == _DropPosition.inside && targetLayer.isGroup) {
      // フォルダ内に移動（レイヤーのみ、フォルダは入れない）
      if (layersToMove.isNotEmpty) {
        if (layersToMove.length > 1) {
          widget.channel.batchSetLayerGroup(layersToMove, -targetId);
        } else {
          widget.channel.setLayerGroup(layersToMove.first, -targetId);
        }
      }
      // フォルダはフォルダ内には移動しない（上/下に配置）
      if (foldersToMove.isNotEmpty) {
        widget.channel.batchMoveLayersRelative(foldersToMove, targetId, insertAfter: true);
      }
    } else {
      // 相対位置移動（フォルダ外への移動、並び替え）
      // UI表示: 上が先頭、内部リスト: 末尾が上 → below=上に挿入=insertAfter false
      final insertAfter = pos == _DropPosition.above;
      final allToMove = [...foldersToMove, ...layersToMove];
      if (allToMove.length > 1) {
        widget.channel.batchMoveLayersRelative(allToMove, targetId, insertAfter: insertAfter);
      } else if (allToMove.length == 1) {
        final id = allToMove.first;
        final isFolder = foldersToMove.contains(id);
        if (isFolder && targetLayer.isGroup) {
          widget.channel.reorderLayerGroup(id, targetId, insertAfter: insertAfter);
        } else {
          widget.channel.reorderLayer(id, targetId, insertAfter: insertAfter);
        }
      }
    }

    _onDragEnd();
  }

  void _startHoverTimer(int folderId) {
    if (_hoverFolderId == folderId) return;
    _hoverTimer?.cancel();
    _hoverFolderId = folderId;
    _hoverTimer = Timer(const Duration(milliseconds: 500), () {
      if (mounted && _hoverFolderId == folderId) {
        setState(() => _expandedFolders.add(folderId));
      }
    });
  }

  void _cancelHoverTimer() {
    _hoverTimer?.cancel();
    _hoverFolderId = null;
  }

  // オートスクロール
  void _handleAutoScroll(double globalY) {
    if (_draggingId == null) {
      _autoScrollTimer?.cancel();
      return;
    }

    final box = context.findRenderObject() as RenderBox?;
    if (box == null) return;

    final localY = box.globalToLocal(Offset(0, globalY)).dy;
    final height = box.size.height;
    const zone = 60.0;

    if (localY < zone) {
      _startAutoScroll(-1);
    } else if (localY > height - zone) {
      _startAutoScroll(1);
    } else {
      _autoScrollTimer?.cancel();
    }
  }

  void _startAutoScroll(int direction) {
    if (_autoScrollTimer?.isActive == true) return;
    _autoScrollTimer = Timer.periodic(const Duration(milliseconds: 50), (_) {
      if (!_scrollCtrl.hasClients) return;
      final delta = direction * 6.0;
      final newOffset = (_scrollCtrl.offset + delta).clamp(
        0.0,
        _scrollCtrl.position.maxScrollExtent,
      );
      _scrollCtrl.jumpTo(newOffset);
    });
  }

  // ─── ビルド ─────────────────────────────────────────────
  @override
  Widget build(BuildContext context) {
    final displayList = _buildDisplayList();
    final folders = widget.state.layers.where((l) => l.isGroup).toList();

    return PanelCard(
      width: 280,
      maxHeight: 400,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // ヘッダー
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 8, 6),
            child: Row(
              children: [
                const Text(
                  'レイヤー',
                  style: TextStyle(
                    color: C.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const Spacer(),
                if (_selectedIds.isNotEmpty)
                  _PanelButton(
                    icon: Icons.deselect_rounded,
                    onTap: _clearSelection,
                    tooltip: '選択解除',
                  ),
                _PanelButton(
                  icon: Icons.create_new_folder_rounded,
                  onTap: () => widget.channel.createLayerGroup('フォルダ'),
                  tooltip: 'フォルダ追加',
                ),
                _PanelButton(
                  icon: Icons.add_rounded,
                  onTap: () => widget.channel.addLayer(),
                  tooltip: 'レイヤー追加',
                ),
              ],
            ),
          ),
          const Divider(height: 1, color: C.border),
          // リスト
          Flexible(
            child: ListView.builder(
              controller: _scrollCtrl,
              shrinkWrap: true,
              padding: const EdgeInsets.only(top: 4, bottom: 8),
              itemCount: displayList.length,
              itemBuilder: (context, index) {
                final layer = displayList[index];
                return _buildLayerTile(layer, folders);
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLayerTile(LayerInfo layer, List<LayerInfo> folders) {
    final isSelected = _selectedIds.contains(layer.id);
    final isExpanded = _expandedLayerId == layer.id;
    final isFolderExpanded = _expandedFolders.contains(layer.id);
    final isDragging = _draggingId == layer.id;
    final isDropTarget = _dropTargetId == layer.id && _draggingId != layer.id;

    // GlobalKey を取得または作成
    _tileKeys[layer.id] ??= GlobalKey();
    final tileKey = _tileKeys[layer.id]!;

    return LongPressDraggable<int>(
      key: ValueKey('drag_${layer.id}'),
      data: layer.id,
      delay: const Duration(milliseconds: 150),
      feedback: _DragFeedback(layer: layer, depth: layer.depth),
      childWhenDragging: Opacity(
        opacity: 0.3,
        child: _LayerTile(
          layer: layer,
          isSelected: isSelected,
          isExpanded: isExpanded,
          isFolderExpanded: isFolderExpanded,
          isDragging: true,
          dropPosition: _DropPosition.none,
          channel: widget.channel,
          selectedIds: _selectedIds,
          folders: folders,
          onTap: () {},
          onToggleFolder: () {},
          onToggleExpand: () {},
          onSwipeSelect: () {},
        ),
      ),
      onDragStarted: () => _onDragStart(layer.id),
      onDragEnd: (_) => _onDragEnd(),
      onDraggableCanceled: (_, __) => _onDragEnd(),
      child: DragTarget<int>(
        onMove: (details) {
          _handleAutoScroll(details.offset.dy);
          // GlobalKey から RenderBox を取得して座標計算
          final box = tileKey.currentContext?.findRenderObject() as RenderBox?;
          if (box != null && box.hasSize) {
            final globalPos = box.localToGlobal(Offset.zero);
            final height = box.size.height;
            // ドラッグフィードバックの中心位置を計算
            final dragY = details.offset.dy + 20;
            final localY = dragY - globalPos.dy;

            final draggingLayer = widget.state.layers
                .cast<LayerInfo?>()
                .firstWhere((l) => l?.id == _draggingId, orElse: () => null);
            final draggingIsFolder = draggingLayer?.isGroup ?? false;
            final draggingGroupId = draggingLayer?.groupId ?? 0;

            _DropPosition pos;
            if (layer.isGroup && !draggingIsFolder) {
              // レイヤー → フォルダ
              // エッジゾーン40%: 上下40%はabove/below、中央20%はinside
              final edgeZone = (height * 0.4).clamp(12.0, 20.0);
              if (localY < edgeZone) {
                pos = _DropPosition.above;
              } else if (localY > height - edgeZone) {
                pos = _DropPosition.below;
              } else {
                // フォルダ内レイヤーを同じフォルダにドロップ → above扱い（ルートに出す）
                if (draggingGroupId == layer.id) {
                  pos = _DropPosition.above;
                } else {
                  pos = _DropPosition.inside;
                }
              }
            } else {
              // それ以外: 上下のみ
              pos = localY < height / 2 ? _DropPosition.above : _DropPosition.below;
            }

            if (_dropTargetId != layer.id || _dropPosition != pos) {
              setState(() {
                _dropTargetId = layer.id;
                _dropPosition = pos;
              });
            }
          }
        },
        onLeave: (_) => _onDragLeave(),
        onAcceptWithDetails: (details) => _onDrop(layer.id),
        builder: (ctx, candidateData, rejectedData) {
          return Container(
            key: tileKey,
            child: _LayerTile(
              layer: layer,
              isSelected: isSelected,
              isExpanded: isExpanded,
              isFolderExpanded: isFolderExpanded,
              isDragging: isDragging,
              dropPosition: isDropTarget ? _dropPosition : _DropPosition.none,
              channel: widget.channel,
              selectedIds: _selectedIds,
              folders: folders,
              onTap: () => _onTapLayer(layer),
              onToggleFolder: () => _toggleFolder(layer.id),
              onToggleExpand: () => _toggleDetailExpand(layer.id),
              onSwipeSelect: () => _toggleSelection(layer.id),
            ),
          );
        },
      ),
    );
  }
}

// ─── ドラッグターゲットタイル（座標計算用ラッパー）─────────────
class _DragTargetTile extends StatefulWidget {
  final LayerInfo layer;
  final bool hasDragOver;
  final bool draggingIsFolder;
  final ValueChanged<_DropPosition> onDragPositionUpdate;
  final Widget child;

  const _DragTargetTile({
    required this.layer,
    required this.hasDragOver,
    required this.draggingIsFolder,
    required this.onDragPositionUpdate,
    required this.child,
  });

  @override
  State<_DragTargetTile> createState() => _DragTargetTileState();
}

class _DragTargetTileState extends State<_DragTargetTile> {
  @override
  void didUpdateWidget(_DragTargetTile oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.hasDragOver && !oldWidget.hasDragOver) {
      _updateDropPosition();
    }
  }

  void _updateDropPosition() {
    // ドラッグが開始されたら位置を計算
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !widget.hasDragOver) return;
      // フォルダにレイヤーをドロップする場合はデフォルトで inside
      if (widget.layer.isGroup && !widget.draggingIsFolder) {
        widget.onDragPositionUpdate(_DropPosition.inside);
      } else {
        widget.onDragPositionUpdate(_DropPosition.below);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    // Listener でポインタ位置を監視
    return Listener(
      behavior: HitTestBehavior.translucent,
      onPointerMove: widget.hasDragOver ? (event) {
        final box = context.findRenderObject() as RenderBox?;
        if (box == null || !box.hasSize) return;

        final localY = event.localPosition.dy;
        final height = box.size.height;

        _DropPosition pos;
        if (widget.layer.isGroup && !widget.draggingIsFolder) {
          // レイヤー → フォルダ: 中央はフォルダ内
          final edgeZone = (height * 0.25).clamp(10.0, 16.0);
          if (localY < edgeZone) {
            pos = _DropPosition.above;
          } else if (localY > height - edgeZone) {
            pos = _DropPosition.below;
          } else {
            pos = _DropPosition.inside;
          }
        } else {
          // それ以外: 上下のみ
          pos = localY < height / 2 ? _DropPosition.above : _DropPosition.below;
        }
        widget.onDragPositionUpdate(pos);
      } : null,
      child: widget.child,
    );
  }
}

// ─── ドロップ位置 ─────────────────────────────────────────
enum _DropPosition { none, above, below, inside }

// ─── パネルボタン ─────────────────────────────────────────
class _PanelButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;
  final String? tooltip;

  const _PanelButton({required this.icon, required this.onTap, this.tooltip});

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip ?? '',
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(4),
        child: Padding(
          padding: const EdgeInsets.all(4),
          child: Icon(icon, size: 18, color: C.textSecondary),
        ),
      ),
    );
  }
}

// ─── ドラッグフィードバック ───────────────────────────────
class _DragFeedback extends StatelessWidget {
  final LayerInfo layer;
  final int depth;

  const _DragFeedback({required this.layer, required this.depth});

  @override
  Widget build(BuildContext context) {
    return Material(
      elevation: 8,
      borderRadius: BorderRadius.circular(8),
      shadowColor: C.accent.withAlpha(100),
      child: Container(
        width: 220,
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        decoration: BoxDecoration(
          color: C.card,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: C.accent, width: 1),
        ),
        child: Row(
          children: [
            if (layer.isGroup)
              Icon(Icons.folder_rounded, size: 16, color: C.accent)
            else
              Icon(Icons.layers_rounded, size: 16, color: C.textSecondary),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                layer.name,
                style: TextStyle(
                  color: layer.isActive ? C.accent : C.textPrimary,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── レイヤータイル ───────────────────────────────────────
class _LayerTile extends StatefulWidget {
  final LayerInfo layer;
  final bool isSelected;
  final bool isExpanded;
  final bool isFolderExpanded;
  final bool isDragging;
  final _DropPosition dropPosition;
  final PaintChannel channel;
  final Set<int> selectedIds;
  final List<LayerInfo> folders;
  final VoidCallback onTap;
  final VoidCallback onToggleFolder;
  final VoidCallback onToggleExpand;
  final VoidCallback onSwipeSelect;

  const _LayerTile({
    required this.layer,
    required this.isSelected,
    required this.isExpanded,
    required this.isFolderExpanded,
    required this.isDragging,
    required this.dropPosition,
    required this.channel,
    required this.selectedIds,
    required this.folders,
    required this.onTap,
    required this.onToggleFolder,
    required this.onToggleExpand,
    required this.onSwipeSelect,
  });

  @override
  State<_LayerTile> createState() => _LayerTileState();
}

class _LayerTileState extends State<_LayerTile> with SingleTickerProviderStateMixin {
  double _swipeOffset = 0;
  late AnimationController _animCtrl;
  late Animation<double> _anim;

  static const double _swipeThreshold = 50;
  static const double _maxSwipe = 80;

  @override
  void initState() {
    super.initState();
    _animCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 180),
    );
    _anim = Tween<double>(begin: 0, end: 0).animate(
      CurvedAnimation(parent: _animCtrl, curve: Curves.easeOut),
    );
  }

  @override
  void dispose() {
    _animCtrl.dispose();
    super.dispose();
  }

  void _animateTo(double target) {
    _anim = Tween<double>(begin: _swipeOffset, end: target).animate(
      CurvedAnimation(parent: _animCtrl, curve: Curves.easeOut),
    );
    _animCtrl.forward(from: 0).then((_) {
      if (mounted) setState(() => _swipeOffset = target);
    });
  }

  @override
  Widget build(BuildContext context) {
    final depth = widget.layer.depth;
    final leftMargin = depth * 16.0 + 6.0;

    return AnimatedBuilder(
      animation: _anim,
      builder: (context, child) {
        final offset = _animCtrl.isAnimating ? _anim.value : _swipeOffset;

        return Stack(
          children: [
            // ドロップインジケーター
            if (widget.dropPosition == _DropPosition.above)
              Positioned(
                top: 0,
                left: leftMargin,
                right: 6,
                child: Container(height: 2, color: C.accent),
              ),
            if (widget.dropPosition == _DropPosition.below)
              Positioned(
                bottom: 0,
                left: leftMargin,
                right: 6,
                child: Container(height: 2, color: C.accent),
              ),

            // スワイプ背景
            if (offset.abs() > 4)
              Positioned.fill(
                child: Container(
                  margin: EdgeInsets.only(left: leftMargin, right: 6, top: 2, bottom: 2),
                  decoration: BoxDecoration(
                    color: widget.isSelected
                        ? C.error.withAlpha(20)
                        : C.accent.withAlpha(20),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  alignment: offset > 0 ? Alignment.centerLeft : Alignment.centerRight,
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        widget.isSelected
                            ? Icons.remove_circle_outline_rounded
                            : Icons.add_circle_outline_rounded,
                        size: 18,
                        color: widget.isSelected ? C.error : C.accent,
                      ),
                      const SizedBox(width: 4),
                      Text(
                        widget.isSelected ? '選択解除' : '複数選択',
                        style: TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                          color: widget.isSelected ? C.error : C.accent,
                        ),
                      ),
                    ],
                  ),
                ),
              ),

            // メインコンテンツ
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onHorizontalDragUpdate: (d) {
                setState(() {
                  _swipeOffset = (_swipeOffset + d.delta.dx).clamp(-_maxSwipe, _maxSwipe);
                });
              },
              onHorizontalDragEnd: (d) {
                if (_swipeOffset.abs() > _swipeThreshold) {
                  widget.onSwipeSelect();
                }
                _animateTo(0);
              },
              child: Transform.translate(
                offset: Offset(offset, 0),
                child: _buildContent(leftMargin),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _buildContent(double leftMargin) {
    final layer = widget.layer;
    final isFolder = layer.isGroup;

    // フォルダ内ドロップ時のハイライト
    final isInsideTarget = widget.dropPosition == _DropPosition.inside;

    return AnimatedContainer(
      duration: const Duration(milliseconds: 150),
      margin: EdgeInsets.only(left: leftMargin, right: 6, top: 2, bottom: 2),
      decoration: BoxDecoration(
        color: isInsideTarget
            ? C.accent.withAlpha(60)
            : widget.isSelected
                ? C.accent.withAlpha(30)
                : layer.isActive
                    ? C.accentDim.withAlpha(40)
                    : C.card,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: isInsideTarget
              ? C.accent
              : widget.isSelected
                  ? C.accent.withAlpha(180)
                  : layer.isActive
                      ? C.accent.withAlpha(60)
                      : Colors.transparent,
          width: isInsideTarget ? 2 : 1,
        ),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          InkWell(
            onTap: widget.onTap,
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              child: Row(
                children: [
                  // フォルダアイコン or 可視性
                  if (isFolder)
                    GestureDetector(
                      onTap: widget.onToggleFolder,
                      behavior: HitTestBehavior.opaque,
                      child: Padding(
                        padding: const EdgeInsets.only(right: 4),
                        child: Icon(
                          widget.isFolderExpanded
                              ? Icons.folder_open_rounded
                              : Icons.folder_rounded,
                          size: 18,
                          color: C.accent,
                        ),
                      ),
                    )
                  else if (widget.isSelected)
                    const Padding(
                      padding: EdgeInsets.only(right: 4),
                      child: Icon(Icons.check_circle_rounded, size: 18, color: C.accent),
                    )
                  else
                    _ToggleIcon(
                      icon: Icons.visibility_rounded,
                      offIcon: Icons.visibility_off_rounded,
                      active: layer.isVisible,
                      activeColor: Colors.white,
                      onTap: () {
                        if (widget.selectedIds.length > 1 && widget.selectedIds.contains(layer.id)) {
                          widget.channel.batchSetVisibility(widget.selectedIds.toList(), !layer.isVisible);
                        } else {
                          widget.channel.setLayerVisibility(layer.id, !layer.isVisible);
                        }
                      },
                    ),

                  // レイヤー属性トグル
                  if (!isFolder) ...[
                    const SizedBox(width: 4),
                    _ToggleIcon(
                      icon: Icons.content_cut_rounded,
                      active: layer.isClipToBelow,
                      activeColor: C.accent,
                      onTap: () => widget.channel.setLayerClip(layer.id, !layer.isClipToBelow),
                    ),
                    const SizedBox(width: 4),
                    _ToggleIcon(
                      icon: layer.isLocked ? Icons.lock_rounded : Icons.lock_open_rounded,
                      active: layer.isLocked,
                      activeColor: C.error,
                      onTap: () => widget.channel.setLayerLocked(layer.id, !layer.isLocked),
                    ),
                    const SizedBox(width: 4),
                    _ToggleIcon(
                      icon: Icons.grid_on_rounded,
                      active: layer.isAlphaLocked,
                      activeColor: C.accent,
                      onTap: () => widget.channel.setAlphaLocked(layer.id, !layer.isAlphaLocked),
                    ),
                    const SizedBox(width: 8),
                  ],

                  // レイヤー名
                  Expanded(
                    child: Text(
                      layer.name,
                      style: TextStyle(
                        color: layer.isActive ? C.accent : C.textPrimary,
                        fontSize: 12,
                        fontWeight: layer.isActive
                            ? FontWeight.w600
                            : isFolder
                                ? FontWeight.w500
                                : FontWeight.normal,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),

                  // 不透明度 / 展開アイコン
                  if (!isFolder)
                    Text(
                      '${(layer.opacity * 100).round()}%',
                      style: const TextStyle(color: C.textSecondary, fontSize: 11),
                    ),
                  const SizedBox(width: 4),
                  GestureDetector(
                    onTap: isFolder ? widget.onToggleFolder : widget.onToggleExpand,
                    child: Icon(
                      isFolder
                          ? (widget.isFolderExpanded
                              ? Icons.expand_less_rounded
                              : Icons.expand_more_rounded)
                          : (widget.isExpanded
                              ? Icons.expand_less_rounded
                              : Icons.expand_more_rounded),
                      size: 20,
                      color: C.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
          ),

          // 詳細パネル
          if (widget.isExpanded && !isFolder)
            _LayerDetails(
              layer: layer,
              channel: widget.channel,
              selectedIds: widget.selectedIds,
            ),
        ],
      ),
    );
  }
}

// ─── トグルアイコン ───────────────────────────────────────
class _ToggleIcon extends StatelessWidget {
  final IconData icon;
  final IconData? offIcon;
  final bool active;
  final Color activeColor;
  final VoidCallback onTap;

  const _ToggleIcon({
    required this.icon,
    this.offIcon,
    required this.active,
    required this.activeColor,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 100),
        child: Icon(
          active ? icon : (offIcon ?? icon),
          size: 16,
          color: active ? activeColor : C.textSecondary.withAlpha(100),
        ),
      ),
    );
  }
}

// ─── レイヤー詳細 ─────────────────────────────────────────
class _LayerDetails extends StatelessWidget {
  final LayerInfo layer;
  final PaintChannel channel;
  final Set<int> selectedIds;

  const _LayerDetails({
    required this.layer,
    required this.channel,
    required this.selectedIds,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 0, 8, 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 不透明度スライダー
          PanelSlider(
            label: '不透明度',
            value: '${(layer.opacity * 100).round()}%',
            current: layer.opacity,
            curve: 1.5,
            onChanged: (v) {
              if (selectedIds.length > 1 && selectedIds.contains(layer.id)) {
                channel.batchSetOpacity(selectedIds.toList(), v);
              } else {
                channel.setLayerOpacity(layer.id, v);
              }
            },
          ),
          const SizedBox(height: 8),

          // アクションボタン
          Row(
            children: [
              Expanded(
                child: _ActionButton(
                  icon: Icons.copy_rounded,
                  label: '複製',
                  onTap: () => channel.duplicateLayer(layer.id),
                ),
              ),
              const SizedBox(width: 4),
              Expanded(
                child: _ActionButton(
                  icon: Icons.merge_rounded,
                  label: '結合',
                  onTap: () => channel.mergeDown(layer.id),
                ),
              ),
              const SizedBox(width: 4),
              Expanded(
                child: _ActionButton(
                  icon: Icons.delete_outline_rounded,
                  label: '削除',
                  color: C.error,
                  onTap: () => channel.removeLayer(layer.id),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// ─── アクションボタン ─────────────────────────────────────
class _ActionButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color? color;
  final VoidCallback onTap;

  const _ActionButton({
    required this.icon,
    required this.label,
    this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final c = color ?? C.textSecondary;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(6),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 6),
        decoration: BoxDecoration(
          color: c.withAlpha(15),
          borderRadius: BorderRadius.circular(6),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 14, color: c),
            const SizedBox(width: 4),
            Text(
              label,
              style: TextStyle(fontSize: 11, color: c, fontWeight: FontWeight.w500),
            ),
          ],
        ),
      ),
    );
  }
}
