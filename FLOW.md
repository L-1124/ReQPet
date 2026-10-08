# QPetCompanion 主循环执行流拓扑与审查记录

## 1. 核心执行拓扑

```text
[启动 / 唤醒] HookEntry (SplashActivity / MobileQQ)
   │
   ▼
[引擎唤醒] PetAdventureEngine.launchLoop(context)
   │  * while(isActive && isLoopRunning && masterEnabled) 协程循环
   ▼
[前置闸门检查] executeMasterCycle
   ├─► 检查 masterEnabled (未开启 -> 休眠 60s)
   ├─► EngineGates.checkStealthWindows: 夜间静默/熄屏静默 (命中 -> 休眠数小时/数分钟)
   └─► Bridge 就绪检查与 ensurePetId (失败 -> 休眠 10s ~ 30s)
   │
   ▼
[状态同步] queryStoryStatusAwait(0x95e1_0)
   ├─► TimeConfigManager: 提取服务端工时并动态校准
   ├─► story.code != 0 (网络异常 -> performMaintenance -> 休眠 8s)
   └─► 记录在途任务状态或空闲状态
   │
   ▼
[三相生命周期推进]
   ├─► 1. PetStoryHandlers.handleOngoingStory: 监控在途任务、评估雇佣提前召回
   ├─► 2. PetStoryHandlers.handleStorySettlement: 检测剩余时间到期 -> 自动收益结算
   └─► 3. PetMaintenanceCoordinator.performMaintenance: 巡检喂食、洗澡、福袋、回踩、串门、PK
   │
   ▼
[分流决策 (rem: 任务剩余秒数)]
   ├─► 【若仍在外出】(rem > 0):
   │     └─ sleepForMaintenance: 取 [拟人任务休眠] 与 [最近维护点] 的最小值 (180s~300s 切片)
   │
   └─► 【若小宠闲置】(rem == 0):
         └─ PetCycleDispatcher.dispatchNextTask: Round-Robin 轮转分发 (学业 / 打工 / 探险) -> 返回 5000ms
```

## 2. 审查发现的关键缺陷与待修问题

| 编号 | 缺陷等级 | 涉及文件与锚点 | 核心问题描述 | 修复策略 |
| :--- | :--- | :--- | :--- | :--- |
| **DEF-01** | 🔴 高危 (时序竞争) | `PetStoryHandlers.kt:82` (`handleStorySettlement`) | **提前到期结算**<br>刚派发完任务返回主循环时，若服务端存在 3~5s 写库延迟，`remaining` 会返回 0。引擎未校验 `currentTaskEndTimeMillis` 就直接调用了 `settleStoryAwait`，导致刚开工 5s 的任务被强杀结算。 | 在调用 `settleStoryAwait` 前增加 `isTimeElapsed` 判断：只有当 `now >= (endTimeMillis - 15_000L)` 才允许触发结算。 |
| **DEF-02** | 🔴 高危 (风控雪崩) | `PetCycleDispatcher.kt:34` (`dispatchNextAction`) | **失败无退避死循环**<br>`executeAction` 返回 `Unit` 吞掉失败结果。若全量可用任务（学业/打工/探险）因体力或网络原因被服务端拒绝，主循环固定返回 `5000ms`，导致每 5 秒轰炸一次服务器发包。 | `dispatchNextAction` 需接收执行结果。若该轮全量任务失败，应返回退避休眠 `30000L + randomJitter`，而非 5 秒死循环。 |
| **DEF-03** | 🟡 中危 (业务冲突) | `PetMaintenanceCoordinator.kt:70` (`checkCareMaintenance`) | **结算期体力断层**<br>小宠打完工结算后体力清零，若恰好在过去 3 分钟内刚刚做过自理检查，`lastCareTimeMillis` 会阻挡本次立即喂食/洗澡。随后调度器试图派遣新任务，服务端因体力为 0 拒绝。 | 增加 `forceCheck` 参数。若本轮巡检刚执行完 `handleStorySettlement`（任务结束），强制忽略 3 分钟冷却立即进食补状态。 |
| **DEF-04** | 🟡 中危 (高频唤醒) | `PetAdventureEngine.kt:401` (`sleepForMaintenance`) | **1秒级 Busy-wait 空转**<br>当 `millisUntilNextCheck` 因网络失败持续返回 `0L` 时，`coerceAtLeast(1_000L)` 会导致协程陷入 1 秒级的空转唤醒。 | 提升保底休眠基线至 `3_000L` ~ `5_000L`（若业务允许）。 |

## 3. 核心包名与混淆控制台账

| 目标对象 | 宿主当前混淆名 | 抗混淆定位手段 | 稳定性 |
| :--- | :--- | :--- | :--- |
| **协议发包接口** | `com.tencent.ergo.hostdelegate.pb.PetPbDelegate` | **契约接口** (Protobuf 边界 Protected) | 🟢 永久保留 |
| **发包实现类** | `com.tencent.mobileqq.qqpet.delegate.m` | **DexParser** 解析 DEX `class_def` 接口表 | 🟢 0 依赖外库 |
| **协议发送方法** | `c(...)` (当前混淆名) | **参数签名匹配** (`[B, String, int, int, Observer]`) | 🟢 100% 免疫混淆 |
| **发包回调协议** | `PetPbDelegate$a` (内部类) | **方法入参截取** (提取第 5 个参数的类型) | 🟢 永久保留 |
