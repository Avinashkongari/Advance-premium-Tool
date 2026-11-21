package com.axion.ultimatefetcher.network.vpn

import com.axion.ultimatefetcher.data.database.entities.DNSLog
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.database.entities.TLSLog
import com.axion.ultimatefetcher.domain.models.PipelineStageStatus
import com.axion.ultimatefetcher.domain.models.VpnLiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

data class RawPacketFrame(
    val bytes: ByteArray,
    val length: Int,
    val capturedAt: Long = System.currentTimeMillis()
)

interface PacketCaptureSink {
    suspend fun insertPackets(items: List<PacketLog>)
    suspend fun insertDnsLogs(items: List<DNSLog>)
    suspend fun insertTlsLogs(items: List<TLSLog>)
}

/**
 * Decoupled 3-stage non-blocking packet capture pipeline:
 *  1) Reader coroutine (IO): reads raw IP frames continuously from TUN without waiting on DNS or Room.
 *  2) Processor coroutine (Default): parses IPv4/IPv6/TCP/UDP/DNS/TLS, rejects malformed packets safely,
 *     deduplicates instant TUN echoes, triggers async DNS relay, and emits DB items.
 *  3) Database Writer coroutine (IO): batches and persists packets every 12 items OR 350ms + guaranteed flush on stop.
 */
