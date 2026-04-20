# Pigeon 移行計画

> **目的**: MethodChannel を Pigeon で型安全化し、LLM (Claude) のコード生成時に起こりがちなキー名タイポ・型ミスマッチ・null チェック漏れを **コンパイル時検査** で構造的に排除する。
>
> **参照**: [pigeons/paint_api.dart](../propaint_flutter/pigeons/paint_api.dart)（定義）・ [FEATURE_PROGRESS.md](../FEATURE_PROGRESS.md)（大枠進捗）

---

## 🔁 次のセッション開始時の手順（必読）

1. このファイルの **「進捗」** セクションを先頭から読み、**未完了チェックの先頭** を確認する。
2. 必要に応じて Pigeon コード再生成（`scripts/gen_pigeon.ps1`）。
3. 該当セッションのタスクを実施。
4. セッション終了時に **進捗セクションを更新**（チェック追加、last updated 書換）。

これによりセッションが途中で切れても次セッションで再開可能。

---

## 配置規則

| 種別 | パス |
|---|---|
| Pigeon 定義 | `propaint_flutter/pigeons/paint_api.dart` |
| Dart 生成 | `propaint_flutter/lib/services/paint_api.g.dart` |
| Kotlin 生成 | `app/src/main/java/com/propaint/app/flutter/pigeon/PaintApi.g.kt` |
| 生成スクリプト | `scripts/gen_pigeon.ps1` |

生成ファイル（`.g.dart` / `.g.kt`）は **git 管理する**（開発環境差による再生成差分を防ぐため）。

---

## Pigeon 生成コマンド

### PowerShell (推奨)
```powershell
.\scripts\gen_pigeon.ps1
```

### 手動実行
```powershell
cd propaint_flutter
dart run pigeon --input pigeons/paint_api.dart
```

初回または依存変更後:
```powershell
cd propaint_flutter
flutter pub get
dart run pigeon --input pigeons/paint_api.dart
```

---

## 並走ポリシー

- 既存の `paint_channel.dart` と `PaintMethodChannelHandler.kt` は **削除しない**。
- Pigeon 化したメソッドも、既存 MethodChannel 経路は当面残す。
- Pigeon 経由を Flutter UI から使い始め、動作確認が取れた段階で該当 MethodChannel 分岐を削除。
- **全移行完了後**（最終セッション）に残余分を一括撤去。

この方針により、セッションが途中で切れても常にアプリは動作する状態が保たれる。

---

## よくある落とし穴

- **パス相対性**: `pigeon` は CWD からの相対でパスを解決する。必ず `propaint_flutter/` で実行。
- **Kotlin package 名**: `com.propaint.app.flutter.pigeon` で固定。変更すると既存 import が壊れる。
- **nullable**: Dart 側で `?` 必須。Kotlin 側生成コードに反映される。
- **enum**: Pigeon の `enum` を使えば Dart / Kotlin 双方で自動マッピングされる。
- **List / Map**: `List<int>` `Map<String, Object>` はサポート。ネストは深くしすぎないこと。
- **戻り値 Future**: Pigeon のメソッドは自動で非同期化される（`Future<T>` が Dart 側）。

---

## ロールバック方法

移行中に致命的問題が出た場合:

1. 生成ファイル削除: `paint_api.g.dart` / `PaintApi.g.kt`
2. `pubspec.yaml` から `pigeon:` 行を削除
3. `pigeons/paint_api.dart` を削除 or 空に戻す
4. MethodChannel 実装は残っているので、アプリは元動作に復帰

---

## 進捗

> **last updated**: 2026-04-20 (**Final 完了**: MethodChannel / EventChannel 撤去、`PaintMethodChannelHandler.kt` 削除、全通信を Pigeon 化、`PaintFlutterApi.onStateChanged` に一本化)
>
> チェック形式: `- [x]` 完了 / `- [ ]` 未完了 / `- [-]` スキップ

### Session 1: セットアップ + 試験移行（完了 2026-04-19）

