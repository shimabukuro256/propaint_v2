# 旧バージョンのUIコンポーネントの削除に関するコードレビュー

現在のアプリのアーキテクチャでは、キャンバス描画の内部処理をJetpack Compose（`CanvasGlRenderer`など）で、UI（ツールパネルやメニューなど）をFlutterで担当しているとのことですね。

指定されたディレクトリ `C:\Users\quinella\Desktop\propaint_v2\app\src\main\java\com\propaint\app\ui` の中身とプロジェクト全体の使用状況を調査した結果、削除しても全く問題のないファイルと、引き続き残すか対応が必要なファイルが明確になりました。

## 削除の影響と結論

### 1. 🗑️ 完全に削除して問題ないディレクトリ・ファイル
以下のディレクトリにあるファイル群は、過去のCompose版ペイント画面（`PaintScreen.kt`）のために作られたUIコンポーネントであり、現在はFlutter側で実装されているUI機能（ツールパネルやサイドバーなど）に相当します。他の場所からの参照もないため、**完全に削除して問題ありません**。

*   **`com.propaint.app.ui.components` 配下のすべてのファイル**
    *   `BrushPanel.kt`
    *   `ColorPickerPanel.kt`
    *   `FileMenu.kt`
    *   `FilterPanel.kt`
    *   `LayerPanel.kt`
    *   `SideQuickBar.kt`
    *   `Toolbar.kt`

*   **`com.propaint.app.ui.screens` 配下のファイル**
    *   `PaintScreen.kt`
        * *(※ `MainActivity.kt` で `useFlutterUi = false` の場合の分岐としてのみ利用されているため、Flutter UIへ完全に移行した状態であれば削除可能です)*

### 2. ⚠️ 削除に注意が必要（残すべき）ディレクトリ・ファイル
以下のディレクトリには、現状のアプリ全体（特にキャンバス描画までの前段階であるギャラリー画面など）で依存しているベーステーマが含まれています。

*   **`com.propaint.app.ui.theme` 配下**
    *   `Theme.kt`
        * **削除NG:** `MainActivity.kt` で起動時のルートUI構成として `ProPaintTheme` が呼び出されています。また、このテーマは現在の起動画面である `GalleryScreen`（Jetpack Composeでの実装）のスタイル基盤として利用されているため、アプリ起動時にクラッシュや表示崩れを起こす原因になります。ギャラリー画面も完全にFlutterにするまでは残しておく必要があります。

---

## 🧹 クリーンアップのために必要な追加作業

上記のファイルを削除する場合、それらを呼び出している `MainActivity.kt` 内の不要なコードも整理（削除）する必要があります。

**対象ファイル:** `MainActivity.kt`
1.  **古いインポートの削除:**
    *   `import com.propaint.app.ui.screens.PaintScreen`
2.  **不要になったフラグと分岐の削除:**
    *   `private val useFlutterUi = true` の削除
    *   `Screen.Paint` 状態の削除 (`private enum class Screen { Gallery, Paint }` → `Paint` の除去)
    *   `when(screen)` 構文における `useFlutterUi == false` の場合の分岐、および `Screen.Paint -> ...` のブロック全体の削除

## まとめ

*   **`ui/components` と `ui/screens` のフォルダごと削除してOK** です（FlutterUIへ置き換わっているため）。
*   **`ui/theme` は残してください**（`GalleryScreen` などのルートでCompose用テーマを使っているため）。
*   削除後、忘れず `MainActivity.kt` 内に残される後処理のコード（FlutterかComposeかを出し分ける部分）を削除すれば、アプリは正常にビルドでき容量も軽量化されます。
