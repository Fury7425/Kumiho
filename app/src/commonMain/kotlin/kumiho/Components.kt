package kumiho

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeMark

@Composable
fun Txt(
    text: String,
    style: TextStyle = Ty.body,
    color: Color = T.text,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Unspecified,
) = BasicText(text, modifier, style.copy(color = color, textAlign = align), overflow = TextOverflow.Ellipsis, maxLines = maxLines)

private val paths = HashMap<String, Path>()
private fun parse(d: String) = paths.getOrPut(d) { PathParser().parsePathString(d).toPath() }

@Composable
fun Ic(d: String, modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = T.muted, weight: Float = 1.75f) {
    val path = parse(d)
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        scale(s, s, Offset.Zero) { drawPath(path, tint, style = Stroke(weight, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/** Hollow Fast, half Private, full Untraceable. */
@Composable
fun Mark(mode: PrivacyMode, modifier: Modifier = Modifier, size: Dp = 12.dp, tint: Color = T.muted) {
    val fill = mode.mark.takeIf { it.isNotEmpty() }?.let(::parse)
    Canvas(modifier.size(size)) {
        val s = this.size.width / 12f
        scale(s, s, Offset.Zero) {
            drawCircle(tint, 4.5f, Offset(6f, 6f), style = Stroke(1.5f))
            fill?.let { drawPath(it, tint) }
        }
    }
}

/** Every pressable in the app: press scale, hover fill, focus ring. `hit` grows the target without growing the fill. */
@Composable
fun Tap(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    scale: Float = .97f,
    bg: Color = Color.Transparent,
    brush: Brush? = null,
    hover: Color = T.hover,
    disabledAlpha: Float = .45f,
    hit: Dp = 0.dp,
    label: String? = null,
    role: Role = Role.Button,
    src: MutableInteractionSource = remember { MutableInteractionSource() },
    padding: PaddingValues = PaddingValues(0.dp),
    arrangement: Arrangement.Horizontal = Arrangement.Center,
    align: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    val pressed by src.collectIsPressedAsState()
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    val s by animateFloatAsState(if (pressed && enabled) scale else 1f, tween(if (pressed) 90 else 160, easing = EOut))
    val fill by animateColorAsState(if ((pressed || hovered) && enabled) hover else bg, tween(150))
    val ring = T.textStrong
    Row(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s; alpha = if (enabled) 1f else disabledAlpha }
            .hoverable(src)
            .clickable(src, null, enabled, role = role, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(vertical = hit)
            .clip(shape)
            .then(if (brush != null && !(hovered && enabled)) Modifier.background(brush) else Modifier.background(fill))
            .then(if (focused) Modifier.border(2.dp, ring, shape) else Modifier)
            .padding(padding),
        horizontalArrangement = arrangement,
        verticalAlignment = align,
        content = content,
    )
}

enum class Kind { Normal, Primary, Ghost, Danger }

@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: Kind = Kind.Normal,
    small: Boolean = false,
    wide: Boolean = false,
    enabled: Boolean = true,
    icon: String? = null,
    tint: Color? = null,
) {
    val fg = tint ?: when (kind) {
        Kind.Primary -> T.onBrand
        Kind.Ghost -> T.muted
        Kind.Danger -> T.danger
        Kind.Normal -> T.textStrong
    }
    val primary = kind == Kind.Primary
    Tap(
        onClick,
        modifier.height(if (wide) 56.dp else 48.dp).then(if (wide) Modifier.fillMaxWidth() else Modifier),
        enabled = enabled,
        shape = CircleShape,
        bg = if (kind == Kind.Normal) T.raised else Color.Transparent,
        brush = if (primary) Brush.verticalGradient(listOf(T.cta1, T.cta2)) else null,
        hover = if (primary) T.brandHover else T.hover,
        hit = if (small) 6.dp else 0.dp,
        padding = PaddingValues(horizontal = if (small) 14.dp else 22.dp),
    ) {
        if (icon != null) { Ic(icon, size = 16.dp, tint = fg); Spacer(Modifier.width(8.dp)) }
        Txt(text, if (small) Ty.labelMed else Ty.bodyMed, fg, maxLines = 1)
    }
}

private class TipPosition(val above: Boolean, val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        if (above) IntOffset(anchorBounds.left, anchorBounds.top - popupContentSize.height - gap)
        else IntOffset((anchorBounds.right - popupContentSize.width).coerceAtLeast(0), anchorBounds.bottom + gap)
}

@Composable
fun IconBtn(
    d: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tip: String? = null,
    tipAbove: Boolean = false,
    on: Boolean = false,
    enabled: Boolean = true,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    var showTip by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        showTip = false
        if (hovered) { delay(400); showTip = true }
    }
    Box(modifier) {
        Tap(
            onClick, Modifier.size(48.dp), enabled = enabled, shape = CircleShape, scale = .94f,
            bg = if (on) T.brand else Color.Transparent, hover = if (on) T.brand else T.hover,
            disabledAlpha = .35f, label = label, src = src,
        ) { Ic(d, tint = if (on) T.onBrand else T.muted) }
        if (tip != null && showTip) {
            val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.roundToPx() }
            Popup(popupPositionProvider = TipPosition(tipAbove, gap)) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(T.brand).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Txt(tip, Ty.microMed, T.onBrand, maxLines = 1)
                }
            }
        }
    }
}

