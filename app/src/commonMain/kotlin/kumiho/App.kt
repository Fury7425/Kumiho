package kumiho

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class Route { Empty, Chat, Add, Confirm, Mode, Contact, Settings }
enum class Filter { All, Unread, Unverified }
enum class ObStep { Welcome, Making, Ready }

class Toast(val id: Long, val text: String) {
    var out by mutableStateOf(false)
}

class AppState(val core: Core, private val scope: CoroutineScope) {
    var route by mutableStateOf(Route.Empty)
    var cid by mutableStateOf<String?>(null)
    var dark by mutableStateOf(true)
    var onboard by mutableStateOf(true)
    var obStep by mutableStateOf(ObStep.Welcome)
    var obDone by mutableStateOf(1)
    var query by mutableStateOf("")
    var filter by mutableStateOf(Filter.All)
    var tab by mutableStateOf(0)
    var linking by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    var glyphSeed by mutableStateOf(424242)
    var glyphDeadline by mutableStateOf<TimeMark>(TimeSource.Monotonic.markNow() + 60.seconds)
    var codeDeadline by mutableStateOf<TimeMark>(TimeSource.Monotonic.markNow() + 10.minutes)
    var newName by mutableStateOf("")
    var nameErr by mutableStateOf(false)
    var inPerson by mutableStateOf(true)
    var modeFor by mutableStateOf<String?>(null)
    var modePick by mutableStateOf(PrivacyMode.Untraceable)
    var cover by mutableStateOf(2)
    var previews by mutableStateOf(false)
    val toasts = mutableStateListOf<Toast>()
    private var toastId = 0L

    val cur get() = core.contacts.find { it.id == cid }
    val editing get() = modeFor?.let { id -> core.contacts.find { it.id == id } }

    fun freshGlyph() {
        glyphSeed = Random.nextInt(1_000_000_000)
        glyphDeadline = TimeSource.Monotonic.markNow() + 60.seconds
        codeDeadline = TimeSource.Monotonic.markNow() + 10.minutes
    }

    fun go(r: Route) {
        if (r == Route.Add && route != Route.Add) { freshGlyph(); scanning = false }
        route = r
    }

    fun goAdd(link: Boolean = false) {
        linking = link
        tab = 0
        go(Route.Add)
    }

    fun open(c: Contact) {
        c.unread = 0
        cid = c.id
        route = Route.Chat
    }

    fun goConfirm() {
        newName = ""
        nameErr = false
        route = Route.Confirm
    }

    fun back() {
        route = when (route) {
            Route.Confirm -> Route.Add
            Route.Mode -> if (editing != null) Route.Contact else Route.Confirm
            Route.Contact -> Route.Chat
            else -> Route.Empty
        }
    }

    fun toast(text: String) {
        val t = Toast(++toastId, text)
        toasts += t
        scope.launch {
            delay(2240); t.out = true
            delay(160); toasts -= t
        }
    }

    fun later(ms: Long, block: () -> Unit) {
        scope.launch { delay(ms); block() }
    }

    fun simulateScan() {
        scanning = true
        later(1300) { scanning = false; goConfirm() }
    }

    fun confirmMatch() {
        if (newName.isBlank()) { nameErr = true; return }
        modeFor = null
        modePick = PrivacyMode.Untraceable
        route = Route.Mode
    }

    fun editMode(c: Contact) {
        modeFor = c.id
        modePick = c.mode
        route = Route.Mode
    }

    fun saveMode() {
        val e = editing
        modeFor = null
        if (e != null) {
            core.setContactMode(e, modePick)
            route = Route.Chat
            return
        }
        val c = core.confirmPairing(newName.trim(), modePick, inPerson)
        cid = c.id
        route = Route.Chat
        query = ""
        filter = Filter.All
        toast("${c.name} added")
    }

    fun createIdentity() {
        obStep = ObStep.Making
        obDone = 1
        scope.launch {
            repeat(3) { delay(600); obDone++ }
            delay(500)
            obStep = ObStep.Ready
        }
    }

    fun wipe() {
        core.panicWipe()
        cid = null
        route = Route.Empty
        query = ""
        filter = Filter.All
        toasts.clear()
        obStep = ObStep.Welcome
        obDone = 1
        onboard = true
    }
}