- [x] `pubspec.yaml` に `pigeon: ^22.7.0` 追加
- [x] `pigeons/paint_api.dart` 作成（setBrushSize のみ定義）
- [x] `docs/PIGEON_MIGRATION.md`（本ファイル）作成
- [x] `scripts/gen_pigeon.ps1` 作成
- [x] `FEATURE_PROGRESS.md` に Pigeon セクション追加
- [x] ユーザーによる `flutter pub get` + `gen_pigeon.ps1` 実行成功 (2026-04-19)
- [x] 生成物 `paint_api.g.dart` (Pigeon v22.7.4) / `PaintApi.g.kt` 出力確認
- [x] `PaintFlutterActivity.configureFlutterEngine` で `PaintHostApi.setUp(..., object : PaintHostApi {...})` 登録
- [x] `cleanUpFlutterEngine` で `PaintHostApi.setUp(..., null)` で解除
- [x] `paint_channel.dart` に `PaintHostApi _pigeon` フィールド追加、`setBrushSize` を `_pigeon.setBrushSize(value)` に切替 (旧 MethodChannel 分岐は並走維持)
- [x] Kotlin コンパイル確認: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- [ ] 実機で setBrushSize が正常動作することを確認（ブラシサイズスライダ操作）

### Session 2: Brush 設定系 (20 メソッド)（完了 2026-04-19）

対象: `paint_channel.dart` L28-97

- [x] `setBrushType(String)` — 文字列渡し方式（BrushType.name と一致必須、不正値は FlutterError("INVALID_ARG")）
- [x] `setBrushSize(double)` ※ Session 1 で先行移行済
- [x] `setBrushOpacity(double)`
- [x] `setBrushHardness(double)`
- [x] `setBrushAntiAliasing(double)`
- [x] `setBrushDensity(double)`
- [x] `setBrushSpacing(double)`
- [x] `setBrushMinSizePercent(int)` — Dart int → Kotlin Long マッピング、`.toInt()` 変換必須
- [x] `setStabilizer(double)`
- [x] `setColorStretch(double)`
- [x] `setWaterContent(double)`
- [x] `setBlurStrength(double)`
- [x] `setBlurPressureThreshold(double)`
- [x] `togglePressureSize()`
- [x] `togglePressureOpacity()`
- [x] `togglePressureDensity()`
- [x] `togglePressureSelection()`
- [x] `exportBrushSettings() -> String`
- [x] `importBrushSettings(String) -> bool`
- [x] `resetBrushToDefaults()`
- [x] `PaintFlutterActivity.configureFlutterEngine` に 20 メソッド実装の `object : PaintHostApi` 登録
- [x] `paint_channel.dart` の Brush 系 20 メソッドを `_pigeon.xxx(...)` 呼び出しに切替
- [x] Kotlin コンパイル確認: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL (2026-04-19)
- [ ] 実機で Brush 系各スライダ・トグルが正常動作することを確認

### Session 3: Color + History + Layer CRUD (18 メソッド)（完了 2026-04-20）

対象: `paint_channel.dart` L94-144, L220-230

- [x] `setColor(int argb)` — Dart int → Kotlin Long マッピング、ARGB → Compose Color 変換
- [x] `commitColor()` — Kotlin 側 `viewModel.commitColorToHistory()`
- [x] `undo()`
- [x] `redo()`
- [x] `addLayer()`
- [x] `removeLayer(int)` — Long → Int 変換
- [x] `selectLayer(int)`
- [x] `setLayerVisibility(int, bool)`
- [x] `setLayerOpacity(int, double)` — 範囲外時 `FlutterError("INVALID_ARG")`
- [x] `setLayerBlendMode(int, int)` — mode も Long → Int 変換
- [x] `setLayerClip(int, bool)` — Kotlin 側 `setLayerClip`（`setLayerClipToBelow` のエイリアス）
- [x] `setLayerLocked(int, bool)`
- [x] `setAlphaLocked(int, bool)`
- [x] `reorderLayer(int fromId, int toId, bool insertAfter)`
- [x] `duplicateLayer(int)`
- [x] `mergeDown(int)`
- [x] `moveLayerUp(int)`
- [x] `moveLayerDown(int)`
- [x] `PaintFlutterActivity.configureFlutterEngine` に 18 メソッド実装の `object : PaintHostApi` 追加
- [x] `paint_channel.dart` の Color+History+Layer CRUD を `_pigeon.xxx(...)` 呼び出しに切替
- [x] Kotlin コンパイル確認: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL (2026-04-20)
- [ ] 実機でカラー選択・Undo/Redo・レイヤー CRUD が正常動作することを確認

### Session 4: Batch Layer + Multi Selection (9 メソッド)（完了 2026-04-20）

