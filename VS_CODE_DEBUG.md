# VS Code での実行・デバッグ

## 🚀 クイックスタート

### 1. ビルド＆実行（キャッシュクリア）
**メインの実行方法** - 全リソース再ビルドします

VS Code で：
- **F5** キーを押す
- または **Run → Start Debugging**
- 「🚀 ProPaint - Build & Run Debug」を選択

このコマンドが実行されます：
```bash
./gradlew clean :app:installDebug
```

### 2. 高速ビルド＆実行（キャッシュ使用）
前回ビルドから変更がほぼないとき

VS Code で：
- **Ctrl+F5** (または Run → Run Without Debugging)
- 「⚡ ProPaint - Build Debug (キャッシュ使用)」を選択

このコマンドが実行されます：
```bash
./gradlew :app:installDebug
```

所要時間：
- 初回: ~1分30秒
- 2回目以降: ~30秒（キャッシュ使用時）

---

## 📋 利用可能な実行設定

### Debug ビルド

| 設定 | 用途 | 実行時間 |
|------|------|--------|
| 🚀 **Build & Run** | 全リソース再構築 | 長め |
| ⚡ **Build (キャッシュ)** | インクリメンタルビルド | 短め |

### Release ビルド

| 設定 | 用途 |
|------|------|
| 📦 **Build Release** | リリース用APK生成 |

### Flutter UI デバッグ

| 設定 | 用途 |
|------|------|
| Flutter UI Debug | UI の Hot Reload |
| Flutter UI Profile | パフォーマンス計測 |
| Flutter UI Release | リリース用 |

---

## 🛠️ トラブルシューティング

### エラー: `Gradle not found`

**解決**: gradlew に実行権限を付与
```bash
chmod +x gradlew
```

### エラー: `Could not resolve dependency`

**解決**: Gradle キャッシュをクリア
```bash
./gradlew clean
```

### デバイス認識されない

**確認**:
```bash
adb devices
```

デバイスが表示されない場合：
1. USB ケーブルを再挿し
2. デバイスの USB デバッグを有効化
3. `adb kill-server && adb start-server`

---

## 💡 Tips

### キーボードショートカット

| キー | 動作 |
|------|------|
| F5 | Debug ビルド（キャッシュクリア） |
| Ctrl+F5 | Run Without Debugging（キャッシュ使用） |
| Ctrl+Shift+D | Debug パネルを開く |

### ビルド時間の短縮

1. **キャッシュを活用**: `⚡ Build (キャッシュ使用)` を使用
2. **Kotlin コンパイルのスキップ**: コード変更なし時は自動スキップ
3. **増分ビルド**: Flutter コード変更のみなら Flutter UI Debug を使用

---

## 📚 参考

- [DEVELOPMENT.md](DEVELOPMENT.md) - ハイブリッドビルド・実行ガイド
- [Gradle ドキュメント](https://gradle.org/)
- [Flutter ドキュメント](https://flutter.dev/docs)
