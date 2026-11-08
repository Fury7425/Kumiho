package kumiho

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
private fun Head(title: String, compact: Boolean, backLabel: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth(), Alignment.TopCenter) {
        Row(
            Modifier.widthIn(max = if (compact) Dp.Unspecified else 560.dp).fillMaxWidth().height(64.dp)
                .padding(start = if (compact) 4.dp else 24.dp, end = if (compact) 8.dp else 24.dp),
            Arrangement.spacedBy(4.dp), Alignment.CenterVertically,
        ) {
            if (compact) IconBtn(IC.back, backLabel, onBack)
            Txt(title, Ty.name, T.textStrong, maxLines = 1)
        }
    }
}

// ---------- Add contact ----------

@Composable
fun AddContact(s: AppState, compact: Boolean) {
    Head(if (s.linking) "Link a device" else "Add contact", compact, "Back to chats") { s.back() }
    Page {
        Seg(listOf("Glyph", "Scan", "Code"), s.tab, { s.tab = it; s.scanning = false }, full = true)
        when (s.tab) {
            0 -> GlyphTab(s)
            1 -> ScanTab(s)
            else -> CodeTab(s)
        }
    }
}

@Composable
private fun GlyphTab(s: AppState) {
    val left = secondsLeft(s.glyphDeadline)
    val expired = left <= 0
    val alpha by animateFloatAsState(if (expired) .15f else 1f, tween(300))
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.spacedBy(16.dp), Alignment.CenterHorizontally) {
        Orb(
            s.glyphSeed, 280.dp,
            Modifier.graphicsLayer { this.alpha = alpha }.softBlur(if (expired) 2.dp else 0.dp, 300),
            paused = expired, label = "Your one-time Glyph. Ask the other person to scan it.",
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(if (expired) "Expired" else fmtSeconds(left), Ty.num, T.textStrong)
            if (!expired) Txt("left · works once", Ty.micro, T.muted)
        }
        val help = when {
            expired -> "This Glyph expired. Old screenshots of it are useless now."
            s.linking -> "Scan this with your other phone or computer."
            else -> "Ask them to open Kumiho and scan this. No number or address is inside."
        }
        Txt(help, Ty.body, T.muted, Modifier.widthIn(max = 280.dp), align = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("New Glyph", { s.freshGlyph() }, kind = if (expired) Kind.Primary else Kind.Normal, icon = IC.refresh)
            Btn("Use a code instead", { s.tab = 2 }, kind = Kind.Ghost)
        }
    }
}

@Composable
private fun ScanTab(s: AppState) {
    val orbScale by animateFloatAsState(if (s.scanning && !LocalReduce.current) .6f else 1f, tween(600, easing = EIo))
    val orbAlpha by animateFloatAsState(if (s.scanning) 0f else .72f, tween(500))
    val lock by animateFloatAsState(if (s.scanning) 1f else 0f, tween(300, 300))
    CompositionLocalProvider(LocalT provides Dark) {
        Box(Modifier.fillMaxWidth(), Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black), Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val inset = 20.dp.toPx()
                val arm = 28.dp.toPx()
                val r = CornerRadius(10.dp.toPx())
                val c = Color(0x99E0E2E8)
                val stroke = Stroke(2.dp.toPx())
                // Each corner is a rounded square clipped to its quarter.
                for ((ax, ay) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                    val x = if (ax == 0f) inset else size.width - inset - arm
                    val y = if (ay == 0f) inset else size.height - inset - arm
                    clipRect(x, y, x + arm, y + arm) {
                        val ox = if (ax == 0f) x else x - arm
                        val oy = if (ay == 0f) y else y - arm
                        drawRoundRect(c, Offset(ox, oy), Size(arm * 2, arm * 2), r, style = stroke)
                    }
                }
            }
            Orb(1730, 220.dp, Modifier.graphicsLayer { scaleX = orbScale; scaleY = orbScale; alpha = orbAlpha })
            Box(Modifier.graphicsLayer { alpha = lock }) { CheckDisc() }
            Txt(
                if (s.scanning) "Glyph found · checking keys" else "Looking for a Glyph", Ty.body, Color(0xFFD5D7DD),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp), align = TextAlign.Center,
            )
        }
        }
    }
    Help("Camera frames are decoded on this device and never uploaded.", Modifier.fillMaxWidth(), TextAlign.Center)
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        Btn("Simulate a scan", { s.simulateScan() }, kind = Kind.Primary, enabled = !s.scanning)
        Btn("No camera? Use a code", { s.tab = 2; s.scanning = false }, kind = Kind.Ghost)
    }
}

