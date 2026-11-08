/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.tls

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The phone's own small certificate authority for the local API:
 *
 * - a **CA certificate** (EC P-256, 20 years), the only thing a client needs to trust. It is
 *   limited by a critical *name constraints* extension to private IPv4 addresses
 *   (10/8, 172.16/12, 192.168/16, 127/8) and `localhost`: even if someone installs it as a trusted
 *   root, it cannot vouch for any internet site;
 * - a **server certificate** (EC P-256, 10 years) signed by that CA, whose SAN lists `localhost`,
 *   127.0.0.1 and the phone's local-network addresses. When the phone gets a new address the
 *   server certificate is re-issued **with the same key**, so the public-key pin
 *   (`curl --pinnedpubkey`) and the CA stay valid.
 *
 * Plain JCA (`EC` key pairs, `SHA256withECDSA`), identical on the JVM and on Android.
 */
object TlsCertificates {
    const val CA_YEARS = 20L
    const val SERVER_YEARS = 10L

    /** Most addresses kept in the server certificate (oldest dropped first). */
    const val MAX_ADDRESSES = 16

    private const val OID_EC_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_CN = "2.5.4.3"
    private const val OID_O = "2.5.4.10"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val OID_SUBJECT_KEY_ID = "2.5.29.14"
    private const val OID_AUTHORITY_KEY_ID = "2.5.29.35"
    private const val OID_SAN = "2.5.29.17"
    private const val OID_NAME_CONSTRAINTS = "2.5.29.30"

    /** Private IPv4 ranges (address, prefix length) the CA may vouch for. */
    private val PERMITTED_IPV4 = listOf("10.0.0.0" to 8, "172.16.0.0" to 12, "192.168.0.0" to 16, "127.0.0.0" to 8)

    private val random = SecureRandom()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** A new CA certificate for [key]; [label] makes the subject unique (e.g. a short random id). */
    fun newCa(key: KeyPair, label: String, now: Instant = Instant.now()): X509Certificate {
        val name = name("BimmerData local CA $label")
        val ski = keyId(key.public)
        val subtrees = PERMITTED_IPV4.map { (addr, prefix) ->
            Der.seq(Der.implicit(7, InetAddress.getByName(addr).address + mask(prefix)))
        } + Der.seq(Der.implicit(2, "localhost".toByteArray(Charsets.US_ASCII)))
        val extensions = listOf(
            ext(OID_BASIC_CONSTRAINTS, true, Der.seq(Der.bool(true), Der.integer(0))),
            // keyCertSign (bit 5) + cRLSign (bit 6) -> 0000 0110, 1 unused bit
            ext(OID_KEY_USAGE, true, Der.bits(byteArrayOf(0x06), unusedBits = 1)),
            ext(OID_SUBJECT_KEY_ID, false, Der.octets(ski)),
            ext(OID_NAME_CONSTRAINTS, true, Der.seq(Der.implicitConstructed(0, *subtrees.toTypedArray()))),
        )
        return sign(name, name, key.public, key.private, now.minus(Duration.ofDays(1)), years(now, CA_YEARS), extensions)
    }

    /** A server certificate for [serverKey], signed by the CA, valid for [addresses] + localhost. */
    fun issueServer(
        serverKey: PublicKey,
        addresses: List<Inet4Address>,
        caCert: X509Certificate,
        caKey: PrivateKey,
        now: Instant = Instant.now(),
    ): X509Certificate {
        val sans = listOf(Der.implicit(2, "localhost".toByteArray(Charsets.US_ASCII))) +
            (listOf(InetAddress.getByName("127.0.0.1") as Inet4Address) + addresses).distinct().map { Der.implicit(7, it.address) }
        val notAfter = minOf(years(now, SERVER_YEARS), caCert.notAfter.toInstant())
        val extensions = listOf(
            ext(OID_BASIC_CONSTRAINTS, true, Der.seq()),
            // digitalSignature (bit 0) -> 1000 0000, 7 unused bits
            ext(OID_KEY_USAGE, true, Der.bits(byteArrayOf(0x80.toByte()), unusedBits = 7)),
            ext(OID_EXT_KEY_USAGE, false, Der.seq(Der.oid(OID_SERVER_AUTH))),
            ext(OID_SUBJECT_KEY_ID, false, Der.octets(keyId(serverKey))),
            ext(OID_AUTHORITY_KEY_ID, false, Der.seq(Der.implicit(0, keyId(caCert.publicKey)))),
            ext(OID_SAN, false, Der.seq(*sans.toTypedArray())),
        )
        return sign(name("BimmerData API"), subjectOf(caCert), serverKey, caKey, now.minus(Duration.ofDays(1)), notAfter, extensions)
    }