@Composable
fun App(captureBlocked: Boolean = false, reduceMotion: Boolean = false) {
    val scope = rememberCoroutineScope()
    val s = remember { AppState(Core(scope), scope) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SystemBack(enabled = !s.onboard && s.route != Route.Empty) { s.back() }
    CompositionLocalProvider(LocalT provides (if (s.dark) Dark else Light), LocalReduce provides reduceMotion, LocalFonts provides loadFonts()) {
        CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(T.textStrong, T.textStrong.copy(alpha = .3f))) {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(T.bg).safeDrawingPadding()
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && !s.onboard && s.route != Route.Empty) { s.back(); true } else false
                    }
                    .focusRequester(focus).focusable(),
            ) {
                val compact = maxWidth < 760.dp
                if (s.onboard) Onboard(s) else Shell(s, compact, captureBlocked)
                Toasts(s, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
            }
        }
    }
}

@Composable
private fun Shell(s: AppState, compact: Boolean, captureBlocked: Boolean) {
    val list = s.route == Route.Empty
    Row(Modifier.fillMaxSize()) {
        if (!compact || list) Sidebar(s, compact, if (compact) Modifier.fillMaxSize() else Modifier.width(340.dp).fillMaxHeight())
        if (!compact) Box(Modifier.width(1.dp).fillMaxHeight().background(T.border))
        if (!compact || !list) Pane(s, compact, captureBlocked, Modifier.weight(1f).fillMaxHeight())
    }
}

// ---------- Sidebar ----------

@Composable
private fun Sidebar(s: AppState, compact: Boolean, modifier: Modifier) {
    val core = s.core
    val q = s.query.trim().lowercase()
    val rows = core.contacts
        .filter { when (s.filter) { Filter.All -> true; Filter.Unread -> it.unread > 0; Filter.Unverified -> !it.verified } }
        .filter { c -> q.isEmpty() || c.name.lowercase().contains(q) || c.msgs.any { it.who != Who.Notice && it.text.lowercase().contains(q) } }
    val unreadChats = core.contacts.count { it.unread > 0 }
    Box(modifier.background(T.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (compact) {
                    Spacer(Modifier.weight(1f))
                    IconBtn(IC.gear, "Settings", { s.go(Route.Settings) })
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Txt("Kumiho", Ty.name, T.textStrong)
                        Txt("구미호", Ty.kr, T.faint, Modifier.padding(bottom = 3.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    IconBtn(IC.plus, "Add contact", { s.goAdd() }, tip = "Add contact")
                }
            }
            if (compact) Txt("Chats", Ty.h1, T.textStrong, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            if (core.contacts.isNotEmpty()) {
                Field(s.query, { s.query = it }, "Search names and messages", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp), label = "Search", height = 44.dp, leading = IC.search)
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp), Arrangement.spacedBy(8.dp)) {
                    Chip("All", s.filter == Filter.All) { s.filter = Filter.All }
                    Chip("Unread", s.filter == Filter.Unread, if (unreadChats > 0) "$unreadChats" else null) { s.filter = Filter.Unread }
                    Chip("Not verified", s.filter == Filter.Unverified) { s.filter = Filter.Unverified }
                }
            }
            when {
                core.contacts.isEmpty() -> ListEmpty(s, Modifier.weight(1f))
                rows.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), Alignment.Center) {
                    Column(Modifier.widthIn(max = 320.dp), Arrangement.spacedBy(16.dp), Alignment.CenterHorizontally) {
                        val text = if (q.isNotEmpty()) "No chats match “${s.query}”." else if (s.filter == Filter.Unread) "You're all caught up." else "Everyone is verified."
                        Txt(text, Ty.body, T.muted, align = TextAlign.Center)
                        Btn("Show all chats", { s.query = ""; s.filter = Filter.All }, small = true)
                    }
                }
                else -> {
                    val listState = rememberLazyListState()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(Modifier.fillMaxSize(), listState, PaddingValues(start = 8.dp, end = 8.dp, bottom = if (compact) 104.dp else 8.dp)) {
                            items(rows, key = { it.id }) { c -> ChatRow(s, c, current = !compact && s.route == Route.Chat && s.cid == c.id) }
                        }
                        VScroll(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
            if (!compact) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(T.border))
                Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val down = core.anyNetworkDown
                    Tap({ s.go(Route.Settings) }, shape = RoundedCornerShape(6.dp), hover = Color.Transparent, arrangement = Arrangement.spacedBy(8.dp)) {
                        NetDot(down)
                        Txt(if (down) "Nym down · ${core.queuedAll} queued" else "All 4 networks up", Ty.micro, T.muted, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    IconBtn(IC.gear, "Settings", { s.go(Route.Settings) }, tip = "Settings")
                }
            }
        }
        if (compact) {
            Tap(
                { s.goAdd() },
                Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 28.dp).size(64.dp).shadow(12.dp, CircleShape),
                shape = CircleShape, scale = .94f,
                brush = Brush.verticalGradient(listOf(T.cta1, T.cta2)), hover = T.brandHover, label = "Add contact",
            ) { Ic(IC.plus, tint = T.onBrand) }
        }
    }
}

