package app.swarsetu.ui.components

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.swarsetu.R
import app.swarsetu.mesh.TransportHealth
import app.swarsetu.ui.theme.SwarSetuTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The connection header's two-plane copy rules. Assertions go through the row's merged content
 * description rather than its text, because the row deliberately collapses dot + label + glyph into a
 * single semantics node — so the description is both what TalkBack reads and the only place the glyph's
 * state is spelled out in words. Follows the Compose-on-Robolectric pattern in
 * `DiagnosticsScreenContentTest`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionStatusRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun show(
        neighborCount: Int,
        health: TransportHealth,
    ) {
        compose.setContent {
            SwarSetuTheme {
                ConnectionStatusRow(neighborCount = neighborCount, health = health)
            }
        }
    }

    @Test
    fun connectedNodesShowsCorrectCount() {
        show(3, TransportHealth.Healthy)

        val mesh = context.resources.getQuantityString(R.plurals.chat_connection_count, 3, 3)
        compose.onNodeWithContentDescription(mesh).assertIsDisplayed()
    }

    @Test
    fun zeroNodesHealthyShowsSearching() {
        show(0, TransportHealth.Healthy)

        compose.onNodeWithContentDescription(context.getString(R.string.chat_connection_none)).assertIsDisplayed()
    }

    @Test
    fun radiosUnavailableShowsRadioOff() {
        show(0, TransportHealth.Unavailable)

        compose.onNodeWithContentDescription(context.getString(R.string.chat_connection_radio_off)).assertIsDisplayed()
    }
}
