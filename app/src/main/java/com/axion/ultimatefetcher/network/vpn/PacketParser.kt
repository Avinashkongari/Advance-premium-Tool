package com.axion.ultimatefetcher.network.vpn

import java.io.ByteArrayOutputStream
import java.net.InetAddress

data class ParsedPacket(
    val ipVersion: Int,
    val protocol: String,
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    val packetSize: Int,
    val payloadOffset: Int,
    val payloadLength: Int
)

data class ParsedDnsPacket(
    val transactionId: Int,
    val isResponse: Boolean,
    val queryName: String,
    val queryType: String,
    val answers: List<String>
)

data class ParsedTlsClientHello(
    val tlsVersion: String,
    val sniHostname: String?,
    val alpnProtocols: List<String>,
    val hasEchExtension: Boolean
)

object PacketParser {

    fun parseIpPacket(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 20 || buffer.size < length) return null
        val version = (buffer[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> parseIpv4(buffer, length)
            6 -> parseIpv6(buffer, length)
            else -> null
        }
    }

    private fun parseIpv4(buf: ByteArray, length: Int): ParsedPacket? {
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return null
        val protoNum = buf[9].toInt() and 0xFF
        val srcIp = "${buf[12].toInt() and 0xFF}.${buf[13].toInt() and 0xFF}.${buf[14].toInt() and 0xFF}.${buf[15].toInt() and 0xFF}"
        val dstIp = "${buf[16].toInt() and 0xFF}.${buf[17].toInt() and 0xFF}.${buf[18].toInt() and 0xFF}.${buf[19].toInt() and 0xFF}"
        return parseTransport(4, protoNum, srcIp, dstIp, buf, ihl, length)
    }

    private fun parseIpv6(buf: ByteArray, length: Int): ParsedPacket? {
        if (length < 40) return null
        val nextHeader = buf[6].toInt() and 0xFF
        val srcIp = runCatching { InetAddress.getByAddress(buf.copyOfRange(8, 24)).hostAddress ?: "::" }.getOrDefault("::")
        val dstIp = runCatching { InetAddress.getByAddress(buf.copyOfRange(24, 40)).hostAddress ?: "::" }.getOrDefault("::")
        return parseTransport(6, nextHeader, srcIp, dstIp, buf, 40, length)
    }

    private fun parseTransport(
        ipVersion: Int,
        protoNum: Int,
        srcIp: String,
        dstIp: String,
        buf: ByteArray,
        transportOffset: Int,
        length: Int
    ): ParsedPacket? {
        return when (protoNum) {
            6 -> { // TCP
                if (length < transportOffset + 20) return null
                val srcPort = readU16(buf, transportOffset)
                val dstPort = readU16(buf, transportOffset + 2)
                val dataOffset = ((buf[transportOffset + 12].toInt() ushr 4) and 0x0F) * 4
                val payloadOff = (transportOffset + dataOffset).coerceAtMost(length)
                val payloadLen = (length - payloadOff).coerceAtLeast(0)
                val label = when {
                    dstPort == 53 || srcPort == 53 -> "DNS"
                    else -> "TCP"
                }
                ParsedPacket(ipVersion, label, srcIp, srcPort, dstIp, dstPort, length, payloadOff, payloadLen)
            }
            17 -> { // UDP
                if (length < transportOffset + 8) return null
                val srcPort = readU16(buf, transportOffset)
                val dstPort = readU16(buf, transportOffset + 2)
                val payloadOff = transportOffset + 8
                val payloadLen = (length - payloadOff).coerceAtLeast(0)
                val label = when {
                    dstPort == 53 || srcPort == 53 -> "DNS"
                    dstPort == 443 || srcPort == 443 -> "QUIC"
                    else -> "UDP"
                }
                ParsedPacket(ipVersion, label, srcIp, srcPort, dstIp, dstPort, length, payloadOff, payloadLen)
            }
            1, 58 -> ParsedPacket(ipVersion, "ICMP", srcIp, 0, dstIp, 0, length, transportOffset, (length - transportOffset).coerceAtLeast(0))
            else -> ParsedPacket(ipVersion, "IP_$protoNum", srcIp, 0, dstIp, 0, length, transportOffset, (length - transportOffset).coerceAtLeast(0))
        }
    }