@Composable
fun NetDot(down: Boolean) = Box(Modifier.size(8.dp).clip(CircleShape).background(if (down) T.danger else T.success))

@Composable
private fun Chip(text: String, on: Boolean, count: String? = null, onClick: () -> Unit) {
    val fg = if (on) T.onBrand else T.muted
    Tap(
        onClick, Modifier.height(48.dp), hit = 6.dp, bg = if (on) T.brand else T.raised, hover = if (on) T.brand else T.hover,
        padding = PaddingValues(horizontal = 14.dp), arrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(text, Ty.labelMed, fg, maxLines = 1)
        if (count != null) Txt(count, Ty.labelMed.copy(fontWeight = FontWeight.SemiBold), fg, maxLines = 1)
    }
}

@Composable
private fun ChatRow(s: AppState, c: Contact, current: Boolean) {
    val last = c.lastMessage
    val preview = buildAnnotatedString {
        if (c.nymDown) withStyle(SpanStyle(color = T.danger)) { append("Waiting for Nym · ") }
        else if (last?.who == Who.Me) withStyle(SpanStyle(color = T.text)) { append("You: ") }
        append(last?.text ?: "No messages yet")
    }
    Tap(
        { s.open(c) }, Modifier.fillMaxWidth(), scale = .985f, bg = if (current) T.raised else Color.Transparent,
        hover = if (current) T.raised else T.hover,
        label = c.name + ", " + c.inUse.label + if (c.unread > 0) ", ${c.unread} unread" else "",
        padding = PaddingValues(horizontal = 8.dp, vertical = 10.dp), arrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(c.name)
        Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(c.name, Ty.name, T.textStrong, maxLines = 1)
                    if (c.inUse != PrivacyMode.Untraceable) Mark(c.inUse, tint = T.faint)
                }
                Txt(last?.time ?: "", if (c.unread > 0) Ty.microMed else Ty.micro, if (c.unread > 0) T.textStrong else T.faint, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    preview,
                    Modifier.weight(1f).softBlur(if (c.peek) 4.dp else 0.dp),
                    Ty.body.themed().copy(color = T.muted),
                    overflow = TextOverflow.Ellipsis, maxLines = 1,
                )
                if (c.unread > 0) {
                    Box(Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).clip(CircleShape).background(T.brand).padding(horizontal = 7.dp), Alignment.Center) {
                        Txt("${c.unread}", Ty.microMed, T.onBrand)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListEmpty(s: AppState, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
        Column(Modifier.widthIn(max = 320.dp), Arrangement.spacedBy(16.dp), Alignment.CenterHorizontally) {
            Txt("Add your first contact", Ty.h2, T.textStrong, align = TextAlign.Center)
            Tips(listOf(IC.ring to "Together? Let them scan your Glyph.", IC.keyboard to "Apart? Read them a short code.", IC.lock to "No phone number or email involved."))
            Btn("Add contact", { s.goAdd() }, kind = Kind.Primary)
        }
    }
}

@Composable
private fun Tips(items: List<Pair<String, String>>) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    items.forEach { (icon, text) ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ic(icon, Modifier.padding(top = 2.dp), size = 16.dp, tint = T.faint)
            Txt(text, Ty.body, T.muted)
        }
    }
}

// ---------- Pane ----------

