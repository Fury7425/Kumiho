package kumiho

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kumiho.res.*
import org.jetbrains.compose.resources.Font

/** Tokens from the "Kumiho UI mockup" canvas: graphite neutrals, one selection fill, vermilion only on the seal and orb. */
class Tokens(
    val dark: Boolean,
    val bg: Color, val surface: Color, val raised: Color, val hover: Color, val segOn: Color, val aura: Color,
    val border: Color, val borderStrong: Color,
    val textStrong: Color, val text: Color, val muted: Color, val faint: Color,
    val brand: Color, val brandHover: Color, val onBrand: Color,
    val cta1: Color, val cta2: Color, val sheen: Color, val toast: Color, val onToast: Color,
    val mine: Color, val mineMeta: Color, val read: Color, val theirs: Color,
    val seal: Color, val orbA: Color, val orbB: Color, val orbC: Color,
    val danger: Color, val success: Color, val warning: Color,
)

private val White = Color.White
private val Ink = Color(0xFF15181B)

val Dark = Tokens(
    dark = true,
    bg = Color(0xFF0A0B0D), surface = Color(0xFF15181C), raised = Color(0xFF20252B), hover = Color(0xFF1F2329),
    segOn = Color(0xFF2A3038), aura = Color(0xFF10151B),
    border = White.copy(alpha = .07f), borderStrong = Color(0xFF30363D),
    textStrong = Color(0xFFF3F4F6), text = Color(0xFFDCDFE3), muted = Color(0xFF9DA5AE), faint = Color(0xFF8A939B),
    brand = Color(0xFFE9EBEE), brandHover = Color(0xFFF5F6F7), onBrand = Color(0xFF0A0B0D),
    cta1 = Color(0xFFF5F6F7), cta2 = Color(0xFFD6D8DB), sheen = White.copy(alpha = .045f),
    toast = Color(0xFF2A3038), onToast = Color(0xFFF3F4F6),
    mine = Color(0xFFE9EBEE), mineMeta = Color(0xFF0A0B0D).copy(alpha = .62f), read = Color(0xFF0A0B0D), theirs = Color(0xFF15181C),
    seal = Color(0xFFE05A45), orbA = Color(0xFFF0694F), orbB = Color(0xFF6E9BF2), orbC = Color(0xFFF3F4F6),
    danger = Color(0xFFFF7A6B), success = Color(0xFF5CD6A0), warning = Color(0xFFF5C451),
)

val Light = Tokens(
    dark = false,
    bg = Color(0xFFF2F3F5), surface = White, raised = Color(0xFFE9EBEE), hover = Color(0xFFE3E7EB),
    segOn = White, aura = Color(0xFFF2F3F5),
    border = Ink.copy(alpha = .08f), borderStrong = Color(0xFFC6CBD1),
    textStrong = Ink, text = Color(0xFF2A2F35), muted = Color(0xFF515A62), faint = Color(0xFF5F6870),
    brand = Ink, brandHover = Color(0xFF2A2F35), onBrand = White,
    cta1 = Color(0xFF2A2F35), cta2 = Ink, sheen = Color.Transparent,
    toast = Ink, onToast = White,
    mine = Ink, mineMeta = White.copy(alpha = .7f), read = White, theirs = White,
    seal = Color(0xFFB83A28), orbA = Color(0xFFC8412C), orbB = Color(0xFF2F5FC4), orbC = Ink,
    danger = Color(0xFFB3261E), success = Color(0xFF14684A), warning = Color(0xFF8A5A00),
)

val LocalT = staticCompositionLocalOf { Dark }
val T: Tokens
    @Composable @ReadOnlyComposable get() = LocalT.current

/** The OS asked for less motion: movement goes, fades stay, the orb shows one still frame. */
val LocalReduce = staticCompositionLocalOf { false }

class Fonts(val sans: FontFamily, val serif: FontFamily)

val LocalFonts = staticCompositionLocalOf { Fonts(FontFamily.Default, FontFamily.Serif) }

@Composable
fun loadFonts() = Fonts(
    FontFamily(
        Font(Res.font.pretendard_light, FontWeight.Light),
        Font(Res.font.pretendard_regular, FontWeight.Normal),
        Font(Res.font.pretendard_medium, FontWeight.Medium),
        Font(Res.font.pretendard_semibold, FontWeight.SemiBold),
    ),
    FontFamily(Font(Res.font.hahmlet, FontWeight.Normal)),
)

