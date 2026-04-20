package com.propaint.app.engine

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * レイヤーツリーのノード基底クラス。
 * Layer（通常レイヤー）と Folder（グループ）の共通インターフェース。
 */
sealed class LayerNode {
    abstract val id: Int
    abstract var name: String
    abstract var isVisible: Boolean
    abstract var isLocked: Boolean
    abstract var opacity: Float
    abstract var blendMode: Int

    /** 親フォルダへの参照（ルートレベルの場合は null） */
    var parent: Folder? = null
        internal set
}

/**
 * 通常レイヤー。ピクセルデータを持つ。
 * 既存の Layer クラスを継承し、ツリー構造に対応。
 */
class LayerData(
    override val id: Int,
    override var name: String,
    val surface: TiledSurface,
    override var isVisible: Boolean = true,
    override var isLocked: Boolean = false,
    override var opacity: Float = 1f,
    override var blendMode: Int = PixelOps.BLEND_NORMAL,
    var isClipToBelow: Boolean = false,
    var isAlphaLocked: Boolean = false,
) : LayerNode() {

    /** Indirect 描画用サブレイヤー */
    var sublayer: TiledSurface? = null

    /** レイヤーマスク (null = マスクなし) */
    var mask: TiledSurface? = null
    var isMaskEnabled: Boolean = true
    var isEditingMask: Boolean = false

    /** テキストレイヤーの設定 (null = 通常レイヤー) */
    var textConfig: TextRenderer.TextConfig? = null

    // ── トランスフォーム情報（ピクセル移動機能対応） ──
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var rotation: Float = 0f

    /** 旧 Layer との互換性: groupId を parent から派生 */
    val groupId: Int get() = parent?.id ?: 0
}

/**
 * フォルダ（レイヤーグループ）。子ノードを持つ。
 */
class Folder(
    override val id: Int,
    override var name: String,
    override var isVisible: Boolean = true,
    override var isLocked: Boolean = false,
    override var opacity: Float = 1f,
    override var blendMode: Int = PixelOps.BLEND_NORMAL,
    var isExpanded: Boolean = true,
) : LayerNode() {

    /** 子ノードリスト（表示順: インデックス0が一番下、末尾が一番上） */
    private val _children = mutableListOf<LayerNode>()
    val children: List<LayerNode> get() = _children

    /** 子ノードを末尾（最上位）に追加 */
    fun addChild(node: LayerNode) {
        node.parent?.removeChild(node)
        node.parent = this
        _children.add(node)
    }

    /** 子ノードを指定位置に挿入 */
    fun insertChild(index: Int, node: LayerNode) {
        node.parent?.removeChild(node)
        node.parent = this
        _children.add(index.coerceIn(0, _children.size), node)
    }

    /** 子ノードを削除 */
    fun removeChild(node: LayerNode): Boolean {
        val removed = _children.remove(node)
        if (removed) node.parent = null
        return removed
    }

    /** 子ノードのインデックスを取得 */
    fun indexOf(node: LayerNode): Int = _children.indexOf(node)

    /** 全ての子孫レイヤー（LayerData）を取得 */
    fun getAllLayers(): List<LayerData> {
        val result = mutableListOf<LayerData>()
        for (child in _children) {
            when (child) {
                is LayerData -> result.add(child)
                is Folder -> result.addAll(child.getAllLayers())
            }
        }
        return result
    }

    /** 全ての子孫ノードを取得（フォルダ含む） */
    fun getAllNodes(): List<LayerNode> {
        val result = mutableListOf<LayerNode>()
        for (child in _children) {
            result.add(child)
            if (child is Folder) {
                result.addAll(child.getAllNodes())
            }
        }
        return result
    }
}

/**
 * レイヤーツリー全体を管理するクラス。
 * CanvasDocument から利用され、レイヤー構造の操作を担当。
 */
class LayerTree(val width: Int, val height: Int) {
    private val lock = ReentrantLock()

    /** ルートレベルのノードリスト（インデックス0が一番下、末尾が一番上） */
    private val _root = mutableListOf<LayerNode>()
    val root: List<LayerNode> get() = _root

