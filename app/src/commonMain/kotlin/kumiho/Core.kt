package kumiho

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Local wall-clock time as HH:MM. */
expect fun clock(): String

/** Ordered least to most private, so `maxOf` is "stricter". Same rule as core/src/lib.rs until the FFI lands. */
enum class PrivacyMode(val label: String, val kr: String, val mark: String) {
    Fast("Fast", "빠르게", ""),
    Private("Private", "은밀하게", "M6 1.5a4.5 4.5 0 0 0 0 9Z"),
    Untraceable("Untraceable", "흔적 없이", "M6 1.5a4.5 4.5 0 1 1 0 9a4.5 4.5 0 1 1 0-9");

    val allowsCalls get() = this == Fast

    companion object {
        fun inUse(mine: PrivacyMode, theirs: PrivacyMode) = maxOf(mine, theirs)
    }
}

enum class DeliveryStatus(val icon: String, val label: String) {
    Queued(IC.clock, "Queued, waiting for the network"),
    Sent(IC.check, "Sent"),
    Stored(IC.inbox, "Stored in their mailbox"),
    Delivered(IC.check2, "Delivered"),
    Read(IC.check2, "Read"),
}

enum class Who { Me, Them, Notice }

class Msg(
    val id: Long,
    val who: Who,
    val text: String,
    val time: String = "",
    status: DeliveryStatus? = null,
    val icon: String = IC.info,
    val capture: Boolean = false,
    val verify: Boolean = false,
    var isNew: Boolean = false,
) {
    var status by mutableStateOf(status)
}

class Device(val icon: String, val name: String, val level: String)

class Contact(
    val id: String,
    val name: String,
    mode: PrivacyMode,
    val theirMode: PrivacyMode,
    verified: Boolean,
    peek: Boolean = false,
    unread: Int = 0,
    nymDown: Boolean = false,
    val shield: String = "",
    val devices: List<Device>,
    msgs: List<Msg>,
) {
    var mode by mutableStateOf(mode)
    var verified by mutableStateOf(verified)
    var peek by mutableStateOf(peek)
    var unread by mutableStateOf(unread)
    var nymDown by mutableStateOf(nymDown)
    val msgs = mutableStateListOf<Msg>().apply { addAll(msgs) }

    val inUse get() = PrivacyMode.inUse(mode, theirMode)
    val queued get() = msgs.count { it.status == DeliveryStatus.Queued }
    val lastMessage get() = msgs.lastOrNull { it.who != Who.Notice }
}

const val DEMO_WORDS = "river-maple · orbit-tiger-seven"
const val DEMO_DIGITS = "4821-0937 · 5512-8803-1146"

enum class CodeCheck { Ok, Malformed, Wrong }

fun checkPairingCode(input: String): CodeCheck {
    val v = input.trim().lowercase()
    val words = v.replace("·", " ").split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
    val digits = v.filter { it in '0'..'9' }
    if (!(words.size == 5 && Regex("[a-z\\s·-]+").matches(v)) && digits.length != 20) return CodeCheck.Malformed
    val match = words.joinToString("-") == "river-maple-orbit-tiger-seven" || digits == "48210937551288031146"
    return if (match) CodeCheck.Ok else CodeCheck.Wrong
}

/** mulberry32, as in the canvas, so the demo orb and safety numbers come out the same. */
fun rng(seed: Int): () -> Double {
    var a = seed
    return {
        a += 0x6d2b79f5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        (t xor (t ushr 14)).toUInt().toDouble() / 4294967296.0
    }
}

private fun fnv(s: String): Int {
    var h = 2166136261L.toInt()
    for (ch in s) h = (h xor ch.code) * 16777619
    return h
}

private val TIMING = mapOf(
    PrivacyMode.Fast to listOf(DeliveryStatus.Sent to 120L, DeliveryStatus.Delivered to 500L),
    PrivacyMode.Private to listOf(DeliveryStatus.Sent to 400L, DeliveryStatus.Stored to 1100L, DeliveryStatus.Delivered to 1900L),
    PrivacyMode.Untraceable to listOf(DeliveryStatus.Sent to 900L, DeliveryStatus.Stored to 2600L, DeliveryStatus.Delivered to 4600L),
)

// ponytail: in-memory stand-in with the spec's Core API names. No crypto, no network, nothing persisted.
// Replaced by the Gobley bindings to core/ as build-order steps 1-7 land.
class Core(private val scope: CoroutineScope) {
    private var nextId = 100L
    val contacts = mutableStateListOf<Contact>().apply { addAll(seedContacts()) }

    val anyNetworkDown get() = contacts.any { it.nymDown }
    val queuedAll get() = contacts.sumOf { it.queued }

    private fun notice(text: String, icon: String, verify: Boolean = false) =
        Msg(++nextId, Who.Notice, text, icon = icon, verify = verify)

    private fun deliver(m: Msg, mode: PrivacyMode) = TIMING.getValue(mode).forEach { (status, ms) ->
        scope.launch { delay(ms); m.status = status }
    }

