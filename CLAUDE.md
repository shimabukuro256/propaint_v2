# ProPaint v2 開発ルール

## プロジェクト概要

Android ペイントアプリ。Kotlin + Jetpack Compose + OpenGL ES 2.0。
描画エンジンは Drawpile 準拠の CPU タイルベース (64x64 premultiplied ARGB_8888)。
UI は Flutter (メイン) + Compose (ギャラリー)。

### ディレクトリ構成

```
app/src/main/java/com/propaint/app/
├── engine/        # 描画エンジン (BrushEngine, CanvasDocument, PixelOps, Tile 等)
├── gl/            # OpenGL レンダラ (表示専用、描画処理なし)
├── ui/            # Compose UI (PaintScreen, パネル類)
├── gallery/       # ギャラリー画面 (GalleryScreen, NewCanvasDialog)
├── viewmodel/     # PaintViewModel
└── flutter/       # Flutter 連携 (MethodChannel)

app/src/test/         # JVM ユニットテスト (エミュレータ不要)
app/src/androidTest/  # Instrumented テスト (エミュレータ必要)
app/testFixtures/golden/  # ゴールデンイメージ (スクリーンショット回帰テスト)
.github/workflows/    # CI (GitHub Actions)
```

---

## バグ報告→修正ワークフロー

ユーザーがバグや不具合を報告したら、以下の手順で **自動的に** 調査・修正・検証すること。
都度の許可確認は不要（パーミッション設定済み）。

### 手順

1. **ログ解析** — logcat が提供されたら下記「デバッグログ解析」の手順で根本原因を特定
2. **関連コード読解** — 原因箇所のコードを読んでバグパターンと照合
3. **修正** — コードを修正（防御的アサーション + 構造化ログを含む）
4. **テスト実行** — `./gradlew :app:testDebugUnitTest --no-daemon` で全テストを実行
5. **回帰テスト追加** — 修正したバグの再発を防ぐテストを追加
6. **結果報告** — テスト結果とともに修正内容を簡潔に報告

### テスト実行コマンド

```bash
# JVM ユニットテスト (全テスト、数秒で完了)
./gradlew :app:testDebugUnitTest --no-daemon

# 特定テストクラスのみ
./gradlew :app:testDebugUnitTest --no-daemon --tests "com.propaint.app.engine.PixelOpsTest"

# Instrumented テスト (エミュレータ接続時のみ)
./gradlew :app:connectedDebugAndroidTest --no-daemon

# ビルドのみ
./gradlew :app:compileDebugKotlin --no-daemon
```

---

## GitHub 反映 (git push)

ユーザーが「GitHub に反映して」「push して」等と言ったら、以下の手順で実行すること。

### 手順

1. **テスト実行** — `./gradlew :app:testDebugUnitTest --no-daemon` で全テストをパスさせる
2. **変更確認** — `git status` と `git diff` で変更内容を確認
3. **ステージング** — 関連ファイルを `git add` (機密ファイルを含めないこと)
4. **コミット** — 変更内容を反映したコミットメッセージで `git commit`
5. **プッシュ** — `git push origin main`
6. **CI 確認** — push 後に GitHub Actions が自動でユニットテストを実行する旨を報告

### 注意事項

- `.claude/settings.local.json` は push しない（ローカル設定）
- `local.properties` は push しない（ANDROID_SDK パス等）
- `app/testFixtures/diff/` は push しない（.gitignore 済み）
- `app/testFixtures/golden/*.png` は push する（ゴールデンイメージ）
- コミットメッセージは日本語 OK

### GitHub Actions CI (無料プラン対応)

リポジトリ: `shimabukuro256/propaint_v2` (パブリック → Actions 無料無制限)

- **push / PR 時**: JVM ユニットテストが自動実行 (~3分)
- **Instrumented テスト**: 手動実行のみ (Actions タブ → "Tests" → "Run workflow")
  - エミュレータ起動に時間がかかるため自動では実行しない
- **連続 push 時**: 古い実行が自動キャンセルされる (concurrency 設定)

テスト結果は GitHub リポジトリの **Actions タブ** で確認できる。
失敗時はテストレポートとゴールデンイメージ diff が Artifact としてダウンロード可能。

---

## テスト基盤

### JVM ユニットテスト (`app/src/test/`)

エミュレータ不要、JVM 上で高速に実行。`android.util.Log` は `mockk` でモック。

| テストファイル | カバー範囲 |
|---|---|
| `PixelOpsTest` | premultiply往復, 二重premul検出, SrcOver, Erase, 全ブレンドモード, sRGB↔リニア, lerpColor |
| `TileAndSurfaceTest` | Tile COW, TiledSurface グリッド計算, 負座標 floorDiv, snapshot, getPixelAt |
| `CanvasDocumentTest` | レイヤー CRUD, activeLayerId更新, Undo/Redo, ロック層, 合成キャッシュ白背景 |
| `BrushEngineTest` | beginStroke/endStroke, NaN/Inf防御, spacing=0防御, pressure範囲外, ストローク描画 |
| `DabMaskAndDirtyTrackerTest` | マスク生成, radius境界値, NaN/Inf, dirty通知/クリア |
| `StrokeRegressionTest` | **ゴールデンイメージ回帰テスト** (8シナリオ) |
| `PerformanceTest` | ホットパスの処理時間回帰テスト (閾値超過でFAIL) |

### ゴールデンイメージ回帰テスト

`StrokeRegressionTest` は固定パラメータでストロークを描画し、結果を `app/testFixtures/golden/` の PNG と比較する。

