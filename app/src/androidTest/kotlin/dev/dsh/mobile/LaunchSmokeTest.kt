package dev.dsh.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesToAUsableScreen() {
        compose.waitForIdle()
        val connectScreen = compose.onAllNodesWithText("Scan QR code").fetchSemanticsNodes().isNotEmpty()
        val browser = compose.onAllNodesWithContentDescription("Menu").fetchSemanticsNodes().isNotEmpty()
        assertTrue("expected the connect screen or the browser shell", connectScreen || browser)
    }
}
