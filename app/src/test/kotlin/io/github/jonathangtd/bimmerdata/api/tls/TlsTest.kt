/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.tls

import io.github.jonathangtd.bimmerdata.api.ApiAccess
import io.github.jonathangtd.bimmerdata.api.ApiServer
import io.github.jonathangtd.bimmerdata.api.ApiTokens
import io.github.jonathangtd.bimmerdata.api.FakeApiBackend
import io.github.jonathangtd.bimmerdata.api.Scope
import io.github.jonathangtd.bimmerdata.settings.httpsExamples
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class TlsTest {
    @TempDir lateinit var dir: File

    private val lan = listOf(ip("192.168.2.22"))
    private val token = ApiTokens.generate()
    private var server: ApiServer? = null
    private var backend: FakeApiBackend? = null

    @AfterEach
    fun stop() {
        server?.stop()
        backend?.close()
    }

    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    /** "Sealing" that really transforms the bytes, so a missing unseal would be noticed. */
    private fun store(file: File = File(dir, "api_tls.bin")) =
        TlsIdentityStore(file, seal = { it.reversedArray() }, unseal = { it.reversedArray() })

    // ---------------------------------------------------------------- certificates

    @Test
    fun `CA and server certificates are well formed`() {
        val id = store().ensure(lan)
        val ca = id.caCert
        val srv = id.serverCert
        val now = Instant.now()

        ca.verify(ca.publicKey)
        srv.verify(ca.publicKey)
        assertEquals(ca.subjectX500Principal, srv.issuerX500Principal)
        assertEquals(0, ca.basicConstraints) // CA, path length 0
        assertEquals(-1, srv.basicConstraints) // not a CA
        assertTrue(ca.keyUsage[5], "keyCertSign")
        assertTrue(srv.keyUsage[0], "digitalSignature")
        assertTrue("1.3.6.1.5.5.7.3.1" in srv.extendedKeyUsage)
        assertTrue("2.5.29.30" in ca.criticalExtensionOIDs, "name constraints are critical")
        assertEquals(3, ca.version)
        assertEquals("SHA256withECDSA", srv.sigAlgName)

        assertTrue(ca.notBefore.toInstant().isBefore(now) && srv.notBefore.toInstant().isBefore(now))
        assertTrue(ca.notAfter.toInstant().isAfter(now.plus(19 * 365L, ChronoUnit.DAYS)))
        assertTrue(srv.notAfter.toInstant().isAfter(now.plus(9 * 365L, ChronoUnit.DAYS)))
        assertFalse(srv.notAfter.after(ca.notAfter))

        assertEquals(listOf(ip("192.168.2.22")), TlsCertificates.sanAddresses(srv))
        assertEquals(listOf("localhost"), TlsCertificates.sanDnsNames(srv))
        val sanIps = srv.subjectAlternativeNames.filter { it[0] == 7 }.map { it[1] }
        assertTrue("127.0.0.1" in sanIps)

        // fingerprint formats
        val sha = MessageDigest.getInstance("SHA-256").digest(ca.encoded).joinToString(":") { "%02X".format(it) }
        assertEquals(sha, id.info.certificateSha256)
        assertTrue(Regex("sha256//[A-Za-z0-9+/]{43}=").matches(id.info.publicKeyPin))
        assertTrue(id.caPem().startsWith("-----BEGIN CERTIFICATE-----\n") && id.caPem().endsWith("-----END CERTIFICATE-----\n"))
        val reparsed = CertificateFactory.getInstance("X.509").generateCertificate(id.caPem().byteInputStream()) as X509Certificate
        assertEquals(ca, reparsed)
    }

    private fun pkix(ca: X509Certificate, cert: X509Certificate) {
        val path = CertificateFactory.getInstance("X.509").generateCertPath(listOf(cert))
        val params = PKIXParameters(setOf(TrustAnchor(ca, null))).apply { isRevocationEnabled = false }
        CertPathValidator.getInstance("PKIX").validate(path, params)
    }

    /**
     * Java's PKIX neither applies nor accepts name constraints on a trust anchor, so the bytes are
     * checked here; OpenSSL (curl, Python) does enforce them: see the e2e test with 8.8.8.8.
     */
    @Test
    fun `PKIX accepts the server certificate, the CA is constrained to private addresses and localhost`() {
        val id = store().ensure(lan + ip("10.1.2.3") + ip("172.20.0.5"))
        pkix(id.caCert, id.serverCert)

        val nc = id.caCert.getExtensionValue("2.5.29.30")!!.toList().map { it.toInt() and 0xFF }
        fun has(vararg bytes: Int) = nc.windowed(bytes.size).any { it == bytes.toList() }
        // GeneralSubtree { iPAddress [7] address + mask }
        assertTrue(has(0x30, 0x0A, 0x87, 0x08, 10, 0, 0, 0, 255, 0, 0, 0))
        assertTrue(has(0x30, 0x0A, 0x87, 0x08, 172, 16, 0, 0, 255, 240, 0, 0))
        assertTrue(has(0x30, 0x0A, 0x87, 0x08, 192, 168, 0, 0, 255, 255, 0, 0))
        assertTrue(has(0x30, 0x0A, 0x87, 0x08, 127, 0, 0, 0, 255, 0, 0, 0))
        assertTrue(has(0x30, 0x0B, 0x82, 0x09, *"localhost".map { it.code }.toIntArray()))
    }

    @Test
    fun `DER times switch to GeneralizedTime from 2050`() {
        assertEquals(0x17, Der.time(Instant.parse("2049-12-31T23:59:59Z"))[0].toInt())
        assertEquals(0x18, Der.time(Instant.parse("2050-01-01T00:00:00Z"))[0].toInt())
        assertEquals(listOf(0x06, 0x03, 0x55, 0x1D, 0x11), Der.oid("2.5.29.17").map { it.toInt() and 0xFF })
        assertEquals(listOf(0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02), Der.oid("1.2.840.10045.4.3.2").map { it.toInt() and 0xFF })
    }

    // ---------------------------------------------------------------- store

    @Test
    fun `identity survives a restart, a new address keeps the CA and the pin, regenerate changes both`() {
        val file = File(dir, "api_tls.bin")
        val first = store(file).ensure(lan)
        assertTrue(file.isFile)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("BEGIN"), "stored through seal()")

        val reloaded = store(file)
        val again = reloaded.current()!!
        assertEquals(first.info.certificateSha256, again.info.certificateSha256)
        assertEquals(first.info.publicKeyPin, again.info.publicKeyPin)
        assertEquals(first.serverCert, reloaded.ensure(lan).serverCert) // already covered: no re-issue

        val moved = reloaded.ensure(listOf(ip("10.0.0.7")))
        assertNotEquals(first.serverCert.serialNumber, moved.serverCert.serialNumber)
        assertEquals(first.info.certificateSha256, moved.info.certificateSha256)
        assertEquals(first.info.publicKeyPin, moved.info.publicKeyPin)
        assertEquals(setOf(ip("10.0.0.7"), ip("192.168.2.22")), TlsCertificates.sanAddresses(moved.serverCert).toSet())
        pkix(moved.caCert, moved.serverCert)

        val fresh = reloaded.regenerate(lan)
        assertNotEquals(first.info.certificateSha256, fresh.info.certificateSha256)
        assertNotEquals(first.info.publicKeyPin, fresh.info.publicKeyPin)
        assertEquals(fresh.info.publicKeyPin, store(file).current()!!.info.publicKeyPin)
    }

    @Test
    fun `an unreadable file gives a new identity instead of failing`() {
        val file = File(dir, "api_tls.bin")
        val first = store(file).ensure(lan)
        val broken = TlsIdentityStore(file, seal = { it }, unseal = { throw IOException("key lost") })
        assertNull(broken.current())
        val second = broken.ensure(lan)
        assertNotEquals(first.info.publicKeyPin, second.info.publicKeyPin)
    }

    // ---------------------------------------------------------------- real TLS server

    private fun startTls(id: TlsIdentity): Int {
        val b = FakeApiBackend().also { backend = it }
        val s = ApiServer(b, { ApiAccess(token, setOf(Scope.Read, Scope.Layout, Scope.Config)) })
        s.start(listOf(InetAddress.getByName("127.0.0.1")), 0, id.sslContext())
        server = s
        assertTrue(s.secure)
        return s.port
    }

    /** Client that trusts exactly one public key (what `curl --pinnedpubkey` does). */
    private fun pinningContext(pin: String): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw UnsupportedOperationException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                if (TlsCertificates.publicKeyPin(chain[0].publicKey) != pin) throw java.security.cert.CertificateException("pin mismatch")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    }

    /** Client that trusts the exported CA certificate (what `curl --cacert` does), with hostname checks. */
    private fun caContext(ca: X509Certificate): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("ca", ca) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    private fun get(ctx: SSLContext, url: String, auth: Boolean = true): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpsURLConnection
        c.sslSocketFactory = ctx.socketFactory
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        if (auth) c.setRequestProperty("Authorization", "Bearer $token")
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }.orEmpty()
        return code to body
    }

    @Test
    fun `HTTPS works with the CA or the pin, the token is still required, the spec says https`() {
        val id = store().ensure(lan)
        val port = startTls(id)
        val base = "https://127.0.0.1:$port/api/v1"

        val (code, body) = get(caContext(id.caCert), "$base/openapi.json", auth = false)
        assertEquals(200, code)
        val url = Json.parseToJsonElement(body).jsonObject["servers"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("https://127.0.0.1:$port", url)

        assertEquals(200, get(pinningContext(id.info.publicKeyPin), "$base/status").first)
        assertEquals(401, get(pinningContext(id.info.publicKeyPin), "$base/status", auth = false).first)
        // "localhost" is in the SAN too
        assertEquals(200, get(caContext(id.caCert), "https://localhost:$port/api/v1/status").first)
    }

    @Test
    fun `a wrong pin, an unknown CA or the default trust store are refused`() {
        val id = store().ensure(lan)
        val port = startTls(id)
        val url = "https://127.0.0.1:$port/api/v1/openapi.json"
        val other = TlsIdentityStore(File(dir, "other.bin"), { it }, { it }).ensure(lan)
        assertThrows(IOException::class.java) { get(pinningContext(other.info.publicKeyPin), url) }
        assertThrows(IOException::class.java) { get(caContext(other.caCert), url) }
        assertThrows(IOException::class.java) { get(SSLContext.getDefault(), url) }
    }

    @Test
    fun `only TLS 1_3 and 1_2 are offered`() {
        val id = store().ensure(lan)
        val port = startTls(id)
        fun handshake(vararg protocols: String): String {
            (pinningContext(id.info.publicKeyPin).socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { s ->
                s.soTimeout = 10_000
                s.enabledProtocols = protocols.filter { it in s.supportedProtocols }.toTypedArray()
                s.startHandshake()
                return s.session.protocol
            }
        }
        assertEquals("TLSv1.3", handshake("TLSv1.3", "TLSv1.2"))
        assertEquals("TLSv1.2", handshake("TLSv1.2"))
        assertThrows(Exception::class.java) { handshake("TLSv1.1") }
        assertThrows(Exception::class.java) { handshake("TLSv1") }
        assertEquals(TLS_PROTOCOLS, listOf("TLSv1.3", "TLSv1.2"))
    }

    @Test
    fun `plain HTTP on the HTTPS port gets no HTTP answer`() {
        val id = store().ensure(lan)
        val port = startTls(id)
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().apply { write("GET /api/v1/openapi.json HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); flush() }
            val got = try { s.getInputStream().readNBytes(5) } catch (_: IOException) { ByteArray(0) }
            assertFalse(String(got, Charsets.ISO_8859_1).startsWith("HTTP/"), "answered in clear text: ${got.toList()}")
        }
        // the server is still fine afterwards
        assertEquals(200, get(caContext(id.caCert), "https://127.0.0.1:$port/api/v1/openapi.json", auth = false).first)
    }

    @Test
    fun `client examples carry the pin, the base URL and no token`() {
        val ex = httpsExamples("https://192.168.2.22:8765/api/v1", "sha256//abc=")
        assertEquals(3, ex.size)
        assertTrue(ex[0].contains("--pinnedpubkey 'sha256//abc='") && ex[0].contains("https://192.168.2.22:8765/api/v1/status"))
        assertTrue(ex[1].contains("--cacert bimmerdata-api-ca.pem"))
        assertTrue(ex[2].contains("verify=\"bimmerdata-api-ca.pem\""))
        assertTrue(ex.all { it.contains("BIMMERDATA_TOKEN") })
    }

    // ---------------------------------------------------------------- real clients (optional)

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS)) { "timeout: ${cmd.joinToString(" ")}" }
        return p.exitValue() to out
    }

    /**
     * curl, Python (3.13 strict X.509 checks) and `openssl verify -x509_strict` against the real
     * server. Runs only with BIMMERDATA_TLS_E2E=1 (needs those tools on the PATH).
     */
    @Test
    fun `curl, python and openssl accept the certificate`() {
        assumeTrue(System.getenv("BIMMERDATA_TLS_E2E") == "1", "set BIMMERDATA_TLS_E2E=1")
        val id = store().ensure(lan)
        val port = startTls(id)
        val ca = File(dir, "bimmerdata-api-ca.pem").apply { writeText(id.caPem()) }
        val leaf = File(dir, "leaf.pem").apply { writeText(TlsCertificates.pem(id.serverCert)) }
        val base = "https://127.0.0.1:$port/api/v1"
        val auth = "Authorization: Bearer $token"

        val strict = run("openssl", "verify", "-x509_strict", "-CAfile", ca.path, leaf.path)
        assertEquals(0, strict.first, strict.second)
        // OpenSSL enforces the CA's name constraints: a certificate for a public address is refused.
        val rogue = File(dir, "rogue.pem").apply {
            writeText(TlsCertificates.pem(TlsCertificates.issueServer(TlsCertificates.newKeyPair().public, listOf(ip("8.8.8.8")), id.caCert, id.caKey)))
        }
        val refused = run("openssl", "verify", "-x509_strict", "-CAfile", ca.path, rogue.path)
        assertNotEquals(0, refused.first, refused.second)
        assertTrue(refused.second.contains("subtree", ignoreCase = true), refused.second)

        val cacert = run("curl", "-sS", "--fail", "--cacert", ca.path, "-H", auth, "$base/status")
        assertEquals(0, cacert.first, cacert.second)
        val pinned = run("curl", "-sS", "--fail", "-k", "--pinnedpubkey", id.info.publicKeyPin, "-H", auth, "$base/status")
        assertEquals(0, pinned.first, pinned.second)
        val wrongPin = run("curl", "-sS", "--fail", "-k", "--pinnedpubkey", "sha256//" + Base64.getEncoder().encodeToString(ByteArray(32)), "$base/openapi.json")
        assertNotEquals(0, wrongPin.first)
        val noTrust = run("curl", "-sS", "--fail", "$base/openapi.json")
        assertNotEquals(0, noTrust.first)
        val old = run("curl", "-sS", "--fail", "--cacert", ca.path, "--tlsv1.1", "--tls-max", "1.1", "$base/openapi.json")
        assertNotEquals(0, old.first)

        val py = run(
            "python3", "-I", "-c",
            "import ssl, sys, urllib.request\n" +
                "ctx = ssl.create_default_context(cafile=sys.argv[1])\n" +
                "print('strict', bool(ctx.verify_flags & ssl.VERIFY_X509_STRICT))\n" +
                "r = urllib.request.urlopen(urllib.request.Request(sys.argv[2], headers={'Authorization': 'Bearer ' + sys.argv[3]}), context=ctx)\n" +
                "print(r.status)",
            ca.path, "$base/status", token,
        )
        assertEquals(0, py.first, py.second)
        assertTrue(py.second.contains("200"), py.second)
        println("python: ${py.second.trim().replace('\n', ' ')}")

        // The exact Python example shown in the app (needs the requests package).
        if (run("python3", "-I", "-c", "import requests").first == 0) {
            val example = httpsExamples(base, id.info.publicKeyPin)[2]
            val r = ProcessBuilder("python3", "-I", "-c", example).directory(dir).redirectErrorStream(true)
                .apply { environment()["BIMMERDATA_TOKEN"] = token }.start()
            val out = r.inputStream.bufferedReader().readText()
            r.waitFor(30, TimeUnit.SECONDS)
            assertEquals(0, r.exitValue(), out)
            println("requests example: ${out.trim().take(80)}")
        }
        // The exact first curl example (pinned key, token from the environment).
        val curlExample = ProcessBuilder("bash", "-c", httpsExamples(base, id.info.publicKeyPin)[0] + " --fail -sS").redirectErrorStream(true)
            .apply { environment()["BIMMERDATA_TOKEN"] = token }.start()
        val curlOut = curlExample.inputStream.bufferedReader().readText()
        curlExample.waitFor(30, TimeUnit.SECONDS)
        assertEquals(0, curlExample.exitValue(), curlOut)
    }
}