/** Swaps the generic families in [Ty] for the bundled fonts; monospace stays the system's. */
@Composable
@ReadOnlyComposable
fun TextStyle.themed(): TextStyle = when (fontFamily) {
    FontFamily.Monospace -> this
    FontFamily.Serif -> copy(fontFamily = LocalFonts.current.serif)
    else -> copy(fontFamily = LocalFonts.current.sans)
}

val EOut = CubicBezierEasing(.23f, 1f, .32f, 1f)
val EIo = CubicBezierEasing(.77f, 0f, .175f, 1f)
val EDrawer = CubicBezierEasing(.32f, .72f, 0f, 1f)

private fun ts(size: Int, line: Int, weight: Int = 400, spacing: Double = 0.0) = TextStyle(
    fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight),
    letterSpacing = spacing.sp, fontFeatureSettings = "tnum",
)

object Ty {
    val micro = ts(12, 16)
    val microMed = ts(12, 16, 500)
    val label = ts(13, 18)
    val labelMed = ts(13, 18, 500)
    val body = ts(14, 20)
    val bodyMed = ts(14, 20, 500)
    val name = ts(16, 24, 500)
    val h2 = ts(22, 28, 400, -.3)
    val h1 = ts(24, 32, 500, -.5)
    val num = ts(36, 44, 250, -.9)
    val mono = ts(12, 16).copy(fontFamily = FontFamily.Monospace)
    val kr = ts(12, 16).copy(fontFamily = FontFamily.Serif)
}

/** Stroke icons, 24-unit paths straight from the canvas. */
object IC {
    const val plus = "M12 5v14M5 12h14"
    const val search = "M17 11a6 6 0 1 1-12 0a6 6 0 1 1 12 0M21 21l-5.2-5.2"
    const val chat = "M21 12a8.5 8.5 0 0 1-12.4 7.5L3.5 21l1.5-4.6A8.5 8.5 0 1 1 21 12Z"
    const val gear = "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"
    const val back = "m15 18-6-6 6-6"
    const val chev = "m9 18 6-6-6-6"
    const val phone = "M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2Z"
    const val shield = "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"
    const val shieldcheck = "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10ZM9 12l2 2 4-4"
    const val check = "M20 6 9 17l-5-5"
    const val check2 = "M18 6 7 17l-5-5M22 10l-7.5 7.5L13 16"
    const val clock = "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0M12 7v5l3 2"
    const val inbox = "M22 12h-6l-2 3h-4l-2-3H2M5.5 5.1 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.8 4H7.2a2 2 0 0 0-1.7 1.1Z"
    const val eye = "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12ZM15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0"
    const val eyeoff = "M9.9 4.2A9 9 0 0 1 12 4c6.4 0 10 8 10 8a17 17 0 0 1-2.2 3.2M6.6 6.6A17 17 0 0 0 2 12s3.6 8 10 8a9.5 9.5 0 0 0 5.4-1.6M2 2l20 20M9.9 9.9a3 3 0 0 0 4.2 4.2"
    const val ring = "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0M17 12a5 5 0 1 1-10 0a5 5 0 1 1 10 0"
    const val camera = "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3ZM15 13a3 3 0 1 1-6 0a3 3 0 1 1 6 0"
    const val keyboard = "M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2ZM6 9h.01M10 9h.01M14 9h.01M18 9h.01M6 13h.01M18 13h.01M10 13h4M7 16h10"
    const val send = "M12 19V5M5 12l7-7 7 7"
    const val alert = "m10.3 3.9-8.5 14.7A2 2 0 0 0 3.5 21h17a2 2 0 0 0 1.7-2.4L13.7 3.9a2 2 0 0 0-3.4 0ZM12 9v4M12 17h.01"
    const val copy = "M11 9h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2ZM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"
    const val refresh = "M21 12a9 9 0 0 1-15.5 6.2L3 16M3 12A9 9 0 0 1 18.5 5.8L21 8M21 3v5h-5M3 21v-5h5"
    const val monitor = "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2ZM8 21h8M12 17v4"
    const val mobile = "M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2ZM11 18h2"
    const val lock = "M6 11h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2ZM8 11V7a4 4 0 0 1 8 0v4"
    const val key = "M12 15.5a4.5 4.5 0 1 1-9 0a4.5 4.5 0 1 1 9 0M10.7 12.3 20 3M17 6l3 3M14 9l2 2"
    const val info = "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0M12 16v-4M12 8h.01"
    const val x = "M18 6 6 18M6 6l12 12"
}