@Composable
private fun CodeTab(s: AppState) {
    var digits by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    val code = if (digits) DEMO_DIGITS else DEMO_WORDS
    val left = secondsLeft(s.codeDeadline)
    val clipboard = LocalClipboardManager.current
    val pairing = remember(s.glyphSeed) {
        val r = rng(s.glyphSeed)
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        "gly1:" + CharArray(140) { alphabet[(r() * 32).toInt()] }.concatToString()
    }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    fun submit() {
        err = when (checkPairingCode(input)) {
            CodeCheck.Malformed -> "Codes are five words like river-maple · orbit-tiger-seven, or 20 digits."
            CodeCheck.Wrong -> "That code didn't match, so it's burned now. Ask them for a new one."
            CodeCheck.Ok -> { input = ""; s.goConfirm(); "" }
        }
    }
    Section("Your code") {
        Box(Modifier.fillMaxWidth().cardBg().padding(horizontal = 16.dp, vertical = 24.dp), Alignment.Center) {
            Txt(code, Ty.name.copy(fontFamily = Ty.mono.fontFamily, fontWeight = Ty.body.fontWeight), T.textStrong, align = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                NetDot(false)
                Txt(if (left > 0) fmtSeconds(left) else "Expired", Ty.microMed, T.textStrong, maxLines = 1)
                Txt("single use", Ty.micro, T.muted, maxLines = 1)
            }
            Seg(listOf("Words", "Digits"), if (digits) 1 else 0, { digits = it == 1 })
            Spacer(Modifier.width(8.dp))
            Btn(
                if (copied) "Copied" else "Copy",
                { clipboard.setText(AnnotatedString(code)); copied = true },
                small = true, icon = if (copied) IC.check else IC.copy, tint = if (copied) T.success else null,
            )
        }
        Help("Read it aloud or send it another way. One wrong guess burns it, so nobody can try twice.")
    }
    Section {
        Txt("Their code", Ty.microMed, T.muted)
        Field(input, { input = it; err = "" }, DEMO_WORDS, label = "Their code", invalid = err.isNotEmpty(), mono = true)
        if (err.isNotEmpty()) ErrLine(err) else Help("Five words or 20 digits. For the demo, type the code above.")
        Btn("Pair", { submit() }, kind = Kind.Primary, enabled = input.isNotBlank())
    }
    Details("Pairing text for copy and paste") {
        Txt(pairing, Ty.mono, T.muted)
        Help("Single use, expires in 10 minutes. Only as private as where you paste it.")
    }
}

// ---------- Confirm the match ----------

@Composable
fun ConfirmMatch(s: AppState, compact: Boolean) {
    Head("Confirm the match", compact, "Back") { s.back() }
    Page {
        Section {
            Txt("Do both screens show these four words?", Ty.h2, T.textStrong)
            Txt("They come from the keys you just exchanged. If they match, nobody is in the middle.", Ty.body, T.muted)
        }
        val words = listOf("amber", "orbit", "lantern", "quiet")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.surface).border(1.dp, T.border, RoundedCornerShape(16.dp))) {
            for (row in 0..1) {
                if (row == 1) Box(Modifier.fillMaxWidth().height(1.dp).background(T.border))
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (col in 0..1) {
                        if (col == 1) Box(Modifier.width(1.dp).fillMaxHeight().background(T.border))
                        val i = row * 2 + col
                        Row(Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 16.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                            Txt("${i + 1}", Ty.micro, T.faint)
                            Txt(words[i], Ty.h2, T.textStrong)
                        }
                    }
                }
            }
        }
        Section(gap = 20.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Name for this contact", Ty.microMed, T.muted)
                Field(s.newName, { if (it.length <= 40) { s.newName = it; s.nameErr = false } }, "Only you see this", label = "Name for this contact", invalid = s.nameErr)
                if (s.nameErr) ErrLine("Add a name so you can find this chat.")
            }
            Card { SwitchRow("We're in the same room", "Marks them Verified. Apart, compare on a voice call.", s.inPerson) { s.inPerson = it } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("They match", { s.confirmMatch() }, kind = Kind.Primary)
                Btn("They don't match", { s.goAdd(); s.toast("Cancelled. Nothing was saved.") }, kind = Kind.Danger)
            }
        }
    }
}

// ---------- Privacy mode ----------

private class ModeCopy(val desc: String, val facts: List<Pair<Boolean, String>>)

