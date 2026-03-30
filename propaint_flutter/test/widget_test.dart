import 'package:flutter_test/flutter_test.dart';

import 'package:propaint_flutter/main.dart';

void main() {
  testWidgets('ProPaintApp builds without error', (WidgetTester tester) async {
    await tester.pumpWidget(const ProPaintApp());
    // PlatformView は test 環境では描画されないため、
    // アプリが例外なくビルドされることだけを確認
    expect(find.byType(ProPaintApp), findsOneWidget);
  });
}
