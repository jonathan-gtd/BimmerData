/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.tls

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.Inet4Address
import java.net.Socket
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/** The CA and the server certificate with their keys (see [TlsCertificates]). */
class TlsIdentity(
    val caCert: X509Certificate,
    internal val caKey: PrivateKey,
    val serverCert: X509Certificate,
    internal val serverKey: PrivateKey,
) {
    /** What clients trust or pin, shown in the app and in `GET /settings`. */
    val info: TlsInfo by lazy {
        TlsInfo(
            certificateSha256 = TlsCertificates.sha256Fingerprint(caCert),
            publicKeyPin = TlsCertificates.publicKeyPin(serverCert.publicKey),
            notAfterMs = caCert.notAfter.time,
            addresses = TlsCertificates.sanAddresses(serverCert).map { it.hostAddress.orEmpty() },
        )
    }

    /** PEM of the CA certificate: the file a client trusts (`curl --cacert`, `requests verify=`). */
    fun caPem(): String = TlsCertificates.pem(caCert)

    /** True when the server certificate names every address of [addresses]. */
    fun covers(addresses: List<Inet4Address>): Boolean {
        val listed = TlsCertificates.sanAddresses(serverCert).toSet()
        return addresses.filter { !it.isLoopbackAddress }.all { it in listed }
    }

    /** Server-side TLS context: chain = server certificate + CA, no client certificates. */
    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf(SingleKeyManager(serverKey, arrayOf(serverCert, caCert))), null, SecureRandom())
    }
}

/** Public facts about the TLS identity (no secret). */
data class TlsInfo(
    /** SHA-256 of the CA certificate, `AB:CD:…`; stays the same until the certificate is regenerated. */
    val certificateSha256: String,
    /** `sha256//…` of the server public key, for `curl --pinnedpubkey`; also stable. */
    val publicKeyPin: String,
    val notAfterMs: Long,
    /** Local-network addresses the server certificate is valid for (127.0.0.1 and localhost always are). */
    val addresses: List<String>,
)

/** Only these: TLS 1.0 / 1.1 are refused even where the platform still offers them. */
val TLS_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")

/** Restricts a server socket to [TLS_PROTOCOLS] and no client authentication. */
fun SSLServerSocket.restrictToModernTls() {
    val wanted = TLS_PROTOCOLS.filter { it in supportedProtocols }
    check(wanted.isNotEmpty()) { "Neither TLS 1.3 nor TLS 1.2 is available" }
    enabledProtocols = wanted.toTypedArray()
    needClientAuth = false
    wantClientAuth = false
}

/** Same restriction for a server-side [SSLSocket] layered on an accepted connection. */
fun SSLSocket.restrictToModernTls() {
    val wanted = TLS_PROTOCOLS.filter { it in supportedProtocols }
    check(wanted.isNotEmpty()) { "Neither TLS 1.3 nor TLS 1.2 is available" }
    enabledProtocols = wanted.toTypedArray()
    needClientAuth = false
    wantClientAuth = false
}

/** Hands one EC key and its chain to the TLS stack (JSSE on the JVM, Conscrypt on Android). */
private class SingleKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
    private fun matches(keyType: String?) = keyType == null || keyType.startsWith("EC", ignoreCase = true)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = if (matches(keyType)) arrayOf(ALIAS) else null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = if (matches(keyType)) ALIAS else null
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = if (matches(keyType)) ALIAS else null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) chain.clone() else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    companion object {
        const val ALIAS = "bimmerdata-api"
    }
}

/**
 * Keeps the [TlsIdentity] in one file, encrypted by [seal] / [unseal] (on Android: AES-256-GCM
 * with a key that never leaves the Android Keystore). Creates it on first use, re-issues the
 * server certificate (same key) when the phone has a new address, and regenerates everything on
 * request. If the file cannot be read (e.g. the Keystore key was lost after a restore) a new
 * identity is created: clients then see a new fingerprint. Thread safe.
 */
class TlsIdentityStore(
    private val file: File,
    private val seal: (ByteArray) -> ByteArray,
    private val unseal: (ByteArray) -> ByteArray,
    private val clock: () -> Instant = Instant::now,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    @Serializable
    private class Stored(val version: Int = 1, val caKey: String, val caCert: String, val serverKey: String, val serverCert: String)

    private val json = Json { ignoreUnknownKeys = true }
    private var cached: TlsIdentity? = null

    /** The identity if one exists (no creation). */
    @Synchronized
    fun current(): TlsIdentity? = cached ?: load()?.also { cached = it }

    /** The identity, created if needed, with a server certificate valid for [addresses]. */
    @Synchronized
    fun ensure(addresses: List<Inet4Address>): TlsIdentity {
        val existing = current()
        if (existing == null) return create(addresses)
        if (existing.covers(addresses) && existing.serverCert.notAfter.toInstant().isAfter(clock().plusSeconds(30L * 86400))) return existing
        // New address (or close to expiry): same keys, same CA, new server certificate.
        val kept = (addresses.filter { !it.isLoopbackAddress } + TlsCertificates.sanAddresses(existing.serverCert)).distinct().take(TlsCertificates.MAX_ADDRESSES)
        val cert = TlsCertificates.issueServer(existing.serverCert.publicKey, kept, existing.caCert, existing.caKey, clock())
        return save(TlsIdentity(existing.caCert, existing.caKey, cert, existing.serverKey))
    }

    /** New CA and new server key: every client must trust / pin again. */
    @Synchronized
    fun regenerate(addresses: List<Inet4Address>): TlsIdentity = create(addresses)

    private fun create(addresses: List<Inet4Address>): TlsIdentity {
        val caKeys = TlsCertificates.newKeyPair()
        val label = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val ca = TlsCertificates.newCa(caKeys, label, clock())
        val serverKeys = TlsCertificates.newKeyPair()
        val server = TlsCertificates.issueServer(serverKeys.public, addresses.filter { !it.isLoopbackAddress }.distinct().take(TlsCertificates.MAX_ADDRESSES), ca, caKeys.private, clock())
        return save(TlsIdentity(ca, caKeys.private, server, serverKeys.private))
    }

    private fun save(identity: TlsIdentity): TlsIdentity {
        val b64 = Base64.getEncoder()
        val stored = Stored(
            caKey = b64.encodeToString(identity.caKey.encoded), caCert = b64.encodeToString(identity.caCert.encoded),
            serverKey = b64.encodeToString(identity.serverKey.encoded), serverCert = b64.encodeToString(identity.serverCert.encoded),
        )
        val bytes = seal(json.encodeToString(Stored.serializer(), stored).toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "cannot write ${file.name}" } }
        cached = identity
        return identity
    }

    private fun load(): TlsIdentity? {
        if (!file.isFile) return null
        return try {
            val s = json.decodeFromString(Stored.serializer(), String(unseal(file.readBytes()), Charsets.UTF_8))
            val b64 = Base64.getDecoder()
            val kf = KeyFactory.getInstance("EC")
            TlsIdentity(
                TlsCertificates.parse(b64.decode(s.caCert)), kf.generatePrivate(PKCS8EncodedKeySpec(b64.decode(s.caKey))),
                TlsCertificates.parse(b64.decode(s.serverCert)), kf.generatePrivate(PKCS8EncodedKeySpec(b64.decode(s.serverKey))),
            )
        } catch (e: Exception) {
            log("TLS identity unreadable, a new one will be created", e)
            null
        }
    }
}