    private var nextId = 1

    /** アクティブレイヤーID */
    var activeLayerId: Int = -1
        private set

    /** 複数選択中のレイヤーID */
    private val _selectedIds = mutableSetOf<Int>()
    val selectedIds: Set<Int> get() = _selectedIds

    /** 変更通知コールバック */
    var onTreeChanged: (() -> Unit)? = null

    // 注意: 初期レイヤーは作成しない
    // syncLayerTree() で CanvasDocument から同期されるため、
    // ここで作成すると重複する

    private fun notifyChange() {
        onTreeChanged?.invoke()
    }

    // ══════════════════════════════════════════════════════════════
    // レイヤー追加・削除
    // ══════════════════════════════════════════════════════════════

    /** 新規レイヤーを追加（ルートまたは指定フォルダ内） */
    fun addLayer(name: String, parentFolderId: Int? = null, atIndex: Int? = null): LayerData = lock.withLock {
        val layer = LayerData(
            id = nextId++,
            name = name,
            surface = TiledSurface(width, height)
        )

        val parent = if (parentFolderId != null) findFolder(parentFolderId) else null
        if (parent != null) {
            if (atIndex != null) parent.insertChild(atIndex, layer)
            else parent.addChild(layer)
        } else {
            if (atIndex != null) _root.add(atIndex.coerceIn(0, _root.size), layer)
            else _root.add(layer)
        }

        if (activeLayerId < 0) activeLayerId = layer.id

        PaintDebug.d(PaintDebug.Layer) {
            "[LayerTree.addLayer] id=${layer.id} name=$name parent=${parentFolderId ?: "root"}"
        }
        notifyChange()
        layer
    }

    /** 既存のレイヤーをツリーに追加（インポート用） */
    fun importLayer(layer: LayerData, parentFolderId: Int? = null, atIndex: Int? = null) = lock.withLock {
        val parent = if (parentFolderId != null) findFolder(parentFolderId) else null
        if (parent != null) {
            if (atIndex != null) parent.insertChild(atIndex, layer)
            else parent.addChild(layer)
        } else {
            if (atIndex != null) _root.add(atIndex.coerceIn(0, _root.size), layer)
            else _root.add(layer)
        }

        if (layer.id >= nextId) nextId = layer.id + 1
        if (activeLayerId < 0) activeLayerId = layer.id
        notifyChange()
    }

    /** 新規フォルダを追加 */
    fun addFolder(name: String, parentFolderId: Int? = null, atIndex: Int? = null): Folder = lock.withLock {
        val folder = Folder(
            id = nextId++,
            name = name
        )

        val parent = if (parentFolderId != null) findFolder(parentFolderId) else null
        if (parent != null) {
            if (atIndex != null) parent.insertChild(atIndex, folder)
            else parent.addChild(folder)
        } else {
            if (atIndex != null) _root.add(atIndex.coerceIn(0, _root.size), folder)
            else _root.add(folder)
        }

        PaintDebug.d(PaintDebug.Layer) {
            "[LayerTree.addFolder] id=${folder.id} name=$name parent=${parentFolderId ?: "root"}"
        }
        notifyChange()
        folder
    }

    /** 既存のフォルダをツリーに追加（インポート用） */
    fun importFolder(folder: Folder, parentFolderId: Int? = null, atIndex: Int? = null) = lock.withLock {
        val parent = if (parentFolderId != null) findFolder(parentFolderId) else null
        if (parent != null) {
            if (atIndex != null) parent.insertChild(atIndex, folder)
            else parent.addChild(folder)
        } else {
            if (atIndex != null) _root.add(atIndex.coerceIn(0, _root.size), folder)
            else _root.add(folder)
        }

        if (folder.id >= nextId) nextId = folder.id + 1
        notifyChange()
    }

