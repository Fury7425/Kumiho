package kumiho

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun formatTime(atMs: Long): String {
    val t = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())
    return t.format(DateTimeFormatter.ofPattern(if (t.toLocalDate() == LocalDate.now()) "HH:mm" else "MMM d"))
}

actual val blurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
actual fun SystemBack(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

// Touch scrolling needs no scrollbar.
@Composable
actual fun VScroll(state: ScrollState, modifier: Modifier) = Unit

@Composable
actual fun VScroll(state: LazyListState, modifier: Modifier) = Unit

private const val ALIAS = "kumiho-db-key"
private const val IV_BYTES = 12

/** A random secret, sealed by a non-exportable Android Keystore key. */
// ponytail: no StrongBox request yet, since it throws on phones without one and needs a fallback path.
private class AndroidVault(dir: File) : Vault {
    override val dbPath = File(dir, "kumiho.db").path
    override val hasDatabase get() = File(dbPath).exists()
    override val seals = true
    private val sealedFile = File(dir, "key.sealed")
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun keystoreKey(): SecretKey {
        (store.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    override fun secret(): String {
        val raw = if (sealedFile.exists()) {
            val sealed = sealedFile.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } else {
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            sealedFile.writeBytes(cipher.iv + cipher.doFinal(fresh))
            fresh
        }
        return raw.joinToString("") { "%02x".format(it) }
    }

    override fun forget() {
        sealedFile.delete()
        store.deleteEntry(ALIAS)
    }
}

class MainActivity : ComponentActivity() {
    private val networkWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { NetworkEvents.onChange?.invoke() }
        override fun onLost(network: Network) { NetworkEvents.onChange?.invoke() }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { NetworkEvents.onChange?.invoke() }
    }

    override fun onDestroy() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkWatch)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkWatch)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val reduceMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val vault = AndroidVault(filesDir)
        setContent { App(vault, captureBlocked = true, reduceMotion = reduceMotion) }
    }
}