対象: `paint_channel.dart` L143-168

- [x] `batchMergeLayers(List<int>)` — List<Long> → List<Int> 変換
- [x] `batchSetVisibility(List<int>, bool)`
- [x] `batchSetOpacity(List<int>, double)` — 範囲外時 `FlutterError("INVALID_ARG")`
- [x] `batchSetBlendMode(List<int>, int)` — mode も Long → Int 変換
- [x] `batchMoveLayersUp(List<int>)`
- [x] `batchMoveLayersDown(List<int>)`
- [x] `setMultiSelection(List<int>)` — Kotlin 側 Set<Int> 型のため `.toSet()` 変換
- [x] `clearMultiSelection()`
- [x] `toggleLayerSelection(int)`
- [x] `PaintFlutterActivity.configureFlutterEngine` に 9 メソッド実装追加
- [x] `paint_channel.dart` の Batch/Multi Selection を `_pigeon.xxx(...)` 呼び出しに切替
- [x] Kotlin コンパイル確認: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL (2026-04-20)
- [ ] 実機で複数レイヤー選択・一括操作が正常動作することを確認

### Session 5: Layer Group (12 メソッド)（完了 2026-04-20）

対象: `paint_channel.dart` L170-204, L543-554

- [x] `createLayerGroup(String name)`
- [x] `deleteLayerGroup(int)`
- [x] `setLayerGroup(int layerId, int groupId)`
- [x] `batchSetLayerGroup(List<int>, int groupId)`
- [x] `batchMoveLayersRelative(List<int>, int targetId, bool insertAfter)`
- [x] `setGroupVisibility(int, bool)`
- [x] `setGroupOpacity(int, double)` — 範囲外時 `FlutterError("INVALID_ARG")`
- [x] `setFolderExpanded(int, bool)`
- [x] `reorderLayerGroup(int, int, bool)`
- [x] `reorderDisplayItem(int, int)`
- [x] `getLayersInGroup(int) -> List<int>` — Kotlin 側で Long → Int 変換、Flutter 側は直接 List<int> を受け取る
- [x] `getLayersInGroupRecursive(int) -> List<int>`
- [x] `PaintFlutterActivity.configureFlutterEngine` に 12 メソッド実装追加
- [x] `paint_channel.dart` のフォルダ系を `_pigeon.xxx(...)` 呼び出しに切替
- [x] Kotlin コンパイル確認: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL (2026-04-20)
- [ ] 実機でフォルダ CRUD・ドラッグ並び替え・展開/折畳が正常動作することを確認

### Session 6: Selection Tool + Selection Ops (19 メソッド)（完了 2026-04-20）

対象: `paint_channel.dart` L246-319（setSelectionMode + 選択ツール・選択範囲操作一式）

- [x] `selectRect(int l, int t, int r, int b)` / `selectEllipse(...)`
- [x] `selectByColor(int, int, int, bool)`
- [x] `selectMagnet(...)` / `cancelMagnetSelection()` / `finalizeMagnetSelection()`
- [x] `selectAll()` / `clearSelection()` / `invertSelection()`
- [x] `paintSelectionAdd(...)` / `paintSelectionErase(...)`
- [x] `deleteSelection()` / `fillSelection(int)` / `copySelection()` / `cutSelection()`
- [x] `moveSelection(int, int)`
- [x] `featherSelection(int)` / `expandSelection(int)`
- [x] `setSelectionMode(SelectionMode)` — Pigeon enum 化（Replace/Add/Subtract/Intersect）、文字列 API 廃止
- [x] `PaintFlutterActivity` に 19 メソッド実装 + Pigeon→engine enum マッピング追加
- [x] `paint_channel.dart` の選択系を `_pigeon.xxx(...)` に切替、`SelectionMode` を export
- [x] `selection_tool_panel.dart` の呼び出し元を `SelectionMode.add`/`.subtract` に更新
- [x] Kotlin コンパイル確認: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL (2026-04-20)
- [x] Dart 静的解析: `dart analyze` で関連ファイル No issues
- [ ] 実機で選択ツール・選択範囲操作・選択モード切替が正常動作することを確認

**Note: Pigeon enum の命名制約**
Pigeon は `Pigeon` で始まる enum 名を拒否する (`Enum name must not begin with "Pigeon"`)。
engine 側 `com.propaint.app.engine.SelectionMode` とパッケージが違うため、同名 `SelectionMode` で衝突しない。
Kotlin 側で変換時は完全修飾名で区別する。