@Composable
private fun Pane(s: AppState, compact: Boolean, captureBlocked: Boolean, modifier: Modifier) {
    val aura = T.aura
    val bg = T.bg
    Box(modifier.drawBehind {
        drawRect(Brush.radialGradient(0f to aura, .72f to bg, center = Offset(size.width * .52f, -size.height * .1f), radius = 900.dp.toPx()))
    }) {
        val routeKey = s.route to s.cid
        key(routeKey) {
            Column(Modifier.fillMaxSize().then(if (compact) Modifier.pushIn(routeKey) else Modifier.enter(routeKey))) {
                val c = s.cur
                when {
                    s.route == Route.Chat && c != null -> Chat(s, c, compact)
                    s.route == Route.Add -> AddContact(s, compact)
                    s.route == Route.Confirm -> ConfirmMatch(s, compact)
                    s.route == Route.Mode -> ModePicker(s, compact)
                    s.route == Route.Contact && c != null -> ContactInfo(s, c, compact)
                    s.route == Route.Settings -> Settings(s, compact, captureBlocked)
                    else -> EmptyPane(s)
                }
            }
        }
    }
}

@Composable
private fun EmptyPane(s: AppState) {
    val line = T.border
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        // Changsal: a ttisal window lattice, faded from the centre.
        Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = .8f }) {
            var x = 0f
            while (x < size.width) { drawRect(line, Offset(x, 0f), Size(1f, size.height)); x += 22.dp.toPx() }
            var y = 0f
            while (y < size.height) { drawRect(line, Offset(0f, y), Size(size.width, 1f)); y += 66.dp.toPx() }
            drawRect(Brush.radialGradient(0f to Color.Black, 1f to Color.Transparent, center = center, radius = min(size.width, size.height) / 2), blendMode = BlendMode.DstIn)
        }
        Column(Modifier.widthIn(max = 320.dp).padding(32.dp), Arrangement.spacedBy(16.dp), Alignment.CenterHorizontally) {
            Txt(if (s.core.contacts.isNotEmpty()) "Select a chat" else "No chats yet", Ty.h2, T.textStrong, align = TextAlign.Center)
            Txt("Messages are end-to-end encrypted and never pass through a server you have to trust.", Ty.body, T.muted, align = TextAlign.Center)
            Btn("Add contact", { s.goAdd() })
            Txt("조용히, 흔적 없이", Ty.kr.copy(fontSize = 13.sp), T.faint, Modifier.padding(top = 8.dp))
        }
    }
}

// ---------- Conversation ----------

@Composable
private fun ColumnScope.Chat(s: AppState, c: Contact, compact: Boolean) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = if (compact) 4.dp else 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (compact) IconBtn(IC.back, "Back to chats", { s.back() })
        Row(Modifier.weight(1f)) {
            Tap(
                { s.go(Route.Contact) }, shape = RoundedCornerShape(10.dp), hover = Color.Transparent,
                label = "${c.name}, contact details", arrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(c.name, 36.dp, Ty.bodyMed)
                Column {
                    Txt(c.name, Ty.name, T.textStrong, maxLines = 1)
                    MetaLine(c)
                }
            }
        }
        val callTip = if (c.inUse.allowsCalls) "Call" else "Calls need Fast mode"
        IconBtn(IC.phone, callTip, { s.toast("Calling ${c.name} over iroh…") }, tip = callTip, enabled = c.inUse.allowsCalls)
        IconBtn(if (c.peek) IC.eyeoff else IC.eye, "Peek mode", { c.peek = !c.peek }, tip = if (c.peek) "Peek mode on" else "Peek mode off", on = c.peek)
    }
    if (c.nymDown && c.queued > 0) NymBanner(s, c)
    Timeline(s, c, compact, Modifier.weight(1f))
    ComposerBar(s, c, compact)
}

@Composable
private fun NymBanner(s: AppState, c: Contact) {
    val q = c.queued
    val body = buildAnnotatedString {
        withStyle(SpanStyle(color = T.textStrong, fontWeight = FontWeight.SemiBold)) { append("Nym is unreachable. ") }
        append("$q ${if (q == 1) "message is" else "messages are"} waiting. Private hides your IP but not when you chat.")
    }
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().cardBg().padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
        Arrangement.spacedBy(12.dp),
    ) {
        Ic(IC.alert, tint = T.warning)
        Column(Modifier.weight(1f), Arrangement.spacedBy(6.dp)) {
            BasicText(body, style = Ty.body.themed().copy(color = T.text))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Keep waiting is primary: the brightest button shouldn't lower someone's privacy.
                Btn("Keep waiting", { s.toast("They'll send once Nym is back") }, kind = Kind.Primary, small = true)
                Btn("Send over Private once", { s.core.acceptDowngrade(c) }, small = true)
            }
        }
    }
}

