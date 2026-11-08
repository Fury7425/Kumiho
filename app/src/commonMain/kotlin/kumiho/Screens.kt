package kumiho

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch
import uniffi.kumiho_core.CoreException

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
    Head("Add contact", compact, "Back to chats") { s.back() }
    Page {
        Seg(listOf("Glyph", "Scan", "Code"), s.tab, { s.tab = it }, full = true)
        if (s.tab == 2) CodeTab(s) else NotBuiltTab(s)
    }
}

/** The Glyph needs a camera decoder nobody has proven yet (build-order step 3), so the UI doesn't pretend. */
@Composable
private fun NotBuiltTab(s: AppState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.spacedBy(16.dp), Alignment.CenterHorizontally) {
        Orb(s.code?.digits.hashCode(), 200.dp, Modifier.graphicsLayer { alpha = .4f })
        Txt("Glyph pairing isn't built yet", Ty.h2, T.textStrong, align = TextAlign.Center)
        Txt("Use a code for now. Read it to them in person or on a call.", Ty.body, T.muted, Modifier.widthIn(max = 280.dp), align = TextAlign.Center)
        Btn("Use a code", { s.tab = 2 }, kind = Kind.Primary)
    }
}

@Composable
private fun CodeTab(s: AppState) {
    var digits by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var formatErr by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val pairingCode = s.code
    val code = pairingCode?.let { if (digits) it.digits else it.words }
    val left = secondsLeft(s.codeDeadline)
    val failed = s.core.pairError
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    fun submit() {
        formatErr = ""
        scope.launch {
            try {
                s.core.joinPairing(input)
            } catch (e: CoreException.BadCode) {
                formatErr = "Codes are five words like river-maple · orbit-tiger-seven, or 20 digits."
            } catch (e: Exception) {
                s.core.pairError = e.message ?: "Couldn't start pairing"
            }
        }
    }
    Section("Your code") {
        Box(Modifier.fillMaxWidth().cardBg().padding(horizontal = 16.dp, vertical = 24.dp), Alignment.Center) {
            Txt(code ?: "Making a code…", Ty.name.copy(fontFamily = Ty.mono.fontFamily, fontWeight = Ty.body.fontWeight), T.textStrong, align = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                NetDot(left > 0 && failed.isEmpty())
                Txt(if (left > 0) fmtSeconds(left) else "Expired", Ty.microMed, T.textStrong, maxLines = 1)
                Txt("single use", Ty.micro, T.muted, maxLines = 1)
            }
            Seg(listOf("Words", "Digits"), if (digits) 1 else 0, { digits = it == 1 })
            Spacer(Modifier.width(8.dp))
            Btn(
                if (copied) "Copied" else "Copy",
                { code?.let { clipboard.setText(AnnotatedString(it)); copied = true } },
                small = true, icon = if (copied) IC.check else IC.copy, tint = if (copied) T.success else null,
                enabled = code != null,
            )
        }
        Help("Read it aloud or send it another way. One wrong guess burns it, so nobody can try twice.")
        if (failed.isNotEmpty() || left <= 0) Btn("New code", { s.newCode() }, icon = IC.refresh)
    }
    Section {
        Txt("Their code", Ty.microMed, T.muted)
        Field(input, { input = it; formatErr = "" }, "river-maple · orbit-tiger-seven", label = "Their code", invalid = formatErr.isNotEmpty() || failed.isNotEmpty(), mono = true)
        when {
            formatErr.isNotEmpty() -> ErrLine(formatErr)
            failed.isNotEmpty() -> ErrLine(failed)
            else -> Help("Five words or 20 digits. Keep this screen open while you pair.")
        }
        Btn(if (s.core.pairBusy) "Connecting…" else "Pair", { submit() }, kind = Kind.Primary, enabled = input.isNotBlank() && !s.core.pairBusy)
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
        val words = s.core.pairWords.orEmpty()
        if (words.size == 4) {
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
                Btn("They don't match", { s.mismatch() }, kind = Kind.Danger)
            }
        }
    }
}

// ---------- Privacy mode ----------

private class ModeCopy(val desc: String, val facts: List<Pair<Boolean, String>>)