- **初回実行**: ゴールデンイメージを自動生成 → Git にコミットする
- **以降**: ピクセル比較。差分があれば `testFixtures/diff/` に actual + diff 画像を出力して FAIL
- **描画ロジックを意図的に変更した場合**: ゴールデンイメージを削除して再生成

テストシナリオと検出バグ:
- `opaque_black_stroke` — 基本描画の回帰
- `red_color_fidelity` — 色くすみ (premul 二重適用)
- `semitrans_overlap` — 半透明の黒ずみ (SrcOver)
- `eraser_removes_paint` — 消しゴムが白く塗る
- `soft_brush_gradient` — ブラシグラデーション崩れ
- `diagonal_no_gaps` — ストロークの途切れ
- `pressure_variation` — 筆圧サイズ変動
- `marker_opacity_ceiling` — マーカーの alpha 天井

### パフォーマンステスト

`PerformanceTest` はホットパスの処理時間を計測し、閾値超過で FAIL する。

- ダブ処理: < 1ms (CLAUDE.md 基準)
- タイルブレンド: < 5ms
- 100点ストローク: < 200ms
- 大ブラシ200点ストローク: < 1000ms
- COW snapshot: < 1ms
- 5レイヤー合成 512x512: < 200ms

### Instrumented テスト (`app/src/androidTest/`)

エミュレータ / 実機で実行。

| テストファイル | 内容 |
|---|---|
| `GlShaderTest` | EGL コンテキスト作成、全シェーダーのコンパイル・リンク、uniform存在確認 |
| `NewCanvasDialogTest` | Compose UI テスト (ダイアログ表示、プリセット選択、作成コールバック) |

### テスト追加ルール

バグ修正時は必ず回帰テストを追加すること:
- **描画結果に関わるバグ** → `StrokeRegressionTest` にゴールデンイメージテストを追加
- **数値計算のバグ** → `PixelOpsTest` 等に数値検証テストを追加
- **状態管理のバグ** → `CanvasDocumentTest` に状態遷移テストを追加
- **パフォーマンスの問題** → `PerformanceTest` にベンチマークを追加
- **新テスト追加後は必ず全テスト実行して既存テストが壊れていないことを確認**

### CI (GitHub Actions)

`.github/workflows/unit-tests.yml`:
- push / PR → JVM ユニットテスト自動実行
- ユニットテスト通過後 → エミュレータで Instrumented テスト実行
- テスト失敗時はレポートとゴールデンイメージ diff を Artifact にアップロード

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

---

## 既知バグパターン（コード生成時に照合すること）

### 色系
- 描画色がくすむ → premultiplied alpha 二重適用を疑う 【テスト: `red_color_fidelity`, `double premul detection`】
- 半透明で重ね描きすると黒ずむ → SrcOver 数式が premultiplied 対応か確認 【テスト: `semitrans_overlap`】
- 消しゴムが白く塗る → DestOut ブレンドモードになっているか確認 【テスト: `eraser_removes_paint`】
- HSV 変換で彩度が消失 → S/V が 0..1 なのか 0..100/360 なのか確認
- 保存画像の色が変わる → PNG 出力前に un-premultiply しているか確認

### 描画系
- ブラシが透明 → radius=0 / opacity=0 / alpha=0 / 範囲外 / dirty 未通知のいずれか
- ストロークが途切れる → spacing が大きすぎるか補間がないか 【テスト: `diagonal_no_gaps`】
- 始点が巨大 → carryDistance が未リセット
- テーパーが効かない → totalDistance 未更新 / smoothStep 正規化ミス
- 高速カーブが折れ線 → getHistoricalX/Y を使っていない
- タイル境界に隙間 → クリップ処理の off-by-one

### 座標・入力・レイヤー系
- ズーム後にブラシがズレる → 逆変換行列の更新タイミング / letterbox 未考慮
- 回転後に鏡像反転 → sin の符号が逆 / 回転中心のミス
- 二本指ズームで線が描かれる → ACTION_POINTER_DOWN で描画を中断していない
- レイヤー追加後に旧レイヤーに描画される → 古い参照をキャッシュしている
- レイヤー合成が全体黒 → 背景が透明（白 0xFFFFFFFF で開始すること）【テスト: `rebuildCompositeCache fills white background`】
- タイル負座標でクラッシュ → floorDiv を使うこと 【テスト: `pixelToTile negative coordinates`】

### Undo・スレッド・性能系
- Undo でクラッシュ → null タイルがスナップショットに未記録 【テスト: `undo restores pixel data after clearLayer`】
- Undo 繰返しで OOM → COW 参照が未解放 / 上限が未設定
- ストローク中の Undo → サブレイヤー未合成でデータ破損（ストローク中は Undo 禁止）
- GL にちらつき → IntArray 書込中に GL が読取（COW コピーで解決）
- GL コンテキスト喪失 → onSurfaceCreated でテクスチャ再生成 【テスト: `GlShaderTest`】
- GC Pause → ホットパスでオブジェクト生成を禁止 【テスト: `PerformanceTest`】

---

## 最終自問（出力直前）

1. 実機で最も壊れやすい箇所に防御コードがあるか？
2. null / 0 / NaN / 空配列が入りうる引数全てに防御処理があるか？
3. マルチスレッドでアクセスされるデータに同期機構 / COW があるか？
4. 上記の既知バグパターンが再発していないか？
5. **修正後に `./gradlew :app:testDebugUnitTest --no-daemon` を実行して全テストがパスしたか？**
6. **修正したバグの回帰テストを追加したか？**

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
ログ解析スクリプト: `tools/logcat_analyzer.py`