    fun sendText(c: Contact, text: String, animate: Boolean) {
        val m = Msg(++nextId, Who.Me, text, clock(), DeliveryStatus.Queued, isNew = animate)
        c.msgs += m
        // Never downgrades silently: Untraceable messages wait while Nym is down.
        if (!(c.nymDown && c.inUse == PrivacyMode.Untraceable)) deliver(m, c.inUse)
    }

    fun acceptDowngrade(c: Contact) {
        val waiting = c.msgs.filter { it.status == DeliveryStatus.Queued }
        c.nymDown = false
        c.msgs += notice("Sent ${waiting.size} over Private while Nym was down", IC.info)
        waiting.forEach { deliver(it, PrivacyMode.Private) }
    }

    fun setContactMode(c: Contact, mode: PrivacyMode) {
        c.mode = mode
        c.msgs += notice("You switched to ${mode.label}", IC.shield)
    }

    fun markVerified(c: Contact) {
        c.verified = true
        c.msgs += notice("Safety numbers matched", IC.shieldcheck)
    }

    fun safetyNumber(c: Contact): List<String> {
        val r = rng(fnv(c.id))
        return List(12) { floor(r() * 1e5).toInt().toString().padStart(5, '0') }
    }

    fun confirmPairing(name: String, mode: PrivacyMode, inPerson: Boolean): Contact {
        val first = if (inPerson) notice("Paired in person", IC.shieldcheck)
        else notice("Paired at a distance, not verified yet", IC.info, verify = true)
        val c = Contact(
            id = "c${++nextId}", name = name, mode = mode, theirMode = mode, verified = inPerson,
            devices = listOf(Device(IC.mobile, "Phone", "Blocked")), msgs = listOf(first),
        )
        contacts.add(0, c)
        return c
    }

    fun panicWipe() = contacts.clear()

    private fun seedContacts(): List<Contact> {
        var id = 0L
        fun them(text: String, t: String) = Msg(++id, Who.Them, text, t)
        fun me(text: String, t: String, s: DeliveryStatus) = Msg(++id, Who.Me, text, t, s)
        fun note(text: String, icon: String, capture: Boolean = false, verify: Boolean = false) =
            Msg(++id, Who.Notice, text, icon = icon, capture = capture, verify = verify)
        val u = PrivacyMode.Untraceable
        return listOf(
            Contact(
                "mina", "Mina", u, u, verified = true, unread = 2, shield = "Mina's Mac can't block screenshots",
                devices = listOf(Device(IC.mobile, "Pixel 8", "Blocked"), Device(IC.monitor, "MacBook Air", "Blur only")),
                msgs = listOf(
                    note("Paired in person", IC.shieldcheck),
                    them("Did pairing work on your laptop?", "20:41"),
                    me("Yes, scanned it with my phone.", "20:43", DeliveryStatus.Read),
                    me("Took about two seconds.", "20:43", DeliveryStatus.Read),
                    them("Good. I'll send the scans of the contract tonight.", "20:44"),
                    note("Mina took a screenshot · 20:57", IC.camera, capture = true),
                    me("Saw the screenshot notice. Everything okay?", "20:58", DeliveryStatus.Delivered),
                    them("Just saving the address.", "21:01"),
                    them("Deleting it now.", "21:02"),
                ),
            ),
            Contact(
                "haneul", "Haneul", u, u, verified = true, nymDown = true,
                devices = listOf(Device(IC.mobile, "iPhone 15", "Blocked, fragile")),
                msgs = listOf(
                    them("Boarding now. Signal will be patchy for a while.", "18:20"),
                    me("Are you still on the ferry?", "19:05", DeliveryStatus.Queued),
                    me("Message me when you land.", "19:06", DeliveryStatus.Queued),
                ),
            ),
            Contact(
                "jun", "Jun", PrivacyMode.Private, PrivacyMode.Private, verified = false,
                devices = listOf(Device(IC.monitor, "Windows desktop", "Blocked")),
                msgs = listOf(
                    note("Paired with a code, not verified yet", IC.info, verify = true),
                    them("The code worked. My words were amber, orbit, lantern, quiet.", "Tue"),
                    me("Same on mine.", "Tue", DeliveryStatus.Delivered),
                ),
            ),
            Contact(
                "dad", "Dad", PrivacyMode.Fast, PrivacyMode.Fast, verified = true, unread = 1,
                devices = listOf(Device(IC.mobile, "Galaxy S23", "Blocked")),
                msgs = listOf(
                    them("Dinner on Sunday? Your mum is making galbi-jjim.", "Mon"),
                    me("I'll be there by six.", "Mon", DeliveryStatus.Read),
                    them("Bring the good kimchi.", "Mon"),
                ),
            ),
            Contact(
                "sora", "Sora", u, u, verified = true, peek = true,
                devices = listOf(Device(IC.mobile, "Pixel 7a", "Blocked")),
                msgs = listOf(
                    them("Door code is 4471. Third floor, on the left.", "Sun"),
                    me("Got it. See you at seven.", "Sun", DeliveryStatus.Read),
                ),
            ),
        )
    }
}