private val MODE_COPY = mapOf(
    PrivacyMode.Fast to ModeCopy("Handed over directly, like a sealed letter.", listOf(true to "Instant", false to "They can see your IP")),
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { PrivacyMode.entries.forEach { ModeCard(it) } }
            Callout(IC.shield, "Private and Untraceable aren't built yet, so every chat uses Fast for now. Your IP is visible to this contact.")
            Btn(if (editing != null) "Done" else "Start chatting", { s.saveMode() }, kind = Kind.Primary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeCard(mode: PrivacyMode) {
    val on = mode.available
    val src = remember { MutableInteractionSource() }
    val edge by animateColorAsState(if (on) T.brand else T.border, tween(150))
    val shape = RoundedCornerShape(16.dp)
    val copy = MODE_COPY.getValue(mode)
    Tap(
        {}, Modifier.fillMaxWidth().border(1.5.dp, edge, shape), enabled = false, disabledAlpha = if (on) 1f else .6f, shape = shape, bg = T.surface,
        role = Role.RadioButton, src = src, padding = PaddingValues(16.dp), arrangement = Arrangement.spacedBy(12.dp), align = Alignment.Top,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(T.raised), Alignment.Center) { Mark(mode, size = 20.dp, tint = T.textStrong) }
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                Txt(mode.label, Ty.name, T.textStrong)
                Txt(mode.kr, Ty.kr.copy(fontSize = 13.sp, lineHeight = 24.sp), T.faint)
                if (!on) Txt("Not built yet", Ty.microMed.copy(lineHeight = 24.sp), T.muted)
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
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape).border(1.5.dp, if (on) T.brand else T.borderStrong, CircleShape).background(if (on) T.brand else Color.Transparent), Alignment.Center) {
            if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(T.onBrand))
        }
    }
}

// ---------- Contact info ----------

@Composable
fun ContactInfo(s: AppState, c: Contact, compact: Boolean) {
    Head("Contact info", compact, "Back to chat") { s.back() }
    val groups by produceState(emptyList<String>(), c.name) {
        value = try { s.core.safetyNumber(c) } catch (e: Exception) { emptyList() }
    }
    Page {
        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
            Avatar(c.name, 88.dp, Ty.num)
            Txt(c.name, Ty.h1, T.textStrong)
            MetaLine(c)
            Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(12.dp)) {
                QuickAction(IC.chat, "Message") { s.nav(Route.Chat) }
                QuickAction(IC.phone, "Call", enabled = false) {}
                QuickAction(if (c.peek) IC.eyeoff else IC.eye, "Peek") { s.core.setPeek(c, !c.peek) }
            }
        }
        Section("Safety number") {
            Card {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp).semantics { contentDescription = "Safety number" }, Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                    groups.chunked(4).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { line.forEach { Txt(it, Ty.body.copy(fontFamily = Ty.mono.fontFamily), T.textStrong) } }
                    }
                }
            }
            Help(if (c.verified) "You marked these as checked. They change only if ${c.name} resets their identity." else "Compare these 60 digits with ${c.name} in person or on a voice call.")
            if (!c.verified) Btn("The numbers match", { s.core.markVerified(c); s.toast("${c.name} is verified") }, kind = Kind.Primary)
        }
        Section("This chat") {
            Card {
                LRowBtn("Privacy mode", { s.editMode(c) }, value = c.inUse.label, chevron = true)
                Hair()
                SwitchRow("Peek mode", "Blur messages until held or hovered", c.peek) { s.core.setPeek(c, it) }
            }
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

private val PLANNED_NETWORKS = listOf(
    "Veilid" to "Offline mail",
    "Tor" to "Hides your IP for Private chats",
    "Nym" to "Hides timing for Untraceable chats",
)

@Composable
fun Settings(s: AppState, compact: Boolean, captureBlocked: Boolean) {
    Head("Settings", compact, "Back to chats") { s.back() }
    Page {
        Section("Identity") {
            Card {
                LRow("Fingerprint", icon = IC.key, value = s.core.fingerprint, mono = true)
                Hair()
                LRow(
                    "Keys",
                    if (s.vault.seals) "Database key sealed by this device" else "Database key derived from your passphrase",
                    IC.lock,
                    "On device",
                )
            }
        }
        Section("Networks") {
            Card {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                    NetDot(s.core.onlineCount > 0)
                    Column(Modifier.weight(1f)) {
                        Txt("iroh", Ty.body, T.textStrong)
                        Txt("Direct pipe for Fast chats", Ty.micro, T.muted)
                    }
                    Txt("${s.core.onlineCount} online", Ty.body, T.muted, maxLines = 1)
                }
                PLANNED_NETWORKS.forEach { (name, job) ->
                    Hair()
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                        NetDot(false)
                        Column(Modifier.weight(1f)) {
                            Txt(name, Ty.body, T.textStrong)
                            Txt(job, Ty.micro, T.muted)
                        }
                        Txt("Not built yet", Ty.body, T.muted, maxLines = 1)
                    }
                }
            }
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
                LRow("Appearance") { Seg(listOf("Dark", "Light"), if (s.dark) 0 else 1, { s.dark = it == 0 }) }
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

// ---------- First run and unlock ----------

@Composable
fun Onboard(s: AppState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp), Alignment.Center) {
            Column(Modifier.widthIn(max = 340.dp).fillMaxWidth(), Arrangement.spacedBy(32.dp), Alignment.CenterHorizontally) {
                when (s.gate) {
                    Gate.Welcome -> ObWelcome(s)
                    Gate.Making -> ObMaking(s)
                    Gate.Ready -> ObReady(s)
                    Gate.Unlock -> ObUnlock(s)
                    else -> Unit
                }
            }
        }
    }
}

