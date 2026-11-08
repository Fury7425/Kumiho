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
import uniffi.kumiho_core.CoreException
import uniffi.kumiho_core.PairingCode
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class Route { Empty, Chat, Add, Confirm, Mode, Contact, Settings }
enum class Filter { All, Unread, Unverified }
enum class Gate { Checking, Welcome, Making, Ready, Unlock, Open }

class Toast(val id: Long, val text: String) {
    var out by mutableStateOf(false)
}

class AppState(val vault: Vault, private val scope: CoroutineScope) {
    val core = Core(scope, vault) { toast(it) }
    var route by mutableStateOf(Route.Empty)
    var cid by mutableStateOf<String?>(null)
    var dark by mutableStateOf(true)
    var gate by mutableStateOf(Gate.Checking)
    var obDone by mutableStateOf(1)
    var gateError by mutableStateOf("")
    var query by mutableStateOf("")
    var filter by mutableStateOf(Filter.All)
    var tab by mutableStateOf(2)
    var code by mutableStateOf<PairingCode?>(null)
    var codeDeadline by mutableStateOf<TimeMark>(TimeSource.Monotonic.markNow())
    var newName by mutableStateOf("")
    var nameErr by mutableStateOf(false)
    var inPerson by mutableStateOf(true)
    var modeFor by mutableStateOf<String?>(null)
    val toasts = mutableStateListOf<Toast>()
    private var toastId = 0L

    val cur get() = core.contacts.find { it.name == cid }
    val editing get() = modeFor?.let { id -> core.contacts.find { it.name == id } }

    private fun pairing(r: Route) = r == Route.Add || r == Route.Confirm || (r == Route.Mode && editing == null)

    fun nav(r: Route) {
        if (pairing(route) && !pairing(r)) core.cancelPairing()
        core.openChat = if (r == Route.Chat) cid else null
        route = r
    }

    fun goAdd() {
        tab = 2
        nav(Route.Add)
        newCode()
    }

    fun newCode() {
        code = null
        scope.launch {
            try {
                code = core.startPairing()
                codeDeadline = TimeSource.Monotonic.markNow() + 10.minutes
            } catch (e: Exception) {
                core.pairError = e.message ?: "Couldn't make a code"
            }
        }
    }

    fun open(c: Contact) {
        cid = c.name
        nav(Route.Chat)
        core.markRead(c)
    }

    fun goConfirm() {
        newName = ""
        nameErr = false
        route = Route.Confirm
    }

    fun back() = nav(
        when (route) {
            Route.Confirm -> Route.Add
            Route.Mode -> if (editing != null) Route.Contact else Route.Confirm
            Route.Contact -> Route.Chat
            else -> Route.Empty
        },
    )

    fun toast(text: String) {
        val t = Toast(++toastId, text)
        toasts += t
        scope.launch {
            delay(2240); t.out = true
            delay(160); toasts -= t
        }
    }

    fun confirmMatch() {
        if (newName.isBlank()) { nameErr = true; return }
        modeFor = null
        route = Route.Mode
    }

    fun mismatch() {
        goAdd()
        toast("Cancelled. Nothing was saved.")
    }

    fun editMode(c: Contact) {
        modeFor = c.name
        route = Route.Mode
    }

    fun saveMode() {
        if (editing != null) {
            modeFor = null
            route = Route.Chat
            core.openChat = cid
            return
        }
        scope.launch {
            try {
                val c = core.confirmPairing(newName.trim(), inPerson)
                query = ""
                filter = Filter.All
                cid = c.name
                core.openChat = c.name
                route = Route.Chat
                toast("${c.name} added")
            } catch (e: Exception) {
                toast("Couldn't add them: ${e.message}")
            }
        }
    }

