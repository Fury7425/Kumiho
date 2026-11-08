package kumiho

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.time.LocalTime

actual fun clock(): String = LocalTime.now().run { "%02d:%02d".format(hour, minute) }

@Suppress("FunctionName")
internal interface User32 : Library {
    fun SetWindowDisplayAffinity(hWnd: Pointer, affinity: Int): Boolean
}

private const val WDA_EXCLUDEFROMCAPTURE = 0x11

// ponytail: Windows only; macOS sharingType and Linux have no equivalent yet, so Settings says "Not blocked" there.
private fun blockCapture(hwnd: Long): Boolean = runCatching {
    System.getProperty("os.name").startsWith("Windows") &&
        Native.load("user32", User32::class.java).SetWindowDisplayAffinity(Pointer(hwnd), WDA_EXCLUDEFROMCAPTURE)
}.getOrDefault(false)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kumiho (demo: no real encryption or network yet)",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        var blocked by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(360, 620)
            while (!window.isDisplayable) delay(50)
            blocked = blockCapture(window.windowHandle)
        }
        App(captureBlocked = blocked)
    }
}