    /** ノードを削除（フォルダの場合は子も全て削除） */
    fun removeNode(nodeId: Int): Boolean = lock.withLock {
        val node = findNode(nodeId) ?: return false

        // 親から削除
        val parent = node.parent
        if (parent != null) {
            parent.removeChild(node)
        } else {
            _root.remove(node)
        }

        // アクティブレイヤーが削除対象の場合、別のレイヤーに切り替え
        if (node is LayerData && activeLayerId == nodeId) {
            activeLayerId = getAllLayers().firstOrNull()?.id ?: -1
        } else if (node is Folder && node.getAllLayers().any { it.id == activeLayerId }) {
            activeLayerId = getAllLayers().firstOrNull()?.id ?: -1
        }

        // 選択から削除
        _selectedIds.remove(nodeId)
        if (node is Folder) {
            node.getAllNodes().forEach { _selectedIds.remove(it.id) }
        }

        // レイヤーが0になったら空レイヤーを作成
        if (getAllLayers().isEmpty()) {
            addLayer("レイヤー 1")
        }

        PaintDebug.d(PaintDebug.Layer) {
            "[LayerTree.removeNode] id=$nodeId type=${node::class.simpleName}"
        }
        notifyChange()
        true
    }

    /** レイヤーを複製 */
    fun duplicateLayer(layerId: Int): LayerData? = lock.withLock {
        val source = findLayer(layerId) ?: return null
        val copy = LayerData(
            id = nextId++,
            name = "${source.name} コピー",
            surface = source.surface.snapshot(),
            isVisible = source.isVisible,
            isLocked = source.isLocked,
            opacity = source.opacity,
            blendMode = source.blendMode,
            isClipToBelow = source.isClipToBelow,
            isAlphaLocked = source.isAlphaLocked,
        )

        // 元レイヤーの直後に挿入
        val parent = source.parent
        if (parent != null) {
            val index = parent.indexOf(source)
            parent.insertChild(index + 1, copy)
        } else {
            val index = _root.indexOf(source)
            _root.add(index + 1, copy)
        }

        PaintDebug.d(PaintDebug.Layer) {
            "[LayerTree.duplicateLayer] source=$layerId copy=${copy.id}"
        }
        notifyChange()
        copy
    }

    // ══════════════════════════════════════════════════════════════
    // ノード移動
    // ══════════════════════════════════════════════════════════════

    /**
     * ノードを指定の位置に移動する。
     * @param nodeId 移動するノードのID
     * @param targetParentId 移動先の親フォルダID（null = ルート）
     * @param targetIndex 挿入位置（null = 末尾）
     */
    fun moveNode(nodeId: Int, targetParentId: Int?, targetIndex: Int? = null): Boolean = lock.withLock {
        val node = findNode(nodeId) ?: return false

        // 自分自身や自分の子孫には移動できない
        if (targetParentId != null) {
            if (targetParentId == nodeId) return false
            if (node is Folder && isDescendant(targetParentId, node)) return false
        }

        // 元の親から削除
        val oldParent = node.parent
        if (oldParent != null) {
            oldParent.removeChild(node)
        } else {
            _root.remove(node)
        }

        // 新しい親に追加
        val newParent = if (targetParentId != null) findFolder(targetParentId) else null
        if (newParent != null) {
            if (targetIndex != null) newParent.insertChild(targetIndex, node)
            else newParent.addChild(node)
        } else {
            if (targetIndex != null) _root.add(targetIndex.coerceIn(0, _root.size), node)
            else _root.add(node)
        }

        PaintDebug.d(PaintDebug.Layer) {
            "[LayerTree.moveNode] id=$nodeId -> parent=${targetParentId ?: "root"} index=$targetIndex"
        }
        notifyChange()
        true
    }

