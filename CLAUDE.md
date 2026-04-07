# ProPaint v2 開発ルール

## プロジェクト概要

Android ペイントアプリ。**Kotlin (Kotlin/Android) + Dart (Flutter)** の組み合わせ。
開発環境：**Android Studio**（Kotlin エンジン）+ **Flutter SDK**（UI フロントエンド）。
描画エンジンは Drawpile 準拠の CPU タイルベース (64x64 premultiplied ARGB_8888)。
UI は **Flutter (メイン UI)** で実装。

### ディレクトリ構成

```
app/src/main/java/com/propaint/app/
├── engine/        # 描画エンジン (BrushEngine, CanvasDocument, PixelOps, Tile 等)
│                  # - 4段階ブラシサイズスケーリング（radScale）
│                  # - Lazy Nezumi風手振れ補正（EMA + キャッチアップ）
│                  # - レイヤーグループ（LayerGroup.kt）
├── gl/            # OpenGL レンダラ (表示専用、描画処理なし)
├── ui/            # Compose UI (PaintScreen, パネル類)
├── gallery/       # ギャラリー画面 (GalleryScreen, NewCanvasDialog)
├── viewmodel/     # PaintViewModel
│                  # - レイヤーグループ（フォルダ）管理
│                  # - UiLayer.isGroup / groupId フィールド
├── io/            # ファイル I/O (ProjectFile 読み込み・保存等)
├── model/         # データモデル (不変データクラス等)
└── flutter/       # Flutter 連携 (MethodChannel)
                   # - レイヤーグループのCRUD操作
                   # - 負のグループID（-gId）変換処理
```

### 主要機能

- **レイヤーグループ（フォルダ）**: CanvasDocument で管理。ツリー構造に対応。ドラッグ & ドロップでレイヤーをフォルダ内に移動可能。
- **ブラシサイズスケーリング**: 4段階（小 <0.4, 小中 0.4-1, 中大 1-8, 大 ≥8）による自動パラメータ調整。
- **手振れ補正**: Lazy Nezumi 準拠の EMA フィルタ + 距離ベースキャッチアップ + 圧力タッパー。

### UI/UX の方針

- ユーザーの操作意図が明確に反映されるようにビジュアルフィードバック（色・アニメーション）を付与
- ドラッグ & ドロップ時は ドロップ対象が視覚的に強調（背景色・枠線変更）
- 展開/折畳・選択・削除などの操作は機能を混同させないよう UI で区別
- ReorderableListView など複数操作ハンドラがある場合は 衝突を避ける設計

### UI インタラクション実装ルール（ジェスチャー・アニメーション系）

複雑なジェスチャー操作（スワイプ、ドラッグ並び替え、長押しメニュー等）を実装する場合、
**ボタンを追加して代替する解決策は禁止**。ユーザーが指定したジェスチャー操作を必ずそのまま実装すること。

#### 指示の受け取り方

ジェスチャー系の実装指示は以下の形式で提供される。これに従って実装すること。

```
■ 状態遷移: Idle → Swiping → ActionRevealed → Executing
■ トリガー: 右スワイプ 40dp超過 → alphaLock toggle
■ アニメーション: AnimatedContainer 200ms ease-out
■ 連携: MethodChannel "toggleAlphaLock" → CanvasDocument.toggleAlphaLock(layerId)
```

この形式が提供されない曖昧な指示（例:「Procreateみたいにして」）の場合は、
**実装を始める前に** 以下を確認すること:
1. どのジェスチャーで何が起きるか（状態遷移）
2. 閾値（dp / ms）
3. アニメーションの種類と時間
4. Kotlin 側のどのメソッドを呼ぶか（MethodChannel 名・引数・戻り値）

#### 実装順序

複数のジェスチャーを同時に実装しない。以下の順で1つずつ実装・動作確認すること:
1. タップ（選択）
2. 長押し → ドラッグ並び替え
3. 横スワイプ → アクション（アルファロック、削除等）
4. マルチタッチ（2本指タップ → マージ等）

#### Flutter 側の実装パターン

```dart
// ジェスチャー状態管理の基本構造
enum SwipeState { idle, swiping, actionRevealed }

GestureDetector(
  onHorizontalDragUpdate: (details) {
    // dx 累積 → 閾値判定 → 状態遷移
  },
  onHorizontalDragEnd: (details) {
    // 状態に応じてアクション実行 or リセット
    // MethodChannel 経由で Kotlin 側を呼ぶ
  },
  onLongPressStart: (_) {
    // ドラッグモード開始（HapticFeedback 付与）
  },
)
```

#### MethodChannel 連携の明示ルール

ジェスチャーから Kotlin 側を呼ぶ場合、以下を全て明示すること:
- チャンネル名: `"com.propaint.app/paint"`
- メソッド名: 例 `"toggleAlphaLock"`
- 引数の型: 例 `{"layerId": Int}`
- 戻り値の型: 例 `Boolean`（成功/失敗）
- Kotlin 側の対応メソッド: 例 `CanvasDocument.toggleAlphaLock(layerId: Int): Boolean`

---

## UI デザイン・インタラクション設計ワークフロー（GUI Claude ↔ CLI Claude 連携）

複雑な UI（レイヤーパネル、ツールバー、ジェスチャー操作等）の設計・実装は以下のフローで行う。

### Phase 1: GUI Claude（claude.ai）でプロトタイプ作成

GUI 版 Claude の Artifact 機能で React/HTML の操作プロトタイプを作成する。
ブラウザ上で実際にスワイプ・ドラッグ・タップして操作感を確認・調整する。

**プロトタイプで確認すべき項目:**
- ジェスチャーの種類と閾値（何dp / 何msで発火するか）
- アニメーションの速度・イージング
- 状態遷移（Idle → Swiping → ActionRevealed → Executing）
- ビジュアルフィードバック（色変化、elevation、フェード等）

### Phase 2: GUI Claude で仕様書を抽出

プロトタイプから状態遷移図とイベント仕様を出力する。形式例:

```
■ コンポーネント: LayerCell
■ 状態: Idle → Swiping → ActionRevealed → Executing
■ 右スワイプ 40dp超過 → alphaLock toggle
■ 左スワイプ 80dp超過 → 削除確認表示
■ 長押し 300ms → ドラッグモード開始 → Y移動で並び替え
■ 2本指タップ → 下レイヤーとマージ
■ アニメーション: 200ms ease-out
■ MethodChannel: "toggleAlphaLock" / args: {layerId: Int} / return: Boolean
```

### Phase 3: CLI Claude で Flutter/Compose 実装