/** One thumb that slides to the choice, 240 ms. */
@Composable
fun Seg(options: List<String>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier, full: Boolean = false) {
    val x by animateFloatAsState(selected.toFloat(), tween(240, easing = EIo))
    val inner = RoundedCornerShape(8.dp)
    Box(modifier.then(if (full) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max)).clip(RoundedCornerShape(12.dp)).background(T.hover).padding(4.dp)) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(1f / options.size)
                    .graphicsLayer { translationX = x * size.width }
                    .clip(inner).background(T.segOn).border(1.dp, T.border, inner),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                val on = i == selected
                val color by animateColorAsState(if (on) T.textStrong else T.muted, tween(240))
                Tap(
                    { onPick(i) }, Modifier.weight(1f).height(32.dp), shape = inner, hover = Color.Transparent,
                    role = Role.Tab, padding = PaddingValues(horizontal = 12.dp),
                ) { Txt(option, if (on) Ty.labelMed else Ty.label, color, maxLines = 1) }
            }
        }
    }
}

@Composable
fun SwitchMark(on: Boolean) {
    val x by animateDpAsState(if (on) 18.dp else 0.dp, tween(160, easing = EOut))
    val track by animateColorAsState(if (on) T.brand else T.borderStrong, tween(160))
    val knob by animateColorAsState(if (on) T.onBrand else T.muted, tween(160))
    Box(Modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(track).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(20.dp).clip(CircleShape).background(knob))
    }
}

@Composable
fun Modifier.cardBg(shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    clip(shape).background(T.surface)
        .background(Brush.verticalGradient(0f to T.sheen, .64f to Color.Transparent))
        .border(1.dp, T.border, shape)

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().cardBg(), content = content)

@Composable
fun Hair() = Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(T.border))

@Composable
private fun RowScope.LMain(title: String, sub: String?, titleColor: Color = T.textStrong) {
    Column(Modifier.weight(1f)) {
        Txt(title, Ty.body, titleColor)
        if (sub != null) Txt(sub, Ty.micro, T.muted)
    }
}

@Composable
fun LRow(
    title: String,
    sub: String? = null,
    icon: String? = null,
    value: String? = null,
    mono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        if (icon != null) Ic(icon)
        LMain(title, sub)
        if (value != null) Txt(value, if (mono) Ty.mono else Ty.body, T.muted, maxLines = 1)
        trailing?.invoke()
    }
}

@Composable
fun LRowBtn(title: String, onClick: () -> Unit, icon: String? = null, value: String? = null, chevron: Boolean = false, accent: Boolean = false) {
    Tap(
        onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RectangleShape, scale = 1f,
        padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), arrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Ic(icon, tint = if (accent) T.textStrong else T.muted)
        LMain(title, null, T.textStrong)
        if (value != null) Txt(value, Ty.body, T.muted, maxLines = 1)
        if (chevron) Ic(IC.chev, size = 16.dp)
    }
}