class PacketCapturePipeline(
    private val scope: CoroutineScope,
    private val liveState: MutableStateFlow<VpnLiveState>,
    private val sink: PacketCaptureSink,
    private val quicBlockFlag: AtomicBoolean,
    private val appScopeLabel: String,
    private val onDnsQueryObserved: ((ParsedPacket, ByteArray, ParsedDnsPacket) -> Unit)? = null,
    private val batchSize: Int = 12,
    private val flushIntervalMs: Long = 350L
) {
    private val rawFrameChannel = Channel<RawPacketFrame>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val packetWriteChannel = Channel<PacketLog>(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val dnsWriteChannel = Channel<DNSLog>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val tlsWriteChannel = Channel<TLSLog>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var readerJob: Job? = null
    private var processorJob: Job? = null
    private var writerJob: Job? = null

    fun start(inputStream: InputStream) {
        liveState.update {
            it.copy(
                packetReadingState = PipelineStageStatus.WAITING,
                packetProcessingState = PipelineStageStatus.WAITING,
                databaseSavingState = PipelineStageStatus.WAITING
            )
        }

        // Stage 3: Non-blocking Room batch writer with periodic flush + final flush on close
        writerJob = scope.launch(Dispatchers.IO) {
            val pktBatch = ArrayList<PacketLog>(batchSize)
            val dnsBatch = ArrayList<DNSLog>(16)
            val tlsBatch = ArrayList<TLSLog>(16)
            var lastFlushAt = System.currentTimeMillis()

            suspend fun flushAll() {
                if (pktBatch.isEmpty() && dnsBatch.isEmpty() && tlsBatch.isEmpty()) return
                val pktsToWrite = if (pktBatch.isNotEmpty()) ArrayList(pktBatch).also { pktBatch.clear() } else emptyList()
                val dnsToWrite = if (dnsBatch.isNotEmpty()) ArrayList(dnsBatch).also { dnsBatch.clear() } else emptyList()
                val tlsToWrite = if (tlsBatch.isNotEmpty()) ArrayList(tlsBatch).also { tlsBatch.clear() } else emptyList()
                lastFlushAt = System.currentTimeMillis()

                try {
                    if (pktsToWrite.isNotEmpty()) sink.insertPackets(pktsToWrite)
                    if (dnsToWrite.isNotEmpty()) sink.insertDnsLogs(dnsToWrite)
                    if (tlsToWrite.isNotEmpty()) sink.insertTlsLogs(tlsToWrite)
                    if (pktsToWrite.isNotEmpty()) {
                        liveState.update { curr ->
                            val saved = curr.packetsSavedToDb + pktsToWrite.size
                            curr.copy(
                                packetsSavedToDb = saved,
                                databaseSavingState = PipelineStageStatus.ACTIVE,
                                statusMessage = "Capturing: ${curr.packetsRead} read • $saved saved (${curr.selectedAppsSummary})"
                            )
                        }
                    }
                } catch (e: Throwable) {
                    liveState.update { curr ->
                        curr.copy(
                            dbInsertErrors = curr.dbInsertErrors + 1,
                            lastDbError = e.message ?: "Room database insert error",
                            databaseSavingState = PipelineStageStatus.ERROR
                        )
                    }
                }
            }

            try {
                while (isActive || !packetWriteChannel.isEmpty || !dnsWriteChannel.isEmpty || !tlsWriteChannel.isEmpty) {
                    var drainedAny = false
                    while (pktBatch.size < batchSize) {
                        val item = packetWriteChannel.tryReceive().getOrNull() ?: break
                        pktBatch.add(item)
                        drainedAny = true
                    }
                    while (dnsBatch.size < 16) {
                        val item = dnsWriteChannel.tryReceive().getOrNull() ?: break
                        dnsBatch.add(item)
                        drainedAny = true
                    }
                    while (tlsBatch.size < 16) {
                        val item = tlsWriteChannel.tryReceive().getOrNull() ?: break
                        tlsBatch.add(item)
                        drainedAny = true
                    }

                    val now = System.currentTimeMillis()
                    val shouldFlush = pktBatch.size >= batchSize ||
                        dnsBatch.size >= 8 ||
                        tlsBatch.size >= 8 ||
                        ((pktBatch.isNotEmpty() || dnsBatch.isNotEmpty() || tlsBatch.isNotEmpty()) && now - lastFlushAt >= flushIntervalMs)

                    if (shouldFlush) {
                        flushAll()
                    } else if (!drainedAny) {
                        if (packetWriteChannel.isClosedForReceive && dnsWriteChannel.isClosedForReceive && tlsWriteChannel.isClosedForReceive) {
                            break
                        }
                        delay(60L)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    // Drain any remaining items before closing
                    while (true) {
                        val item = packetWriteChannel.tryReceive().getOrNull() ?: break
                        pktBatch.add(item)
                    }
                    while (true) {
                        val item = dnsWriteChannel.tryReceive().getOrNull() ?: break
                        dnsBatch.add(item)
                    }
                    while (true) {
                        val item = tlsWriteChannel.tryReceive().getOrNull() ?: break
                        tlsBatch.add(item)
                    }
                    flushAll()
                }
            }
        }

        // Stage 2: Packet Parser & Metadata Extractor
        processorJob = scope.launch(Dispatchers.Default) {
            var lastHash = 0
            var lastHashTs = 0L

            try {
                for (frame in rawFrameChannel) {
                    // Guard against instant duplicate TUN echo frames (identical raw bytes within 15ms)
                    val frameHash = computeQuickFrameHash(frame.bytes, frame.length)
                    if (frameHash == lastHash && (frame.capturedAt - lastHashTs) in 0L..15L) {
                        continue
                    }
                    lastHash = frameHash
                    lastHashTs = frame.capturedAt

                    val parsed = runCatching { PacketParser.parseIpPacket(frame.bytes, frame.length) }.getOrNull()
                    if (parsed == null) {
                        liveState.update { curr ->
                            curr.copy(
                                packetsRejected = curr.packetsRejected + 1,
                                packetProcessingState = PipelineStageStatus.ACTIVE
                            )
                        }
                        continue
                    }

                    val isQuic = parsed.protocol == "QUIC" || (parsed.transportProtocol == "UDP" && parsed.dstPort == 443)
                    val isBlocked = isQuic && quicBlockFlag.get()
                    val isDns = parsed.protocol == "DNS" || parsed.dstPort == 53 || parsed.dstPort == 853
                    var metadataNote = ""

                    if (parsed.protocol == "DNS" && parsed.payloadLength >= 12) {
                        val dns = runCatching {
                            PacketParser.parseDnsPayload(frame.bytes, parsed.payloadOffset, parsed.payloadLength)
                        }.getOrNull()
                        if (dns != null && dns.queryName.isNotBlank()) {
                            metadataNote = "DNS ${dns.queryType} ${dns.queryName}"
                            if (!dns.isResponse) {
                                val relayHandler = onDnsQueryObserved
                                if (relayHandler != null) {
                                    relayHandler(parsed, frame.bytes.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + parsed.payloadLength), dns)
                                } else {
                                    dnsWriteChannel.trySend(
                                        DNSLog(
                                            timestamp = frame.capturedAt,
                                            queryName = dns.queryName,
                                            queryType = dns.queryType,
                                            dnsServer = parsed.dstIp,
                                            responseIps = dns.answers.joinToString(", "),
                                            latencyMs = -1L,
                                            protocolClass = if (parsed.transportProtocol == "TCP") "TCP_53" else "UDP_53",
                                            isEncrypted = false
                                        )
                                    )
                                }
                            }
                        }
                    } else if (parsed.dstPort == 853) {
                        metadataNote = "Encrypted DNS-over-TLS (DoT)"
                        dnsWriteChannel.trySend(
                            DNSLog(
                                timestamp = frame.capturedAt,
                                queryName = "Encrypted DNS-over-TLS (${parsed.dstIp}:853)",
                                queryType = "DoT",
                                dnsServer = parsed.dstIp,
                                responseIps = "",
                                latencyMs = -1L,
                                protocolClass = "DOT_ENCRYPTED",
                                isEncrypted = true
                            )
                        )
                    } else if (parsed.transportProtocol == "TCP" && parsed.payloadLength >= 43) {
                        val tls = runCatching {
                            PacketParser.parseTlsClientHello(frame.bytes, parsed.payloadOffset, parsed.payloadLength)
                        }.getOrNull()
                        if (tls != null) {
                            val sniDisplay = tls.sniHostname ?: if (tls.hasEchExtension) "[ECH Encrypted SNI]" else "[SNI Unavailable]"
                            metadataNote = "${tls.tlsVersion} SNI=$sniDisplay"
                            tlsWriteChannel.trySend(
                                TLSLog(
                                    timestamp = frame.capturedAt,
                                    sniHostname = sniDisplay,
                                    dstIp = parsed.dstIp,
                                    dstPort = parsed.dstPort,
                                    tlsVersion = tls.tlsVersion,
                                    alpnProtocols = tls.alpnProtocols.joinToString(", "),
                                    isEchOrUnavailable = tls.hasEchExtension || tls.sniHostname == null
                                )
                            )
                        }
                    } else if (isBlocked) {
                        metadataNote = "Filtered locally (Opt-in UDP:443 QUIC filter)"
                    }

                    packetWriteChannel.trySend(
                        PacketLog(
                            timestamp = frame.capturedAt,
                            protocol = parsed.protocol,
                            srcIp = parsed.srcIp,
                            srcPort = parsed.srcPort,
                            dstIp = parsed.dstIp,
                            dstPort = parsed.dstPort,
                            packetSize = parsed.packetSize,
                            direction = "OUTBOUND",
                            appPackage = appScopeLabel,
                            isBlocked = isBlocked,
                            metadata = metadataNote
                        )
                    )

                    liveState.update { curr ->
                        val nextParsed = curr.packetsParsed + 1
                        curr.copy(
                            packetsParsed = nextParsed,
                            totalPackets = nextParsed,
                            tcpPackets = curr.tcpPackets + if (parsed.transportProtocol == "TCP") 1 else 0,
                            udpPackets = curr.udpPackets + if (parsed.transportProtocol == "UDP") 1 else 0,
                            dnsPackets = curr.dnsPackets + if (isDns) 1 else 0,
                            quicPackets = curr.quicPackets + if (isQuic) 1 else 0,
                            blockedQuicPackets = curr.blockedQuicPackets + if (isBlocked) 1 else 0,
                            capturedBytes = curr.capturedBytes + parsed.packetSize,
                            lastPacketTimestamp = frame.capturedAt,
                            packetProcessingState = PipelineStageStatus.ACTIVE
                        )
                    }
                }
            } finally {
                packetWriteChannel.close()
                dnsWriteChannel.close()
                tlsWriteChannel.close()
            }
        }

        // Stage 1: Continuous TUN Packet Reader (never blocked by DNS or Room!)
        readerJob = scope.launch(Dispatchers.IO) {
            val readBuf = ByteArray(32767)
            try {
                while (isActive) {
                    val readLen = try {
                        inputStream.read(readBuf)
                    } catch (_: Exception) {
                        -1
                    }
                    if (readLen < 0) break
                    if (readLen == 0) {
                        delay(5L)
                        continue
                    }

                    val copy = readBuf.copyOf(readLen)
                    val ts = System.currentTimeMillis()
                    liveState.update { curr ->
                        curr.copy(
                            packetsRead = curr.packetsRead + 1,
                            packetReadingState = PipelineStageStatus.ACTIVE
                        )
                    }
                    rawFrameChannel.trySend(RawPacketFrame(copy, readLen, ts))
                }
            } finally {
                rawFrameChannel.close()
            }
        }
    }

    fun submitRelayedDnsLog(log: DNSLog) {
        dnsWriteChannel.trySend(log)
    }

    suspend fun stopAndDrain() {
        readerJob?.cancel()
        rawFrameChannel.close()
        processorJob?.join()
        writerJob?.join()
        readerJob = null
        processorJob = null
        writerJob = null
    }

    fun stopAsync() {
        readerJob?.cancel()
        rawFrameChannel.close()
        readerJob = null
    }

    private fun computeQuickFrameHash(bytes: ByteArray, length: Int): Int {
        var h = length
        val sampleEnd = length.coerceAtMost(96)
        for (i in 0 until sampleEnd) {
            h = 31 * h + bytes[i].toInt()
        }
        return h
    }
}