    /**
     * ノードを別のノードの直前/直後に移動する。
     * @param nodeId 移動するノードのID
     * @param targetNodeId 基準となるノードのID
     * @param insertAfter true=直後に挿入、false=直前に挿入
     */
    fun moveNodeRelative(nodeId: Int, targetNodeId: Int, insertAfter: Boolean = false): Boolean = lock.withLock {
        if (nodeId == targetNodeId) return false

        val node = findNode(nodeId) ?: return false
        val target = findNode(targetNodeId) ?: return false

        // 自分の子孫には移動できない
        if (node is Folder && isDescendant(targetNodeId, node)) return false

        // 元の親から削除
        val oldParent = node.parent
        if (oldParent != null) {
            oldParent.removeChild(node)
        } else {
            _root.remove(node)
        }

        // ターゲットと同じ親に挿入
        val targetParent = target.parent
        if (targetParent != null) {
            val targetIndex = targetParent.indexOf(target)
            val insertIndex = if (insertAfter) targetIndex + 1 else targetIndex
            targetParent.insertChild(insertIndex, node)
        } else {
            val targetIndex = _root.indexOf(target)
            val insertIndex = if (insertAfter) targetIndex + 1 else targetIndex
            _root.add(insertIndex.coerceIn(0, _root.size), node)
        }

        val newParentName = node.parent?.name ?: "root"
        PaintDebug.d(PaintDebug.Layer) {
            "[LayerTree.moveNodeRelative] id=$nodeId -> ${if (insertAfter) "after" else "before"} $targetNodeId (newParent=$newParentName)"
        }
        notifyChange()
        true
    }

    /** nodeId が folder の子孫かどうか */
    private fun isDescendant(nodeId: Int, folder: Folder): Boolean {
        return folder.getAllNodes().any { it.id == nodeId }
    }

    // ══════════════════════════════════════════════════════════════
    // 検索・取得
    // ══════════════════════════════════════════════════════════════

    /** ID でノードを検索 */
    fun findNode(id: Int): LayerNode? {
        for (node in _root) {
            if (node.id == id) return node
            if (node is Folder) {
                node.getAllNodes().find { it.id == id }?.let { return it }
            }
        }
        return null
    }

    /** ID でレイヤーを検索 */
    fun findLayer(id: Int): LayerData? = findNode(id) as? LayerData

    /** ID でフォルダを検索 */
    fun findFolder(id: Int): Folder? = findNode(id) as? Folder

    /** 全てのレイヤー（フォルダ除く）を取得（描画順: 下から上） */
    fun getAllLayers(): List<LayerData> {
        val result = mutableListOf<LayerData>()
        collectLayers(_root, result)
        return result
    }

    private fun collectLayers(nodes: List<LayerNode>, result: MutableList<LayerData>) {
        for (node in nodes) {
            when (node) {
                is LayerData -> result.add(node)
                is Folder -> collectLayers(node.children, result)
            }
        }
    }

    /** 全てのノードを取得（フォルダ含む） */
    fun getAllNodes(): List<LayerNode> {
        val result = mutableListOf<LayerNode>()
        for (node in _root) {
            result.add(node)
            if (node is Folder) {
                result.addAll(node.getAllNodes())
            }
        }
        return result
    }

    /** アクティブレイヤーを取得 */
    fun getActiveLayer(): LayerData? = findLayer(activeLayerId)