    fun buildDnsQueryPacket(hostname: String, recordType: String, transactionId: Int = (System.nanoTime() and 0xFFFF).toInt()): ByteArray {
        val out = ByteArrayOutputStream()
        // Header (12 bytes)
        out.write((transactionId ushr 8) and 0xFF)
        out.write(transactionId and 0xFF)
        out.write(0x01); out.write(0x00) // Standard query, RD=1
        out.write(0x00); out.write(0x01) // QDCOUNT=1
        out.write(0x00); out.write(0x00) // ANCOUNT=0
        out.write(0x00); out.write(0x00) // NSCOUNT=0
        out.write(0x00); out.write(0x00) // ARCOUNT=0

        // Question QNAME
        hostname.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size.coerceAtMost(63))
            out.write(bytes, 0, bytes.size.coerceAtMost(63))
        }
        out.write(0x00) // Root terminator

        val qType = when (recordType.uppercase()) {
            "A" -> 1
            "CNAME" -> 5
            "MX" -> 15
            "TXT" -> 16
            "AAAA" -> 28
            else -> 1
        }
        out.write((qType ushr 8) and 0xFF)
        out.write(qType and 0xFF)
        out.write(0x00); out.write(0x01) // QCLASS=IN
        return out.toByteArray()
    }

    fun parseDnsPayload(buf: ByteArray, offset: Int, length: Int): ParsedDnsPacket? {
        if (length < 12 || offset < 0 || offset + length > buf.size) return null
        val txId = readU16(buf, offset)
        val flags = readU16(buf, offset + 2)
        val isResponse = (flags and 0x8000) != 0
        val qdCount = readU16(buf, offset + 4)
        val anCount = readU16(buf, offset + 6)
        if (qdCount <= 0) return null

        val end = offset + length
        val (qName, nextPos) = readDnsName(buf, offset, offset + 12, end) ?: return null
        if (nextPos + 4 > end) return null
        val qTypeCode = readU16(buf, nextPos)
        val qType = dnsTypeName(qTypeCode)
        var cursor = nextPos + 4

        // Skip remaining questions if any
        for (i in 1 until qdCount) {
            val (_, pos) = readDnsName(buf, offset, cursor, end) ?: break
            cursor = pos + 4
            if (cursor > end) break
        }

        val answers = mutableListOf<String>()
        if (isResponse && anCount > 0) {
            for (i in 0 until anCount) {
                if (cursor >= end) break
                val (_, afterName) = readDnsName(buf, offset, cursor, end) ?: break
                if (afterName + 10 > end) break
                val rType = readU16(buf, afterName)
                val rdLength = readU16(buf, afterName + 8)
                val rdataStart = afterName + 10
                if (rdataStart + rdLength > end) break

                val decoded = when (rType) {
                    1 -> if (rdLength == 4) {
                        "${buf[rdataStart].toInt() and 0xFF}.${buf[rdataStart + 1].toInt() and 0xFF}.${buf[rdataStart + 2].toInt() and 0xFF}.${buf[rdataStart + 3].toInt() and 0xFF}"
                    } else null
                    28 -> if (rdLength == 16) {
                        runCatching { InetAddress.getByAddress(buf.copyOfRange(rdataStart, rdataStart + 16)).hostAddress }.getOrNull()
                    } else null
                    5 -> readDnsName(buf, offset, rdataStart, end)?.first
                    15 -> if (rdLength > 2) {
                        val pref = readU16(buf, rdataStart)
                        val ex = readDnsName(buf, offset, rdataStart + 2, end)?.first
                        if (ex != null) "$pref $ex" else null
                    } else null
                    16 -> {
                        val sb = StringBuilder()
                        var p = rdataStart
                        val rEnd = rdataStart + rdLength
                        while (p < rEnd) {
                            val txtLen = buf[p].toInt() and 0xFF
                            p++
                            if (p + txtLen > rEnd) break
                            sb.append(String(buf, p, txtLen, Charsets.UTF_8))
                            p += txtLen
                        }
                        sb.toString().ifEmpty { null }
                    }
                    else -> null
                }
                if (!decoded.isNullOrBlank()) answers.add(decoded)
                cursor = rdataStart + rdLength
            }
        }

        return ParsedDnsPacket(
            transactionId = txId,
            isResponse = isResponse,
            queryName = qName,
            queryType = qType,
            answers = answers
        )
    }

    private fun readDnsName(buf: ByteArray, baseOffset: Int, startPos: Int, end: Int): Pair<String, Int>? {
        val parts = mutableListOf<String>()
        var pos = startPos
        var jumped = false
        var returnPos = startPos
        var guard = 0

        while (pos < end && guard < 64) {
            guard++
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) returnPos = pos + 1
                return parts.joinToString(".") to returnPos
            }
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= end) return null
                val ptr = ((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                if (!jumped) {
                    returnPos = pos + 2
                    jumped = true
                }
                val target = baseOffset + ptr
                if (target < baseOffset || target >= end) return null
                pos = target
            } else {
                pos++
                if (pos + len > end) return null
                parts.add(String(buf, pos, len, Charsets.US_ASCII))
                pos += len
                if (!jumped) returnPos = pos
            }
        }
        return null
    }

    private fun dnsTypeName(code: Int): String = when (code) {
        1 -> "A"
        2 -> "NS"
        5 -> "CNAME"
        6 -> "SOA"
        12 -> "PTR"
        15 -> "MX"
        16 -> "TXT"
        28 -> "AAAA"
        33 -> "SRV"
        65 -> "HTTPS"
        else -> "TYPE$code"
    }

    fun parseTlsClientHello(buf: ByteArray, offset: Int, length: Int): ParsedTlsClientHello? {
        if (length < 43 || offset < 0 || offset + length > buf.size) return null
        val end = offset + length
        // TLS Record Header: ContentType=0x16 (Handshake)
        if ((buf[offset].toInt() and 0xFF) != 0x16) return null
        val recordVerMajor = buf[offset + 1].toInt() and 0xFF
        val recordVerMinor = buf[offset + 2].toInt() and 0xFF
        if (recordVerMajor != 0x03) return null
        // Handshake Type=0x01 (ClientHello)
        if ((buf[offset + 5].toInt() and 0xFF) != 0x01) return null

        var pos = offset + 9 // Skip Record(5) + HandshakeType(1) + Length(3)
        if (pos + 34 > end) return null
        val chMajor = buf[pos].toInt() and 0xFF
        val chMinor = buf[pos + 1].toInt() and 0xFF
        pos += 2 + 32 // Client version + Random

        if (pos >= end) return null
        val sessionIdLen = buf[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen
        if (pos + 2 > end) return null

        val cipherSuitesLen = readU16(buf, pos)
        pos += 2 + cipherSuitesLen
        if (pos >= end) return null

        val compressionLen = buf[pos].toInt() and 0xFF
        pos += 1 + compressionLen
        if (pos + 2 > end) return null

        val extTotalLen = readU16(buf, pos)
        pos += 2
        val extEnd = (pos + extTotalLen).coerceAtMost(end)

        var sni: String? = null
        val alpn = mutableListOf<String>()
        var hasEch = false
        var tlsVersion = formatTlsVersion(chMajor, chMinor, recordVerMinor)

        while (pos + 4 <= extEnd) {
            val extType = readU16(buf, pos)
            val extLen = readU16(buf, pos + 2)
            pos += 4
            if (pos + extLen > extEnd) break

            when (extType) {
                0x0000 -> { // SNI
                    if (extLen >= 5) {
                        val nameType = buf[pos + 2].toInt() and 0xFF
                        val nameLen = readU16(buf, pos + 3)
                        if (nameType == 0 && pos + 5 + nameLen <= pos + extLen) {
                            sni = String(buf, pos + 5, nameLen, Charsets.US_ASCII)
                        }
                    }
                }
                0x0010 -> { // ALPN
                    if (extLen >= 2) {
                        val listLen = readU16(buf, pos)
                        var p = pos + 2
                        val listEnd = (p + listLen).coerceAtMost(pos + extLen)
                        while (p < listEnd) {
                            val protoLen = buf[p].toInt() and 0xFF
                            p++
                            if (p + protoLen > listEnd) break
                            alpn.add(String(buf, p, protoLen, Charsets.US_ASCII))
                            p += protoLen
                        }
                    }
                }
                0x002B -> { // supported_versions
                    if (extLen >= 3) {
                        val svLen = buf[pos].toInt() and 0xFF
                        var p = pos + 1
                        val svEnd = (p + svLen).coerceAtMost(pos + extLen)
                        while (p + 1 < svEnd) {
                            val v = readU16(buf, p)
                            if (v == 0x0304) tlsVersion = "TLS 1.3"
                            p += 2
                        }
                    }
                }
                0xFE0D -> { // Encrypted ClientHello (ECH)
                    hasEch = true
                }
            }
            pos += extLen
        }

        return ParsedTlsClientHello(
            tlsVersion = tlsVersion,
            sniHostname = sni,
            alpnProtocols = alpn,
            hasEchExtension = hasEch
        )
    }

    private fun formatTlsVersion(major: Int, minor: Int, recordMinor: Int): String = when {
        major == 3 && minor == 4 -> "TLS 1.3"
        major == 3 && minor == 3 -> "TLS 1.2"
        major == 3 && minor == 2 -> "TLS 1.1"
        major == 3 && minor == 1 -> "TLS 1.0"
        recordMinor == 3 -> "TLS 1.2"
        else -> "TLS"
    }

    private fun readU16(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }
}
