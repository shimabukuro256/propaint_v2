package com.propaint.app.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onOpenProject: (id: String) -> Unit,
    onNewCanvas: (name: String, width: Int, height: Int) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { GalleryRepository(context) }
    var items by remember { mutableStateOf(repo.listProjects()) }
    var showNewDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<GalleryItem?>(null) }
    var renameTarget by remember { mutableStateOf<GalleryItem?>(null) }
    var renameText by remember { mutableStateOf("") }

    // サムネイルキャッシュ
    val thumbnails = remember { mutableStateMapOf<String, Bitmap?>() }

    fun refresh() { items = repo.listProjects() }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Column(Modifier.fillMaxSize()) {
            // ヘッダー
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp)
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("ギャラリー", color = Color.White, fontSize = 22.sp)
                IconButton(onClick = { showNewDialog = true }) {
                    Icon(Icons.Default.Add, "新規作成", tint = Color(0xFF6CB4EE),
                        modifier = Modifier.size(32.dp))
                }
            }

            if (items.isEmpty()) {
                // 空の状態
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("作品がありません", color = Color(0xFF888888), fontSize = 16.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { showNewDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A7C)),
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("新しいキャンバス")
                        }
                    }
                }
            } else {
                // サムネイルグリッド
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 180.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // + ボタン (先頭)
                    item {
                        Box(
                            modifier = Modifier.aspectRatio(0.75f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF2A2A2A))
                                .border(2.dp, Color(0xFF444444), RoundedCornerShape(12.dp))
                                .clickable { showNewDialog = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Add, null, tint = Color(0xFF6CB4EE),
                                    modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("新規作成", color = Color(0xFF888888), fontSize = 13.sp)
                            }
                        }
                    }

                    items(items, key = { it.id }) { item ->
                        val thumb = thumbnails.getOrPut(item.id) { repo.loadThumbnail(item.id) }

                        Card(
                            modifier = Modifier.aspectRatio(0.75f)
                                .combinedClickable(
                                    onClick = { onOpenProject(item.id) },
                                    onLongClick = { deleteTarget = item },
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                            elevation = CardDefaults.cardElevation(4.dp),
                        ) {
                            Column {
                                // サムネイル
                                Box(
                                    modifier = Modifier.fillMaxWidth().weight(1f)
                                        .background(Color(0xFF3A3A3A)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (thumb != null) {
                                        Image(
                                            bitmap = thumb.asImageBitmap(),
                                            contentDescription = item.name,
                                            modifier = Modifier.fillMaxSize().padding(4.dp),
                                            contentScale = ContentScale.Fit,
                                        )
                                    } else {
                                        Text("No Preview", color = Color(0xFF666666), fontSize = 11.sp)
                                    }
                                }
                                // 情報
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(item.name, color = Color.White, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${item.width} x ${item.height}", color = Color(0xFF888888),
                                        fontSize = 10.sp)
                                    Text(GalleryRepository.formatDate(item.modifiedAt),
                                        color = Color(0xFF666666), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 新規キャンバスダイアログ
    if (showNewDialog) {
        NewCanvasDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { name, w, h ->
                showNewDialog = false
                onNewCanvas(name, w, h)
            },
        )
    }

    // 削除確認ダイアログ
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Color(0xFF2A2A2A),
            title = { Text(target.name, color = Color.White) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                renameTarget = target; renameText = target.name; deleteTarget = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A7C)),
                        ) {
                            Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("名前変更", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                repo.deleteProject(target.id)
                                thumbnails.remove(target.id)
                                refresh(); deleteTarget = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC4444)),
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("削除", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("閉じる", color = Color(0xFFAAAAAA))
                }
            },
        )
    }

    // リネームダイアログ
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = Color(0xFF2A2A2A),
            title = { Text("名前を変更", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6CB4EE),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        repo.renameProject(target.id, renameText.trim())
                        refresh()
                    }
                    renameTarget = null
                }) { Text("変更", color = Color(0xFF6CB4EE)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("キャンセル", color = Color(0xFFAAAAAA))
                }
            },
        )
    }
}
