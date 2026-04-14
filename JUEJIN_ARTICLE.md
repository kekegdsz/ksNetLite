# 每次都复现不了线上超时？我开源了一个 Android 弱网测试工具（含关键代码）

线上最难追的不是必现问题，而是“偶发网络异常”：  
同一个接口，有人超时、有人正常；测试说弱网必现，开发却怎么也跑不出来。

我做了一个 Android 弱网工具 `KsNetLite`，核心目标不是“模拟网络”，而是把**弱网故障变成可重复实验**。  
仓库：`https://github.com/kekegdsz/ksNetLite`

这篇只讲技术实现：系统网络通道怎么承载弱网仿真、参数如何实时生效、限速算法怎么落地。

先看实际界面演示（首页列表 + 浮窗控制区）：

![KsNetLite 首页与浮窗演示](./docs/images/home-overlay-demo.png)

## 1. 整体架构：控制面与数据面解耦

我把系统拆成两层：

- **控制面**：`MainActivity` + `OverlayService`
  - 负责场景切换（2G/3G/断网）、参数调节（丢包/延迟/抖动/带宽）
  - 参数写入 `ProfileStore`（`StateFlow` + 持久化）
- **数据面**：`KsNetNetworkService` + `RuleEngine` + `TrafficShaper`
  - 负责每个数据包的“丢不丢、等多久、发不发”

这样做的好处是：UI 怎么改都不会影响流量处理链路，稳定性更高。

## 2. 入口为什么选系统网络通道？

弱网模拟最关键是“可控流量入口”。这里通过系统提供的网络通道能力建立 TUN，将 IPv4 流量统一导入仿真链路：

```kotlin
val builder = Builder()
    .setSession("KsNetLite")
    .addAddress("10.10.0.2", 24)
    .addDnsServer("8.8.8.8")
    .addRoute("0.0.0.0", 0)

tunInterface = builder.establish() ?: return
```

`addRoute("0.0.0.0", 0)` 的意义是把默认路由导进虚拟网卡，后续就能在用户态做延迟、抖动、丢包、限速等网络仿真规则。

## 3. 包处理主循环：先判丢包，再叠加延迟/限速

核心循环非常直接：读包 -> 规则判定 -> 延迟 -> 回写。

```kotlin
while (isActive) {
    val len = input.read(buffer)
    if (len <= 0) continue

    val currentProfile = ProfileStore.profile.value
    if (ruleEngine.shouldDropPacket(currentProfile)) {
        StatsStore.onPacketDropped()
        continue
    }

    val networkDelayMs = ruleEngine.computeDelayMs(currentProfile)
    val shapeDelayMs = trafficShaper.computeRequiredDelayMs(len, currentProfile)
    val totalDelayMs = networkDelayMs + shapeDelayMs
    if (totalDelayMs > 0) delay(totalDelayMs)

    output.write(buffer, 0, len)
    StatsStore.onPacketForwarded(len)
}
```

这里有两个关键点：

- **丢包优先于延迟**：被丢弃的包不参与后续等待，更符合真实弱网下“直接丢失”的语义。
- **时延模型可叠加**：基础网络时延 + 带宽整形引入的排队等待，能覆盖更多真实抖动场景。

## 4. 丢包与抖动：用最小模型先解决 80% 复现问题

`RuleEngine` 没做复杂马尔可夫链，先用概率模型保证可解释、可调试：

```kotlin
fun shouldDropPacket(profile: NetworkProfile): Boolean {
    if (profile.packetLossPercent <= 0) return false
    return random.nextInt(100) < profile.packetLossPercent
}

fun computeDelayMs(profile: NetworkProfile): Long {
    val jitter = if (profile.jitterMs == 0) 0 else random.nextInt(profile.jitterMs + 1)
    return (profile.latencyMs + jitter).toLong()
}
```

这个实现足够覆盖：

- 高丢包导致的重试/超时
- 延迟 + 抖动导致的响应漂移
- 场景切换时的行为差异（比如 4G -> 差网）

## 5. 限速实现：令牌桶控制吞吐，避免“粗暴 sleep”

带宽整形我用的是令牌桶思路。核心逻辑：

- 按时间补充 token
- token 足够则立即放行
- token 不足则计算需要等待的毫秒数

```kotlin
fun computeRequiredDelayMs(packetSizeBytes: Int, profile: NetworkProfile): Long {
    val bandwidthBytesPerSec = profile.bandwidthKbps * 1024.0 / 8.0
    if (bandwidthBytesPerSec <= 0.0) return 0

    refillTokens(bandwidthBytesPerSec)

    val burstLimit = max(bandwidthBytesPerSec, 8_192.0)
    tokensBytes = min(tokensBytes, burstLimit)

    if (tokensBytes >= packetSizeBytes) {
        tokensBytes -= packetSizeBytes
        return 0
    }

    val missingBytes = packetSizeBytes - tokensBytes
    tokensBytes = 0.0
    val seconds = missingBytes / bandwidthBytesPerSec
    return (seconds * 1000).toLong().coerceAtLeast(1L)
}
```

相比固定 sleep，这种方式在“突发流量 + 平均带宽约束”之间平衡更自然，也更接近真实链路。

## 6. 参数热更新：`StateFlow` 让控制台和处理循环同步

参数状态单独放在 `ProfileStore`，处理循环每次取当前值，天然支持运行中动态切换：

```kotlin
private val _profile = MutableStateFlow(NetworkProfile())
val profile: StateFlow<NetworkProfile> = _profile.asStateFlow()

fun update(profile: NetworkProfile) {
    _profile.value = profile
}
```

同时做了本地持久化，保证重启后仍能恢复上次测试配置。

## 7. 浮窗设计：把“切网动作成本”压到最低

`OverlayService` 本质是测试效率工具：把高频动作做成按钮，不再反复进设置页。

例如“预设场景 + 确保服务已运行”的触发链路：

```kotlin
fourG.setOnClickListener {
    resetClickTimer()
    applyProfile(PresetProfile.FOUR_G.profile)
    ensureServiceRunning()
}
threeG.setOnClickListener {
    resetClickTimer()
    applyProfile(PresetProfile.THREE_G.profile)
    ensureServiceRunning()
}
```

这块看似是 UI，实际上决定了工具是否“真能被天天用”。

## 8. 这套实现解决了什么问题？

对我自己最有价值的是三件事：

- 把“偶发网络问题”变成可重复实验
- 把“调环境”从分钟级降低到秒级
- 把“测试口径”统一成可共享的配置参数

也就是说，它不是一个“网络黑魔法工具”，而是一个让客户端网络问题可工程化验证的基础设施。

## 9. 后续规划

- 指定 App 生效（白名单，避免全局影响）
- 场景脚本化（按时间轴自动切换）
- 更细粒度统计（按场景聚合耗时、失败率）

---

如果你也在做客户端网络稳定性测试，欢迎交流：  
GitHub：`https://github.com/kekegdsz/ksNetLite`
