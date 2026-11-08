/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.api.tls

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Just enough DER (ITU-T X.690) to write an X.509 v3 certificate: no parser, no dependency.
 * Every function returns a complete TLV.
 */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n <= 0xFF -> { out.write(0x81); out.write(n) }
            n <= 0xFFFF -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun integer(v: Long) = integer(BigInteger.valueOf(v))
    fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))
    fun octets(v: ByteArray) = tlv(0x04, v)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    /** BIT STRING with [unusedBits] trailing zero bits in the last byte. */
    fun bits(v: ByteArray, unusedBits: Int = 0) = tlv(0x03, byteArrayOf(unusedBits.toByte()) + v)

    /** `[n] EXPLICIT`: constructed context-specific tag around a complete TLV. */
    fun explicit(n: Int, inner: ByteArray) = tlv(0xA0 or n, inner)

    /** `[n] IMPLICIT` primitive context-specific tag (GeneralName dNSName = 2, iPAddress = 7...). */
    fun implicit(n: Int, content: ByteArray) = tlv(0x80 or n, content)

    /** `[n] IMPLICIT` constructed context-specific tag around several TLVs. */
    fun implicitConstructed(n: Int, vararg parts: ByteArray) = tlv(0xA0 or n, concat(parts))

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2)
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            var v = arc
            val stack = ArrayList<Int>()
            stack += (v and 0x7F).toInt()
            v = v shr 7
            while (v > 0) { stack += ((v and 0x7F) or 0x80).toInt(); v = v shr 7 }
            for (b in stack.asReversed()) out.write(b)
        }
        return tlv(0x06, out.toByteArray())
    }

    private val utcTime = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)
    private val generalizedTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)

    /** RFC 5280 4.1.2.5: UTCTime until 2049, GeneralizedTime from 2050. */
    fun time(t: Instant): ByteArray {
        val year = t.atZone(ZoneOffset.UTC).year
        return if (year < 2050) tlv(0x17, utcTime.format(t).toByteArray(Charsets.US_ASCII))
        else tlv(0x18, generalizedTime.format(t).toByteArray(Charsets.US_ASCII))
    }
}
