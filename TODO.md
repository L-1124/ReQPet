# 待办事项

当前状态：模块已迁移到 **libxposed API 102**，设置页整体为 **Compose + Material 3 Expressive**，依赖只保留必要项；总开关默认关闭，未开启时模块不发起任何请求。

## 本轮已完成

- [x] 迁移到 libxposed API 102：`XposedModule` + `onModuleLoaded/onPackageReady` + `hook(executable).intercept {}`，元数据改为 `META-INF/xposed/{module.prop,java_init.list,scope.list}`，`staticScope=true` 固定作用域。
- [x] 设置页重写为 Compose：顶栏、分节、弹窗全部 Compose，宿主 `QUISecNavBar` 与 QUI 桩已移除。
- [x] 移除模块 App 主界面与 Launcher Activity，只注入 QQ 主进程。
- [x] 删除模块内广播与广播式配置同步；日志改为 `EngineLog` 内存订阅（无广播）。
- [x] 显式请求-响应配对（`RequestTracker`），不再依赖隐式等待。
- [x] 依赖收敛到最新稳定版并删除未用项；APK 内不再打包 appcompat / material / constraintlayout。
- [x] 构建链升级：Gradle 9.8、AGP 9.4.1、version catalog、compileSdk/targetSdk 37。

## 待真机验证（优先）

- [ ] LSPosed 侧需重新启用并授权作用域：入口从 `assets/xposed_init` 换成 `META-INF/xposed/*`，确认 `org.lsposed.corepatch` 支持 `minApiVersion=101`。
- [ ] 两个弹窗（雇佣白名单 / PK 免战黑名单）的输入框在键盘弹出时不被遮挡，勾选 → 保存 → 重开能正确回填。
- [ ] 手动即时指令 11 项逐个执行，与旧版行为对照（尤其召回、结算、自动 PK）。
- [ ] 长日志下日志面板的滚动、清空与每秒刷新无卡顿。
- [ ] 宿主切换日夜皮肤后重新进入页面，配色是否跟随。
- [ ] 断网、超时、截断回包、异常回包下主循环不会永久挂起。
- [ ] 切换两个 QQ 账号，确认宠物 ID、每日限制、点赞与福袋状态完全隔离。

## P0：并发与健壮性

- [ ] 为 `PetPkTask.kt` 的 `suspendCancellableCoroutine` 增加超时、取消和回包丢失处理。
- [ ] 将 `PetCycleDispatcher.kt` 的 `suspendCoroutine` 改为可取消的挂起方式，确保 `withTimeoutOrNull` 能真正超时。
- [ ] 为 `OidbChannel.kt` 的 Observer 回调增加异常边界，避免解析异常冒泡到 QQ 宿主线程。
- [ ] 为 `ProtoWire.firstString`、`firstVarint`、`firstBytes` 增加越界、长度溢出和畸形数据保护。
- [ ] 按账号清理和隔离 `cachedPetId`、可雇佣好友、当前故事等运行时缓存。
- [ ] 确保 `clearStaticRuntimeCache()` 在账号切换和会话失效时被实际调用。
- [ ] 按账号隔离点赞集合、福袋集合和每日上限状态，禁止跨账号复用。
- [ ] 修复召回成功但结算失败时的状态处理：保留待结算状态并记录日志，支持后续重试。

## P1：调度与业务正确性

### 协程与并发

- [ ] 为主循环、维护任务和手动指令增加互斥，避免同一账号并发执行重复操作。
- [ ] 检查维护任务的节流时间戳更新顺序，任务成功后再记录执行时间。
- [ ] 区分取消异常和业务异常，避免 `catch (_: Throwable)` 吞掉协程取消。
- [ ] 统一 `xxxAwait` 包装函数的超时、取消和单次回调恢复逻辑。

### 查询失败处理

- [ ] 宠物属性查询失败时停止喂养、购买和洗澡等依赖属性的操作。
- [ ] 库存查询失败时停止自动购买，避免查询失败触发盲买。
- [ ] 好友状态查询失败时不要按“可用”处理。
- [ ] 检查 `getPetAttributes` 是否读取传入 `petId` 对应的数据，避免使用宿主当前展示值。

### 任务限制与结果判断