### Session 7: Transform + Pixel Copy + Pixel Movement (17 メソッド) ✅ 完了 (2026-04-20)

- [x] `flipLayerH()` / `flipLayerV()` / `rotateLayer90CW()`
- [x] `transformLayer(...)` / `distortLayer(List<double>)` / `meshWarpLayer(...)`
- [x] `applyPreviewTransform(...)` / `applyMultiLayerSimpleTransform(...)`
- [x] `beginLiquify()` / `liquifyLayer(...)` / `endLiquify()`
- [x] `startPixelCopy(List<int>?) -> PixelCopyBounds` ※ data class 化、paint_channel.dart 内で `Map<String, int>` に変換して既存呼び出し元を維持
- [x] `applyPixelCopy(...)` / `cancelPixelCopy()`
- [x] `setLayersOffset(...)` / `resetLayersOffset(...)` / `commitPixelMovement(...)`

#### Session 7 実装メモ

- **PixelCopyBounds**: Pigeon の `class` 宣言でデータクラス化。フィールドは `left/top/right/bottom`(int)。Kotlin 側は `com.propaint.app.flutter.pigeon.PixelCopyBounds(...)` を return。Dart 側は `paint_channel.startPixelCopy()` が `Map<String, dynamic>` に変換して返す（transform_panel.dart / selection_tool_panel.dart / main.dart の `Function(Map<String, int>)?` コールバックに影響を与えない）。
- **List 変換**: layerIds は Dart `List<int>` → Kotlin `List<Long>` のため、全実装で `.map { it.toInt() }` 変換。nodes/corners は `List<Double>` → `FloatArray` 変換。
- **検証**: distortLayer は Dart 側 `assert(corners.length >= 8)` + Kotlin 側 `FlutterError("INVALID_ARG")`。applyPreviewTransform / applyMultiLayerSimpleTransform は空 layerIds 時 `FlutterError("INVALID_ARG")`。

### Session 8: Filter (13 メソッド) ✅ 完了 (2026-04-20)

全ピクセル走査の重い処理のため、Pigeon `@async` アノテーションで Kotlin 側を
`callback: (Result<Unit>) -> Unit` 形式にし、`lifecycleScope.launch(Dispatchers.Default)` で
UI スレッドをブロックしない構成とした。既存 `launchHeavy` と同等の責務。

- [x] `applyUnsharpMask(int radius, double amount, int threshold)`
- [x] `applyMosaic(int blockSize)`
- [x] `applyNoise(int amount, bool monochrome)`
- [x] `applyPosterize(int levels)`
- [x] `applyThreshold(int threshold)`
- [x] `applyInvertColors()`
- [x] `applyMotionBlur(double angleDeg, int distance)`
- [x] `applyLinearGradient(sx, sy, ex, ey, startColor, endColor)`
- [x] `applyLinearGradientAngle(angleDeg, startColor, endColor)`
- [x] `applyRadialGradient(cx, cy, radius, startColor, endColor)`
- [x] `applyRadialGradientCenter(startColor, endColor)`
- [x] `applyLevels(inBlack, inWhite, gamma, outBlack, outWhite)`
- [x] `applyColorBalance(cyanRed, magentaGreen, yellowBlue)`
- [x] `PaintFlutterActivity` に 13 メソッド実装追加 + `runFilter` ヘルパー（Default ディスパッチャ → Main で callback）
- [x] `paint_channel.dart` のフィルター系を `_pigeon.xxx(...)` 呼び出しに切替
- [x] Kotlin コンパイル確認: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL (2026-04-20)
- [x] Dart 静的解析: `dart analyze` で関連ファイル No issues
- [ ] 実機で各フィルター適用が正常動作することを確認（重処理中に UI が固まらないこと）

#### Session 8 実装メモ

- **@async**: Pigeon 22 の `@async` アノテーションを各メソッドに付与。Kotlin 側は `fun applyXxx(..., callback: (Result<Unit>) -> Unit)` が生成される。Dart 側シグネチャは従来同様 `Future<void>`。
- **runFilter ヘルパー**: `PaintFlutterActivity.runFilter(callback, block)` で Default → block() → Main で `callback(Result.success(Unit))`。例外は `Result.failure(e)` で返す。object 式内から `runFilter(callback) { ... }` を expression body で呼ぶ設計。
- **並走**: `PaintMethodChannelHandler.kt` L637-710 の MethodChannel 分岐は並走維持（Final セッションで撤去予定）。

