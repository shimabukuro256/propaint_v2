# ProPaint v2 開発ガイド

## ビルド・実行方法

このプロジェクトは Kotlin + Flutter のハイブリッド構成です：
- **Kotlin**: Android 描画エンジン、OpenGL レンダラ、MethodChannel ハンドラ
- **Flutter**: UI レイヤー

### 方法1：Android Studio/Gradle で実行（推奨）

app module を直接ビルド・実行します：

```bash
# デバッグ APK をビルド
./gradlew :app:assembleDebug

# デバイスにインストール・実行
./gradlew :app:installDebug

# または、ワンコマンドで実行
./gradlew :app:installDebug
```

## トラブルシューティング

### MissingPluginException: MethodChannel ハンドラが見つからない

**原因**: propaint_flutter で `flutter run` を実行した場合

**解決策**: `./gradlew :app:installDebug` を使用して app module を直接ビルド・実行

### Kotlin コンパイルが実行されない

**確認**: Gradle ビルド時に以下のタスクが実行されているか確認

```
> Task :app:compileDebugKotlin
```

実行されない場合は、キャッシュをクリアしてビルドし直します：

```bash
./gradlew clean :app:assembleDebug
```

## プロジェクト構成

```
propaint_v2/
├── app/                          # Kotlin + Compose Android アプリケーション
│   ├── src/main/java/com/propaint/app/
│   │   ├── flutter/              # Flutter 統合 (PaintFlutterActivity, MethodChannelHandler)
│   │   ├── engine/               # 描画エンジン
│   │   ├── gl/                   # OpenGL レンダラ
│   │   ├── viewmodel/            # ViewModel
│   │   └── ...
│   └── build.gradle.kts
│
└── propaint_flutter/             # Flutter UI モジュール
    ├── lib/
    │   └── main.dart             # エントリーポイント
    ├── .android/                 # Flutter host APK（開発時のホスト）
    └── pubspec.yaml
```

## MethodChannel 通信

Flutter ↔ Kotlin 通信は `PaintChannel` (Dart) と `PaintMethodChannelHandler` (Kotlin) で実装されています：

- **Dart**: `propaint_flutter/lib/services/paint_channel.dart`
- **Kotlin**: `app/src/main/java/com/propaint/app/flutter/PaintMethodChannelHandler.kt`

チャネル名：
- MethodChannel: `com.propaint.app/paint`
- EventChannel: `com.propaint.app/state`
