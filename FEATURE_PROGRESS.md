# ProPaint v2 機能実装進捗

> HiPaint / Procreate をリファレンスとした機能一覧。
> 各セッションで実装後にチェックを入れること。
> **操作方針**: ペンとタブレットのみで全操作が完結する設計。キーボードショートカットはジェスチャー操作・ツール切替の互換ショートカット（キー単体で機能）として実装し、キーボード＋ジェスチャーの組み合わせ操作（Ctrl+クリック等）は行わない。
> **除外機能（実装しない）**: アニメーション/オニオンスキン, CMYK色空間, ブルーム, グリッチ, ハーフトーン, 色収差, パースペクティブブラー

---




## Pigeon による MethodChannel 型安全化（段階移行中）

### 目的
LLM (Claude) のコード生成時のキー名タイポ・型ミスマッチ・null チェック漏れを、
**コンパイル時検査** で構造的に排除する。

### 進捗管理
詳細手順・セッション別チェックリストは [docs/PIGEON_MIGRATION.md](docs/PIGEON_MIGRATION.md) を参照。
**次セッション開始時は必ず docs/PIGEON_MIGRATION.md の「進捗」セクションを読むこと。**

### 大枠チェックリスト

- [x] **Session 1**: セットアップ + setBrushSize 試験移行 完了（2026-04-19）
  - [x] pubspec.yaml に pigeon 追加
  - [x] pigeons/paint_api.dart 作成
  - [x] docs/PIGEON_MIGRATION.md 作成
  - [x] scripts/gen_pigeon.ps1 作成
  - [x] ユーザー環境で pigeon コード生成成功
  - [x] PaintFlutterActivity で PaintHostApi 実装登録・cleanUpで解除
  - [x] paint_channel.dart の setBrushSize を Pigeon 経由に置換（並走）
  - [x] Kotlin コンパイル成功
  - [ ] 実機動作確認（ブラシサイズスライダで描画）
- [x] **Session 2**: Brush 設定系 完了（2026-04-19、20 メソッド）
  - [x] pigeons/paint_api.dart に 20 メソッド追加・再生成
  - [x] PaintFlutterActivity に `object : PaintHostApi` 全 20 メソッド実装登録
  - [x] paint_channel.dart の Brush 系 20 メソッドを Pigeon 経由に切替（並走維持）
  - [x] Dart int → Kotlin Long マッピング対応（setBrushMinSizePercent）
  - [x] 不正値検証: setBrushType は BrushType.name 不一致時 `FlutterError("INVALID_ARG")`、setBrushMinSizePercent は範囲外時 `FlutterError("INVALID_ARG")`
  - [x] Kotlin コンパイル成功（`./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL）
  - [ ] 実機動作確認（Brush 系各スライダ・筆圧トグル・エクスポート/インポート/リセット）
- [x] **Session 3**: Color + History + Layer CRUD 完了（2026-04-20、18 メソッド）
  - [x] pigeons/paint_api.dart に 18 メソッド追加・再生成
  - [x] PaintFlutterActivity に `object : PaintHostApi` で Session 3 全 18 メソッド実装追加
  - [x] paint_channel.dart の Color/History/Layer CRUD を Pigeon 経由に切替（並走維持）
  - [x] Dart int → Kotlin Long マッピング対応（setColor / レイヤー ID 系全般）
  - [x] setLayerOpacity: 範囲外時 `FlutterError("INVALID_ARG")`
  - [x] Kotlin コンパイル成功（`./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL）
  - [ ] 実機動作確認（カラー選択・Undo/Redo・レイヤー追加/削除/複製/結合/移動/並び替え・不透明度/ブレンドモード/クリップ/ロック/αロック/可視）
