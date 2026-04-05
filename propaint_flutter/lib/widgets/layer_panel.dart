import 'package:flutter/material.dart';
import 'dart:async';

import '../models/paint_state.dart';
import '../services/paint_channel.dart';
import '../theme/app_colors.dart';
import 'panel_card.dart';

class LayerPanel extends StatefulWidget {
  final PaintState state;
  final PaintChannel channel;

  const LayerPanel({super.key, required this.state, required this.channel});

  @override
  State<LayerPanel> createState() => _LayerPanelState();
}

class _LayerPanelState extends State<LayerPanel> {
  int? _expandedLayerId;
  final Set<int> _selectedIds = {};
  final Set<int> _expandedGroupIds = {};

  // ドラッグ関連の状態
  int? _draggingId;           // ドラッグ中のアイテムID
  int? _dragOverGroupId;      // ホバー中のフォルダID（0.5秒タイマー用）
  int? _dropInsertIndex;      // ドロップ挿入インデックス
  bool _dropIntoFolder = false; // true=フォルダ内、false=フォルダ間
  Timer? _hoverTimer;         // 0.5秒ホバー展開タイマー

  @override
  void dispose() {
    _hoverTimer?.cancel();
    super.dispose();
  }

  void _toggleSelection(int id) {
    setState(() {
      if (_selectedIds.contains(id)) {
        _selectedIds.remove(id);
      } else {
        _selectedIds.add(id);
      }
    });
  }

  void _clearSelection() {
    setState(() => _selectedIds.clear());
  }

  void _toggleGroupExpanded(int groupId) {
    setState(() {
      if (_expandedGroupIds.contains(groupId)) {
        _expandedGroupIds.remove(groupId);
      } else {
        _expandedGroupIds.add(groupId);
      }
    });
  }

  /// 0.5秒ホバーでフォルダを自動展開
  void _onDragHoverFolder(int folderId) {
    if (_dragOverGroupId == folderId) return; // 同じフォルダなら無視
    _hoverTimer?.cancel();
    setState(() => _dragOverGroupId = folderId);
    _hoverTimer = Timer(const Duration(milliseconds: 500), () {
      if (_dragOverGroupId == folderId && mounted && !_expandedGroupIds.contains(folderId)) {
        setState(() => _expandedGroupIds.add(folderId));
      }
    });
  }

  /// ドラッグがフォルダから離れた時
  void _onDragLeaveFolder() {
    _hoverTimer?.cancel();
    setState(() => _dragOverGroupId = null);
  }

  /// 選択レイヤーを一括結合
  void _mergeSelected() {
    if (_selectedIds.length < 2) return;
    widget.channel.batchMergeLayers(_selectedIds.toList());
    _clearSelection();
  }

  /// 選択レイヤーを一括削除
  void _deleteSelected() {
    for (final id in _selectedIds.toList()) {
      final layer = widget.state.layers.firstWhere((l) => l.id == id, orElse: () => LayerInfo(id: 0, name: ''));
      if (layer.isGroup) {
        widget.channel.deleteLayerGroup(-id);
      } else {
        widget.channel.removeLayer(id);
      }
    }
    _clearSelection();
  }