### Session 9: Mask + Shape/Fill/Text + Save/Export/Import (13 メソッド) ✅ 完了 (2026-04-20)

Mask 系 5、Shape/Fill/Text 4、Save/Export/Import/Gallery 4 の合計 13。`saveProject` だけ
ファイル IO で重いため `@async` で Default ディスパッチャにオフロード（Session 8 の `runFilter` を再利用）。
`returnToGallery` / `requestExport` / `requestImport` は ViewModel 側コールバックを invoke するだけなので sync のまま。

- [x] `addLayerMask(bool fillWhite)` / `removeLayerMask()` / `toggleMaskEnabled()` / `toggleEditMask()` / `addMaskFromSelection()`
- [x] `drawShape(String shapeType, int left, int top, int right, int bottom, bool fill, double thickness)`
- [x] `floodFill(int x, int y, int tolerance)` / `setFloodFillTolerance(int tolerance)`
- [x] `addTextLayer(String text, double fontSize, double x, double y, bool bold, bool italic, bool vertical)`
- [x] `saveProject()` (@async)
- [x] `returnToGallery()` / `requestExport(String format)` / `requestImport(String type)`
- [x] `PaintFlutterActivity` に 13 メソッド実装追加（saveProject は `runFilter(callback) { viewModel.saveCurrentProject() }`）
- [x] `paint_channel.dart` の該当メソッドを `_pigeon.xxx(...)` に切替、既存呼び出し元の関数シグネチャは維持
- [x] Kotlin コンパイル確認: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL (2026-04-20)
- [x] Dart 静的解析: `dart analyze` Session 9 関連の新規 issue なし（既存警告のみ）
- [ ] 実機でマスク追加/編集、図形描画、塗りつぶし、テキストレイヤー、保存・エクスポート・インポートが正常動作することを確認

### Session 10: View + Tool Mode (5 メソッド) ✅ 完了 (2026-04-20)

`ToolMode` を Pigeon enum 化（13 要素）し、`ViewTransform` を data class 化。既存の
`paint_channel.setToolMode(String)` API と `getViewTransform() -> Map<String, dynamic>` の
シグネチャは維持し、内部で Pigeon 型へ変換。呼び出し元の変更ゼロ。

- [x] `activateEyedropper()` / `deactivateEyedropper()`
- [x] `setToolMode(ToolMode)` — Pigeon enum 化（13 要素: draw/eyedropper/selectMagnet/selectMagicWand/selectPen/transform/pixelCopy/shapeLine/shapeRect/shapeEllipse/floodFill/gradient/text）
- [x] `resetView()`
- [x] `getViewTransform() -> ViewTransform`（data class: zoom/panX/panY/rotation:double, surfaceWidth/surfaceHeight/docWidth/docHeight:int）
- [x] `PaintFlutterActivity` に 5 メソッド実装追加 + Pigeon ToolMode → engine ToolMode マッピング
- [x] `paint_channel.dart` で `_toolModeByName` マップ経由の String → Pigeon enum 変換を内部実装、`getViewTransform` は ViewTransform → Map 変換で互換維持
- [x] `paint_channel.dart` export に `ToolMode` 追加
- [x] Kotlin コンパイル確認: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL (2026-04-20)
- [x] Dart 静的解析: `dart analyze paint_channel.dart` No issues
- [ ] 実機でツール切替・スポイト・ビューリセット・PixelCopyOverlay の座標変換が正常動作することを確認

**Note: Pigeon enum と engine enum の命名規則**
Pigeon は Dart 側 lowerCamelCase / Kotlin 側 UPPER_SNAKE_CASE に変換する。engine 側
`com.propaint.app.viewmodel.ToolMode` は PascalCase のため、`setToolMode` 内の `when` 式で
明示的に 1:1 マッピングを記述している。両者のパッケージが違うため同名 `ToolMode` でも衝突しない。

### Session 11: State Stream (FlutterApi) ✅ 完了 (2026-04-20)