Phase 2 の仕様書を CLI Claude に渡し、Flutter/Compose コードに変換する。
CLI Claude はプロジェクト全体のコンテキストを持つため、
既存の CanvasDocument・MethodChannel・ViewModel との整合性を保った実装ができる。

**CLI Claude への指示テンプレート:**

```
以下の仕様に従って [Flutter/Compose] 側の [コンポーネント名] を実装してください。

【状態遷移】
(Phase 2 の仕様をそのまま貼る)

【MethodChannel 連携】
- チャンネル: "com.propaint.app/paint"
- メソッド: "toggleAlphaLock"
- 引数: {layerId: Int}
- 戻り値: Boolean
- Kotlin側: CanvasDocument.toggleAlphaLock(layerId)

【制約】
- ボタン追加による代替は禁止
- 1ジェスチャーずつ実装（まず右スワイプのみ）
- 既存の LayerListState を壊さない
```

---

## バグ報告→修正ワークフロー

ユーザーがバグや不具合を報告したら、以下の手順で **自動的に** 調査・修正すること。
都度の許可確認は不要（パーミッション設定済み）。

### 手順

1. **ログ解析** — logcat が提供されたら下記「デバッグログ解析」の手順で根本原因を特定
2. **関連コード読解** — 原因箇所のコードを読んでバグパターンと照合
3. **修正** — コードを修正（防御的アサーション + 構造化ログを含む）
4. **ビルド確認** — `./gradlew :app:compileDebugKotlin --no-daemon` でビルドが通ることを確認（Android Studio のコンソール出力でも確認可）
5. **結果報告** — 修正内容を簡潔に報告

### 開発環境での動作確認

- **Kotlin コード修正**: Android Studio で `Build > Make Module 'app'` または `Ctrl+Shift+F9` でコンパイル確認
- **Flutter UI 修正**: `flutter run` コマンド、または Android Studio の Run ボタンで実機/エミュレータに展開

---

## GitHub 反映 (git push)

ユーザーが「GitHub に反映して」「push して」等と言ったら、**許可確認なしで** 以下の手順を一気通貫で実行すること。

### 手順

1. **ビルド確認** — `./gradlew :app:compileDebugKotlin --no-daemon` でビルドが通ることを確認（Android Studio のコンパイラでも可）
2. **変更確認** — `git status` と `git diff` で変更内容を確認
3. **ステージング** — 関連ファイルを `git add` (機密ファイル・ローカル設定を含めないこと)
4. **コミット** — 変更内容を反映した日本語コミットメッセージで `git commit`
5. **プッシュ** — `git push origin main`

### 除外ファイル（絶対に push しない）

- `.claude/` — ローカル設定・トークン含む（.gitignore 済み）
- `.idea/` — Android Studio プロジェクト設定（.gitignore 済み）
- `local.properties` — ANDROID_SDK パス等
- `*.iml` — Android Studio モジュール設定
- `implementation_plan.md` — 一時的な作業メモ

---

## コード生成ルール

### 防御的アサーション（必須）

ペイントアプリのコードを書く・修正するとき、以下の箇所に必ず `require()` / `check()` を埋め込むこと。
ビルドが通っても値が不正なまま伝播するコードは禁止。

- ダブ radius > 0
- hardness 0..1, pressure 0..1
- タイル座標が配列範囲内
- IntArray インデックスが配列サイズ内
- premultiplied 条件: R,G,B <= A
- 逆行列 determinant != 0
- Float 値が NaN / Infinity でないこと

### 構造化診断ログ（必須）

重要な処理に構造化ログを埋め込むこと。

タグ体系:
- `PaintDebug.Brush` — ダブ生成・配置
- `PaintDebug.Tile` — タイル操作・ダーティ通知
- `PaintDebug.Layer` — レイヤー合成・管理
- `PaintDebug.Input` — タッチ・座標変換
- `PaintDebug.GL` — テクスチャ・描画
- `PaintDebug.Undo` — スナップショット・復元
- `PaintDebug.Perf` — 処理時間計測

形式: `Log.d(TAG, "[カテゴリ] key=value key=value")`

DebugConfig.enableDiagnosticLog フラグで制御可能にすること。
ホットパスでは inline 関数 + ラムダで文字列生成コストを回避。

---

## コード出力前チェックリスト

コードを出力する前に以下を全項目確認し、該当する問題があれば修正してから出力すること。

### 数値・範囲
- .toInt() の切り捨てで 0 にならないか
- Float 演算で NaN / Infinity が発生しないか
- 配列インデックスが負や範囲外にならないか
- width * height の Int オーバーフロー
- 色値が 0..255 (8bit) / 0..32767 (15bit) 範囲内か
- spacing = 0 で無限ループにならないか
- 筆圧 0.0 で radius = 0 になる問題の対処

### スレッド安全性
- GL スレッドとメインスレッドで同一 IntArray に同時アクセスしていないか
- ConcurrentModificationException の可能性がある Collection 操作がないか
- suspend 関数内で Dispatchers.Main なしに UI 操作していないか
- GL コンテキスト外で GL 関数を呼んでいないか

### 座標・リソース
- タッチ→ドキュメント座標変換で zoom/pan/rotation が全て考慮されているか
- タイル座標で floorDiv を使用しているか（負座標対応）
- ダブ影響範囲がキャンバス境界をクリップしているか
- Bitmap / IntArray の確保と解放が対応しているか
- GL テクスチャの glDeleteTextures が適切か
- 4096x4096 以上のキャンバスでメモリが足りるか概算

### ブラシ・状態
- Indirect (Wash) モードのサブレイヤーが確実に破棄されているか
- Smudge で null タイル（透明）を正しく処理しているか
- Undo 後にアクティブなストロークが残っていないか
- レイヤー削除後に activeLayerIndex が更新されているか
- ズーム中のタッチが描画にならないか（ジェスチャー排他）
- ACTION_CANCEL が処理されているか
- HistoricalEvent が全て処理されているか

### レイヤー・グループ
- Kotlin で UiLayer に `isGroup` フラグが含まれているか
- Flutter の EventChannel で `"isGroup"` を `serializeLayers()` に含めているか
- フォルダID変換：Flutter で負のID（-gId）を正に変換しているか
  - `deleteLayerGroup(-id)` で削除
  - `setLayerGroup(layerId, -groupId)` で移動
- フォルダの `selectLayer()` 呼び出しを避けているか（選択不可）
- フォルダ展開/折畳のクリックハンドラが正しく分離しているか

