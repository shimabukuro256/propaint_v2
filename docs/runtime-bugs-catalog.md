# ランタイムバグカタログ — ペイントアプリ特有の既知バグパターン

このカタログはペイントアプリ開発で繰り返し発生するランタイムバグを
カテゴリ別に整理したものである。新しいバグに遭遇した場合はこのファイルに追記する。

各エントリの構成:
- **症状**: ユーザーが観察する現象
- **根本原因**: コード上の問題
- **検出方法**: ログやアサーションでの検出パターン
- **修正パターン**: 正しい実装

---

## 目次

1. [ブラシ描画系](#1-ブラシ描画系)
2. [色・アルファ系](#2-色アルファ系)
3. [座標変換系](#3-座標変換系)
4. [タイルシステム系](#4-タイルシステム系)
5. [入力処理系](#5-入力処理系)
6. [レイヤー系](#6-レイヤー系)
7. [Undo/Redo 系](#7-undoredo-系)
8. [スレッド・同期系](#8-スレッド同期系)
9. [メモリ・パフォーマンス系](#9-メモリパフォーマンス系)
10. [GL 表示系](#10-gl-表示系)
11. [ファイル IO 系](#11-ファイル-io-系)
12. [ジェスチャー・UI 系](#12-ジェスチャーui-系)

---

## 1. ブラシ描画系

### BUG-B001: ブラシが何も描画しない (透明)

**症状**: ストロークしても何も表示されない
**根本原因の候補**:
1. ダブの radius が 0 になっている (筆圧 0.0 × brushSize)
2. opacity が 0.0 のまま
3. 色の alpha が 0
4. ダブがキャンバス範囲外に配置されている
5. DirtyTile がGLスレッドに通知されていない
6. サブレイヤー (Indirect モード) が合成されていない

**検出方法**:
```
Log.d(TAG_BRUSH, "[DAB] x=$x y=$y r=$radius opacity=$opacity color=#${color.toString(16)}")
→ radius=0 または opacity=0.0 または alpha=00 が連続していないか
```

**修正パターン**:
```kotlin
// 描画パスの入り口で全パラメータをチェック
fun drawDab(x: Float, y: Float, pressure: Float) {
    val effectiveRadius = max(1, (brushSize * pressure * 0.5f).toInt())
    val effectiveOpacity = brushOpacity.coerceIn(0.001f, 1.0f)
    val effectiveColor = if (Color.alpha(brushColor) == 0) {
        brushColor or 0xFF000000.toInt()  // alpha=0 を修正
    } else brushColor
    // ...
}
```

### BUG-B002: ストロークが点々になる / 途切れる

**症状**: 連続した線にならず点が等間隔で並ぶ
**根本原因**: spacing が大きすぎる、またはパス補間が無い
**検出方法**:
```
Log.d(TAG_BRUSH, "[PATH] from=($x0,$y0) to=($x1,$y1) dist=$dist spacing=$spacing dabCount=$dabCount")
→ dabCount が 1 以下の場合、spacing が distance より大きい
```
**修正パターン**: spacing = brushDiameter * spacingRatio (0.05～0.3)。
最低でも distance / spacing >= 1 になるよう spacing を clamp する。

### BUG-B003: ストロークの最初のダブが巨大 / 位置がズレる

**症状**: タッチ開始点に異常に大きいダブが出る
**根本原因**: 前回のストロークの残距離 (carryDistance) がリセットされていない、
または初回ポイントの筆圧が正しく取得できていない
**検出方法**:
```
beginStroke 時に carryDistance=0 にリセットされているかログで確認
```

### BUG-B004: ストロークの始点・終点が尖らない (テーパーが効かない)

**症状**: 入り抜き設定しても太さが均一
**根本原因**: テーパー計算で使う totalDistance がストローク中に更新されていない、
または smoothStep 関数の引数が正規化されていない (0..1 範囲外)
**検出方法**: ストローク開始/終了付近の radius をログ出力して変化を確認

### BUG-B005: 高速ストロークで角が直線になる

**症状**: 素早くカーブを描くと角が丸まらず折れ線になる
**根本原因**: MotionEvent.getHistoricalX/Y を使っていない
**修正パターン**:
```kotlin
override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
        ACTION_MOVE -> {
            // 全ての中間ポイントを処理する
            for (h in 0 until event.historySize) {
                processPoint(
                    event.getHistoricalX(h),
                    event.getHistoricalY(h),
                    event.getHistoricalPressure(h),
                    event.getHistoricalEventTime(h)
                )
            }
            processPoint(event.x, event.y, event.pressure, event.eventTime)
        }
    }
}
```

---

## 2. 色・アルファ系

### BUG-C001: 描画した色がくすむ / 暗くなる

**症状**: 明るい赤を選んでも暗い赤で描画される
**根本原因**: premultiplied alpha の二重適用。
色を premultiply してからさらに alpha 乗算している
**検出方法**:
```
ダブ合成前後のピクセル値をログ出力し、
RGB が alpha 値を超えていないか確認 (premultiplied 条件)
```
**修正パターン**: 色の変換パスを一箇所に集約し、premultiply は1回だけ行う

### BUG-C002: 半透明で描くと色が黒ずむ

**症状**: opacity 50% で重ね描きすると黒に近づく
**根本原因**: SrcOver 合成の数式で premultiplied を考慮していない
**修正パターン**:
```kotlin
// 正しい premultiplied SrcOver
fun blendSrcOver(dst: Int, src: Int): Int {
    val sa = (src ushr 24) and 0xFF
    val invSa = 255 - sa
    val a = sa + ((invSa * ((dst ushr 24) and 0xFF)) / 255)
    val r = ((src ushr 16) and 0xFF) + ((invSa * ((dst ushr 16) and 0xFF)) / 255)
    val g = ((src ushr 8) and 0xFF)  + ((invSa * ((dst ushr 8) and 0xFF)) / 255)
    val b = (src and 0xFF)           + ((invSa * (dst and 0xFF)) / 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
```

### BUG-C003: Indirect (Wash) モードで opacity が蓄積しすぎる

**症状**: 同じ場所を塗り直すとどんどん濃くなる（direct と同じ動作になる）
**根本原因**: サブレイヤーが毎ダブ生成されている、
またはサブレイヤーへの合成が opacity=1.0 で行われていない
**修正パターン**: ストローク開始で1つサブレイヤー作成、
ダブは opacity=1.0 でサブレイヤーに合成、
ストローク終了時に brush.opacity でメインレイヤーに合成

### BUG-C004: 消しゴムが白く塗る (透明にならない)

**症状**: 消しゴムで描くと白い線が引かれる
**根本原因**: Erase ブレンドモード (DestOut) ではなく白色の Normal 描画になっている、
または合成済みバッファしかなく元タイルの alpha を変更できていない
**修正パターン**: 消しゴムは該当レイヤーのタイルに対して DestOut 合成を行う

### BUG-C005: HSV→RGB 変換で彩度が失われる

**症状**: 鮮やかな色を選んでもくすんで見える
**根本原因**: HSV の S や V が 0..1 ではなく 0..100 や 0..360 で渡されている
**検出方法**: HSV 値と変換後 RGB 値を並べてログ出力

---

## 3. 座標変換系

### BUG-X001: ズーム後にブラシ位置がズレる

**症状**: ズームするとタッチ位置とダブの位置が離れる
**根本原因**: 逆変換行列の更新タイミングが合っていない、
または letterbox offset が考慮されていない
**検出方法**:
```
Log.d(TAG_INPUT, "[TRANSFORM] screen=($sx,$sy) doc=($dx,$dy) zoom=$zoom panX=$panX panY=$panY")
→ 既知座標 (キャンバス中央等) でscreen/doc の対応が正しいか検証
```

### BUG-X002: 回転後に座標が鏡像反転する

**症状**: 回転するとブラシが反対方向に動く
**根本原因**: 逆行列計算で sin の符号が逆、
または回転中心がスクリーン中央でなくキャンバス原点になっている
**修正パターン**: 変換行列は concat 順序が重要:
`translate(pivotX, pivotY) → rotate(angle) → scale(zoom) → translate(-pivotX, -pivotY) → translate(panX, panY)`

### BUG-X003: タイル境界でダブが途切れる

**症状**: 描線がグリッド状に途切れる / タイル境界に隙間ができる
**根本原因**: ダブをタイル分割する際のクリップ計算が off-by-one
**修正パターン**: ダブ影響範囲の各タイルに対して正しいクリップ矩形を計算し、
範囲端を inclusive で処理する

---

## 4. タイルシステム系

### BUG-T001: タイル外への書き込み (ArrayIndexOutOfBounds)

**症状**: 特定位置でクラッシュ
**根本原因**: floorDiv を使わず通常の除算で負の座標を処理している
**修正パターン**:
```kotlin
// ❌ NG: -1 / 64 = 0 (Kotlin の整数除算は 0 方向に切り捨て)
val tileX = canvasX / TILE_SIZE

// ✅ OK: -1 floorDiv 64 = -1
val tileX = Math.floorDiv(canvasX, TILE_SIZE)
val localX = Math.floorMod(canvasX, TILE_SIZE)
```

### BUG-T002: タイルが更新されない (ダーティ通知漏れ)

**症状**: 描画したはずの領域が表示されない
**根本原因**: DirtyTileTracker への通知が漏れている
**検出方法**: ダブ合成後の dirtyCount と GL 側の更新回数を比較

### BUG-T003: Copy-on-Write のコピー漏れ

**症状**: Undo すると別のレイヤーまで巻き戻る
**根本原因**: refCount チェックなしにタイルを直接変更している
**修正パターン**: タイル変更前に必ず `tile.ensureExclusive()` (refCount > 1 ならコピー)

---

## 5. 入力処理系

### BUG-I001: 二本指ズームがストロークとして記録される

**症状**: ピンチズームすると2本の線が描画される
**根本原因**: マルチタッチのポインター判定が不十分、
ACTION_POINTER_DOWN で描画を中断していない
**修正パターン**:
```kotlin
when (event.actionMasked) {
    ACTION_DOWN -> { isDrawing = true; beginStroke(...) }
    ACTION_POINTER_DOWN -> {
        // 2本目の指が触れた → 描画を中止してジェスチャーモードに入る
        if (isDrawing) { cancelStroke(); isDrawing = false }
        isGesture = true
    }
    ACTION_MOVE -> {
        if (isGesture) { handlePinchZoom(event) }
        else if (isDrawing) { continueStroke(...) }
    }
    ACTION_UP, ACTION_CANCEL -> { resetState() }
}
```

### BUG-I002: スタイラスのパームリジェクションが効かない

**症状**: 手のひらが画面に触れると誤描画される
**根本原因**: TOOL_TYPE_FINGER で touchMajor が大きいイベントを除外していない
**修正パターン**: `event.getToolType(pointerIndex) == TOOL_TYPE_FINGER &&
event.getTouchMajor(pointerIndex) > PALM_THRESHOLD` を除外

### BUG-I003: 筆圧が常に 1.0 / 0.0 になる

**症状**: 筆圧感知しない
**根本原因の候補**:
1. スタイラスが TOOL_TYPE_STYLUS で報告されていない
2. getPressure() ではなく getSize() を使っている
3. 筆圧カーブの計算で入力値のクランプが不正

### BUG-I004: ACTION_CANCEL 未処理でゴーストストローク

**症状**: システムがタッチを奪った後、半分のストロークが残る
**修正パターン**: ACTION_CANCEL で endStroke() と同等のクリーンアップを行う

---

## 6. レイヤー系

### BUG-L001: レイヤー追加後に描画対象が旧レイヤーのまま

**症状**: 新レイヤーを追加して選択したのに下のレイヤーに描かれる
**根本原因**: activeLayerIndex は更新したが実際の描画ターゲットが古い参照のまま
**修正パターン**: activeLayer は index ではなく毎回 `layers[activeLayerIndex]` で参照

### BUG-L002: 非表示レイヤーに描画できてしまう

**修正パターン**: 描画開始時に `if (!activeLayer.isVisible) return` をチェック

### BUG-L003: レイヤー合成で全体が黒くなる

**症状**: レイヤーを重ねると画面が真っ黒
**根本原因**: 合成開始時の背景色が透明 (0x00000000) で、
premultiplied alpha の合成結果が黒に引っ張られる
**修正パターン**: 最下層は白背景 (0xFFFFFFFF) から開始する

### BUG-L004: レイヤー順序変更後に合成結果がおかしい

**根本原因**: 合成キャッシュが古い順序のままになっている
**修正パターン**: 順序変更後に全タイルをダーティにマークする

---

## 7. Undo/Redo 系

### BUG-U001: Undo でクラッシュ (null タイル参照)

**根本原因**: スナップショット保存時に null タイル (透明) の扱いが不正
**修正パターン**: null タイルも「null であること」をスナップショットに記録する

### BUG-U002: Undo/Redo を繰り返すとメモリが増え続ける

**根本原因**: Copy-on-Write のリファレンスが解放されない、
または Undo 上限が設定されていない
**修正パターン**: 最大 Undo 数を設定し、古いスナップショットを明示的に解放

### BUG-U003: ストローク中に Undo するとデータ破損

**根本原因**: ストロークが完了前 (サブレイヤー未合成) の状態で Undo が実行される
**修正パターン**: ストローク中は Undo ボタンを無効化する、
または Undo 実行時に進行中ストロークを先に確定/キャンセルする

---

## 8. スレッド・同期系

### BUG-S001: ConcurrentModificationException in layer list

**根本原因**: UI スレッドでレイヤー一覧を表示中にエンジンスレッドがレイヤーを変更
**修正パターン**: レイヤーリストは不変スナップショットを UI に渡す

### BUG-S002: GL テクスチャ更新でちらつき (tearing)

**根本原因**: IntArray の書き込み途中で GL スレッドが読み取っている
**修正パターン**: Copy-on-Write でコピーを渡す、またはダブルバッファリング

### BUG-S003: IllegalStateException: GL context lost

**根本原因**: アプリがバックグラウンドから復帰してGL コンテキストが再作成されたが
テクスチャが再アップロードされていない
**修正パターン**: `onSurfaceCreated()` で全テクスチャを再生成・再アップロード

---

## 9. メモリ・パフォーマンス系

### BUG-M001: 大きいキャンバスで OOM

**メモリ概算**:
```
4096 × 4096 キャンバス = 64 × 64 タイル
1タイル = 64 × 64 × 4 bytes = 16 KB
全タイル = 4096 × 16 KB = 64 MB (1レイヤー)
10レイヤー = 640 MB → Android で OOM の可能性大
```
**修正パターン**: null タイル (未描画=透明) を活用して実メモリを削減

### BUG-M002: ストローク中にGC Pause で引っかかる

**修正パターン**: ストロークのホットパスでオブジェクト生成を避ける。
IntArray, FloatArray はプール or 事前確保。

### BUG-M003: フレームレート低下 (ダブ合成が遅い)

**検出方法**:
```
Log.d(TAG_PERF, "[FRAME] composite=${compositeMs}ms upload=${uploadMs}ms render=${renderMs}ms")
→ composite > 16ms なら1フレームに収まっていない
```
**修正パターン**: ダーティタイルのみ再合成する。全タイル再合成は避ける。

---

## 10. GL 表示系

### BUG-G001: テクスチャが白 / 黒の四角で表示される

**根本原因の候補**:
1. glTexSubImage2D のフォーマットが RGBA ではなく BGRA
2. テクスチャサイズが Power of 2 でない (古いGPU)
3. ミップマップが設定されているが生成されていない
**修正パターン**: `GL_TEXTURE_MIN_FILTER = GL_NEAREST` (ミップマップ不要にする)

### BUG-G002: キャンバスが表示されない (真っ白/真っ黒)

**根本原因の候補**:
1. MVP 行列の設定ミス (スケール 0, 視野外)
2. テクスチャがバインドされていない
3. シェーダーコンパイルエラー (ログに出るが画面は黒)
**検出方法**: `GLES20.glGetError()` を描画パスの各ステップで呼ぶ

---

## 11. ファイル IO 系

### BUG-F001: 保存した画像の色が変わる

**根本原因**: 保存時に premultiplied → straight alpha に変換せずに PNG 出力している
**修正パターン**: PNG 出力前に un-premultiply する:
```kotlin
fun unpremultiply(pixel: Int): Int {
    val a = (pixel ushr 24) and 0xFF
    if (a == 0) return 0
    val r = min(255, ((pixel ushr 16) and 0xFF) * 255 / a)
    val g = min(255, ((pixel ushr 8) and 0xFF) * 255 / a)
    val b = min(255, (pixel and 0xFF) * 255 / a)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
```

---

## 12. ジェスチャー・UI 系

### BUG-J001: カラーピッカーの値が描画に反映されない

**根本原因**: カラーピッカーの ViewModel と ブラシエンジンの色が別変数で同期していない
**修正パターン**: Single Source of Truth パターン — brushColor は一箇所で管理

### BUG-J002: ブラシサイズスライダー変更が次のストロークに反映されない

**根本原因**: スライダー変更イベントが非同期で、描画開始時にまだ反映されていない
**修正パターン**: ストローク開始時にパラメータのスナップショットを取る

### BUG-J003: 画面回転でキャンバスが消える

**根本原因**: Activity 再生成で ViewModel/Canvas データが破棄される
**修正パターン**: ViewModel にキャンバスデータを保持し、
onSaveInstanceState は使わない (データが大きすぎる)
