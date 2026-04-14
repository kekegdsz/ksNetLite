# KsNetLite

KsNetLite is an Android weak-network testing tool with a fast floating control panel, built for QA regression, reliability testing, and production issue reproduction.

## Highlights

- Floating quick actions: Normal, Offline, 4G, 3G, 2G, Bad Network
- Packet loss testing: instant 30% / 60% and +/-10 fine tuning
- Latency testing: quick +/-50ms adjustments
- Network shaping: packet loss, latency, jitter, bandwidth
- Real-time counters: total packets and dropped packets
- Click timer: resets to 0s on every floating-button action

## Features

- Draggable interactive floating panel
- One-tap restore to normal network
- One-tap offline mode
- Persistent profile storage
- `VpnService` based traffic control entrypoint

## APK Download

- Local debug APK: `./apk/ksnetlite-debug.apk`
- **Download (highlight):**  
  **[`https://github.com/kekegdsz/ksNetLite/blob/main/apk/ksnetlite-debug.apk`](https://github.com/kekegdsz/ksNetLite/blob/main/apk/ksnetlite-debug.apk)**

## Demo Screenshot

Home list and floating overlay in one view:

![Home and overlay demo](./docs/images/home-overlay-demo.png)

## Current Architecture

- `MainActivity`: main control screen
- `KsNetVpnService`: VPN foreground service and shaping loop
- `OverlayService`: floating quick-action controller
- `RuleEngine`: packet drop + latency logic
- `TrafficShaper`: bandwidth shaping
- `ProfileStore` / `StatsStore` / `ServiceStateStore`: runtime state

## Use Cases

- Weak-network regression testing
- Network reliability testing
- Failure reproduction with packet loss/high latency/offline transitions

## Roadmap

- Per-app targeting (whitelist mode)
- Profile import/export
- Scripted scenario timeline
- Better telemetry and logs