### UI/UX（ユーザーフレンドリー性）
- **ビジュアルフィードバック**：操作結果が視覚的に明確か
  - ドラッグ中のハイライト / ホバー状態の色変化
  - ドロップ可能な領域の強調表示（背景色・枠線）
  - クリック/タップの応答性が見えるか
  - 状態遷移時のアニメーション（elevation, fade-in/out など）

- **操作の直感性**：ユーザーが目的を達成しやすいか
  - ボタン/アイコンの配置が機能と一致しているか
  - テキスト・ツールチップが明確か
  - 混同しやすい機能（例：フォルダ選択 vs 展開/折畳）が区別できるか
  - よく使う操作がアクセスしやすい位置にあるか

- **フィードバックメッセージ**：エラーや確認が適切か
  - 危険な操作（削除など）に確認ダイアログはあるか
  - エラー発生時に原因がユーザーに伝わるか
  - 長時間の処理中にプログレス表示があるか

- **状態の追跡性**：複数操作後の UI 一貫性
  - オンオフの切り替え（チェックボックス等）の状態が正しく反映されているか
  - リスト展開時の子要素の表示が一貫しているか
  - ドラッグ操作後の レイアウト再計算が正しく行われているか

---

## 既知バグパターン（コード生成時に照合すること）

### 色系
- 描画色がくすむ → premultiplied alpha 二重適用を疑う
- 半透明で重ね描きすると黒ずむ → SrcOver 数式が premultiplied 対応か確認
- 消しゴムが白く塗る → DestOut ブレンドモードになっているか確認
- HSV 変換で彩度が消失 → S/V が 0..1 なのか 0..100/360 なのか確認
- 保存画像の色が変わる → PNG 出力前に un-premultiply しているか確認

### 描画系
- ブラシが透明 → radius=0 / opacity=0 / alpha=0 / 範囲外 / dirty 未通知のいずれか
- ストロークが途切れる → spacing が大きすぎるか補間がないか
- 始点が巨大 → carryDistance が未リセット
- テーパーが効かない → totalDistance 未更新 / smoothStep 正規化ミス
- 高速カーブが折れ線 → getHistoricalX/Y を使っていない
- タイル境界に隙間 → クリップ処理の off-by-one

### 座標・入力・レイヤー系
- ズーム後にブラシがズレる → 逆変換行列の更新タイミング / letterbox 未考慮
- 回転後に鏡像反転 → sin の符号が逆 / 回転中心のミス
- 二本指ズームで線が描かれる → ACTION_POINTER_DOWN で描画を中断していない
- レイヤー追加後に旧レイヤーに描画される → 古い参照をキャッシュしている
- レイヤー合成が全体黒 → 背景が透明（白 0xFFFFFFFF で開始すること）
- タイル負座標でクラッシュ → floorDiv を使うこと

### Undo・スレッド・性能系
- Undo でクラッシュ → null タイルがスナップショットに未記録
- Undo 繰返しで OOM → COW 参照が未解放 / 上限が未設定
- ストローク中の Undo → サブレイヤー未合成でデータ破損（ストローク中は Undo 禁止）
- GL にちらつき → IntArray 書込中に GL が読取（COW コピーで解決）
- GL コンテキスト喪失 → onSurfaceCreated でテクスチャ再生成
- GC Pause → ホットパスでオブジェクト生成を禁止

### Flutter ↔ Kotlin 連携系
- スライダー操作中に UI が固まる → `debounce` を `sample` / `conflate` に変更
- バックグラウンド遷移で ANR → onPause/onStop の保存を非同期化 + 重複排除
- 全体的にカクつく → `buildStateMap()` の全状態同期をやめ差分送信にする
- ファイルピッカーから戻ると何も起きない → `pendingExportFormat` を SavedState で保持
- プロジェクトファイル破損 → 保存処理に Mutex 排他制御を追加
- PlatformView でメモリリーク → `dispose()` で GL リソース解放を実装
- レイヤー操作でフリーズ → MethodChannel ハンドラの重い処理を `launchHeavy` に移動

### レイヤーグループ系
- フォルダが削除できない / 移動できない → Flutter で負のグループID（-gId）から正のIDに変換していない
  - `deleteLayerGroup(-id)` でフォルダIDを正に変換
  - `setLayerGroup(layerId, -groupId)` でグループIDを正に変換
- フォルダがUI上で表示されない → `serializeLayers()` に `isGroup` フラグが含まれていない
  - EventChannel で `"isGroup" to layer.isGroup` を追加
- フォルダ選択時にクラッシュ → 負のIDで `selectLayer()` を呼ばない
  - フォルダはタップで展開/折畳のみ（選択操作をスキップ）

### UI/UX系
- ドラッグ時にドロップ先が不明確 → ドロップ可能な領域をハイライト・色変更していない
  - `isDragOverTarget` で対象領域を強調（背景色・枠線）
  - ドロップ可能な領域の判定ロジックが正しいか確認
- ユーザーが操作結果を認識できない → ビジュアルフィードバック不足
  - クリック直後の色変化・アニメーション追加
  - トグル系操作の状態表示（チェックボックス、アイコン色など）
- 展開/折畳が機能しない → タップハンドラが複数競合している
  - 親の onTap と子要素の GestureDetector の両方が発火していないか確認
  - フォルダアイコン/フォルダ行をタップするハンドラを分離
- ドラッグで誤った領域にドロップされた → onReorder の判定ロジック不正
  - 同じ型（フォルダ↔フォルダ、レイヤー↔レイヤー）のみ並び替え許可
  - フォルダ←レイヤーの移動は setLayerGroup で処理

---

## 最終自問（出力直前）

1. 実機で最も壊れやすい箇所に防御コードがあるか？
2. null / 0 / NaN / 空配列が入りうる引数全てに防御処理があるか？
3. マルチスレッドでアクセスされるデータに同期機構 / COW があるか？
4. 上記の既知バグパターンが再発していないか？
5. **レイヤーグループ操作時に、ID の正負変換が必要か確認したか？**
   - Kotlin で の `groupId` は正数（1, 2, 3...）
   - Flutter での フォルダ `id` は負数（-1, -2, -3...）
   - オペレーション時に必ず変換する
6. **UI/UX が使いやすいか確認したか？**
   - ビジュアルフィードバック：ドラッグ中のハイライト、ホバー状態、アニメーション
   - 操作の直感性：ボタン配置、ツールチップ、機能の区別
   - 状態の一貫性：UI の状態が操作後も正しく保たれているか
   - ドロップ可能な領域は視覚的に明確か（色・枠線・アニメーション）

→ 1つでも不十分なら修正してから出力する。

---

## デバッグログ解析

logcat やデバッグログが提供された場合の解析手順:

1. `FATAL EXCEPTION` / `ANR` / `OutOfMemoryError` を検索
2. `Caused by:` チェーンで根本原因を特定
3. `PaintDebug.ASSERT` タグのエラーを抽出
4. `PaintDebug.Perf` から処理時間の異常を検出（フレーム > 16ms, ダブ > 1ms）
5. `PaintDebug.Brush/Tile/Layer` タグから不正値パターンを検索
6. `EGL` / `GLError` / `GL_INVALID` を検索

検出結果はバグパターン ID（BUG-xxxx）で紐付けて報告すること。

---

## 複数選択レイヤー×選択範囲による一括編集・変形機能

複数のレイヤーを同時選択し、選択範囲内で統一した編集・変形を適用する機能。
Photoshop/CLIP STUDIO PAINT など業務用ツールでの標準機能。

### 機能概要

**ユースケース:**
- 複数レイヤーを同時選択 → 選択範囲内で **スケール/回転/スキュー** を一括適用
- 複数レイヤーの選択範囲内のみ **移動**
- 複数レイヤーの選択範囲内のみ **色調補正**（将来）
- グループ内の全レイヤーを選択 → グループ全体を選択範囲内で変形

**有効条件:**
- `multipleLayersSelected` が true （2個以上のレイヤーが選択状態）
- `hasSelection` が true （選択範囲が存在）
- TransformTool / MoveTool / AdjustmentTool をアクティブ

**スコープ（Phase 1）:**
- スケール・回転・スキュー（Transform操作）
- 移動（Move操作）
- クリップ：選択範囲外のピクセルは処理対象外

### アーキテクチャ設計

#### Kotlin側（CanvasDocument）

**新データ構造:**
```kotlin
// 複数選択レイヤーの管理
data class MultiLayerSelection(
    val layerIds: List<Int>,  // 選択中のレイヤーID（昇順）
    val bounds: Rect,         // 全レイヤーの合成バウンディングボックス
    val timestamp: Long       // 選択時刻（Undo用）
)

// 一括変形操作
sealed class MultiLayerTransformOp {
    data class Scale(val sx: Float, val sy: Float, val cx: Float, val cy: Float) : MultiLayerTransformOp()
    data class Rotate(val angle: Float, val cx: Float, val cy: Float) : MultiLayerTransformOp()
    data class Skew(val sx: Float, val sy: Float) : MultiLayerTransformOp()
    data class Move(val dx: Float, val dy: Float) : MultiLayerTransformOp()
}
```

**新メソッド:**
```kotlin
// CanvasDocument.kt に追加

fun getMultipleLayerSelection(): List<Int>
    // 現在選択中のレイヤーID一覧を返す（複数選択未実装なら空リスト）

fun applyMultiLayerTransform(
    layerIds: List<Int>,
    op: MultiLayerTransformOp,
    selectionMask: ByteArray?  // null = 全範囲、非nullの場合はマスク内のみ
): Boolean
    // 複数レイヤーに統一の変形を適用
    // 各レイヤーに対して:
    //   1. selectionMask が有効な場合、変形範囲をマスク外はクリップ
    //   2. 逆行列計算で元画像座標に変換
    //   3. バイリニア補間でサンプリング
    //   4. 変形後のピクセルに selectionMask の値を α値として乗算
    // 戻り値: 成功時 true
    // エラー: layerIds が空 / 逆行列計算失敗 → false + ログ出力

fun applyMultiLayerMove(
    layerIds: List<Int>,
    dx: Float, dy: Float,
    selectionMask: ByteArray?
): Boolean
    // 複数レイヤーを同時移動
    // selectionMask が有効な場合、マスク外のピクセルは非表示状態に
    // ※ 本来は画像データ上は移動しないが、maskOffset を適用
```

**実装上の注意:**
- 座標変換：各レイヤー独立の変形行列（共通の中心座標で統一計算）
- マスク適用：タイルごとのマスク判定で CPU 効率化
- Undo対応：各レイヤーのスナップショット取得（COW で効率化）
- スレッド安全性：ReentrantLock で全体をロック

#### Flutter側（paint_state.dart / layer_panel.dart）

**状態管理:**
```dart
// PaintState に追加
class PaintState {
  final Set<int> selectedLayerIds;      // 複数選択中のレイヤーID
  final bool multipleLayersSelected;    // 複数選択の有無
  
  bool canTransformSelection() =>
      multipleLayersSelected && hasSelection;  // 変形操作が有効か
}
```

**UIロジック（layer_panel.dart）:**
```dart
// 複数選択の実装
GestureDetector(
  onTap: (details) {
    if (details.isCtrlKey || details.isShiftKey) {
      // Ctrl: Toggle / Shift: Range select
      if (isCtrlKey) {
        paintState.toggleLayerSelection(layerId);
      } else {
        paintState.selectLayersInRange(lastSelectedId, layerId);
      }
    } else {
      // 通常: 単一選択
      paintState.clearSelection();
      paintState.selectLayer(layerId);
    }
  },
  child: Container(
    color: selectedLayerIds.contains(layerId)
        ? Colors.blue.withOpacity(0.3)  // 複数選択時は水色ハイライト
        : Colors.transparent,
    child: LayerCell(...)
  )
)
```

**TransformTool への連携:**
```dart
// transform_tool.dart (将来実装)

void applyTransformToSelection(MultiLayerTransformOp op) {
  if (!paintState.canTransformSelection()) return;
  
  final layerIds = paintState.selectedLayerIds.toList();
  channel.invokeMethod('applyMultiLayerTransform', {
    'layerIds': layerIds,
    'operation': _serializeOp(op),
    'selectionMaskBase64': _encodeSelectionMask(paintState.selectionMask),
  });
}
```

### 実装計画

#### **Phase 0: ID変換バグ修正（優先度：最高）**
- ファイル: PaintViewModel.kt:1602-1615
- 内容: レイヤーグループID変換修正
- 見積もり: 30分

#### **Phase 1: 複数選択UI実装（優先度：高）**

**1-1. 複数選択状態管理（2時間）**
- PaintState に `selectedLayerIds: Set<int>` 追加
- `toggleLayerSelection()`, `selectLayersInRange()` メソッド実装
- EventChannel で Kotlin 側と同期

**1-2. 複数選択UIビジュアル（2時間）**
- layer_panel.dart でハイライト表示
- Shift / Ctrl キー検出ロジック
- 複数選択時のドラッグ操作（並び替え）の無効化

**1-3. Kotlin側の複数選択管理（1時間）**
- PaintViewModel に `multipleLayerSelection: MultiLayerSelection` 追加
- EventChannel で Flutter に状態を送信

