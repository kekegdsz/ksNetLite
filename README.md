# KsNetLite

<p align="center">
  <b>Android weak-network testing tool with a fast floating panel.</b><br/>
  <b>Android 弱网测试工具，面向真实业务回归与压测。</b>
</p>

<p align="center">
  <a href="./README_ZH.md">中文文档</a> ·
  <a href="./README_EN.md">English Docs</a>
</p>

---

## Why KsNetLite?

KsNetLite helps QA and developers reproduce unstable network conditions quickly on real Android devices.

KsNetLite 帮助测试和开发在真机上快速复现弱网场景，减少“线上才能复现”的问题。

- One-tap floating shortcuts for daily testing  
  一键浮窗快捷切换，测试流程更快
- Packet loss / latency / jitter / bandwidth shaping  
  支持丢包、延迟、抖动、带宽整形
- Presets for Normal / Offline / 4G / 3G / 2G / Bad network  
  内置正常、断网、4G、3G、2G、差网等预置场景
- Real-time counters for packets and dropped packets  
  实时统计总包数与丢包数

## Features

- Interactive floating overlay (draggable)
- Instant switch: `Normal` / `Offline`
- Quick packet-loss test buttons: `30%` / `60%`
- Fine tuning: loss +/-10, latency +/-50ms
- Start/Stop timer shown on floating action button
- Persistent profile storage
- VPN-based traffic interception entrypoint (`VpnService`)

## APK Download

- Local debug APK: `./apk/ksnetlite-debug.apk`
- **Download (highlight):**  
  **[`https://github.com/kekegdsz/ksNetLite/blob/main/apk/ksnetlite-debug.apk`](https://github.com/kekegdsz/ksNetLite/blob/main/apk/ksnetlite-debug.apk)**

## UI Demo

Home page + floating panel demonstration:

![Home and overlay demo](./docs/images/home-overlay-demo.png)

## Architecture (Current)

- `MainActivity`  
  Main control page and profile editing
- `KsNetVpnService`  
  Foreground VPN service lifecycle and packet handling loop
- `OverlayService`  
  Draggable floating panel with quick actions
- `RuleEngine` + `TrafficShaper`  
  Packet drop decision, delay/jitter, and bandwidth shaping
- `ProfileStore` + `StatsStore` + `ServiceStateStore`  
  Runtime state and persistence

## Notes

- Current version is practical for weak-network regression testing and scenario replay.
- For full production-grade network emulation (complex TCP/UDP/QUIC forwarding), a complete `TUN -> Socket -> TUN` pipeline is recommended.

## Roadmap

- App-level targeting (only affect selected apps)
- Import/export profiles
- Scriptable scenario timeline
- Better observability (session logs, charts)

## License

MIT (recommended) - you can replace with your preferred license.
