package com.axion.ultimatefetcher.network.diagnostics

import com.axion.ultimatefetcher.domain.models.DnsResolveResult
import com.axion.ultimatefetcher.network.vpn.PacketParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom

class DnsResolverEngine {

    private val rng = SecureRandom()

    suspend fun resolve(hostname: String, recordType: String, dnsServer: String, port: Int = 53): DnsResolveResult =
        withContext(Dispatchers.IO) {
            val cleanHost = hostname.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .substringBefore(":")
                .trimEnd('.')
            val cleanType = recordType.uppercase().trim().ifEmpty { "A" }
            val cleanServer = dnsServer.trim().ifEmpty { "1.1.1.1" }

            if (cleanHost.isEmpty() || !isValidHostname(cleanHost)) {
                return@withContext DnsResolveResult(
                    hostname = cleanHost,
                    recordType = cleanType,
                    dnsServer = cleanServer,
                    records = emptyList(),
                    latencyMs = -1L,
                    protocolUsed = "UDP_53",
                    error = "Enter a valid hostname (e.g., example.com)"
                )
            }

            if (cleanServer.equals("System", ignoreCase = true)) {
                return@withContext resolveWithSystem(cleanHost, cleanType, "SYSTEM_RESOLVER")
            }

            val txId = rng.nextInt(0xFFFF) + 1
            val udpResult = runCatching {
                val query = PacketParser.buildDnsQueryPacket(cleanHost, cleanType, txId)
                val serverAddr = InetAddress.getByName(cleanServer)
                val protoLabel = if (serverAddr is Inet6Address) "UDP_53_IPV6" else "UDP_53"
                DatagramSocket().use { socket ->
                    socket.soTimeout = 3500
                    val t0 = System.nanoTime()
                    socket.send(DatagramPacket(query, query.size, serverAddr, port.coerceIn(1, 65535)))
                    val buf = ByteArray(2048)
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    val latencyMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                    val parsed = PacketParser.parseDnsPayload(buf, 0, resp.length)
                        ?: error("Malformed DNS response packet")
                    if (!parsed.isResponse || parsed.transactionId != txId) {
                        error("DNS response transaction ID mismatch")
                    }
                    val rcodeErr = decodeRcodeError(parsed.rcode)
                    DnsResolveResult(
                        hostname = cleanHost,
                        recordType = cleanType,
                        dnsServer = cleanServer,
                        records = parsed.answers,
                        latencyMs = latencyMs,
                        protocolUsed = protoLabel,
                        error = rcodeErr ?: if (parsed.answers.isEmpty()) "No $cleanType records returned" else null
                    )
                }
            }

            udpResult.getOrElse { err ->
                if (err is java.io.IOException && port == 53 && (cleanType == "A" || cleanType == "AAAA")) {
                    resolveWithSystem(
                        hostname = cleanHost,
                        recordType = cleanType,
                        protocolLabel = "SYSTEM_RESOLVER (UDP:53 fallback)"
                    )
                } else {
                    DnsResolveResult(
                        hostname = cleanHost,
                        recordType = cleanType,
                        dnsServer = cleanServer,
                        records = emptyList(),
                        latencyMs = -1L,
                        protocolUsed = "UDP_53",
                        error = "DNS query failed: ${err.message ?: "Timeout / Unreachable"}"
                    )
                }
            }
        }

    private fun resolveWithSystem(
        hostname: String,
        recordType: String,
        protocolLabel: String
    ): DnsResolveResult {
        return runCatching {
            val t0 = System.nanoTime()
            val all = InetAddress.getAllByName(hostname)
            val latencyMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            val filtered = when (recordType) {
                "A" -> all.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                "AAAA" -> all.filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress }
                else -> all.mapNotNull { it.hostAddress }
            }
            DnsResolveResult(
                hostname = hostname,
                recordType = recordType,
                dnsServer = "System",
                records = filtered,
                latencyMs = latencyMs,
                protocolUsed = protocolLabel,
                error = if (filtered.isEmpty()) "No $recordType addresses resolved by system DNS" else null
            )
        }.getOrElse { err ->
            DnsResolveResult(
                hostname = hostname,
                recordType = recordType,
                dnsServer = "System",
                records = emptyList(),
                latencyMs = -1L,
                protocolUsed = protocolLabel,
                error = err.message ?: "System resolver failed"
            )
        }
    }

    companion object {
        fun isValidHostname(host: String): Boolean {
            if (host.isBlank() || host.length > 253) return false
            return host.all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '_' }
        }

        fun decodeRcodeError(rcode: Int): String? = when (rcode) {
            0 -> null
            1 -> "DNS FORMERR (Format Error)"
            2 -> "DNS SERVFAIL (Server Failure)"
            3 -> "DNS NXDOMAIN (Domain Does Not Exist)"
            4 -> "DNS NOTIMP (Not Implemented)"
            5 -> "DNS REFUSED (Query Refused by Server)"
            else -> "DNS Error (RCODE $rcode)"
        }
    }
}
