# UltimateFetcher

**Developer:** Avinash  
**Platform:** Android (Kotlin, Jetpack Compose, Material 3)  
**Architecture:** MVVM + Clean Architecture, Local-First Persistence (Room + DataStore + Android KeyStore)

## Overview
UltimateFetcher is a local-first Android network telemetry, non-root packet metadata monitor, API testing workbench, and read-only network diagnostics suite built for ordinary non-root Android devices (with strictly gated read-only root diagnostics when a rooted device explicitly authorizes them).

## Core Modules
1. **Live Dashboard**
   - Real-time download/upload throughput via `TrafficStats` with animated Compose Canvas chart
   - Active network interface, Wi-Fi/Mobile transport, DNS servers, Private DNS mode, live TCP latency probe
   - Explicit user-consented Public IP lookup (`api.ipify.org`)
   - Packet counters & TCP/UDP telemetry split, local VPN controls, passive root status, sanitized JSON report export
2. **Non-Root Network Fetchers**
   - **VPN Traffic Monitor**: Local `VpnService` TUN metadata inspector with foreground notification & safe shutdown
   - **DNS Query Monitor**: RFC 1035 UDP/TCP port 53 decoding + transparent DoH/DoT encrypted detection
   - **App Traffic Filter**: Launchable app discovery via Manifest `<queries>` + `VpnService.Builder.addAllowedApplication()`
   - **QUIC Diagnostic & Blocker**: UDP port 443 endpoint visibility + opt-in local UDP:443 diagnostic filter
   - **TLS Domain Extractor**: ClientHello SNI, TLS version, ALPN, and ECH/encrypted limitation reporting
   - **Connection Analyzer**: Endpoint grouping, port service classification, duration & volume
   - **DNS Resolver Tester**: Direct RFC 1035 UDP/53 queries (`A`, `AAAA`, `MX`, `TXT`, `CNAME`) + System resolver fallback
   - **Network Quality Tester**: Real TCP latency, jitter, probe loss, and HTTP download/upload throughput tests
3. **API Lab & Advanced API Diagnostics**
   - Full HTTP client (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`), custom headers, query params, JSON & Form URL bodies, JSON pretty-print + syntax highlighting, Android KeyStore AES-256-GCM encrypted credential vault, history & JSON collections import/export
   - **API Endpoint Inspector**, **SSL Certificate Inspector**, **HTTP Header Analyzer** (Cookie values strictly redacted), **REST API Discovery Assistant** (explicitly provided OpenAPI/Swagger specs only), **WebSocket Tester**, **Local Webhook Tester** (`ServerSocket`), **API Response Diff**, **API Health Monitor** (WorkManager periodic checks + failure notifications)
4. **Read-Only Root Diagnostics & Own-App API Debugger**
   - Passive `su` detection (never requests superuser automatically)
   - Explicit user confirmation required before each read-only command (`ip addr`, `ip route`, `iptables -L -n -v`, `/proc/net/dev`, `sysctl`), audited in Room `RootAuditLog`
   - Debug-build-only opt-in OkHttp interceptor with automatic sensitive header redaction
5. **Local Database & Log Explorer**
   - Room database with 8 entities (`PacketLog`, `DNSLog`, `TLSLog`, `ApiRequestLog`, `NetworkSession`, `EndpointMonitor`, `SavedCollection`, `RootAuditLog`)
   - Pause/resume live stream, search, protocol/date filters, pagination, individual delete, CSV & sanitized JSON export

## Security & Privacy
- 100% local-first: captured traffic and logs remain on device; zero analytics SDKs or background cloud uploads.
- Sensitive HTTP headers (`Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, tokens) are automatically redacted in logs and exports.
- Encrypted API credentials use `AndroidKeyStore` with AES-256-GCM.

## Build Instructions
```bash
gradle :app:assembleDebug
```
