<p align="center">
  <img src="kryon.png" width="180" alt="Kryon Logo"/>
</p>

<h1 align="center">Kryon</h1>

<p align="center">
  <strong>Zero-leak Android proxy client</strong><br>
  Route your device traffic through SOCKS5, HTTP, or HTTPS proxies with military-grade leak protection.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?logo=android" alt="Android"/>
  <img src="https://img.shields.io/badge/Kotlin-1.9-purple?logo=kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Min%20SDK-24-blue" alt="Min SDK 24"/>
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="MIT"/>
</p>

---

## Why Kryon?

Most proxy clients leak your real IP. We tested popular clients — they expose your real location through IPv6, DNS, and WebRTC while showing the proxy IP. Kryon was built to fix that.

**Kryon blocks every known leak vector:**

| Leak Vector | How Kryon Blocks It |
|---|---|
| IPv6 leak | `::/0` route captures all IPv6 traffic, dropped at TUN |
| DNS leak | FakeDNS intercepts all queries, returns forged responses |
| DNS-over-TLS | RST on port 853, forces fallback to intercepted UDP DNS |
| DNS-over-HTTPS | Tunneled through proxy as regular HTTPS |
| AAAA / IPv6 DNS | Immediate NODATA response for all non-A query types |
| WebRTC / STUN | All UDP silently dropped at TUN layer |
| Reconnect window | TUN stays alive as blackhole (kill switch) |
| IPv6 setup failure | VPN refuses to start (fail-closed) |

## Features

- **Auto protocol detection** &mdash; Connects via SOCKS5, HTTP, or HTTPS automatically
- **Country flag detection** &mdash; Shows the proxy exit country flag on the card
- **Kill switch** &mdash; Blocks all traffic when proxy drops (on by default)
- **Real-time stats** &mdash; Live upload/download speed display
- **Auto-reconnect** &mdash; Exponential backoff reconnection with network awareness
- **Boot persistence** &mdash; Reconnects automatically after device restart
- **Multi-profile** &mdash; Save and switch between multiple proxy configurations
- **Lightweight** &mdash; Pure Kotlin, no native libraries, no third-party dependencies

## Architecture

```
App Traffic
    |
    v
[ TUN Interface ] ──> PacketForwarder
    |         |              |
    |         |              ├── TCP SYN ──> TcpConnection ──> Proxy (SOCKS5/HTTP/HTTPS)
    |         |              ├── UDP:53  ──> FakeDNS (forged response)
    |         |              └── Other   ──> Dropped (no leak)
    |         |
    v         v
  IPv4      IPv6
  routed    captured & dropped
```

**Key design decisions:**
- FakeDNS assigns IPs from `198.18.0.0/15`, proxy resolves real IPs via domain name
- No full TCP stack needed &mdash; device kernel handles retransmission/ordering
- App excluded from VPN (`addDisallowedApplication`) to prevent routing loops
- Protected sockets bypass VPN for proxy server communication

## Getting Started

### Prerequisites
- Android Studio Hedgehog or later
- Android SDK 34+
- Kotlin 1.9.20+

### Build
```bash
git clone https://github.com/user/kryon.git
cd kryon
./gradlew assembleDebug
```

### Install
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Usage
1. Open Kryon
2. Tap **+** to add a proxy (host, port, optional username/password)
3. Tap the proxy card to connect
4. Tap again to disconnect

The app auto-detects whether your proxy is SOCKS5, HTTP, or HTTPS.

## Project Structure

```
app/src/main/java/com/proxyconnect/app/
├── data/
│   ├── ProxyProfile.kt      # Profile model + repository
│   ├── ProxyConfig.kt       # Active config + persistence
│   └── FreeProxies.kt       # Default proxy list
├── proxy/
│   ├── PacketForwarder.kt   # TUN read loop, packet dispatch
│   ├── TcpConnection.kt     # Per-connection proxy tunneling
│   ├── Packet.kt            # IPv4/TCP/UDP packet parser/builder
│   ├── FakeDns.kt           # DNS interception + fake IP assignment
│   └── TrafficStats.kt      # Upload/download speed tracking
├── service/
│   └── ProxyVpnService.kt   # VPN lifecycle, reconnection, country detection
├── receiver/
│   └── BootReceiver.kt      # Auto-connect on boot
├── ui/
│   ├── MainActivity.kt      # Proxy list, connect/disconnect
│   ├── ProxyAdapter.kt      # RecyclerView adapter with flags
│   └── SettingsActivity.kt  # Kill switch, auto-connect, battery
└── ProxyApp.kt              # Application class, notification channel
```

## License

MIT License. See [LICENSE](LICENSE) for details.