- [x] **Session 4**: Batch Layer + Multi Selection 完了（2026-04-20、9 メソッド）
  - [x] pigeons/paint_api.dart に 9 メソッド追加・再生成
  - [x] PaintFlutterActivity に Session 4 全 9 メソッド実装追加
  - [x] paint_channel.dart の Batch/Multi Selection 系を Pigeon 経由に切替（並走維持）
  - [x] List<Long> → List<Int> 変換（`ids.map { it.toInt() }`）実装
  - [x] batchSetOpacity: 範囲外時 `FlutterError("INVALID_ARG")`
  - [x] setMultiSelection: List<Long> → Set<Int> 変換（`.map { it.toInt() }.toSet()`）
  - [x] Kotlin コンパイル成功（`./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL）
  - [ ] 実機動作確認（複数レイヤー選択・一括可視/不透明度/ブレンドモード・一括マージ/移動）
- [x] **Session 5**: Layer Group 完了（2026-04-20、12 メソッド）
  - [x] pigeons/paint_api.dart に 12 メソッド追加・再生成（getLayersInGroup / getLayersInGroupRecursive は戻り値 List<int>）
  - [x] PaintFlutterActivity に Session 5 全 12 メソッド実装追加
  - [x] paint_channel.dart のフォルダ系を Pigeon 経由に切替（並走維持）
  - [x] List<Long> → List<Int> 変換 + 戻り値は Long → Int の逆変換も実装
  - [x] setGroupOpacity: 範囲外時 `FlutterError("INVALID_ARG")`
  - [x] Kotlin コンパイル成功（`./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL）
  - [ ] 実機動作確認（フォルダ作成/削除・レイヤーのグループ移動・ドラッグ並び替え・展開/折畳）
- [x] **Session 6**: Selection Tool + Selection Ops 完了（2026-04-20、19 メソッド）
  - [x] pigeons/paint_api.dart に 19 メソッド追加・再生成
  - [x] Pigeon enum `SelectionMode` を追加（Replace/Add/Subtract/Intersect）→ setSelectionMode の文字列排除
  - [x] PaintFlutterActivity に Session 6 全 19 メソッド実装追加（Pigeon→engine enum マッピング）
  - [x] paint_channel.dart の選択系を Pigeon 経由に切替（並走維持）
  - [x] selection_tool_panel.dart の呼び出し元を `SelectionMode.add`/`SelectionMode.subtract` に更新
  - [x] paint_channel.dart が `SelectionMode` を `export` 経由で公開
  - [x] Kotlin コンパイル成功（`./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL）
  - [x] Dart 静的解析成功（`dart analyze` No issues）
  - [ ] 実機動作確認（矩形/楕円/カラー/マグネット選択・選択ペン/消し・塗りつぶし/削除/コピー/切取/移動・反転/全選択/解除・ぼかし/拡張縮小・選択モード切替）
- [x] **Session 7**: Transform + Pixel Copy + Pixel Movement 完了（2026-04-20、17 メソッド + PixelCopyBounds データクラス化）
  - [x] pigeons/paint_api.dart に 17 メソッド + PixelCopyBounds data class 追加・再生成
  - [x] PaintFlutterActivity に Session 7 全 17 メソッド実装追加
  - [x] paint_channel.dart の Transform/Pixel Copy/Pixel Movement 系を Pigeon 経由に切替（並走維持）
  - [x] startPixelCopy 戻り値をデータクラス化（PixelCopyBounds）、呼び出し元互換のため paint_channel.dart 内で Map<String, int> に変換
  - [x] List<Long> → List<Int> 変換実装（layerIds / nodes / corners 系）
  - [x] distortLayer は Dart 側で `assert(corners.length >= 8)`、Kotlin 側は `FlutterError("INVALID_ARG")`
  - [x] applyPreviewTransform / applyMultiLayerSimpleTransform: 空 layerIds 時 `FlutterError("INVALID_ARG")`
  - [x] Kotlin コンパイル成功（`./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL）
  - [x] Dart 静的解析成功（`dart analyze lib/services/paint_channel.dart` No issues）
  - [ ] 実機動作確認（反転/回転・自由変形・ディストート/メッシュワープ・複数レイヤー一括変形・リクイファイ・ピクセルコピー・ピクセル移動）
- [ ] **Session 8**: Filter（15 メソッド）
- [ ] **Session 9**: Mask + Shape/Fill/Text + Export/Import（10 メソッド）
- [ ] **Session 10**: View + Tool Mode + 残余
- [ ] **Session 11**: State Stream（FlutterApi 化）
- [ ] **Final**: 旧 MethodChannel 撤去