**方針: A 案（並走維持）**。EventChannel (`buildStateMap` → `stateStream`) は Final セッションまで残し、
Session 11 では `getState` を Pigeon 経由に切替 + `PaintFlutterApi` インスタンスのみ先行登録する。
Kotlin 側は `onStateChanged` を送出せず、`onNativeGesture` / `onErrorMessage` は従来通り
MethodChannel 側で送る（Dart 側 FlutterApi 実装は受信ハンドラを空で保持）。

- [x] `LayerInfo` データクラス化（17 フィールド: id/name/opacity/blendMode/isVisible/isLocked/isClipToBelow/isActive/isAlphaLocked/hasMask/isMaskEnabled/isEditingMask/groupId/isTextLayer/isGroup/depth/isExpanded）
- [x] `PaintState` データクラス化（34 フィールド、`toolMode: ToolMode` enum、`viewTransform: ViewTransform?`、`layers: List<LayerInfo>`、`selectedLayerIds: List<int>`（Pigeon は Set 非対応のため List 化）、デバイスメモリ情報 7 フィールド含む）
- [x] `@FlutterApi PaintFlutterApi` 定義（`onStateChanged(PaintState)` / `onNativeGesture(String)` / `onErrorMessage(String)`）
- [x] `PaintHostApi.getState() -> PaintState` 追加
- [x] `PaintFlutterActivity` に `buildPaintState()` ヘルパー + `toolModeToPigeon()` マッピング + `colorToArgbInt()` 追加
- [x] `PaintFlutterActivity` で `PaintFlutterApi` インスタンス化（送信は Final で有効化、現状は生成のみ）
- [x] `paint_channel.dart` の `getState()` を `_pigeon.getState()` + `_paintStateToMap()` 経由に切替（既存 `Map<String, dynamic>` シグネチャ維持で呼び出し元変更ゼロ）
- [x] `paint_channel.dart` の `_toolModeToEngineName()` で Pigeon ToolMode → engine PascalCase 逆変換（Session 10 `_toolModeByName` の逆マップ）
- [x] `paint_flutter_api_impl.dart` 作成（受信ハンドラはコンストラクタで注入、フィールド名は `stateChangedHandler` / `nativeGestureHandler` / `errorMessageHandler` で override メソッドと区別）
- [x] `PaintChannel` constructor で `PaintFlutterApi.setUp(PaintFlutterApiImpl(...))` 登録（Kotlin 非送信のため未配線だが Final に向け配管のみ先行）
- [x] Kotlin コンパイル確認: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL (2026-04-20)
- [x] Dart 静的解析: `flutter analyze` で Session 11 関連の新規 issue なし（既存警告のみ）
- [ ] 実機で `getState()` 初期化経路・ジェスチャ/エラー通知が正常動作することを確認
- [ ] Final セッションで `stateStream` を FlutterApi.onStateChanged に切替、EventChannel 廃止

**Note: A 案を採った理由**
`buildStateMap` は差分配信（`sendDiff`）で使われており、PaintState 全体を毎回送ると帯域とパース
コストが増える。Final セッションで EventChannel 廃止と差分送信ロジック（`onStateChanged` 差分化）
を同時に設計したほうが、中間状態で両方を書き換える手戻りを避けられる。Session 11 は型定義と
getState 切替までに留める。

### Final: 旧 MethodChannel 撤去 ✅ 完了 (2026-04-20)

**成果**:
- `PaintMethodChannelHandler.kt` (993 行) を完全削除
- `paint_channel.dart` から `MethodChannel` / `EventChannel` の使用を全廃
- Flutter ↔ Kotlin 全通信が Pigeon (`PaintHostApi` + `PaintFlutterApi`) 経由に一本化

**Kotlin 側変更 (`PaintFlutterActivity.kt`)**:
- [x] `PaintMethodChannelHandler` の参照削除（`channelHandler` フィールド削除、生成処理削除、`cleanUpFlutterEngine` 内の dispose 呼び出し削除）
- [x] State observer (`startPaintStateObserver`) を移植：`brushSize` 等の StateFlow 変更を `combine` / `sample(32ms)` / `distinctUntilChanged` / `conflate` で監視し、`sendPaintStateSnapshot` を呼ぶ
- [x] `sendPaintStateSnapshot()` 実装：`buildPaintState()` で PaintState を構築し、`lastSentPaintState` と equals 比較して差分があれば `paintFlutterApi?.onStateChanged(...)` で送信
- [x] ジェスチャ通知：`viewModel.onGestureEvent = { api.onNativeGesture(it) { } }` を `configureFlutterEngine` に配線
- [x] エラー通知：`viewModel.errorMessage.collect { api.onErrorMessage(it) { } }` を `lifecycleScope.launch` で配線
- [x] `cleanUpFlutterEngine`: `stateObserverJob?.cancel()` / `paintFlutterApi = null` / `lastSentPaintState = null` / `viewModel.onGestureEvent = null` を追加
- [x] 注意点：StateFlow に `distinctUntilChanged()` を明示適用するとコンパイルエラー（Operator Fusion 違反）。生の StateFlow.collect で十分

