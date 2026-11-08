package kumiho

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.time.LocalTime

actual fun clock(): String = LocalTime.now().run { "%02d:%02d".format(hour, minute) }

actual val blurSupported: Boolean = true

@Composable
actual fun SystemBack(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
private fun thinScrollbar() = ScrollbarStyle(
    minimalHeight = 16.dp,
    thickness = 6.dp,
    shape = RoundedCornerShape(3.dp),
    hoverDurationMillis = 150,
    unhoverColor = T.borderStrong,
    hoverColor = T.muted,
)

@Composable
actual fun VScroll(state: ScrollState, modifier: Modifier) =
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = thinScrollbar())

@Composable
actual fun VScroll(state: LazyListState, modifier: Modifier) =
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = thinScrollbar())

@Suppress("FunctionName")
internal interface User32 : Library {
    fun SetWindowDisplayAffinity(hWnd: Pointer, affinity: Int): Boolean
    fun SystemParametersInfoW(action: Int, param: Int, out: IntByReference, winIni: Int): Boolean
}

private const val WDA_EXCLUDEFROMCAPTURE = 0x11
private const val SPI_GETCLIENTAREAANIMATION = 0x1042

private val isWindows = System.getProperty("os.name").startsWith("Windows")
private val user32 by lazy { Native.load("user32", User32::class.java) }

// ponytail: Windows only; macOS sharingType and Linux have no equivalent yet, so Settings says "Not blocked" there.
private fun blockCapture(hwnd: Long): Boolean = runCatching {
    isWindows && user32.SetWindowDisplayAffinity(Pointer(hwnd), WDA_EXCLUDEFROMCAPTURE)
}.getOrDefault(false)

// ponytail: read once at launch, Windows only. macOS and Linux have no single setting to read from the JVM.
private fun systemReducesMotion(): Boolean = runCatching {
    val on = IntByReference(1)
    isWindows && user32.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, on, 0) && on.value == 0
}.getOrDefault(false)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kumiho (demo: no real encryption or network yet)",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        var blocked by remember { mutableStateOf(false) }
        val reduceMotion = remember { systemReducesMotion() }
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(360, 620)
            while (!window.isDisplayable) delay(50)
            blocked = blockCapture(window.windowHandle)
        }
        App(captureBlocked = blocked, reduceMotion = reduceMotion)
    }
}
