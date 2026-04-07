# ProPaint v2 開発ルール

## プロジェクト概要

Android ペイントアプリ。Kotlin + Jetpack Compose + OpenGL ES 2.0。
描画エンジンは Drawpile 準拠の CPU タイルベース (64x64 premultiplied ARGB_8888)。
UI は Flutter (メイン) + Compose (ギャラリー)。

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
4. **ビルド確認** — `./gradlew :app:compileDebugKotlin --no-daemon` でビルドが通ることを確認
5. **結果報告** — 修正内容を簡潔に報告

---

## GitHub 反映 (git push)

ユーザーが「GitHub に反映して」「push して」等と言ったら、**許可確認なしで** 以下の手順を一気通貫で実行すること。

### 手順

1. **ビルド確認** — `./gradlew :app:compileDebugKotlin --no-daemon` でビルドが通ることを確認
2. **変更確認** — `git status` と `git diff` で変更内容を確認
3. **ステージング** — 関連ファイルを `git add` (機密ファイル・ローカル設定を含めないこと)
4. **コミット** — 変更内容を反映した日本語コミットメッセージで `git commit`
5. **プッシュ** — `git push origin main`

### 除外ファイル（絶対に push しない）

- `.claude/` — ローカル設定・トークン含む（.gitignore 済み）
- `local.properties` — ANDROID_SDK パス等
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