    /** On launch: nothing yet means first run; otherwise open silently if no passphrase is set, or ask. */
    fun boot() {
        scope.launch {
            if (!vault.hasDatabase) {
                gate = Gate.Welcome
                return@launch
            }
            if (!vault.seals) {
                gate = Gate.Unlock
                return@launch
            }
            try {
                core.open("")
                gate = Gate.Open
            } catch (e: CoreException.WrongPassphrase) {
                gate = Gate.Unlock
            } catch (e: Exception) {
                gateError = e.message ?: "Couldn't open your data"
                gate = Gate.Unlock
            }
        }
    }

    fun createIdentity(passphrase: String) {
        gateError = ""
        gate = Gate.Making
        obDone = 1
        scope.launch {
            try {
                core.open(passphrase)
                repeat(3) { obDone++; delay(250) }
                gate = Gate.Ready
            } catch (e: Exception) {
                gateError = e.message ?: "Couldn't set up this device"
                gate = Gate.Welcome
            }
        }
    }

    fun unlock(passphrase: String) {
        gateError = ""
        scope.launch {
            try {
                core.open(passphrase)
                gate = Gate.Open
            } catch (e: CoreException.WrongPassphrase) {
                gateError = "That passphrase didn't work."
            } catch (e: Exception) {
                gateError = e.message ?: "Couldn't open your data"
            }
        }
    }

    fun wipe() {
        scope.launch {
            try {
                core.wipe()
                cid = null
                route = Route.Empty
                query = ""
                filter = Filter.All
                toasts.clear()
                obDone = 1
                gate = Gate.Welcome
            } catch (e: Exception) {
                toast("Wipe failed: ${e.message}")
            }
        }
    }
}

@Composable
fun App(vault: Vault, captureBlocked: Boolean = false, reduceMotion: Boolean = false) {
    val scope = rememberCoroutineScope()
    val s = remember { AppState(vault, scope) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        s.boot()
    }
    val words = s.core.pairWords
    LaunchedEffect(words) { if (words != null && s.route == Route.Add) s.goConfirm() }
    SystemBack(enabled = s.gate == Gate.Open && s.route != Route.Empty) { s.back() }
    CompositionLocalProvider(LocalT provides (if (s.dark) Dark else Light), LocalReduce provides reduceMotion, LocalFonts provides loadFonts()) {
        CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(T.textStrong, T.textStrong.copy(alpha = .3f))) {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(T.bg).safeDrawingPadding()
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && s.gate == Gate.Open && s.route != Route.Empty) { s.back(); true } else false
                    }
                    .focusRequester(focus).focusable(),
            ) {
                val compact = maxWidth < 760.dp
                if (s.gate == Gate.Open) Shell(s, compact, captureBlocked) else Onboard(s)
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
        .filter { c -> q.isEmpty() || c.name.lowercase().contains(q) || c.msgs.any { it.text.lowercase().contains(q) } }
    val unreadChats = core.contacts.count { it.unread > 0 }
    Box(modifier.background(T.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (compact) {
                    Spacer(Modifier.weight(1f))
                    IconBtn(IC.gear, "Settings", { s.nav(Route.Settings) })
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
                            items(rows, key = { it.name }) { c -> ChatRow(s, c, current = !compact && s.route == Route.Chat && s.cid == c.name) }
                        }
                        VScroll(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
            if (!compact) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(T.border))
                Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tap({ s.nav(Route.Settings) }, shape = RoundedCornerShape(6.dp), hover = Color.Transparent, arrangement = Arrangement.spacedBy(8.dp)) {
                        NetDot(core.onlineCount > 0)
                        Txt("Direct links · ${core.onlineCount} online", Ty.micro, T.muted, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    IconBtn(IC.gear, "Settings", { s.nav(Route.Settings) }, tip = "Settings")
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
fun NetDot(on: Boolean) = Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) T.success else T.faint))

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
        if (last?.mine == true) withStyle(SpanStyle(color = T.text)) { append("You: ") }
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
                    Mark(c.inUse, tint = T.faint)
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
            Tips(listOf(IC.keyboard to "Read them a short one-time code.", IC.lock to "No phone number or email involved."))
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
                { s.nav(Route.Contact) }, shape = RoundedCornerShape(10.dp), hover = Color.Transparent,
                label = "${c.name}, contact details", arrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(c.name, 36.dp, Ty.bodyMed)
                Column {
                    Txt(c.name, Ty.name, T.textStrong, maxLines = 1)
                    MetaLine(c)
                }
            }
        }
        IconBtn(IC.phone, "Calls aren't built yet", {}, tip = "Calls aren't built yet", enabled = false)
        IconBtn(if (c.peek) IC.eyeoff else IC.eye, "Peek mode", { s.core.setPeek(c, !c.peek) }, tip = if (c.peek) "Peek mode on" else "Peek mode off", on = c.peek)
    }
    Timeline(s, c, compact, Modifier.weight(1f))
    ComposerBar(s, c, compact)
}