@Composable
private fun Pill(icon: String, text: String, capture: Boolean = false, onVerify: (() -> Unit)? = null) {
    val color = if (capture) T.danger else T.faint
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp, start = 12.dp, end = 12.dp),
        Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), Alignment.CenterVertically,
    ) {
        Ic(icon, size = 14.dp, tint = color)
        Txt(text, Ty.micro.copy(letterSpacing = .2.sp), color, align = TextAlign.Center)
        if (onVerify != null) Tap(onVerify, shape = RoundedCornerShape(4.dp), hover = Color.Transparent) { Txt("Verify", Ty.microMed, T.textStrong) }
    }
}

@Composable
private fun Timeline(s: AppState, c: Contact, compact: Boolean, modifier: Modifier) {
    val list = rememberLazyListState()
    val real = c.msgs.count { it.who != Who.Notice }
    val total = (if (c.peek) 1 else 0) + (if (c.shield.isNotEmpty()) 1 else 0) + (if (real > 0) 1 else 0) + c.msgs.size + (if (real == 0) 1 else 0)
    LaunchedEffect(c.id, c.msgs.size) { list.scrollToItem(total - 1) }
    val column = Modifier.widthIn(max = 728.dp).fillMaxWidth()
    Box(modifier.fillMaxWidth()) {
    LazyColumn(
        Modifier.fillMaxSize().fadeEdges(),
        state = list,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (c.peek) item { Box(column) { Pill(IC.eyeoff, "Peek is on. Hold or hover to read.") } }
        if (c.shield.isNotEmpty()) item { Box(column) { Pill(IC.shield, c.shield) } }
        if (real > 0) item {
            Row(column.padding(top = 12.dp, bottom = 8.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(T.border))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Txt("Today ·", Ty.micro, T.faint)
                    Txt("오늘", Ty.kr, T.faint)
                }
                Box(Modifier.weight(1f).height(1.dp).background(T.border))
            }
        }
        itemsIndexed(c.msgs, key = { _, m -> m.id }) { i, m ->
            Box(column) {
                if (m.who == Who.Notice) {
                    Pill(m.icon, m.text, m.capture, if (m.verify && !c.verified) { { s.go(Route.Contact) } } else null)
                } else {
                    val prev = c.msgs.getOrNull(i - 1)
                    val next = c.msgs.getOrNull(i + 1)
                    Bubble(m, first = prev?.who != m.who, last = next?.who != m.who, peek = c.peek, compact = compact)
                }
            }
        }
        if (real == 0) item {
            Box(column.fillParentMaxHeight(.8f), Alignment.Center) {
                Column(Modifier.widthIn(max = 320.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
                    Avatar(c.name, 88.dp, Ty.num)
                    Txt("Say hello to ${c.name}", Ty.h2, T.textStrong, align = TextAlign.Center)
                    val speed = when (c.inUse) {
                        PrivacyMode.Fast -> "Messages arrive instantly."
                        PrivacyMode.Private -> "Messages take about a second over Tor."
                        PrivacyMode.Untraceable -> "Messages take a few seconds to hide when you chat."
                    }
                    Tips(listOf(IC.clock to speed, IC.lock to "Only ${c.name}'s devices can read what you send."))
                }
            }
        }
    }
    VScroll(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Bubble(m: Msg, first: Boolean, last: Boolean, peek: Boolean, compact: Boolean) {
    val me = m.who == Who.Me
    val big = 16.dp
    val small = 6.dp
    val shape = if (me) RoundedCornerShape(big, if (first) big else small, if (last) big else small, big)
    else RoundedCornerShape(if (first) big else small, big, big, if (last) big else small)
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val focused by src.collectIsFocusedAsState()
    val blur = if (peek && !(hovered || pressed || focused)) 6.dp else 0.dp
    val rise = remember(m.id) { Animatable(if (m.isNew) 0f else 1f) }
    LaunchedEffect(m.id) {
        rise.animateTo(1f, tween(180, easing = EOut))
        m.isNew = false
    }
    val riseBy = if (LocalReduce.current) 0.dp else 6.dp
    val metaColor = if (me) T.mineMeta else T.faint
    // Transparent copy of the time reserves room so the real one tucks into the last line of text.
    val text = buildAnnotatedString {
        append(m.text)
        withStyle(SpanStyle(color = Color.Transparent, fontSize = 12.sp)) {
            append("   " + m.time + if (me) "       " else "")
        }
    }
    val align = if (me) Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxWidth().padding(top = if (first) 10.dp else 2.dp), align) {
        Box(Modifier.fillMaxWidth(if (compact) .8f else .76f).widthIn(max = 520.dp), align) {
            Box(
                Modifier
                    .graphicsLayer { alpha = rise.value; translationY = (1 - rise.value) * riseBy.toPx() }
                    .then(if (peek) Modifier.hoverable(src).clickable(src, null) {} else Modifier)
                    .softBlur(blur)
                    .clip(shape)
                    .background(if (me) T.mine else T.theirs)
                    .then(if (me) Modifier else Modifier.border(1.dp, T.border, shape))
                    .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            ) {
                BasicText(text, style = Ty.body.themed().copy(color = if (me) T.onBrand else T.textStrong))
                Row(
                    Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp),
                    Arrangement.spacedBy(3.dp), Alignment.CenterVertically,
                ) {
                    Txt(m.time, Ty.micro.copy(lineHeight = 12.sp), metaColor, maxLines = 1)
                    m.status?.let { status ->
                        Ic(
                            status.icon,
                            Modifier.semantics { contentDescription = status.label },
                            size = 15.dp, tint = if (status == DeliveryStatus.Read) T.read else metaColor, weight = 2f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerBar(s: AppState, c: Contact, compact: Boolean) {
    var draft by remember(c.id) { mutableStateOf(TextFieldValue("")) }
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    fun send(fromKeyboard: Boolean) {
        val text = draft.text.trim()
        if (text.isEmpty()) return
        // Keyboard-driven sends never animate; only a clicked send gets the fade.
        s.core.sendText(c, text, animate = !fromKeyboard)
        draft = TextFieldValue("")
    }
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth(), Alignment.TopCenter) {
        Row(
            Modifier.widthIn(max = 784.dp).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (compact) 28.dp else 12.dp),
            Arrangement.spacedBy(8.dp), Alignment.Bottom,
        ) {
            IconBtn(IC.plus, "Attach", { s.toast("Files are encrypted before they leave") }, tip = "Attach", tipAbove = true)
            BasicTextField(
                draft, { draft = it },
                Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp).clip(shape).background(T.hover)
                    .border(1.dp, if (focused) T.textStrong else Color.Transparent, shape)
                    .semantics { contentDescription = "Message ${c.name}" }
                    .onPreviewKeyEvent { e ->
                        // composition != null: mid-syllable in a Korean IME, so Enter must not send yet.
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed && draft.composition == null) { send(true); true } else false
                    },
                textStyle = Ty.body.themed().copy(color = T.textStrong),
                interactionSource = src,
                cursorBrush = SolidColor(T.textStrong),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        if (draft.text.isEmpty()) Txt("Message", Ty.body, T.faint)
                        inner()
                    }
                },
            )
            Tap(
                { send(false) }, Modifier.size(48.dp), enabled = draft.text.isNotBlank(), shape = CircleShape, scale = .94f,
                bg = T.brand, hover = T.brandHover, disabledAlpha = .35f, label = "Send",
            ) { Ic(IC.send, tint = T.onBrand) }
        }
    }
}

// ---------- Toasts ----------

@Composable
private fun Toasts(s: AppState, modifier: Modifier) {
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }, Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
        s.toasts.forEach { t -> key(t.id) { ToastView(t) } }
    }
}

@Composable
private fun ToastView(t: Toast) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(t.out) { a.animateTo(if (t.out) 0f else 1f, tween(if (t.out) 160 else 240, easing = EOut)) }
    val shape = RoundedCornerShape(16.dp)
    val riseBy = if (LocalReduce.current) 0.dp else 8.dp
    Box(
        Modifier.graphicsLayer { alpha = a.value; translationY = (1 - a.value) * riseBy.toPx() }
            .shadow(12.dp, shape).background(T.toast, shape).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        Alignment.Center,
    ) { Txt(t.text, Ty.body, T.onToast, maxLines = 1) }
}
