# ペイントアプリ開発ルール

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

---

## 最終自問（出力直前）

1. 実機で最も壊れやすい箇所に防御コードがあるか？
2. null / 0 / NaN / 空配列が入りうる引数全てに防御処理があるか？
3. マルチスレッドでアクセスされるデータに同期機構 / COW があるか？
4. 上記の既知バグパターンが再発していないか？

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