private val MODE_COPY = mapOf(
    PrivacyMode.Fast to ModeCopy("Handed over directly, like a sealed letter.", listOf(true to "Instant", true to "Calls and big files", false to "They can see your IP")),
    PrivacyMode.Private to ModeCopy("Passed through several couriers (Tor).", listOf(true to "Hides your IP", false to "About 1 second", false to "Timing visible to strong watchers")),
    PrivacyMode.Untraceable to ModeCopy("Mixed into a sack of identical letters (Nym).", listOf(true to "Hides your IP", true to "Hides when you chat", false to "A few seconds, no calls")),
)

@Composable
fun ModePicker(s: AppState, compact: Boolean) {
    val editing = s.editing
    Head(if (editing != null) "Privacy with ${editing.name}" else "New contact", compact, "Back") { s.back() }
    Page {
        Section {
            Txt("How private should this chat be?", Ty.h2, T.textStrong)
            Txt("What you write is always end-to-end encrypted. This decides what else stays hidden.", Ty.body, T.muted)
        }
        Section(gap = 20.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrivacyMode.entries.forEach { ModeCard(it, s.modePick == it) { s.modePick = it } }
            }
            if (editing != null && editing.theirMode > s.modePick) Callout(IC.info, "${editing.name} chose ${editing.theirMode.label}. The stricter mode always wins.")
            Callout(IC.shield, "Never downgraded silently. If a network is down, messages wait and you decide.")
            Btn(if (editing != null) "Save" else "Start chatting", { s.saveMode() }, kind = Kind.Primary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeCard(mode: PrivacyMode, on: Boolean, onPick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val edge by animateColorAsState(if (on) T.brand else if (hovered) T.borderStrong else T.border, tween(150))
    val radio by animateColorAsState(if (on) T.brand else T.borderStrong, tween(150))
    val shape = RoundedCornerShape(16.dp)
    val copy = MODE_COPY.getValue(mode)
    Tap(
        onPick, Modifier.fillMaxWidth().border(1.5.dp, edge, shape), shape = shape, scale = .985f, bg = T.surface, hover = T.surface,
        role = Role.RadioButton, src = src, padding = PaddingValues(16.dp), arrangement = Arrangement.spacedBy(12.dp), align = Alignment.Top,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(T.raised), Alignment.Center) { Mark(mode, size = 20.dp, tint = T.textStrong) }
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                Txt(mode.label, Ty.name, T.textStrong)
                Txt(mode.kr, Ty.kr.copy(fontSize = 13.sp, lineHeight = 24.sp), T.faint)
                if (mode == PrivacyMode.Untraceable) Txt("Recommended", Ty.microMed.copy(lineHeight = 24.sp), T.muted)
            }
            Txt(copy.desc, Ty.body, T.muted)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                copy.facts.forEach { (yes, text) ->
                    val c = if (yes) T.textStrong else T.faint
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Ic(if (yes) IC.check else IC.x, size = 14.dp, tint = c)
                        Txt(text, Ty.micro, c)
                    }
                }
            }
        }
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape).border(1.5.dp, radio, CircleShape).background(if (on) T.brand else Color.Transparent), Alignment.Center) {
            if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(T.onBrand))
        }
    }
}

// ---------- Contact info ----------

@Composable
fun ContactInfo(s: AppState, c: Contact, compact: Boolean) {
    Head("Contact info", compact, "Back to chat") { s.back() }
    Page {
        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
            Avatar(c.name, 88.dp, Ty.num)
            Txt(c.name, Ty.h1, T.textStrong)
            MetaLine(c)
            Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(12.dp)) {
                QuickAction(IC.chat, "Message") { s.go(Route.Chat) }
                QuickAction(IC.phone, "Call", c.inUse.allowsCalls) { s.toast("Calling ${c.name} over iroh…") }
                QuickAction(if (c.peek) IC.eyeoff else IC.eye, "Peek") { c.peek = !c.peek }
            }
        }
        Section("Safety number") {
            val groups = s.core.safetyNumber(c)
            Card {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp).semantics { contentDescription = "Safety number" }, Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                    groups.chunked(4).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { line.forEach { Txt(it, Ty.body.copy(fontFamily = Ty.mono.fontFamily), T.textStrong) } }
                    }
                }
            }
            Help(if (c.verified) "You checked these in person. They change only if ${c.name} resets their identity." else "Compare these 60 digits with ${c.name} in person or on a voice call.")
            if (!c.verified) Btn("The numbers match", { s.core.markVerified(c); s.toast("${c.name} is verified") }, kind = Kind.Primary)
        }
        Section("This chat") {
            Card {
                LRowBtn("Privacy mode", { s.editMode(c) }, value = c.inUse.label, chevron = true)
                Hair()
                SwitchRow("Peek mode", "Blur messages until held or hovered", c.peek) { c.peek = it }
            }
        }
        Section("${c.name}'s devices") {
            Card {
                c.devices.forEachIndexed { i, d ->
                    if (i > 0) Hair()
                    LRow(d.name, icon = d.icon, value = d.level)
                }
            }
            Help("Screenshot protection as reported by their app. A claim, not proof.")
        }
    }
}

