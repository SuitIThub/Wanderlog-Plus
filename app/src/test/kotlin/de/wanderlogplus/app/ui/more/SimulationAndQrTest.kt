package de.wanderlogplus.app.ui.more

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.app.data.SimulatedTime
import de.wanderlogplus.app.data.simulatedTimeOf
import de.wanderlogplus.app.sync.SyncStatus
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.setup.SetupLink
import de.wanderlogplus.app.ui.today.rememberMinuteNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class SimulationAndQrTest {
    @get:Rule val rule = createComposeRule()

    private val real = LocalDateTime.parse("2026-11-04T11:50")

    @Test fun simulatedTimes() {
        assertEquals(LocalDateTime.parse("2027-05-20T11:50"), SimulatedTime("2027-05-20").at(real)) // real time of day
        assertEquals(LocalDateTime.parse("2027-05-20T07:15"), SimulatedTime("2027-05-20", "07:15").at(real))
        assertEquals("2027-05-21", SimulatedTime("2027-05-20").shiftDays(1).date)
        assertEquals("2027-04-30", SimulatedTime("2027-05-01").shiftDays(-1).date)
        assertEquals("Do., 20.05.2027 · 09:00", SimulatedTime("2027-05-20", "09:00").label())
        assertNull(simulatedTimeOf("20.05.2027", ""))
        assertNull(simulatedTimeOf("2027-05-20", "9 Uhr"))
        assertEquals(SimulatedTime("2027-05-20", null), simulatedTimeOf(" 2027-05-20 ", " "))
    }

    @Test fun settingsSetAndClearTheSimulation() {
        var sim by mutableStateOf<SimulatedTime?>(null)
        rule.setContent {
            WanderlogPlusTheme {
                SettingsScreen(
                    SettingsInfo("http://192.168.178.76:8080", "t", "dev"), SyncStatus(), onChangeServer = {}, onSyncNow = {}, onResetSync = {},
                    debugMode = true, onDebugMode = {}, simulatedTime = sim, onSimulatedTime = { sim = it },
                )
            }
        }
        rule.onNodeWithTag("sim-date").performScrollTo().performTextInput("20.05.2027")
        rule.onNodeWithTag("sim-apply").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("sim-message").assertExists()
        assertNull(sim)
        rule.onNodeWithTag("sim-date").performTextClearance()
        rule.onNodeWithTag("sim-date").performTextInput("2027-05-20")
        rule.onNodeWithTag("sim-time").performTextInput("08:30")
        rule.onNodeWithTag("sim-apply").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(SimulatedTime("2027-05-20", "08:30"), sim)
        rule.onNodeWithText("Aktiv: Do., 20.05.2027 · 08:30").assertExists()
        rule.onNodeWithTag("sim-off").performSemanticsAction(SemanticsActions.OnClick)
        assertNull(sim)
    }

    @Test fun simulationIsNotOfferedOutsideDebugMode() {
        rule.setContent {
            WanderlogPlusTheme {
                SettingsScreen(SettingsInfo(null, null, "dev"), SyncStatus(), onChangeServer = {}, onSyncNow = {}, onResetSync = {}, debugMode = false, onDebugMode = {})
            }
        }
        rule.onNodeWithTag("sim-date").assertDoesNotExist()
    }

    @Test fun barMovesTheDayAndTheClockFollowsAtOnce() {
        var sim by mutableStateOf<SimulatedTime?>(SimulatedTime("2027-05-20", "09:00"))
        rule.setContent {
            WanderlogPlusTheme {
                val current = sim
                if (current != null) SimulationBar(current) { sim = it }
                val clock: () -> LocalDateTime = remember(sim) { { sim?.at(real) ?: real } }
                Text("jetzt " + rememberMinuteNow(clock).toLocalDate())
            }
        }
        rule.onNodeWithText("jetzt 2027-05-20").assertExists()
        rule.onNodeWithTag("sim-next").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("jetzt 2027-05-21").assertExists()
        rule.onNodeWithTag("sim-prev").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("sim-prev").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("jetzt 2027-05-19").assertExists()
        rule.onNodeWithText("Aus").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("jetzt 2026-11-04").assertExists()
    }

    @Test fun qrCodeDecodesToTheSetupLink() {
        val server = ServerSettings("http://192.168.178.76:8080", "a-secret-token-1234567890")
        val modules = qrModules(SetupLink.build(server), margin = 4)
        val scale = 4
        val n = modules.size * scale
        val pixels = IntArray(n * n) { i -> if (modules[(i / n) / scale][(i % n) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(n, n, pixels)))).text
        assertEquals(server, SetupLink.parse(decoded))
    }

    @Test fun settingsShowTheQrOfTheConnectionInUse() {
        rule.setContent {
            WanderlogPlusTheme {
                SettingsScreen(
                    SettingsInfo("https://home.example", "tok", "dev"), SyncStatus(), onChangeServer = {}, onSyncNow = {}, onResetSync = {},
                    server = ServerSettings("https://home.example", "home-token-123456"),
                )
            }
        }
        rule.onNodeWithTag("settings-show-qr").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("setup-qr").assertExists()
        rule.onNodeWithText("QR-Code für die Einrichtung").assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key, calc)
}
