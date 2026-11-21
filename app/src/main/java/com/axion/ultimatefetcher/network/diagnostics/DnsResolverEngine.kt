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

class DnsResolverEngine {

    suspend fun resolve(hostname: String, recordType: String, dnsServer: String): DnsResolveResult =
        withContext(Dispatchers.IO) {
            val cleanHost = hostname.trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
            val cleanType = recordType.uppercase().trim().ifEmpty { "A" }
            val cleanServer = dnsServer.trim().ifEmpty { "1.1.1.1" }

            if (cleanHost.isEmpty()) {
                return@withContext DnsResolveResult(
                    hostname = cleanHost,
                    recordType = cleanType,
                    dnsServer = cleanServer,
                    records = emptyList(),
                    latencyMs = -1L,
                    protocolUsed = "UDP_53",
                    error = "Enter a valid hostname"
                )
            }

            if (cleanServer.equals("System", ignoreCase = true)) {
                return@withContext resolveWithSystem(cleanHost, cleanType)
            }

            val udpResult = runCatching {
                val query = PacketParser.buildDnsQueryPacket(cleanHost, cleanType)
                val serverAddr = InetAddress.getByName(cleanServer)
                DatagramSocket().use { socket ->
                    socket.soTimeout = 3500
                    val t0 = System.nanoTime()
                    socket.send(DatagramPacket(query, query.size, serverAddr, 53))
                    val buf = ByteArray(2048)
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    val latencyMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                    val parsed = PacketParser.parseDnsPayload(buf, 0, resp.length)
                    DnsResolveResult(
                        hostname = cleanHost,
                        recordType = cleanType,
                        dnsServer = cleanServer,
                        records = parsed?.answers ?: emptyList(),
                        latencyMs = latencyMs,
                        protocolUsed = "UDP_53",
                        error = if (parsed?.answers.isNullOrEmpty()) "No $cleanType records returned" else null
                    )
                }
            }

            udpResult.getOrElse { err ->
                if (cleanType == "A" || cleanType == "AAAA") {
                    resolveWithSystem(cleanHost, cleanType, fallbackNote = "UDP:53 failed (${err.message}); used System Resolver")
                } else {
                    DnsResolveResult(
                        hostname = cleanHost,
                        recordType = cleanType,
                        dnsServer = cleanServer,
                        records = emptyList(),
                        latencyMs = -1L,
                        protocolUsed = "UDP_53",
                        error = "DNS query failed: ${err.message}"
                    )
                }
            }
        }

    private fun resolveWithSystem(hostname: String, recordType: String, fallbackNote: String? = null): DnsResolveResult {
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
                protocolUsed = "SYSTEM_RESOLVER",
                error = fallbackNote ?: if (filtered.isEmpty()) "No $recordType addresses resolved" else null
            )
        }.getOrElse { err ->
            DnsResolveResult(
                hostname = hostname,
                recordType = recordType,
                dnsServer = "System",
                records = emptyList(),
                latencyMs = -1L,
                protocolUsed = "SYSTEM_RESOLVER",
                error = err.message ?: "System resolver failed"
            )
        }
    }
}
