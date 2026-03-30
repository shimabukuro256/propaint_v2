package com.propaint.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propaint.app.gallery.NewCanvasDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NewCanvasDialog の Compose UI テスト (Instrumented)。
 * エミュレータ / 実機で実行。
 */
@RunWith(AndroidJUnit4::class)
class NewCanvasDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dialog_showsTitle() {
        composeTestRule.setContent {
            NewCanvasDialog(
                onDismiss = {},
                onCreate = { _, _, _ -> },
            )
        }
        composeTestRule.onNodeWithText("新しいキャンバス").assertIsDisplayed()
    }

    @Test
    fun dialog_showsPresets() {
        composeTestRule.setContent {
            NewCanvasDialog(
                onDismiss = {},
                onCreate = { _, _, _ -> },
            )
        }
        // プリセットの一部が表示されること
        composeTestRule.onNodeWithText("HD (1920x1080)", substring = true).assertExists()
    }

    @Test
    fun dialog_hasCreateButton() {
        composeTestRule.setContent {
            NewCanvasDialog(
                onDismiss = {},
                onCreate = { _, _, _ -> },
            )
        }
        composeTestRule.onNodeWithText("作成").assertExists()
    }

    @Test
    fun dialog_createCallbackFires() {
        var createdName = ""
        var createdWidth = 0
        var createdHeight = 0

        composeTestRule.setContent {
            NewCanvasDialog(
                onDismiss = {},
                onCreate = { name, w, h ->
                    createdName = name
                    createdWidth = w
                    createdHeight = h
                },
            )
        }

        // プリセットを選択
        composeTestRule.onNodeWithText("HD (1920x1080)", substring = true).performClick()
        composeTestRule.waitForIdle()

        // 作成ボタンを押す
        composeTestRule.onNodeWithText("作成").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1920, createdWidth)
        assertEquals(1080, createdHeight)
    }
}