#### **Phase 2: 一括削除・一括プロパティ変更（優先度：高）**

**2-1. 一括削除（1時間）**
- `deleteMultipleLayers(layerIds: List<Int>)` MethodChannel メソッド

**2-2. 一括プロパティ変更（2時間）**
- `setMultipleLayersOpacity(layerIds, opacity)`
- `setMultipleLayersBlendMode(layerIds, blendMode)`
- `setMultipleLayersVisibility(layerIds, visible)`

**2-3. 一括移動（フォルダ内）（1時間）**
- `moveMultipleLayersToGroup(layerIds, groupId)`

#### **Phase 3: 選択範囲×複数レイヤー変形（優先度：高）**

**3-1. 変形オペレーション定義（1時間）**
- Kotlin: `MultiLayerTransformOp` sealed class 定義
- Flutter ↔ Kotlin: serialization ロジック

**3-2. Kotlin側変形実装（4-5時間）**
```kotlin
// CanvasDocument.kt

fun applyMultiLayerTransform(
    layerIds: List<Int>,
    op: MultiLayerTransformOp,
    selectionMask: ByteArray?
): Boolean {
    require(layerIds.isNotEmpty()) { "layerIds must not be empty" }
    
    val layers = layerIds.mapNotNull { id -> getLayerById(id) }
    if (layers.size != layerIds.size) {
        Log.e(TAG, "[Layer] some layers not found: requested=${layerIds.size}, found=${layers.size}")
        return false
    }
    
    // スナップショット取得（Undo用）
    val snapshots = layers.associate { it.id to it.takeSnapshot() }
    
    try {
        for (layer in layers) {
            when (op) {
                is Scale -> {
                    transformLayerScale(layer, op.sx, op.sy, op.cx, op.cy, selectionMask)
                }
                is Rotate -> {
                    transformLayerRotate(layer, op.angle, op.cx, op.cy, selectionMask)
                }
                // ... 他のオペレーション
            }
            // タイルをダーティマーク
            layer.surface.markAllTilesDirty()
        }
        
        // Undo スタックに追加
        pushUndoSnapshot(
            UndoSnapshot.MultiLayerTransform(
                layerIds = layerIds,
                before = snapshots,
                op = op
            )
        )
        
        updateLayerState()
        return true
    } catch (e: Exception) {
        Log.e(TAG, "[Layer] multiLayerTransform failed: ${e.message}", e)
        // ロールバック
        layers.zip(snapshots.values).forEach { (layer, snapshot) ->
            layer.restoreSnapshot(snapshot)
        }
        return false
    }
}

private fun transformLayerScale(
    layer: Layer,
    sx: Float, sy: Float,
    cx: Float, cy: Float,
    selectionMask: ByteArray?
) {
    require(sx > 0 && sy > 0) { "scale factors must be > 0" }
    
    val surface = layer.surface
    val width = surface.width
    val height = surface.height
    val tiledSurface = surface as TiledSurface
    
    // 逆行列計算（元画像座標へ）
    val invSx = 1f / sx
    val invSy = 1f / sy
    
    val tempPixels = IntArray(Tile.SIZE * Tile.SIZE)
    
    for (ty in tiledSurface.tileRange) {
        for (tx in tiledSurface.tileRange) {
            val tile = tiledSurface.getTile(tx, ty) ?: continue
            
            for (ly in 0 until Tile.SIZE) {
                for (lx in 0 until Tile.SIZE) {
                    val px = tx * Tile.SIZE + lx
                    val py = ty * Tile.SIZE + ly
                    
                    // 選択範囲チェック
                    if (selectionMask != null) {
                        val maskIdx = py * width + px
                        if (maskIdx < 0 || maskIdx >= selectionMask.size) continue
                        if ((selectionMask[maskIdx].toInt() and 0xFF) == 0) {
                            tempPixels[ly * Tile.SIZE + lx] = 0
                            continue
                        }
                    }
                    
                    // 逆変換で元座標を計算
                    val srcX = (px - cx) * invSx + cx
                    val srcY = (py - cy) * invSy + cy
                    
                    // バイリニア補間でサンプリング
                    val color = sampleBilinear(surface, srcX, srcY)
                    
                    // 選択範囲がある場合、アルファに乗算
                    if (selectionMask != null) {
                        val maskValue = (selectionMask[py * width + px].toInt() and 0xFF).toFloat() / 255f
                        tempPixels[ly * Tile.SIZE + lx] = multiplyAlpha(color, maskValue)
                    } else {
                        tempPixels[ly * Tile.SIZE + lx] = color
                    }
                }
            }
            
            // タイルを更新
            tile.setPixels(tempPixels)
        }
    }
}

private fun sampleBilinear(surface: TiledSurface, x: Float, y: Float): Int {
    // バウンダリーチェック
    if (x < 0 || x >= surface.width || y < 0 || y >= surface.height) return 0
    
    val x0 = x.toInt()
    val y0 = y.toInt()
    val x1 = minOf(x0 + 1, surface.width - 1)
    val y1 = minOf(y0 + 1, surface.height - 1)
    
    val fx = x - x0
    val fy = y - y0
    
    val c00 = surface.getPixelAt(x0, y0)
    val c10 = surface.getPixelAt(x1, y0)
    val c01 = surface.getPixelAt(x0, y1)
    val c11 = surface.getPixelAt(x1, y1)
    
    // ARGB をフロート分解 → 補間 → 再合成
    val result = IntArray(4)
    for (ch in 0..3) {
        val v00 = (c00 shr (ch * 8)) and 0xFF
        val v10 = (c10 shr (ch * 8)) and 0xFF
        val v01 = (c01 shr (ch * 8)) and 0xFF
        val v11 = (c11 shr (ch * 8)) and 0xFF
        
        val v0 = (v00 * (1 - fx) + v10 * fx).toInt()
        val v1 = (v01 * (1 - fx) + v11 * fx).toInt()
        result[ch] = (v0 * (1 - fy) + v1 * fy).toInt() and 0xFF
    }
    
    return (result[3] shl 24) or (result[0] shl 16) or (result[1] shl 8) or result[2]
}
```

**3-3. Flutter側UI（TransformTool）（3-4時間）**
- ドラッグで変形操作を入力
- リアルタイムプレビュー
- 確定 / キャンセルボタン
- MethodChannel で Kotlin に送信

#### **Phase 4: 移動操作の選択範囲クリップ対応（優先度：中）**

**4-1. MoveTool の修正（2時間）**
- 複数選択 + 選択範囲の組み合わせ時、マスク外のピクセルを隠す