    /** アクティブレイヤーを設定 */
    fun setActiveLayer(layerId: Int): Unit = lock.withLock {
        if (findLayer(layerId) != null) {
            activeLayerId = layerId
            notifyChange()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 複数選択
    // ══════════════════════════════════════════════════════════════

    fun selectNode(nodeId: Int): Unit = lock.withLock {
        if (findNode(nodeId) != null) {
            _selectedIds.add(nodeId)
            notifyChange()
        }
    }

    fun deselectNode(nodeId: Int): Unit = lock.withLock {
        _selectedIds.remove(nodeId)
        notifyChange()
    }

    fun toggleSelection(nodeId: Int): Unit = lock.withLock {
        if (_selectedIds.contains(nodeId)) {
            _selectedIds.remove(nodeId)
        } else if (findNode(nodeId) != null) {
            _selectedIds.add(nodeId)
        }
        notifyChange()
    }

    fun clearSelection(): Unit = lock.withLock {
        _selectedIds.clear()
        notifyChange()
    }

    fun setSelection(ids: Set<Int>): Unit = lock.withLock {
        _selectedIds.clear()
        _selectedIds.addAll(ids.filter { findNode(it) != null })
        notifyChange()
    }

    // ══════════════════════════════════════════════════════════════
    // プロパティ変更
    // ══════════════════════════════════════════════════════════════

    fun setLayerVisibility(layerId: Int, visible: Boolean): Unit = lock.withLock {
        findNode(layerId)?.isVisible = visible
        notifyChange()
    }

    fun setLayerOpacity(layerId: Int, opacity: Float): Unit = lock.withLock {
        findNode(layerId)?.opacity = opacity.coerceIn(0f, 1f)
        notifyChange()
    }

    fun setLayerBlendMode(layerId: Int, mode: Int): Unit = lock.withLock {
        findNode(layerId)?.blendMode = mode
        notifyChange()
    }

    fun setLayerLocked(layerId: Int, locked: Boolean): Unit = lock.withLock {
        findNode(layerId)?.isLocked = locked
        notifyChange()
    }

    fun setLayerClipToBelow(layerId: Int, clip: Boolean): Unit = lock.withLock {
        (findNode(layerId) as? LayerData)?.isClipToBelow = clip
        notifyChange()
    }

    fun setLayerAlphaLocked(layerId: Int, locked: Boolean): Unit = lock.withLock {
        (findNode(layerId) as? LayerData)?.isAlphaLocked = locked
        notifyChange()
    }

    fun setFolderExpanded(folderId: Int, expanded: Boolean): Unit = lock.withLock {
        (findNode(folderId) as? Folder)?.isExpanded = expanded
        notifyChange()
    }

    fun renameNode(nodeId: Int, name: String): Unit = lock.withLock {
        findNode(nodeId)?.name = name
        notifyChange()
    }

    // ══════════════════════════════════════════════════════════════
    // シリアライズ（Flutter向け）
    // ══════════════════════════════════════════════════════════════

    /**
     * ツリー構造をフラットなリストとしてシリアライズ。
     * UI表示用に depth 情報を含める。
     */
    fun serializeForUI(): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        serializeNodes(_root, 0, result)
        return result
    }

    private fun serializeNodes(nodes: List<LayerNode>, depth: Int, result: MutableList<Map<String, Any?>>) {
        // 表示順は上が先（リストの末尾が一番上）なので逆順
        for (node in nodes.asReversed()) {
            val map = mutableMapOf<String, Any?>(
                "id" to node.id,
                "name" to node.name,
                "isVisible" to node.isVisible,
                "isLocked" to node.isLocked,
                "opacity" to node.opacity,
                "blendMode" to node.blendMode,
                "depth" to depth,
                "isGroup" to (node is Folder),
                "isActive" to (node.id == activeLayerId),
                "isSelected" to _selectedIds.contains(node.id),
                "parentId" to node.parent?.id,
            )

            when (node) {
                is LayerData -> {
                    map["isClipToBelow"] = node.isClipToBelow
                    map["isAlphaLocked"] = node.isAlphaLocked
                    map["hasMask"] = node.mask != null
                    map["isMaskEnabled"] = node.isMaskEnabled
                    map["isEditingMask"] = node.isEditingMask
                    map["isTextLayer"] = node.textConfig != null
                    // 旧互換性のため groupId も出力
                    map["groupId"] = node.groupId
                }
                is Folder -> {
                    map["isExpanded"] = node.isExpanded
                    map["childCount"] = node.children.size
                }
            }

            result.add(map)

            // フォルダの子を再帰的に追加（展開状態に関係なく常に送信）
            if (node is Folder) {
                serializeNodes(node.children, depth + 1, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // クリア・リセット
    // ══════════════════════════════════════════════════════════════

    fun clear(): Unit = lock.withLock {
        _root.clear()
        _selectedIds.clear()
        activeLayerId = -1
        nextId = 1
        addLayer("レイヤー 1")
    }

    /** インポート用: 初期レイヤーを作成せずにクリア */
    fun clearForImport(): Unit = lock.withLock {
        _root.clear()
        _selectedIds.clear()
        activeLayerId = -1
        nextId = 1
    }
}