  /// フォルダ作成ダイアログを表示
  void _showCreateFolderDialog(BuildContext context) {
    final nameController = TextEditingController(text: 'フォルダ');
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('新規フォルダ'),
        content: TextField(
          controller: nameController,
          decoration: const InputDecoration(hintText: 'フォルダ名'),
          onSubmitted: (val) {
            if (val.isNotEmpty) {
              widget.channel.createLayerGroup(val);
              Navigator.pop(context);
            }
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('キャンセル'),
          ),
          TextButton(
            onPressed: () {
              if (nameController.text.isNotEmpty) {
                widget.channel.createLayerGroup(nameController.text);
                Navigator.pop(context);
              }
            },
            child: const Text('作成'),
          ),
        ],
      ),
    );
  }

  /// レイヤーをグループに移動
  void _moveLayerToGroup(int layerId, int groupId) {
    widget.channel.setLayerGroup(layerId, groupId);
  }

  /// ドロップ処理
  void _onDropLayer(int draggedId, int targetIndex, bool intoFolder) {
    if (targetIndex < 0 || targetIndex >= displayItems.length) return;

    final oldItem = displayItems.firstWhere((item) => item.layer.id == draggedId, orElse: () => displayItems[0]);
    final targetItem = displayItems[targetIndex];

    if (intoFolder && targetItem.layer.isGroup) {
      // フォルダ内に移動
      widget.channel.setLayerGroup(draggedId, -targetItem.layer.id);
    } else {
      // レイヤー順序の変更
      if (!oldItem.layer.isGroup && !targetItem.layer.isGroup) {
        widget.channel.reorderLayer(oldItem.docIndex, targetItem.docIndex);
      } else if (oldItem.layer.isGroup && targetItem.layer.isGroup) {
        widget.channel.reorderLayerGroup(-oldItem.layer.id, -targetItem.layer.id);
      }
    }

    setState(() {
      _draggingId = null;
      _dropInsertIndex = null;
      _dragOverGroupId = null;
    });
  }

  List<_LayerDisplayItem> displayItems = [];

  @override
  Widget build(BuildContext context) {
    final allLayers = widget.state.layers.reversed.toList();
    final hasSelection = _selectedIds.isNotEmpty;

    final folders = allLayers.where((l) => l.isGroup).toList();
    final nonGroupLayers = allLayers.where((l) => !l.isGroup).toList();

    // displayItems を構築
    displayItems = <_LayerDisplayItem>[];
    for (final folder in folders) {
      final folderDocIndex = allLayers.indexWhere((l) => l.id == folder.id);
      displayItems.add(_LayerDisplayItem(
        layer: folder,
        depth: 0,
        docIndex: folderDocIndex,
      ));
      if (_expandedGroupIds.contains(folder.id)) {
        final groupLayers = nonGroupLayers.where((l) => l.groupId == folder.id).toList();
        for (final layer in groupLayers) {
          final layerDocIndex = allLayers.indexWhere((l) => l.id == layer.id);
          displayItems.add(_LayerDisplayItem(
            layer: layer,
            depth: 1,
            parentGroupId: folder.id,
            docIndex: layerDocIndex,
          ));
        }
      }
    }

    for (final layer in nonGroupLayers) {
      if (layer.groupId == 0) {
        final layerDocIndex = allLayers.indexWhere((l) => l.id == layer.id);
        displayItems.add(_LayerDisplayItem(
          layer: layer,
          depth: 0,
          docIndex: layerDocIndex,
        ));
      }
    }

    return PanelCard(
      width: 280,
      maxHeight: 440,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // ヘッダー
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 10, 8, 4),
            child: Row(
              children: [
                if (hasSelection) ...[
                  GestureDetector(
                    onTap: _clearSelection,
                    child: const Icon(Icons.close_rounded, size: 18, color: C.textSecondary),
                  ),
                  const SizedBox(width: 6),
                  Text(
                    '${_selectedIds.length}件選択',
                    style: const TextStyle(color: C.accent, fontSize: 13, fontWeight: FontWeight.w600),
                  ),
                  const Spacer(),
                  if (_selectedIds.length >= 2)
                    _SmallTextButton(label: '結合', onTap: _mergeSelected),
                  _SmallIconButton(
                    icon: Icons.delete_outline_rounded,
                    color: C.error,
                    tooltip: '選択削除',
                    onTap: _deleteSelected,
                  ),
                ] else ...[
                  const Text('レイヤー', style: TextStyle(color: C.textPrimary, fontSize: 14, fontWeight: FontWeight.w600)),
                  const Spacer(),
                  _SmallIconButton(
                    icon: Icons.folder_rounded,
                    color: C.textSecondary,
                    onTap: () => _showCreateFolderDialog(context),
                    tooltip: 'フォルダ追加',
                  ),
                  _SmallIconButton(
                    icon: Icons.add_rounded,
                    color: C.accent,
                    onTap: widget.channel.addLayer,
                    tooltip: 'レイヤー追加',
                  ),
                ],
              ],
            ),
          ),
          const Divider(color: C.border, height: 1),

          // レイヤー一覧（カスタムドラッグ＆ドロップ対応）
          Flexible(
            child: ListView.builder(
              shrinkWrap: true,
              padding: const EdgeInsets.only(bottom: 8),
              itemCount: displayItems.length,
              itemBuilder: (context, i) {
                final item = displayItems[i];
                final layer = item.layer;
                final expanded = _expandedLayerId == layer.id;
                final selected = _selectedIds.contains(layer.id);
                final isFolder = layer.isGroup;
                final isExpandedGroup = isFolder && _expandedGroupIds.contains(layer.id);
                final isDragging = _draggingId == layer.id;
                final showDropIndicator = _dropInsertIndex == i;

                return LongPressDraggable<int>(
                  data: layer.id,
                  feedback: _buildDragFeedback(layer, item.depth),
                  childWhenDragging: Opacity(
                    opacity: 0.5,
                    child: _buildLayerItem(
                      context: context,
                      layer: layer,
                      item: item,
                      expanded: expanded,
                      selected: selected,
                      isFolder: isFolder,
                      isExpandedGroup: isExpandedGroup,
                      isDragging: isDragging,
                      folders: folders,
                      isDragOverTarget: _dragOverGroupId == layer.id && isFolder,
                      showDropIndicator: false,
                    ),
                  ),
                  onDragStarted: () {
                    setState(() => _draggingId = layer.id);
                  },
                  onDragEnd: (_) {
                    setState(() {
                      _draggingId = null;
                      _dropInsertIndex = null;
                      _dragOverGroupId = null;
                    });
                    _hoverTimer?.cancel();
                  },
                  child: DragTarget<int>(
                    onMove: (details) {
                      // フォルダの自動展開（ホバーディレイ）
                      if (isFolder) {
                        _onDragHoverFolder(layer.id);
                      }
                      setState(() {
                        _dropInsertIndex = i;
                        _dropIntoFolder = isFolder;
                      });
                    },
                    onLeave: (_) {
                      if (isFolder) {
                        _onDragLeaveFolder();
                      }
                    },
                    onAcceptWithDetails: (details) {
                      _onDropLayer(details.data, i, isFolder);
                    },
                    builder: (context, candidateData, rejectedData) {
                      return Stack(
                        children: [
                          // インデント視覚ガイド（青い横線）
                          if (showDropIndicator && !_dropIntoFolder)
                            Positioned(
                              top: 0,
                              left: item.depth * 16.0 + 6.0,
                              right: 6,
                              child: Container(
                                height: 2,
                                color: C.accent,
                              ),
                            ),
                          if (showDropIndicator && _dropIntoFolder && isFolder)
                            Positioned(
                              top: 40,
                              left: (item.depth + 1) * 16.0 + 6.0,
                              right: 6,
                              child: Container(
                                height: 2,
                                color: C.accent,
                              ),
                            ),
                          // レイヤーアイテム
                          _buildLayerItem(
                            context: context,
                            layer: layer,
                            item: item,
                            expanded: expanded,
                            selected: selected,
                            isFolder: isFolder,
                            isExpandedGroup: isExpandedGroup,
                            isDragging: isDragging,
                            folders: folders,
                            isDragOverTarget: candidateData.isNotEmpty && isFolder,
                            showDropIndicator: showDropIndicator && _dropIntoFolder && isFolder,
                          ),
                        ],
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  /// ドラッグ中のゴースト表示
  Widget _buildDragFeedback(LayerInfo layer, int depth) {
    return Material(
      elevation: 8,
      borderRadius: BorderRadius.circular(8),
      shadowColor: C.accent.withAlpha(100),
      child: Container(
        width: 260,
        margin: EdgeInsets.only(left: depth * 16.0 + 6.0, right: 6),
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
        decoration: BoxDecoration(
          color: C.card,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(
                layer.name,
                style: TextStyle(
                  color: layer.isActive ? C.accent : C.textPrimary,
                  fontSize: 12,
                  fontWeight: layer.isActive ? FontWeight.w600 : FontWeight.normal,
                ),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// レイヤーアイテムの構築
  Widget _buildLayerItem({
    required BuildContext context,
    required LayerInfo layer,
    required _LayerDisplayItem item,
    required bool expanded,
    required bool selected,
    required bool isFolder,
    required bool isExpandedGroup,
    required bool isDragging,
    required List<LayerInfo> folders,
    required bool isDragOverTarget,
    required bool showDropIndicator,
  }) {
    return _SwipeableLayerItem(
      key: ValueKey('${layer.id}_${item.depth}'),
      layer: layer,
      expanded: expanded,
      selected: selected,
      depth: item.depth,
      parentGroupId: item.parentGroupId,
      isExpandedGroup: isExpandedGroup,
      isDragOverTarget: isDragOverTarget,
      channel: widget.channel,
      onTap: () {
        if (_selectedIds.isNotEmpty) {
          _toggleSelection(layer.id);
        } else {
          if (isFolder) {
            _toggleGroupExpanded(layer.id);
          } else {
            widget.channel.selectLayer(layer.id);
          }
        }
      },
      onToggleExpand: () {
        setState(() => _expandedLayerId = expanded ? null : layer.id);
      },
      onToggleGroup: () {
        _toggleGroupExpanded(layer.id);
      },
      onSwipeSelect: () => _toggleSelection(layer.id),
      onMoveToGroup: (layerId, groupId) => _moveLayerToGroup(layerId, groupId),
      availableGroups: folders.where((g) => g.id != layer.id).toList(),
    );
  }
}

class _LayerDisplayItem {
  final LayerInfo layer;
  final int depth;
  final int? parentGroupId;
  final int docIndex;

  _LayerDisplayItem({
    required this.layer,
    required this.depth,
    this.parentGroupId,
    required this.docIndex,
  });
}

/// スワイプ可能なレイヤーアイテム
class _SwipeableLayerItem extends StatefulWidget {
  final LayerInfo layer;
  final bool expanded;
  final bool selected;
  final int depth;
  final int? parentGroupId;
  final bool isExpandedGroup;
  final bool isDragOverTarget;
  final PaintChannel channel;
  final VoidCallback onTap;
  final VoidCallback onToggleExpand;
  final VoidCallback onToggleGroup;
  final VoidCallback onSwipeSelect;
  final Function(int, int) onMoveToGroup;
  final List<LayerInfo> availableGroups;

  const _SwipeableLayerItem({
    required super.key,
    required this.layer,
    required this.expanded,
    required this.selected,
    required this.depth,
    this.parentGroupId,
    required this.isExpandedGroup,
    required this.isDragOverTarget,
    required this.channel,
    required this.onTap,
    required this.onToggleExpand,
    required this.onToggleGroup,
    required this.onSwipeSelect,
    required this.onMoveToGroup,
    required this.availableGroups,
  });

  @override
  State<_SwipeableLayerItem> createState() => _SwipeableLayerItemState();
}

class _SwipeableLayerItemState extends State<_SwipeableLayerItem>
    with SingleTickerProviderStateMixin {
  late AnimationController _animCtrl;
  late Animation<double> _anim;
  double _dragOffset = 0;
  static const double _leftRevealWidth = 120;
  static const double _rightTriggerWidth = 80;

  @override
  void initState() {
    super.initState();
    _animCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 200),
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
    _anim = Tween<double>(begin: _dragOffset, end: target).animate(
      CurvedAnimation(parent: _animCtrl, curve: Curves.easeOut),
    );
    _animCtrl.forward(from: 0).then((_) {
      if (mounted) setState(() => _dragOffset = target);
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _anim,
      builder: (context, child) {
        final offset = _animCtrl.isAnimating ? _anim.value : _dragOffset;
        return SizedBox(
          height: null,
          child: Stack(
            children: [
              // 左スワイプ背景
              if (offset < -4)
                Positioned.fill(
                  child: Container(
                    alignment: Alignment.centerRight,
                    padding: const EdgeInsets.only(right: 4),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (!widget.layer.isGroup) ...[
                          _ActionChip(
                            icon: Icons.copy_rounded,
                            label: '複製',
                            color: C.accent,
                            onTap: () {
                              widget.channel.duplicateLayer(widget.layer.id);
                              _animateTo(0);
                            },
                          ),
                          const SizedBox(width: 2),
                          _ActionChip(
                            icon: Icons.merge_rounded,
                            label: '結合',
                            color: C.textSecondary,
                            onTap: () {
                              widget.channel.mergeDown(widget.layer.id);
                              _animateTo(0);
                            },
                          ),
                          const SizedBox(width: 2),
                        ],
                        _ActionChip(
                          icon: Icons.delete_rounded,
                          label: '削除',
                          color: C.error,
                          onTap: () {
                            if (widget.layer.isGroup) {
                              widget.channel.deleteLayerGroup(-widget.layer.id);
                            } else {
                              widget.channel.removeLayer(widget.layer.id);
                            }
                            _animateTo(0);
                          },
                        ),
                      ],
                    ),
                  ),
                ),

              // 右スワイプ背景
              if (offset > 4)
                Positioned.fill(
                  child: Container(
                    alignment: Alignment.centerLeft,
                    padding: const EdgeInsets.only(left: 12),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          widget.selected ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded,
                          size: 18,
                          color: widget.selected ? C.accent : C.textSecondary,
                        ),
                        const SizedBox(width: 4),
                        Text(
                          widget.selected ? '選択解除' : '選択',
                          style: const TextStyle(color: C.textSecondary, fontSize: 11),
                        ),
                      ],
                    ),
                  ),
                ),

              // スライドするレイヤーカード
              GestureDetector(
                onHorizontalDragUpdate: (d) {
                  setState(() {
                    _dragOffset = (_dragOffset + d.delta.dx).clamp(-_leftRevealWidth, _rightTriggerWidth);
                  });
                },
                onHorizontalDragEnd: (d) {
                  if (_dragOffset < -50) {
                    _animateTo(-_leftRevealWidth);
                  } else if (_dragOffset > 40) {
                    widget.onSwipeSelect();
                    _animateTo(0);
                  } else {
                    _animateTo(0);
                  }
                },
                child: Transform.translate(
                  offset: Offset(offset, 0),
                  child: _LayerItem(
                    layer: widget.layer,
                    expanded: widget.expanded,
                    selected: widget.selected,
                    depth: widget.depth,
                    isExpandedGroup: widget.isExpandedGroup,
                    isDragOverTarget: widget.isDragOverTarget,
                    channel: widget.channel,
                    onTap: widget.onTap,
                    onToggleExpand: widget.onToggleExpand,
                    onToggleGroup: widget.onToggleGroup,
                    onMoveToGroup: widget.onMoveToGroup,
                    availableGroups: widget.availableGroups,
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _ActionChip extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;

  const _ActionChip({
    required this.icon,
    required this.label,
    required this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
        decoration: BoxDecoration(
          color: color.withAlpha(30),
          borderRadius: BorderRadius.circular(6),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 16, color: color),
            const SizedBox(height: 1),
            Text(label, style: TextStyle(color: color, fontSize: 9)),
          ],
        ),
      ),
    );
  }
}

class _LayerItem extends StatelessWidget {
  final LayerInfo layer;
  final bool expanded;
  final bool selected;
  final int depth;
  final bool isExpandedGroup;
  final bool isDragOverTarget;
  final PaintChannel channel;
  final VoidCallback onTap;
  final VoidCallback onToggleExpand;
  final VoidCallback onToggleGroup;
  final Function(int, int) onMoveToGroup;
  final List<LayerInfo> availableGroups;

  const _LayerItem({
    required this.layer,
    required this.expanded,
    required this.selected,
    required this.depth,
    required this.isExpandedGroup,
    required this.isDragOverTarget,
    required this.channel,
    required this.onTap,
    required this.onToggleExpand,
    required this.onToggleGroup,
    required this.onMoveToGroup,
    required this.availableGroups,
  });

  @override
  Widget build(BuildContext context) {
    final leftMargin = depth * 16.0 + 6.0;

    return Container(
      margin: EdgeInsets.only(left: leftMargin, right: 6, top: 2, bottom: 2),
      decoration: BoxDecoration(
        color: isDragOverTarget && layer.isGroup
            ? C.accent.withAlpha(80)
            : selected
                ? C.accent.withAlpha(40)
                : layer.isActive ? C.accentDim.withAlpha(60) : C.card,
        borderRadius: BorderRadius.circular(8),
        border: isDragOverTarget && layer.isGroup
            ? Border.all(color: C.accent, width: 2)
            : selected
                ? Border.all(color: C.accent, width: 1.5)
                : layer.isActive
                    ? Border.all(color: C.accent.withAlpha(80), width: 1)
                    : null,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              child: Row(
                children: [
                  if (layer.isGroup)
                    GestureDetector(
                      onTap: onToggleGroup,
                      child: Padding(
                        padding: const EdgeInsets.only(right: 4),
                        child: Icon(
                          Icons.folder_rounded,
                          size: 18,
                          color: C.accent,
                        ),
                      ),
                    )
                  else if (selected)
                    const Padding(
                      padding: EdgeInsets.only(right: 4),
                      child: Icon(Icons.check_circle_rounded, size: 18, color: C.accent),
                    )
                  else ...[
                    _TinyToggle(
                      icon: Icons.visibility_rounded,
                      offIcon: Icons.visibility_off_rounded,
                      active: layer.isVisible,
                      activeColor: Colors.white,
                      onTap: () => channel.setLayerVisibility(layer.id, !layer.isVisible),
                    ),
                    const SizedBox(width: 4),
                  ],
                  if (!layer.isGroup) ...[
                    _TinyToggle(
                      icon: Icons.content_cut_rounded,
                      active: layer.isClipToBelow,
                      activeColor: C.accent,
                      onTap: () => channel.setLayerClip(layer.id, !layer.isClipToBelow),
                    ),
                    const SizedBox(width: 4),
                    _TinyToggle(
                      icon: layer.isLocked ? Icons.lock_rounded : Icons.lock_open_rounded,
                      active: layer.isLocked,
                      activeColor: C.error,
                      onTap: () => channel.setLayerLocked(layer.id, !layer.isLocked),
                    ),
                    const SizedBox(width: 4),
                    _TinyToggle(
                      icon: Icons.grid_on_rounded,
                      active: layer.isAlphaLocked,
                      activeColor: C.accent,
                      onTap: () => channel.setAlphaLocked(layer.id, !layer.isAlphaLocked),
                    ),
                    const SizedBox(width: 8),
                  ],
                  Expanded(
                    child: Text(
                      layer.name,
                      style: TextStyle(
                        color: layer.isActive ? C.accent : C.textPrimary,
                        fontSize: 12,
                        fontWeight: layer.isActive ? FontWeight.w600 : (layer.isGroup ? FontWeight.w500 : FontWeight.normal),
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  if (!layer.isGroup)
                    Text(
                      '${(layer.opacity * 100).round()}%',
                      style: const TextStyle(color: C.textSecondary, fontSize: 11),
                    ),
                  if (!layer.isGroup) const SizedBox(width: 4),
                  if (layer.isGroup)
                    GestureDetector(
                      onTap: onToggleGroup,
                      child: Icon(
                        isExpandedGroup ? Icons.expand_less_rounded : Icons.expand_more_rounded,
                        size: 20,
                        color: C.textSecondary,
                      ),
                    )
                  else if (availableGroups.isNotEmpty)
                    PopupMenuButton<int>(
                      itemBuilder: (context) => [
                        const PopupMenuItem(
                          value: 0,
                          child: Text('フォルダなし'),
                        ),
                        ...availableGroups.map((group) =>
                          PopupMenuItem(
                            value: group.id,
                            child: Text(group.name),
                          ),
                        ),
                      ],
                      onSelected: (groupId) {
                        if (groupId == 0) {
                          onMoveToGroup(layer.id, 0);
                        } else {
                          onMoveToGroup(layer.id, -groupId);
                        }
                      },
                      child: Icon(
                        Icons.more_vert_rounded,
                        size: 20,
                        color: C.textSecondary,
                      ),
                    )
                  else
                    GestureDetector(
                      onTap: onToggleExpand,
                      child: Icon(
                        expanded ? Icons.expand_less_rounded : Icons.expand_more_rounded,
                        size: 20,
                        color: C.textSecondary,
                      ),
                    ),
                ],
              ),
            ),
          ),

          if (expanded && !layer.isGroup) _LayerExpanded(layer: layer, channel: channel),
        ],
      ),
    );
  }
}

class _LayerExpanded extends StatelessWidget {
  final LayerInfo layer;
  final PaintChannel channel;

  const _LayerExpanded({required this.layer, required this.channel});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 0, 8, 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          PanelSlider(
            label: '不透明度',
            value: '${(layer.opacity * 100).round()}%',
            current: layer.opacity,
            curve: 1.5,
            onChanged: (v) => channel.setLayerOpacity(layer.id, v),
          ),

          const SizedBox(height: 4),

          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(
              children: [
                const SizedBox(
                  width: 72,
                  child: Text('合成', style: TextStyle(color: C.textSecondary, fontSize: 12)),
                ),
                Expanded(
                  child: Container(
                    height: 30,
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    decoration: BoxDecoration(
                      color: C.surface,
                      borderRadius: BorderRadius.circular(6),
                      border: Border.all(color: C.border, width: 0.5),
                    ),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<int>(
                        value: layer.blendMode.clamp(0, kBlendModeNames.length - 1),
                        isExpanded: true,
                        dropdownColor: C.card,
                        style: const TextStyle(color: C.textPrimary, fontSize: 12),
                        iconSize: 18,
                        iconEnabledColor: C.textSecondary,
                        items: List.generate(kBlendModeNames.length, (i) {
                          return DropdownMenuItem(value: i, child: Text(kBlendModeNames[i]));
                        }),
                        onChanged: (v) {
                          if (v != null) channel.setLayerBlendMode(layer.id, v);
                        },
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 8),

          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              _SmallTextButton(
                label: '複製',
                onTap: () => channel.duplicateLayer(layer.id),
              ),
              _SmallTextButton(
                label: '結合',
                onTap: () => channel.mergeDown(layer.id),
              ),
              const SizedBox(width: 4),
              _SmallIconButton(
                icon: Icons.delete_outline_rounded,
                color: C.error,
                tooltip: '削除',
                onTap: () => channel.removeLayer(layer.id),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _TinyToggle extends StatelessWidget {
  final IconData icon;
  final IconData? offIcon;
  final bool active;
  final Color activeColor;
  final VoidCallback onTap;

  const _TinyToggle({
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
      child: Icon(
        active ? icon : (offIcon ?? icon),
        size: 18,
        color: active ? activeColor : C.disabled,
      ),
    );
  }
}

class _SmallIconButton extends StatelessWidget {
  final IconData icon;
  final Color? color;
  final String? tooltip;
  final VoidCallback onTap;

  const _SmallIconButton({
    required this.icon,
    this.color,
    this.tooltip,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip ?? '',
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(6),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(6),
          child: SizedBox(
            width: 32,
            height: 32,
            child: Icon(icon, size: 18, color: color ?? C.iconDefault),
          ),
        ),
      ),
    );
  }
}

class _SmallTextButton extends StatelessWidget {
  final String label;
  final VoidCallback onTap;

  const _SmallTextButton({required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(6),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(6),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
          child: Text(label, style: const TextStyle(color: C.textSecondary, fontSize: 11)),
        ),
      ),
    );
  }
}