#### **Phase 5: グループ×複数選択×選択範囲（優先度：低・将来）**

**5-1. グループ内全レイヤーの自動選択（1時間）**
- フォルダをCtrl+Click → 配下の全レイヤーを複数選択

**5-2. リンク機能（1週間以上・別タスク）**
- 複数選択レイヤーを「リンク」状態に設定
- リンク済みレイヤーへの操作を全て同期

### 座標変換・マスク処理の詳細

#### **変形行列の統一**

全レイヤーで **共通の中心座標 (cx, cy)** を使用：
```
中心座標 = (複数選択レイヤーの合成BoundingBox の中心)
         = ((minX + maxX) / 2, (minY + maxY) / 2)
```

効果：複数レイヤーが「一体化して回転・スケール」される（Photoshop と同じ動作）

#### **選択範囲マスクの適用**

```
最終ピクセル色 = 変形後の色 × (selectionMask / 255)
```

つまり：
- マスク値 255（完全選択）→ 変形色がそのまま表示
- マスク値 128（半選択）→ 変形色と元色の 50:50 ブレンド
- マスク値 0（非選択）→ 元色が保持（変形されない）

#### **パフォーマンス最適化**

- **タイル単位の処理**: タイルが完全に非選択域なら処理スキップ
- **COW (Copy-on-Write)**: スナップショット取得は参照のみ、変更時にコピー
- **バイリニア補間**: float 演算は ホットパスで inline 化
- **Dispatcher.Default**: 複数レイヤーの変形を並列化可能（将来）

### 既知の課題・制約

1. **ビルドモード未考慮**
   - 複数レイヤーが異なるブレンドモード → 変形時は全て Normal で処理
   - 本来は元のブレンドモードを保持したまま変形すべき（高難度）

2. **テクスチャ境界での補間**
   - タイル境界で バイリニア補間が隣接タイルを参照 → 性能低下の可能性
   - 最適化は後回し

3. **選択範囲のアンチエイリアシング未対応**
   - 変形後の選択範囲エッジが「ギザギザ」になる可能性
   - 高品質が必要な場合は FXAA などを後処理

4. **メモリ使用量**
   - 複数レイヤー選択時のスナップショットで メモリ倍増
   - 50 レイヤー × 2K×2K = 数百 MB → OOM リスク
   - 将来: 差分スナップショット導入で圧縮

### チェックリスト（実装開始前に確認）

- [x] Phase 0: ID変換バグ修正完了 ✅ 2026-04-07
- [ ] Kotlin側の `MultiLayerTransformOp` sealed class 定義済み
- [ ] Flutter側の `selectedLayerIds: Set<int>` 状態管理実装済み
- [ ] MethodChannel メソッドの引数・戻り値を明示書か
- [ ] 逆行列計算の determinant check 実装
- [ ] selectionMask null チェック完全
- [ ] Undo/Redo データモデル更新
- [ ] UI/UX：複数選択時の視覚フィードバック
- [ ] ビルド確認: `./gradlew :app:compileDebugKotlin --no-daemon` 成功
- [ ] 実機テスト：複数選択 → 変形 → Undo 一連の動作確認

---

## 選択範囲内ピクセルの浮遊選択層（Floating Selection）機能

選択範囲で指定したピクセル領域を「浮遊選択層」として独立させ、**8ハンドル付きバウンディングボックス** で移動・リサイズできる機能。
Photoshop、CLIP STUDIO PAINT の「浮遊レイヤー」、Microsoft Excel の画像リサイズ操作と同等。

### 機能概要

**UI/UX：**
- 選択範囲を作成 → 選択範囲内のピクセルが「浮遊選択層」として確保
- バウンディングボックスが表示：8個のハンドル（角4 + 辺中央4）
  - **角ハンドル**: ドラッグで 縦横同時スケール（宽高比保持 or 自由）
  - **辺ハンドル**: ドラッグで 単軸スケール（水平 / 垂直）
  - **背景領域**: ドラッグで 移動
- **確定操作**: Enter キー / 外部タップで元レイヤーに統合
- **キャンセル操作**: Escape キーで浮遊選択層を破棄

**技術的特性：**
- 浮遊選択層は **新規レイヤーグループ** として CanvasDocument に追加（一時的）
- 変形処理は Phase 3 の `applyMultiLayerTransform()` と共通化
- Undo/Redo 対応：確定時にスナップショット
- 複数選択レイヤー × 選択範囲の組み合わせで、複数レイヤーの浮遊も可能

### ユースケース

1. **選択範囲内のピクセル移動**
   - 一部の描画内容だけを別の位置に移動
   - 例：顔だけ右に 30px スライド

2. **スケール・回転による変形**
   - 選択範囲内の描画を拡大・回転
   - 例：選択範囲内のオブジェクトを 2倍にスケール

3. **複数レイヤーの同時変形**
   - 複数選択 + 選択範囲内で、全レイヤーを統一変形

4. **マスク風の効果**
   - 選択範囲のみを移動・リサイズすることで、マスク境界を動的に調整

### アーキテクチャ設計

#### Kotlin側（CanvasDocument）

**浮遊選択層の管理：**
```kotlin
// CanvasDocument.kt に追加

data class FloatingSelection(
    val id: Int,                    // 浮遊選択層ID（負数で管理: -1, -2...）
    val sourceLayerIds: List<Int>,  // 元のレイヤーID（複数可）
    val selectionBounds: Rect,      // 選択範囲のバウンディングボックス
    val originalPixels: Map<Int, IntArray>,  // 元のピクセルデータ（COW）
    val currentBounds: Rect,        // 現在の変形後バウンディングボックス
    val transformMatrix: Matrix4x4, // スケール・回転の変形行列
    val createdAt: Long,            // 作成時刻
    var isConfirmed: Boolean = false // 確定済みか
)

var floatingSelection: FloatingSelection? = null
```

**操作メソッド：**
```kotlin
fun createFloatingSelection(
    selectionMask: ByteArray,
    layerIds: List<Int> = listOf(activeLayerId)  // 複数選択対応
): Boolean {
    // 選択範囲内のピクセルを各レイヤーからコピー
    // 元データは COW で保持（メモリ効率化）
    // 浮遊選択層を visualLayers に追加（UI描画用）
}

fun moveFloatingSelection(dx: Float, dy: Float): Boolean {
    // currentBounds を移動
}

fun scaleFloatingSelection(
    sx: Float, sy: Float,
    anchor: FloatingSelectionAnchor  // CENTER / TOP_LEFT / TOP_RIGHT / etc.
): Boolean {
    // transformMatrix を更新
}

fun rotateFloatingSelection(angle: Float, cx: Float, cy: Float): Boolean {
    // transformMatrix を回転成分で更新
}

fun confirmFloatingSelection(): Boolean {
    // 浮遊選択層を各元レイヤーに統合
    // Undo スタックに追加
    // floatingSelection = null
}

fun cancelFloatingSelection(): Boolean {
    // 浮遊選択層を破棄
    // 元データは变更なし
    // floatingSelection = null
}
```