- [ ] 区分单轮限制和每日累计限制，修正主动串门的 `dailyLimit` 语义。
- [ ] 修正好友照料的实际限制、扫描周期和 README 描述，使代码与文档一致。
- [ ] PK 结算失败时不要计入已完成场次。
- [ ] 明确 PK 本地战力比较只用于日志，或改用服务端返回的真实结果。
- [ ] 检查好友空闲状态获取失败时的雇佣与照料决策。
- [ ] 修复 `parseEmployedUin` 始终返回 `0` 的死参数问题，或删除无效调用。

## P1：协议语义确认（需真机或抓包）

- [ ] 确认 `PetBathProtocolClient.bath()` 使用的 `0x96a6_1` 是洗澡接口、兼容接口还是行为埋点接口。
- [ ] 确认 `0x9bf3_1` 与 `0x96a6_1` 的调用顺序和实际职责。
- [ ] 确认 `feedState` 各数值的服务端语义。
- [ ] 确认 `orderResult` 的成功、失败和处理中状态。
- [ ] 确认协议字段 `135091`、`135092`、`135096`、`135098` 的含义。
- [ ] 在不同 QQ 版本上验证设置注入类名、回调签名和协议代理类是否兼容。

## P2：UI 与性能

- [ ] `SettingsState.refresh()` 每秒全量重读 prefs 并写入 Compose 快照，改为只更新变化项或事件驱动。
- [ ] `SettingsState.pkBlacklistSummary` 每秒重算（读 prefs + 缓存好友），必要时改为按需刷新。
- [ ] 被锁的分段项现在仅置灰 + 文案提示，旧实现是点击 Toast 说明原因，按需补回。
- [ ] `ComposeInjectionHost` 目前无条件自建 `ViewTree*Owner`；若宿主将来提供，应优先使用宿主的，避免覆盖。
- [ ] `QPetExpressiveTheme` 关闭了动态取色（宿主进程里 `dynamicLightColorScheme` 解析出全 0 导致整页变黑）；若后续 androidx 修复可重新评估。
- [ ] 更新 README，使其与当前调度周期、任务限制和新 UI 一致；并统一仓库地址（`congsmile/qpet-companion` 与 `orz12/qpet-companion`）。

## P2：可维护性与清理

- [ ] material3 目前钉在 `1.5.0-alpha29`（expressive API 只在 alpha 线），等 1.5.0 稳定后切回稳定版。
- [ ] `gradlew wrapper --gradle-version 9.8.0` 重新生成 wrapper jar（当前仍是 8.6 版，只换了 distributionUrl）。
- [ ] release 打开 R8（现在 `optimization { enable = false }`，APK 约 12.9 MB），补 libxposed 入口与 Compose 的 keep 规则并实测。
- [ ] 增加 `.gitattributes` 统一换行，消除 git 反复提示的 LF→CRLF 警告。
- [ ] `HostClassLoaderBridge.HOST_PREFIXES` 仍含 `com.tencent.biz.`、`com.tencent.widget`（QUI 桩已删），确认无用后清理。
- [ ] 统一配置存储，避免普通 key、账号作用域 key 与多份 Preferences 互相覆盖。
- [ ] 清理 UI 中存在但后台未完整使用的学习模式、打工模式等配置。
- [ ] 统一重复的 `8000 ms` 网络超时常量，合并结构相同的 `xxxAwait` 包装代码。
- [ ] 修复 `if (cont.isActive) cont.resume()` 的回调恢复竞态。
- [ ] 清理未使用的 DTO、配置字段和辅助函数。
- [ ] 拆分过长的任务函数，优先处理自适应打工和配置同步。
- [ ] 将静默的 `catch (_: Throwable)` 改为最小范围捕获并记录必要上下文。
- [ ] 移除或接入 `WakeLockHelper` 中未调用的 AlarmManager 唤醒路径。
- [ ] 评估 `DeviceTrace` 将协议内容写入外部文件的隐私和残留风险。

## 发布前

- [ ] 本地提交尚未推送（`https://github.com/orz12/qpet-companion`），推送前确认 README、模块名与包名（`io.github.congsmile.qqpet`）符合 LSPosed 模块仓库要求。