@Composable
fun SwitchRow(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .toggleable(on, remember { MutableInteractionSource() }, null, role = Role.Switch, onValueChange = onChange)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        LMain(title, sub)
        SwitchMark(on)
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String = placeholder,
    invalid: Boolean = false,
    mono: Boolean = false,
    password: Boolean = false,
    height: Dp = 48.dp,
    leading: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val edge = if (invalid) T.danger else if (focused) T.textStrong else Color.Transparent
    val style = (if (mono) Ty.body.copy(fontFamily = Ty.mono.fontFamily) else Ty.body).copy(color = T.textStrong)
    BasicTextField(
        value, onChange,
        modifier.fillMaxWidth().height(height).clip(shape).background(T.hover).border(1.dp, edge, shape).semantics { contentDescription = label },
        textStyle = style,
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        interactionSource = src,
        cursorBrush = SolidColor(T.textStrong),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(start = if (leading != null) 12.dp else 14.dp, end = if (trailing != null) 0.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) { Ic(leading, size = 18.dp, tint = T.faint); Spacer(Modifier.width(8.dp)) }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, Ty.body, T.faint, maxLines = 1)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

@Composable
fun Dojang() = Box(
    Modifier.rotate(-4f).size(14.dp).border(1.25.dp, T.seal, RoundedCornerShape(3.dp)),
    Alignment.Center,
) { Txt("인", Ty.kr.copy(fontSize = 9.sp, lineHeight = 9.sp), T.seal) }

@Composable
fun MetaLine(c: Contact) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Mark(c.inUse)
    Txt(c.inUse.label, Ty.micro, T.muted, maxLines = 1)
    Txt("·", Ty.micro, T.faint)
    if (c.verified) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Dojang()
            Txt("Verified", Ty.micro, T.textStrong, maxLines = 1)
        }
    } else Txt("Not verified", Ty.micro, T.muted, maxLines = 1)
}

@Composable
fun Avatar(name: String, size: Dp = 48.dp, style: TextStyle = Ty.name) = Box(
    Modifier.size(size).clip(CircleShape).background(T.raised), Alignment.Center,
) { Txt(name.take(1), style, T.textStrong) }

fun Modifier.fadeEdges() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val f = (16.dp.toPx() / size.height).coerceAtMost(.5f)
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, f to Color.Black, (1 - f) to Color.Black, 1f to Color.Transparent),
        blendMode = BlendMode.DstIn,
    )
}

/** Fade and rise on first composition for this key. */
@Composable
fun Modifier.enter(key: Any?, dy: Dp = 4.dp, ms: Int = 200, delayMs: Int = 0): Modifier {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(ms, delayMs, EOut)) }
    return graphicsLayer { alpha = a.value; translationY = (1 - a.value) * dy.toPx() }
}

/** Phone screen push, slides in from the right. */
@Composable
fun Modifier.pushIn(key: Any?): Modifier {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(320, easing = EDrawer)) }
    return graphicsLayer { translationX = (1 - a.value) * size.width }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().fadeEdges().verticalScroll(rememberScrollState()), Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            content = content,
        )
    }
}

@Composable
fun Section(title: String? = null, gap: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap)) {
        if (title != null) Txt(title, Ty.labelMed, T.muted, Modifier.padding(horizontal = 4.dp))
        content()
    }
}

@Composable
fun Help(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Unspecified) =
    Txt(text, Ty.micro, T.muted, modifier.padding(horizontal = 4.dp), align = align)

@Composable
fun ErrLine(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Ic(IC.alert, size = 16.dp, tint = T.danger)
    Txt(text, Ty.micro, T.danger)
}

@Composable
fun Callout(icon: String, text: String) = Row(Modifier.padding(horizontal = 4.dp), Arrangement.spacedBy(8.dp)) {
    Ic(icon, Modifier.padding(top = 1.dp), size = 16.dp)
    Txt(text, Ty.micro, T.muted)
}

@Composable
fun Details(summary: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Tap({ open = !open }, shape = RoundedCornerShape(6.dp), hover = Color.Transparent, arrangement = Arrangement.spacedBy(4.dp)) {
            Ic(IC.chev, Modifier.rotate(if (open) 90f else 0f), size = 14.dp)
            Txt(summary, Ty.microMed, T.muted)
        }
        if (open) Column(Modifier.padding(top = 12.dp), Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun Spinner() {
    val angle by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart))
    val c = T.textStrong
    Canvas(Modifier.size(16.dp)) {
        val w = 2.dp.toPx()
        drawArc(c, angle, 270f, false, topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w))
    }
}

