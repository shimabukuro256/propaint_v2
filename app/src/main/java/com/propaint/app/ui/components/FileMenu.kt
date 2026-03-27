package com.propaint.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ファイルメニュー: インポート/エクスポート操作。
 * ファイルピッカーの起動は呼び出し側でコールバック経由で処理する。
 */
@Composable
fun FileMenu(
    onDismiss: () -> Unit,
    onImportImage: () -> Unit,
    onImportPsd: () -> Unit,
    onImportProject: () -> Unit,
    onExportPng: () -> Unit,
    onExportJpeg: () -> Unit,
    onExportWebp: () -> Unit,
    onExportPsd: () -> Unit,
    onExportProject: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.width(220.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE222222)),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("ファイル", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))

            // 保存
            TextButton(onClick = { onSave(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("プロジェクト保存", fontSize = 12.sp, color = Color.White,
                    modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(vertical = 4.dp))

            // インポート
            Text("インポート", color = Color(0xFF6CB4EE), fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp))

            TextButton(onClick = { onImportImage(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("画像 (PNG/JPEG/WebP)", fontSize = 12.sp, color = Color.White,
                    modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { onImportPsd(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("PSD (Photoshop)", fontSize = 12.sp, color = Color.White,
                    modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { onImportProject(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("プロジェクト (.ppaint)", fontSize = 12.sp, color = Color.White,
                    modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(vertical = 4.dp))

            // エクスポート
            Text("エクスポート", color = Color(0xFF6CB4EE), fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp))

            TextButton(onClick = { onExportPng(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("PNG (透過対応)", fontSize = 12.sp, color = Color.White,
                    modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { onExportJpeg(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("JPEG", fontSize = 12.sp, color = Color.White,
                    modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { onExportWebp(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("WebP", fontSize = 12.sp, color = Color.White,
                    modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { onExportPsd(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("PSD (Photoshop)", fontSize = 12.sp, color = Color.White,
                    modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { onExportProject(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("プロジェクト (.ppaint)", fontSize = 12.sp, color = Color.White,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
