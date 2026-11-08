package kumiho

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.kumiho_core.ContactInfo
import uniffi.kumiho_core.Events
import uniffi.kumiho_core.MessageInfo
import uniffi.kumiho_core.Node
import uniffi.kumiho_core.PairingCode

/** HH:MM today, otherwise the date. */
expect fun formatTime(atMs: Long): String

/** Where a platform keeps the database and the key that seals it. */
interface Vault {
    val dbPath: String
    val hasDatabase: Boolean

    /** True if the OS can seal a key (Android Keystore, Windows DPAPI). Otherwise a passphrase is required. */
    val seals: Boolean

    /** A device-sealed secret mixed into the database key, created on first use. Empty if [seals] is false. */
    fun secret(): String

    fun forget()
}

/** Ordered least to most private, so `maxOf` is "stricter". Same rule as core/src/lib.rs. */
enum class PrivacyMode(val label: String, val kr: String, val mark: String) {
    Fast("Fast", "빠르게", ""),
    Private("Private", "은밀하게", "M6 1.5a4.5 4.5 0 0 0 0 9Z"),
    Untraceable("Untraceable", "흔적 없이", "M6 1.5a4.5 4.5 0 1 1 0 9a4.5 4.5 0 1 1 0-9");

    val allowsCalls get() = this == Fast

    /** Only iroh exists so far. The others stay visible but can't be chosen, so nothing claims more than it does. */
    val available get() = this == Fast

    companion object {
        fun inUse(mine: PrivacyMode, theirs: PrivacyMode) = maxOf(mine, theirs)
    }
}

enum class DeliveryStatus(val icon: String, val label: String) {
    Queued(IC.clock, "Queued, waiting for the network"),
    Sent(IC.check, "Sent"),
    Delivered(IC.check2, "Delivered");

    companion object {
        fun of(s: String) = when (s) {
            "delivered" -> Delivered
            "sent" -> Sent
            "queued" -> Queued
            else -> null
        }
    }
}

class Msg(val seq: Long, val mine: Boolean, val text: String, val atMs: Long, status: DeliveryStatus?, var isNew: Boolean = false) {
    var status by mutableStateOf(status)
    val time get() = formatTime(atMs)
    val key get() = "$mine-$seq"
}

class Contact(val name: String, verified: Boolean, peek: Boolean, unread: Int) {
    var verified by mutableStateOf(verified)
    var peek by mutableStateOf(peek)
    var unread by mutableStateOf(unread)
    var online by mutableStateOf(false)
    val msgs = mutableStateListOf<Msg>()

    val inUse get() = PrivacyMode.Fast
    val queued get() = msgs.count { it.status == DeliveryStatus.Queued }
    val lastMessage get() = msgs.lastOrNull()
}

/** mulberry32, as in the canvas, so the decorative orb looks the same. */
fun rng(seed: Int): () -> Double {
    var a = seed
    return {
        a += 0x6d2b79f5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        (t xor (t ushr 14)).toUInt().toDouble() / 4294967296.0
    }
}

private fun MessageInfo.toMsg(animate: Boolean = false) =
    Msg(seq.toLong(), outgoing, text, atMs.toLong(), if (outgoing) DeliveryStatus.of(status) else null, animate)

/** The UI's view of the Rust node. Node calls block on its worker thread, so they all run off the UI thread. */
class Core(private val scope: CoroutineScope, private val vault: Vault, private val onError: (String) -> Unit) : Events {
    private var node: Node? = null
    val contacts = mutableStateListOf<Contact>()
    var fingerprint by mutableStateOf("")
    var openChat: String? = null

    var pairWords by mutableStateOf<List<String>?>(null)
    var pairError by mutableStateOf("")
    var pairBusy by mutableStateOf(false)

    val onlineCount get() = contacts.count { it.online }

    private fun find(name: String) = contacts.find { it.name == name }

    private suspend fun <T> call(block: (Node) -> T): T = withContext(Dispatchers.Default) { block(node!!) }

    private fun fireAndForget(block: (Node) -> Unit) {
        scope.launch {
            try {
                call(block)
            } catch (e: Exception) {
                onError(e.message ?: "Something went wrong")
            }
        }
    }

    private fun model(c: ContactInfo, history: List<MessageInfo>) =
        Contact(c.name, c.verified, c.peek, c.unread.toInt()).apply { msgs.addAll(history.map { it.toMsg() }) }

    /** Opens or creates the database. Throws CoreException.WrongPassphrase if the key is wrong. */
    suspend fun open(passphrase: String) {
        val (n, loaded) = withContext(Dispatchers.Default) {
            val n = Node.start(vault.dbPath, vault.secret() + passphrase, this@Core)
            n to n.contacts().map { it to n.history(it.name) }
        }
        node = n
        fingerprint = call { it.fingerprint() }
        contacts.clear()
        contacts.addAll(loaded.map { (c, h) -> model(c, h) })
    }

    fun sendText(c: Contact, text: String, animate: Boolean) {
        scope.launch {
            try {
                c.msgs += call { it.sendText(c.name, text) }.toMsg(animate)
            } catch (e: Exception) {
                onError(e.message ?: "Couldn't send that")
            }
        }
    }

    fun markRead(c: Contact) {
        c.unread = 0
        fireAndForget { it.markRead(c.name) }
    }

    fun setPeek(c: Contact, on: Boolean) {
        c.peek = on
        fireAndForget { it.setPeek(c.name, on) }
    }

    fun markVerified(c: Contact) {
        c.verified = true
        fireAndForget { it.markVerified(c.name) }
    }

    suspend fun safetyNumber(c: Contact): List<String> = call { it.safetyNumber(c.name) }

    suspend fun startPairing(): PairingCode {
        pairWords = null
        pairError = ""
        pairBusy = false
        return call { it.startPairing() }
    }

    /** Throws CoreException.BadCode if the code isn't in a valid format, which doesn't burn anything. */
    suspend fun joinPairing(code: String) {
        pairWords = null
        pairError = ""
        call { it.joinPairing(code) }
        pairBusy = true
    }

    suspend fun confirmPairing(name: String, inPerson: Boolean): Contact {
        val info = call { it.confirmPairing(name, inPerson) }
        pairWords = null
        return model(info, emptyList()).also { contacts.add(0, it) }
    }

    fun cancelPairing() {
        pairWords = null
        pairError = ""
        pairBusy = false
        if (node != null) fireAndForget { it.cancelPairing() }
    }

    suspend fun wipe() {
        call { it.wipe() }
        node = null
        vault.forget()
        contacts.clear()
        fingerprint = ""
        openChat = null
    }

    // Events arrive on the node's thread. Hop to the UI before touching Compose state.
    override fun message(m: MessageInfo) {
        scope.launch {
            val c = find(m.contact) ?: return@launch
            c.msgs += m.toMsg(animate = true)
            if (openChat == c.name) markRead(c) else c.unread += 1
        }
    }

    override fun status(contact: String, seq: ULong, status: String) {
        scope.launch {
            val msg = find(contact)?.msgs?.find { it.mine && it.seq == seq.toLong() } ?: return@launch
            val next = DeliveryStatus.of(status) ?: return@launch
            if (msg.status != DeliveryStatus.Delivered) msg.status = next
        }
    }

    override fun link(contact: String, up: Boolean) {
        scope.launch { find(contact)?.online = up }
    }

    override fun pairingReady(words: List<String>) {
        scope.launch {
            pairBusy = false
            pairWords = words
        }
    }

    override fun pairingFailed(reason: String) {
        scope.launch {
            pairBusy = false
            pairError = reason
        }
    }
}