@Composable
private fun QuickAction(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val fg = if (enabled) T.textStrong else T.faint
    Tap(onClick, Modifier.width(84.dp), enabled = enabled, disabledAlpha = 1f, bg = T.raised, padding = PaddingValues(vertical = 10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Ic(icon, tint = fg)
            Txt(label, Ty.microMed, fg)
        }
    }
}

// ---------- Settings ----------

private val COVER = listOf("Off", "Low", "Normal", "High")
private val COVER_HELP = listOf(
    "Off: timing can link you to contacts in Untraceable chats.",
    "Low: about 2% battery a day while open.",
    "Normal: about 4% battery a day while open. Recommended.",
    "High: about 8% battery a day while open.",
)

@Composable
fun Settings(s: AppState, compact: Boolean, captureBlocked: Boolean) {
    val nymDown = s.core.anyNetworkDown
    Head("Settings", compact, "Back to chats") { s.back() }
    Page {
        Section("Identity") {
            Card {
                LRow("Fingerprint", icon = IC.key, value = "7f3a 91c2 0be4", mono = true)
                Hair()
                LRow("Keys", "Sealed by this device's security chip", IC.lock, "On device")
            }
        }
        Section("Networks") {
            Card {
                listOf(
                    "Veilid" to "Finds contacts and holds offline mail",
                    "iroh" to "Direct pipe for Fast chats",
                    "Tor" to "Hides your IP for Private chats",
                    "Nym" to "Hides timing for Untraceable chats",
                ).forEachIndexed { i, (name, job) ->
                    if (i > 0) Hair()
                    val down = name == "Nym" && nymDown
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                        NetDot(down)
                        Column(Modifier.weight(1f)) {
                            Txt(name, Ty.body, T.textStrong)
                            Txt(job, Ty.micro, T.muted)
                        }
                        Txt(if (down) "Retrying in 0:24" else "Connected", Ty.body, T.muted, maxLines = 1)
                    }
                }
            }
        }
        Section("Cover traffic") {
            Seg(COVER, s.cover, { s.cover = it }, full = true)
            Help(COVER_HELP[s.cover])
        }
        Section("This device") {
            Card {
                LRow(
                    "Screenshot protection",
                    if (captureBlocked) "Captures and screen shares show a blank window" else "This system can't block captures. Use Peek in sensitive chats.",
                    IC.shield,
                    if (captureBlocked) "Blocked" else "Not blocked",
                )
                Hair()
                SwitchRow("Message previews in notifications", "Off shows only “New message”", s.previews) { s.previews = it }
                Hair()
                LRow("Appearance") { Seg(listOf("Dark", "Light"), if (s.dark) 0 else 1, { s.dark = it == 0 }) }
            }
        }
        Section("Linked devices") {
            Card {
                LRow("This computer", icon = IC.monitor, value = "Always on")
                Hair()
                LRow("Pixel 9", icon = IC.mobile, value = "3 weeks ago")
                Hair()
                LRowBtn("Link a device", { s.goAdd(link = true) }, icon = IC.plus, accent = true)
            }
        }
        Section("Panic wipe") {
            HoldToWipe { s.wipe() }
            Help("Deletes your keys, contacts and messages from this device. It can't be undone, so it asks you to hold for 2 seconds.")
        }
    }
}