@Composable
fun CheckDisc() = Box(Modifier.size(64.dp).clip(CircleShape).background(T.brand), Alignment.Center) {
    Ic(IC.check, size = 28.dp, tint = T.onBrand, weight = 2.2f)
}

/** Whole seconds until the mark, updated while it counts down. */
@Composable
fun secondsLeft(deadline: TimeMark): Int {
    fun now() = max(0L, ((-deadline.elapsedNow()).inWholeMilliseconds + 999) / 1000).toInt()
    var s by remember(deadline) { mutableStateOf(now()) }
    LaunchedEffect(deadline) {
        while (s > 0) { delay(250); s = now() }
    }
    return s
}

fun fmtSeconds(s: Int) = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"

// ---------- The Glyph orb ----------

private class OrbPoint(val x: Float, val y: Float, val z: Float, val s: Float, val ph: Float, val c: Int, val bit: Int)

private const val ORB_POINTS = 1400
private const val ORB_BIT = .03f

private fun orbModel(seed: Int): List<OrbPoint> {
    val r = rng(seed)
    val golden = PI * (3 - sqrt(5.0))
    return List(ORB_POINTS) { i ->
        val y0 = 1 - (i.toDouble() / (ORB_POINTS - 1)) * 2
        val ring = sqrt(1 - y0 * y0)
        val th = golden * i
        val tone = r()
        // scatter each point off the Fibonacci lattice so no rows or spirals show
        val x = cos(th) * ring + (r() - .5) * .09
        val y = y0 + (r() - .5) * .09
        val z = sin(th) * ring + (r() - .5) * .09
        val j = (1 + (r() - .5) * .08) / sqrt(x * x + y * y + z * z)
        OrbPoint(
            (x * j).toFloat(), (y * j).toFloat(), (z * j).toFloat(),
            (.6 + r() * .9).toFloat(), (r() * PI * 2).toFloat(),
            if (tone < .55) 0 else if (tone < .7) 1 else 2,
            if (r() > .5) 1 else -1,
        )
    }
}

// ponytail: bits are random like the canvas; the real payload comes from core's glyph module at build-order step 3.
@Composable
fun Orb(seed: Int, size: Dp, modifier: Modifier = Modifier, paused: Boolean = false, label: String? = null) {
    val parts = remember(seed) { orbModel(seed) }
    var time by remember { mutableLongStateOf(0L) }
    if (!paused) LaunchedEffect(Unit) { while (true) withFrameMillis { time = it } }
    val colors = listOf(T.orbA, T.orbB, T.orbC)
    val dark = T.dark
    Canvas(modifier.size(size).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)) {
        val w = this.size.width
        val c = w / 2
        val r = w * .34f
        val t = time.toDouble()
        drawRect(
            Brush.radialGradient(0f to colors[0], 1f to colors[0].copy(alpha = 0f), center = Offset(c, c), radius = r * 1.3f),
            alpha = if (dark) .18f else .08f,
        )
        val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
        val ay = t * .00022
        val ax = .4 + sin(t * .00017) * .18
        val sy = sin(ay).toFloat()
        val cy = cos(ay).toFloat()
        val sx = sin(ax).toFloat()
        val cx = cos(ax).toFloat()
        for (p in parts) {
            val k = (1 + .045 * sin(t * .0011 + p.y * 3.2 + p.x * 1.4)).toFloat()
            val x = p.x * k
            val y = p.y * k
            val z = p.z * k
            val x1 = x * cy - z * sy
            val z1 = x * sy + z * cy
            val y1 = y * cx - z1 * sx
            val z2 = y * sx + z1 * cx
            val d = (z2 + 1) / 2
            val sc = 1 + z2 * .22f
            val tw = (.85 + .15 * sin(t * .002 + p.ph)).toFloat()
            val a = max(0f, min(1f, (.06f + .86f * d * d) * tw * (1 + p.bit * ORB_BIT)))
            drawCircle(colors[p.c], w * .0032f * p.s * (.55f + d * .7f), Offset(c + x1 * r * sc, c + y1 * r * sc), alpha = a, blendMode = blend)
        }
    }
}

/** Blur that lifts with an animated radius, used by Peek and the expired Glyph. */
@Composable
fun Modifier.softBlur(radius: Dp, ms: Int = 150): Modifier {
    val r by animateDpAsState(radius, tween(ms))
    return blur(r, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
}