/** First run only, so it can afford a short stagger. */
@Composable
private fun Modifier.stagger(i: Int) = enter("ob", dy = 8.dp, ms = 420, delayMs = i * 60)

@Composable
private fun PassField(value: String, onChange: (String) -> Unit, placeholder: String) {
    var show by remember { mutableStateOf(false) }
    Field(
        value, onChange, placeholder, label = "Passphrase", password = !show,
        trailing = { IconBtn(if (show) IC.eyeoff else IC.eye, if (show) "Hide passphrase" else "Show passphrase", { show = !show }) },
    )
}

@Composable
private fun ObWelcome(s: AppState) {
    var pass by remember { mutableStateOf("") }
    val required = !s.vault.seals
    val tooShort = pass.isNotEmpty() && pass.length < 8
    Orb(7, 200.dp, Modifier.stagger(0))
    Column(Modifier.stagger(1), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
        Txt("구미호 · 아홉 꼬리 여우", Ty.kr, T.faint)
        Txt("Kumiho", Ty.h1, T.textStrong)
        Txt("Private messages with no phone number and no server you have to trust.", Ty.body, T.muted, align = TextAlign.Center)
    }
    Column(Modifier.stagger(2).fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
        if (required) {
            Txt("Passphrase", Ty.microMed, T.muted)
            PassField(pass, { pass = it }, "At least 8 characters")
            Help("This device has no key store Kumiho can use yet, so your passphrase protects everything. It can't be recovered.")
        }
        Btn("Create my identity", { s.createIdentity(pass) }, kind = Kind.Primary, wide = true, enabled = !tooShort && (!required || pass.isNotEmpty()))
        if (s.gateError.isNotEmpty()) ErrLine(s.gateError)
        if (!required) {
            Details("Add an app passphrase (optional)") {
                Txt("Passphrase", Ty.microMed, T.muted)
                PassField(pass, { pass = it }, "At least 8 characters")
                Help("Mixed into your storage key. You'll type it each time you open Kumiho, and it can't be recovered.")
            }
        }
    }
}

@Composable
private fun ObUnlock(s: AppState) {
    var pass by remember { mutableStateOf("") }
    Orb(7, 160.dp, Modifier.stagger(0))
    Column(Modifier.stagger(1), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
        Txt("Welcome back", Ty.h1, T.textStrong)
        Txt("Enter your passphrase to open your messages.", Ty.body, T.muted, align = TextAlign.Center)
    }
    Column(Modifier.stagger(2).fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
        PassField(pass, { pass = it }, "Passphrase")
        if (s.gateError.isNotEmpty()) ErrLine(s.gateError)
        Btn("Unlock", { s.unlock(pass) }, kind = Kind.Primary, wide = true, enabled = pass.isNotEmpty())
    }
}

@Composable
private fun ObMaking(s: AppState) {
    val steps = listOf(
        "This device is ready",
        "Identity keys created",
        if (s.vault.seals) "Storage key sealed on this device" else "Storage encrypted with your passphrase",
        "Network started",
    )
    val bar by animateFloatAsState(s.obDone.toFloat() / steps.size, tween(400, easing = EOut))
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
        Txt("Setting up this device", Ty.h2, T.textStrong)
        Txt("This takes a few seconds and happens entirely on this device.", Ty.body, T.muted, align = TextAlign.Center)
    }
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(T.border)) {
        Box(Modifier.fillMaxWidth(bar).fillMaxHeight().background(T.brand))
    }
    Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { i, text ->
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
        Txt("Add your first contact. Read them a short one-time code.", Ty.body, T.muted, align = TextAlign.Center)
    }
    Column(Modifier.stagger(2).fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
        Btn("Add my first contact", { s.gate = Gate.Open; s.goAdd() }, kind = Kind.Primary, wide = true)
        Btn("Later", { s.gate = Gate.Open; s.nav(Route.Empty) }, kind = Kind.Ghost, wide = true)
    }
}