#### Flutter側（paint_canvas.dart / floating_selection_tool.dart）

**UI状態管理：**
```dart
class PaintState {
  final FloatingSelectionState? floatingSelection;  // 浮遊選択層の状態
  
  bool get hasFloatingSelection => floatingSelection != null;
}

class FloatingSelectionState {
  final Rect bounds;                    // 画面座標でのバウンディングボックス
  final Matrix4 transformMatrix;        // 変形行列
  final List<FloatingSelectionHandle> handles;  // 8個のハンドル
}

enum FloatingSelectionHandle {
  topLeft, top, topRight,
  left, right,
  bottomLeft, bottom, bottomRight
}
```

**UIレンダリング（CustomPainter）：**
```dart
class FloatingSelectionPainter extends CustomPainter {
  final FloatingSelectionState state;
  
  @override
  void paint(Canvas canvas, Size size) {
    final bounds = state.bounds;
    
    // バウンディングボックス（破線）
    canvas.drawRect(
      bounds,
      Paint()
        ..color = Colors.blue
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeDashPattern = [5, 5]  // 破線
    );
    
    // 8個のハンドル
    for (final handle in state.handles) {
      drawHandle(canvas, handle.position, handle.type);
    }
    
    // 中央ドラッグ用インジケータ
    canvas.drawCircle(
      bounds.center,
      10,
      Paint()
        ..color = Colors.blue.withOpacity(0.3)
        ..style = PaintingStyle.fill
    );
  }
  
  void drawHandle(Canvas canvas, Offset position, FloatingSelectionHandle type) {
    canvas.drawCircle(
      position,
      8,
      Paint()
        ..color = Colors.blue
        ..style = PaintingStyle.fill
    );
  }
}
```

**ジェスチャー処理：**
```dart
// paint_canvas.dart に追加

GestureDetector(
  onPanUpdate: (details) {
    if (!paintState.hasFloatingSelection) return;
    
    final floatingState = paintState.floatingSelection!;
    final draggedHandle = detectHandleAtPosition(details.globalPosition);
    
    if (draggedHandle == null) {
      // 背景ドラッグ → 移動
      channel.invokeMethod('moveFloatingSelection', {
        'dx': details.delta.dx / zoom,
        'dy': details.delta.dy / zoom,
      });
    } else {
      // ハンドルドラッグ → スケール or 回転
      applyHandleDrag(draggedHandle, details);
    }
  },
  onTapUp: (details) {
    // 浮遊選択層の外をタップ → 確定
    if (!isPointInFloatingSelection(details.globalPosition)) {
      channel.invokeMethod('confirmFloatingSelection');
    }
  },
  onLongPress: () {
    // 長押し → 回転モード に切り替え（将来）
  },
  child: CustomPaint(
    painter: FloatingSelectionPainter(paintState.floatingSelection),
    child: GestureDetector(
      onKey: (event) {
        if (event.isKeyPressed(LogicalKeyboardKey.enter)) {
          channel.invokeMethod('confirmFloatingSelection');
        } else if (event.isKeyPressed(LogicalKeyboardKey.escape)) {
          channel.invokeMethod('cancelFloatingSelection');
        }
      },
    )
  )
)
```

### 実装計画

#### **Phase 3.5: 浮遊選択層の基本実装（優先度：高）**

**3.5-1. FloatingSelection データモデル定義（1時間）**
- Kotlin: FloatingSelection データクラス
- Serialization (MethodChannel 用)

**3.5-2. 浮遊選択層の作成・統合（3時間）**
- `createFloatingSelection(selectionMask, layerIds)` 実装
- `confirmFloatingSelection()` 実装
- `cancelFloatingSelection()` 実装
- Undo/Redo 対応

**3.5-3. 移動・スケール処理（2時間）**
- `moveFloatingSelection(dx, dy)` 実装
- `scaleFloatingSelection(sx, sy, anchor)` 実装
- 変形行列の管理

**3.5-4. Flutter UI（CustomPainter + GestureDetector）（3時間）**
- FloatingSelectionPainter の実装
- 8ハンドルの描画と座標計算
- ドラッグハンドラの実装
- Enter/Escape キー処理

**3.5-5. ハンドル検出・ドラッグ処理（2時間）**
- タップ座標がハンドル範囲内か判定
- ハンドル種別に応じたスケール計算（宽高比保持オプション）

#### **Phase 3.6: 浮遊選択層のプレビュー・視覚フィードバック（優先度：中）**

**3.6-1. リアルタイムプレビュー（2時間）**
- ドラッグ中に FloatingSelectionPainter を即座に更新
- マーチングアンツアニメーション（選択範囲エッジ）

**3.6-2. ハンドル UI の改善（1時間）**
- ハンドルホバー時のハイライト
- カーソル変更（N-Resize, NW-Resize, Move 等）

#### **Phase 3.7: 複数選択レイヤー × 浮遊選択層（優先度：中）**

**3.7-1. 複数レイヤー浮遊対応（1.5時間）**
- createFloatingSelection(selectionMask, layerIds: List) の実装
- 各レイヤーのピクセルを別々に保持・復元

### 既知の課題・最適化機会

1. **ハンドルの宽高比保持**
   - Shift キー押下で宽高比ロック
   - 将来的には設定で選択可能に

2. **回転ハンドル（9番目のハンドル）**
   - 現状はスケール・移動のみ
   - 将来：中央ハンドルの外側にドラッグで回転

3. **メモリ効率化（大規模浮遊選択）**
   - 選択範囲が大きい場合（例：2K×2K）のメモリ使用量
   - COW + タイルキャッシュで圧縮可能

4. **複数レイヤー浮遊時の演算コスト**
   - 各レイヤーの変形処理が O(n) → 将来並列化可能

### チェックリスト

- [ ] Phase 3.5-1: FloatingSelection データモデル定義
- [ ] Phase 3.5-2: 浮遊選択層の CRUD 実装
- [ ] Phase 3.5-3: 移動・スケール処理
- [ ] Phase 3.5-4: Flutter UI (CustomPainter + GestureDetector)
- [ ] Phase 3.5-5: ハンドル検出・ドラッグ処理
- [ ] ビルド確認
- [ ] 実機テスト：選択範囲作成 → 移動 → スケール → 確定

