import 'paint_api.g.dart';

/// Session 11: Kotlin → Flutter 通知を受け取る Pigeon 実装。
///
/// A 案（並走）のため、Kotlin 側は現状 `onStateChanged` を送っていない。
/// Final セッションで EventChannel を廃止したら `onStateChanged` ハンドリングを有効化する。
/// 現時点は `onNativeGesture` / `onErrorMessage` も MethodChannel 側と並走し、
/// 重複通知にならないよう Kotlin 送信は MethodChannel のみを使用。
class PaintFlutterApiImpl implements PaintFlutterApi {
  PaintFlutterApiImpl({
    this.stateChangedHandler,
    this.nativeGestureHandler,
    this.errorMessageHandler,
  });

  /// 全状態スナップショット受信コールバック（Final セッション以降）。
  final void Function(PaintState state)? stateChangedHandler;

  /// ネイティブジェスチャ通知コールバック。
  final void Function(String gesture)? nativeGestureHandler;

  /// ネイティブエラー通知コールバック。
  final void Function(String message)? errorMessageHandler;

  @override
  void onStateChanged(PaintState state) {
    stateChangedHandler?.call(state);
  }

  @override
  void onNativeGesture(String gesture) {
    nativeGestureHandler?.call(gesture);
  }

  @override
  void onErrorMessage(String message) {
    errorMessageHandler?.call(message);
  }
}
