package kumiho

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Same tokens as mockup/index.html (dark theme, sRGB approximations of the OKLCH values).
private val KumihoDark = darkColorScheme(
    primary = Color(0xFFF2945A),
    onPrimary = Color(0xFF2A1408),
    background = Color(0xFF15141C),
    surface = Color(0xFF1B1A23),
    onBackground = Color(0xFFEEEDF3),
    onSurface = Color(0xFFEEEDF3),
)

@Composable
fun App() {
    MaterialTheme(colorScheme = KumihoDark) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Kumiho", style = MaterialTheme.typography.headlineMedium)
                Text("A messenger with no server you have to trust.")
                Button(onClick = { /* create_identity lands with the core FFI */ }) {
                    Text("Create my identity")
                }
            }
        }
    }
}