@Composable
private fun Pill(icon: String, text: String, onVerify: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp, start = 12.dp, end = 12.dp),
        Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), Alignment.CenterVertically,
    ) {
        Ic(icon, size = 14.dp, tint = T.faint)
        Txt(text, Ty.micro.copy(letterSpacing = .2.sp), T.faint, align = TextAlign.Center)
        if (onVerify != null) Tap(onVerify, shape = RoundedCornerShape(4.dp), hover = Color.Transparent) { Txt("Verify", Ty.microMed, T.textStrong) }
    }
}

@Composable
private fun Timeline(s: AppState, c: Contact, compact: Boolean, modifier: Modifier) {
    val list = rememberLazyListState()
    val total = (if (c.peek) 1 else 0) + (if (c.verified) 0 else 1) + (if (c.online) 0 else 1) + c.msgs.size + (if (c.msgs.isEmpty()) 1 else 0)
    LaunchedEffect(c.name, c.msgs.size) { if (total > 0) list.scrollToItem(total - 1) }
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
            if (!c.verified) item { Box(column) { Pill(IC.info, "Paired with a code, not verified yet", onVerify = { s.nav(Route.Contact) }) } }
            if (!c.online) item { Box(column) { Pill(IC.clock, "Waiting for ${c.name}. Messages send when you both have Kumiho open.") } }
            itemsIndexed(c.msgs, key = { _, m -> m.key }) { i, m ->
                Box(column) {
                    val prev = c.msgs.getOrNull(i - 1)
                    val next = c.msgs.getOrNull(i + 1)
                    Bubble(m, first = prev?.mine != m.mine, last = next?.mine != m.mine, peek = c.peek, compact = compact)
                }
            }
            if (c.msgs.isEmpty()) item {
                Box(column.fillParentMaxHeight(.8f), Alignment.Center) {
                    Column(Modifier.widthIn(max = 320.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
                        Avatar(c.name, 88.dp, Ty.num)
                        Txt("Say hello to ${c.name}", Ty.h2, T.textStrong, align = TextAlign.Center)
                        Tips(listOf(IC.clock to "Messages arrive instantly when you're both online.", IC.lock to "Only ${c.name}'s device can read what you send."))
                    }
                }
            }
        }
        VScroll(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Bubble(m: Msg, first: Boolean, last: Boolean, peek: Boolean, compact: Boolean) {
    val me = m.mine
    val big = 16.dp
    val small = 6.dp
    val shape = if (me) RoundedCornerShape(big, if (first) big else small, if (last) big else small, big)
    else RoundedCornerShape(if (first) big else small, big, big, if (last) big else small)
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val focused by src.collectIsFocusedAsState()
    val blur = if (peek && !(hovered || pressed || focused)) 6.dp else 0.dp
    val rise = remember(m.key) { Animatable(if (m.isNew) 0f else 1f) }
    LaunchedEffect(m.key) {
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
                            size = 15.dp, tint = if (status == DeliveryStatus.Delivered) T.read else metaColor, weight = 2f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerBar(s: AppState, c: Contact, compact: Boolean) {
    var draft by remember(c.name) { mutableStateOf(TextFieldValue("")) }
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
            IconBtn(IC.plus, "Attaching files isn't built yet", {}, tip = "Attaching files isn't built yet", tipAbove = true, enabled = false)
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