**Dart 側変更 (`paint_channel.dart`)**:
- [x] `MethodChannel('com.propaint.app/paint')` / `EventChannel('com.propaint.app/state')` の定数を削除
- [x] `import 'package:flutter/services.dart'` を `import 'dart:async'` に置換（StreamController 用）
- [x] `_method.setMethodCallHandler(...)` 削除（ジェスチャ/エラーは PaintFlutterApiImpl で受信）
- [x] `stateStream` を `StreamController<Map<String, dynamic>>.broadcast()` 経由に変更し、`PaintFlutterApiImpl.stateChangedHandler` で `_paintStateToMap()` 変換して push
- [x] 既存 `Stream<Map<String, dynamic>>` シグネチャ維持 — main.dart の `StreamBuilder<PaintState>` + `copyWithMap` はそのまま動作

**ファイル削除**:
- [x] `app/src/main/java/com/propaint/app/flutter/PaintMethodChannelHandler.kt` 削除

**設計メモ：差分送信 → フルスナップショット**:
旧 `sendDiff()` はキー単位で変更項目のみ送信していたが、Final では Pigeon 型安全な PaintState data class の equals 比較に切替。
送信ペイロードは増える（34 フィールド + レイヤー配列）が、
- `StateFlow` の `distinctUntilChanged` と `sample(32ms)` / `conflate` で発火頻度は元と同等
- data class equals により同一状態の連続送信は抑制される
- Dart 側 `PaintState.copyWithMap` は Map の欠損キー対応（`m['key'] as T? ?? previous`）のため全キー送信で問題なし
- 実機で性能問題が出た場合は、PaintFlutterApi に差分送信用の別メソッド（`onPartialStateChanged(Map)` 等）を追加する余地あり

**検証**:
- [x] Kotlin コンパイル: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- [x] Dart 静的解析: `flutter analyze` 新規 issue なし（既存警告のみ）
- [ ] 実機動作確認：起動、描画、レイヤー操作、Undo/Redo、ジェスチャ（2 本指タップ）、エラー表示、保存/ロード、ツール切替、ビューリセット

---

## データクラス化が必要な戻り値

移行時に単純型だけでは表現できないものを `class` 化する:

- `getState()` の戻り値: `PaintState { ... }`
- `getViewTransform()` の戻り値: `ViewTransform { double zoom; double panX; ... }`
- `startPixelCopy()` の戻り値: `PixelCopyBounds { int left; int top; int right; int bottom; }`
- `MemoryInfo`: 既存 Dart 側クラスと揃える

---

## 移行 1 メソッドの標準手順

次セッションで参照するため記録:

### 例: setBrushSize

**1. Pigeon 定義追加** (`pigeons/paint_api.dart`)
```dart
@HostApi()
abstract class PaintHostApi {
  void setBrushSize(double value);
}
```

**2. 生成**
```powershell
.\scripts\gen_pigeon.ps1
```

**3. Kotlin 側実装クラスを `PaintFlutterActivity.kt` で登録**
```kotlin
// PaintFlutterActivity.configureFlutterEngine() の中で
PaintHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger,
    object : PaintHostApi {
        override fun setBrushSize(value: Double) {
            require(value > 0) { "BrushSize must be > 0" }
            viewModel.setBrushSize(value.toFloat())
        }
    }
)
```

**4. Dart 側使用箇所を書き換え** (`paint_channel.dart`)
```dart
final _pigeon = PaintHostApi();

Future<void> setBrushSize(double value) {
  assert(value > 0);
  return _pigeon.setBrushSize(value);  // 旧: _method.invokeMethod(...)
}
```

**5. 既存の MethodChannel 分岐は残しておく**（並走）

**6. 実機で動作確認後、MethodChannel 側を削除**（必要なら後回し）

---