---

## 複数選択レイヤー×選択範囲による統一編集・変形

複数のレイヤーを同時選択した状態で選択範囲を適用し、**選択範囲内のみ** 統一した編集・変形操作（移動・スケール・回転など）を実行する機能。

### タブレット＋ペン最適化ジェスチャー仕様

**現在の実装（Flutter layer_panel.dart）:**

#### **左スワイプ：複数選択に追加**
```
状態遷移: 単一選択 → 複数選択
トリガー: レイヤーを左にスワイプ（-40dp 以上）
結果:
  - レイヤーが複数選択リストに追加
  - 背景に「+追加」表示
  - アニメーション: 200ms easeOut で背景アクションUI表示
  
複数選択中：
  - 左スワイプ：未選択レイヤーを追加
  - 既に選択中なら何もしない
```

#### **右スワイプ：複数選択から削除**
```
状態遷移: 複数選択 → 複数選択から1件削除
トリガー: レイヤーを右にスワイプ（+40dp 以上）
結果:
  - 複数選択中かつこのレイヤーが選択済み → 削除
  - 複数選択未開始 → 複数選択開始
  - 背景表示：
    - 複数選択中＆選択済み：「-削除」（赤）
    - 複数選択中＆未選択：「+追加」（青）
    - 複数選択未開始：「+選択」（青）
  - アニメーション: 200ms easeOut で背景アクションUI表示
```

#### **ヘッダー「X件選択」表示**
```
複数選択中のみ表示:
  - 選択件数カウント（例：「3件選択」）
  - 「✕」ボタンで一括解除
  - 上移動・下移動・結合・削除ボタン
```

#### **長押し後のドラッグ：複数レイヤー一括移動**
```
状態遷移: Idle → LongPress → Dragging
トリガー: レイヤーを長押し（300ms以上）→ ドラッグ開始
結果:
  - 複数選択中：全選択レイヤーを同時移動
  - 単一選択中：該当レイヤーのみ移動
  - ドラッグフィードバック: Material elevation:8
  - オートスクロール: リスト上下端 80dp ゾーン
  - ドロップ先：
    - フォルダ内にドロップ → 全選択レイヤーを移動
    - 同じ階層にドロップ → 相対順序を保持して並び替え
    - ホバー 0.5秒でフォルダ自動展開
  
実装: LongPressDraggable<int> + DragTarget + ReorderableListView互換
```

### 複数選択レイヤーの状態管理

**Flutter側（layer_panel.dart）:**
```dart
class _LayerPanelState extends State<LayerPanel> {
  final Set<int> _selectedIds = {};          // 複数選択中のレイヤーID
  
  bool get _hasMultipleSelection => _selectedIds.length > 1;
  
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
}
```

**Kotlin側（PaintViewModel.kt）:**
```
現状: PaintViewModel に複数選択フィールドなし
推奨: 将来的に selectedLayerIds: MutableSet<Int> を追加して
      Kotlin側でも複数選択状態を管理可能にする
      ただし現在は Flutter側で十分に機能
```

### 選択範囲×複数選択レイヤーの統一編集

**シーケンス:**
```
1. 複数レイヤーを選択（左スワイプで追加）
2. SelectionTool で選択範囲を作成
3. TransformTool / MoveTool をアクティブ化
4. 変形操作実行:
   - ドラッグで移動（選択範囲内のみ）
   - ハンドルドラッグでスケール・回転
   - 全選択レイヤーに統一した変形を適用
5. Enter キーで確定 / Escape で キャンセル
```

**制約:**
- 選択範囲がない場合：変形操作は対象外
- 複数選択がない場合：単一レイヤーの変形のみ
- レイヤーグループが選択中：グループ内の全レイヤーに適用

### 実装上の注意点

#### **UI/UX：ビジュアルフィードバック（タブレット最適）**

1. **複数選択中の表示**
   - レイヤーアイテムの背景色を薄いアクセント色に（alpha: 40）
   - 選択アイコン：チェックマークアイコン表示
   - ハイライト色：C.accent (例：青）

2. **スワイプアニメーション**
   - 左スワイプ：背景に「+追加」ボタン表示（アクセント色）
   - 右スワイプ：背景に「-削除」ボタン表示（エラー色）or「+選択」（アクセント色）
   - アニメーション速度：200ms easeOut（ResponsiveTouch 最適化）

3. **ドラッグフィードバック**
   - ドラッグ中のアイテム：opacity 0.5（半透明）
   - ドロップ可能フォルダ：背景色変更 + elevation 上昇
   - ドロップインジケータ：青い破線の横線（挿入位置表示）
   - ドラッグ中のマテリアル elevation: 8（浮き上がり効果）

4. **複数選択状態の確認**
   - ヘッダーに「N件選択」テキスト表示
   - スワイプ背景で現在のアクション（追加/削除）を明確化

#### **ジェスチャー衝突回避**

- **2本指タップ**: Undo用（スワイプと競合しないよう機器レベルで処理）
- **3本指タップ**: Redo用（Undo同様）
- **長押し**: ドラッグ開始（300ms閾値）
- **スワイプ**: 左右（-40dp / +40dp 閾値）

上記が衝突しない設計に。

#### **複数選択解除のトリガー**

```
以下の操作で自動的に複数選択をクリア:
1. ドラッグ＆ドロップ完了後
2. 単一レイヤーをタップ（複数選択がない場合）
3. ヘッダーの「✕」ボタンをタップ
4. 空白エリア（レイヤーパネル外）をタップ（将来）
5. Escape キー（将来、キーボード接続時）
```

### チェックリスト（実装状況）

**既に実装済み ✅**
- [x] `_selectedIds: Set<int>` で複数選択管理
- [x] 左スワイプで複数選択追加
- [x] 右スワイプで複数選択削除
- [x] 長押し後ドラッグで複数レイヤー移動
- [x] ドロップ後に自動選択解除
- [x] 複数選択時のハイライト表示
- [x] ヘッダーに選択件数表示

**未実装・将来予定 📋**
- [ ] 複数選択×選択範囲の統一変形（Phase 3）
- [ ] 複数レイヤーの変形時にすべてのレイヤーに統一の変形行列を適用
- [ ] 選択範囲マスクで変形範囲をクリップ
- [ ] 複数選択時の Kotlin側でも状態管理
- [ ] EventChannel で複数選択状態の同期
- [ ] 空白タップで選択解除（ジェスチャーハンドラ追加）
- [ ] キーボード検出（Shift/Ctrl+タップ）対応（将来）
