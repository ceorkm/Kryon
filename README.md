<p align="center">
  <img src="kryon.png" width="180" alt="Kryon Logo"/>
</p>

<h1 align="center">Kryon</h1>

<p align="center">
  <strong>Open-source Android proxy client</strong><br>
  Connect to any SOCKS5, HTTP, or HTTPS proxy in one tap.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?logo=android" alt="Android"/>
  <img src="https://img.shields.io/badge/Kotlin-1.9-purple?logo=kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Min%20SDK-24-blue" alt="Min SDK 24"/>
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="MIT"/>
</p>

---

## Features

- **One-tap connect** &mdash; Tap a proxy card to connect, tap again to disconnect
- **Auto protocol detection** &mdash; Automatically detects SOCKS5, HTTP, or HTTPS
- **Country flag detection** &mdash; Detects and displays the proxy exit country
- **Kill switch** &mdash; Blocks all traffic if the proxy connection drops
- **Real-time stats** &mdash; Live upload/download speed on each proxy card
- **Auto-reconnect** &mdash; Smart reconnection with exponential backoff
- **Boot persistence** &mdash; Reconnects automatically after device restart
- **Multi-profile** &mdash; Save and manage multiple proxy configurations
- **Lightweight** &mdash; Pure Kotlin, no native libraries, no third-party dependencies

## Getting Started

### Prerequisites
- Android Studio Hedgehog or later
- Android SDK 34+
- Kotlin 1.9.20+

### Build
```bash
git clone https://github.com/ceorkm/Kryon.git
cd Kryon
./gradlew assembleDebug
```

### Install
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Open Kryon
2. Tap **+** to add a proxy (host, port, optional username/password)
3. Tap the proxy card to connect
4. Tap again to disconnect

The app auto-detects whether your proxy speaks SOCKS5, HTTP, or HTTPS &mdash; no manual configuration needed.

## Settings

| Setting | Description |
|---|---|
| **Kill Switch** | Blocks all internet traffic when the proxy disconnects (on by default) |
| **Auto-connect on boot** | Reconnects to your last proxy after device restart |
| **Battery optimization** | Guides you to disable battery restrictions for reliable background operation |

## Project Structure

```
app/src/main/java/com/proxyconnect/app/
├── data/
│   ├── ProxyProfile.kt        # Profile model + repository
│   ├── ProxyConfig.kt         # Active config + persistence
│   └── FreeProxies.kt         # Default proxy list
├── proxy/
│   ├── PacketForwarder.kt     # TUN packet dispatch
│   ├── TcpConnection.kt       # Per-connection proxy tunneling
│   ├── Packet.kt              # IP/TCP/UDP packet parser & builder
│   ├── FakeDns.kt             # DNS interception
│   └── TrafficStats.kt        # Speed tracking
├── service/
│   └── ProxyVpnService.kt     # VPN lifecycle & reconnection
├── receiver/
│   └── BootReceiver.kt        # Auto-connect on boot
├── ui/
│   ├── MainActivity.kt        # Main proxy list screen
│   ├── ProxyAdapter.kt        # Proxy card with flags & stats
│   └── SettingsActivity.kt    # App settings
└── ProxyApp.kt                # App initialization
```

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

## License

MIT License. See [LICENSE](LICENSE) for details.