/** Slow while you decide (2 s linear), fast when you let go (200 ms). Works by pointer or by holding Space or Enter. */
@Composable
private fun HoldToWipe(onWipe: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        if (holding) { delay(2000); holding = false; onWipe() }
    }
    val progress by animateFloatAsState(if (holding) 1f else 0f, tween(if (holding) 2000 else 200, easing = if (holding) LinearEasing else EOut))
    val press by animateFloatAsState(if (holding && !LocalReduce.current) .98f else 1f, tween(160, easing = EOut))
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        Modifier.fillMaxWidth().height(48.dp)
            .graphicsLayer { scaleX = press; scaleY = press }
            .clip(CircleShape).background(T.raised)
            .then(if (focused) Modifier.border(2.dp, T.textStrong, CircleShape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    holding = true
                    waitForUpOrCancellation()
                    holding = false
                }
            }
            .onKeyEvent { e ->
                if (e.key == Key.Spacebar || e.key == Key.Enter) { holding = e.type == KeyEventType.KeyDown; true } else false
            }
            .hoverable(src)
            .focusable(interactionSource = src)
            .semantics { contentDescription = "Hold for two seconds to wipe this device"; role = Role.Button },
        Alignment.Center,
    ) {
        Txt("Hold to wipe this device", Ty.bodyMed, T.danger)
        Box(
            Modifier.matchParentSize()
                .drawWithContent { clipRect(right = size.width * progress) { this@drawWithContent.drawContent() } }
                .background(T.danger),
            Alignment.Center,
        ) { Txt("Keep holding…", Ty.bodyMed, Color.White) }
    }
}

// ---------- First run ----------

private val OB_STEPS = listOf("This device is ready", "Identity keys created", "Storage key sealed in the security chip", "Networks reached")

@Composable
fun Onboard(s: AppState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp), Alignment.Center) {
            Column(Modifier.widthIn(max = 340.dp).fillMaxWidth(), Arrangement.spacedBy(32.dp), Alignment.CenterHorizontally) {
                when (s.obStep) {
                    ObStep.Welcome -> ObWelcome(s)
                    ObStep.Making -> ObMaking(s)
                    ObStep.Ready -> ObReady(s)
                }
            }
        }
    }
}

/** First run only, so it can afford a short stagger. */
@Composable
private fun Modifier.stagger(i: Int) = enter("ob", dy = 8.dp, ms = 420, delayMs = i * 60)

@Composable
private fun ObWelcome(s: AppState) {
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    Orb(7, 200.dp, Modifier.stagger(0))
    Column(Modifier.stagger(1), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
        Txt("구미호 · 아홉 꼬리 여우", Ty.kr, T.faint)
        Txt("Kumiho", Ty.h1, T.textStrong)
        Txt("Private messages with no phone number and no server you have to trust.", Ty.body, T.muted, align = TextAlign.Center)
    }
    Column(Modifier.stagger(2).fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
        Btn("Create my identity", { s.createIdentity() }, kind = Kind.Primary, wide = true)
        Details("Add an app passphrase (optional)") {
            Txt("Passphrase", Ty.microMed, T.muted)
            Field(
                pass, { pass = it }, "At least 8 characters", label = "Passphrase", password = !show,
                trailing = { IconBtn(if (show) IC.eyeoff else IC.eye, if (show) "Hide passphrase" else "Show passphrase", { show = !show }) },
            )
            Help("Mixed into your storage key. Required on Linux without a TPM.")
        }
    }
}

@Composable
private fun ObMaking(s: AppState) {
    val bar by animateFloatAsState(s.obDone.toFloat() / OB_STEPS.size, tween(400, easing = EOut))
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
        Txt("Setting up this device", Ty.h2, T.textStrong)
        Txt("This takes a few seconds and happens entirely on this device.", Ty.body, T.muted, align = TextAlign.Center)
    }
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(T.border)) {
        Box(Modifier.fillMaxWidth(bar).fillMaxHeight().background(T.brand))
    }
    Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
        OB_STEPS.forEachIndexed { i, text ->
            val done = i < s.obDone
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(20.dp), Alignment.Center) {
                    when {
                        done -> Ic(IC.check, size = 18.dp, tint = T.success, weight = 2.2f)
                        i == s.obDone -> Spinner()
                        else -> Box(Modifier.size(8.dp).clip(CircleShape).background(T.borderStrong))
                    }
                }
                Txt(text, Ty.body, if (done) T.textStrong else T.muted)
            }
        }
    }
}

@Composable
private fun ObReady(s: AppState) {
    Box(Modifier.size(120.dp).stagger(0), Alignment.Center) { CheckDisc() }
    Column(Modifier.stagger(1), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
        Txt("You're ready", Ty.h2, T.textStrong)
        Txt("Add your first contact. They scan your Glyph, or you read them a short code.", Ty.body, T.muted, align = TextAlign.Center)
    }
    Column(Modifier.stagger(2).fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
        Btn("Add my first contact", { s.onboard = false; s.goAdd() }, kind = Kind.Primary, wide = true)
        Btn("Later", { s.onboard = false; s.route = Route.Empty }, kind = Kind.Ghost, wide = true)
    }
}