    /** IPv4 addresses listed in the SAN of [cert], in certificate order (127.0.0.1 excluded). */
    fun sanAddresses(cert: X509Certificate): List<Inet4Address> =
        cert.subjectAlternativeNames.orEmpty()
            .filter { it[0] == 7 }
            .mapNotNull { runCatching { InetAddress.getByName(it[1] as String) }.getOrNull() as? Inet4Address }
            .filter { !it.isLoopbackAddress }

    fun sanDnsNames(cert: X509Certificate): List<String> =
        cert.subjectAlternativeNames.orEmpty().filter { it[0] == 2 }.map { it[1] as String }

    /** `AB:CD:…` SHA-256 of the DER certificate, as `openssl x509 -fingerprint -sha256` prints it. */
    fun sha256Fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** `sha256//<base64>` of the SubjectPublicKeyInfo: the format of `curl --pinnedpubkey`. */
    fun publicKeyPin(key: PublicKey): String =
        "sha256//" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded))

    fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) +
            "\n-----END CERTIFICATE-----\n"

    fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    // ---------------------------------------------------------------- internals

    private fun years(now: Instant, n: Long): Instant = now.atZone(java.time.ZoneOffset.UTC).plusYears(n).toInstant()

    private fun name(cn: String): ByteArray = Der.seq(
        Der.set(Der.seq(Der.oid(OID_O), Der.utf8("BimmerData"))),
        Der.set(Der.seq(Der.oid(OID_CN), Der.utf8(cn))),
    )

    /** The DER subject of an existing certificate, reused byte for byte as the issuer. */
    private fun subjectOf(cert: X509Certificate): ByteArray = cert.subjectX500Principal.encoded

    private fun mask(prefix: Int): ByteArray {
        val m = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return byteArrayOf((m shr 24).toByte(), (m shr 16).toByte(), (m shr 8).toByte(), m.toByte())
    }

    /** RFC 5280 4.2.1.2 method 1: SHA-1 of the subjectPublicKey BIT STRING contents. */
    private fun keyId(key: PublicKey): ByteArray = MessageDigest.getInstance("SHA-1").digest(subjectPublicKeyBits(key.encoded))

    /** The bytes of the BIT STRING inside a DER SubjectPublicKeyInfo (without the unused-bits byte). */
    private fun subjectPublicKeyBits(spki: ByteArray): ByteArray {
        var i = 0
        fun readLen(): Int {
            val first = spki[i++].toInt() and 0xFF
            if (first < 0x80) return first
            var n = 0
            repeat(first and 0x7F) { n = (n shl 8) or (spki[i++].toInt() and 0xFF) }
            return n
        }
        check(spki[i++].toInt() == 0x30); readLen() // SubjectPublicKeyInfo
        check(spki[i++].toInt() == 0x30); val algorithmLength = readLen(); i += algorithmLength // AlgorithmIdentifier (skipped)
        check(spki[i++].toInt() == 0x03); val len = readLen()
        return spki.copyOfRange(i + 1, i + len)
    }

    private fun ext(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) Der.seq(Der.oid(oid), Der.bool(true), Der.octets(value)) else Der.seq(Der.oid(oid), Der.octets(value))

    private fun serial(): BigInteger {
        val b = ByteArray(16)
        random.nextBytes(b)
        b[0] = (b[0].toInt() and 0x7F or 0x01).toByte() // positive, never zero, always 16 bytes
        return BigInteger(1, b)
    }

    private fun sign(
        subject: ByteArray,
        issuer: ByteArray,
        subjectKey: PublicKey,
        issuerKey: PrivateKey,
        notBefore: Instant,
        notAfter: Instant,
        extensions: List<ByteArray>,
    ): X509Certificate {
        val algorithm = Der.seq(Der.oid(OID_EC_SHA256))
        val tbs = Der.seq(
            Der.explicit(0, Der.integer(2)), // v3
            Der.integer(serial()),
            algorithm,
            issuer,
            Der.seq(Der.time(notBefore), Der.time(notAfter)),
            subject,
            subjectKey.encoded, // SubjectPublicKeyInfo, already DER
            Der.explicit(3, Der.seq(*extensions.toTypedArray())),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(issuerKey)
            update(tbs)
            sign()
        }
        return parse(Der.seq(tbs, algorithm, Der.bits(signature)))
    }
}
