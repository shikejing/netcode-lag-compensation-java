# 服务端延迟补偿与状态快照 — 设计说明

一套完全运行在**单进程内**的实时对战服务端能力：时间戳输入、固定步长权威世界、
可回放的状态快照、客户端/服务器时钟估计、有界回退的延迟补偿命中判定，以及观察者
插值输出。链路通过内存回环管道（`InMemoryTransport`）送达，不依赖任何外部服务。

## 1. 总览与模块

| 模块 | 职责 |
| --- | --- |
| `config.ServerConfig` | 全部可调参数（时间均以毫秒计），不可变 |
| `time.ClockEstimator` | 客户端↔服务器时钟偏移与 RTT 估计，EMA 平滑，单调不回拨 |
| `model.*` | `InputCommand` / `PlayerState` / `WorldSnapshot` / `ShotQuery` / `ShotResult` 等不可变值类型 |
| `simulation.World` | 纯确定性固定步长仿真（移动 + 开火命中） |
| `simulation.DeterministicRng` | 64 位 LCG，状态随每个快照流转，支持从保存状态恢复 |
| `snapshot.SnapshotHistory` | 只追加、按 tick 索引、有保留边界的不可变快照历史 |
| `snapshot.SnapshotInterpolator` | 严格在两个**已收到**快照之间插值，永不外推 |
| `input.InputMailbox` | 并发安全、按客户端隔离、`(clientId, sequence)` 去重、按 tick 排空 |
| `input.AuditLog` | 有界环形审计日志：每次摄入决定都可追溯 |
| `lagcomp.LagCompensator` | 按射手视角把世界回退到其输入时刻后做命中判定，回退窗口有硬上界 |
| `transport.InMemoryTransport` | 进程内回环网络：固定种子确定性地模拟抖动/丢包/重复/乱序 |
| `engine.GameServer` | 前门：并发摄入 + 单写者串行推进 + 状态导出/恢复 |

## 2. 时间模型

- **固定步长**：服务器以 `tickDurationMs`（默认 50 ms，20 Hz）为一个权威 tick。
  快照满足恒等式 `serverTimeMs == tick * tickDurationMs`。tick 0 是注册玩家齐备
  后、首次 `advanceTo` 时发布的初始快照。
- **输入时间戳量化**：客户端输入携带其本地时钟时间戳；服务器用该客户端的时钟估计
  把它映射到服务器时间，再量化到覆盖该时刻的权威 tick：
  `targetTick = floor(serverTimeEstimate / tickDurationMs)`。tick N 的输入时间窗为
  `[N*dt, (N+1)*dt)`。
- **时钟同步**（`ClockEstimator`，NTP 中点法）：
  - 一次采样 `(clientSend, serverReceive, clientReceive)`：
    `rtt = clientReceive - clientSend`，
    `offsetInstant = serverReceive - (clientSend + rtt/2)`。
  - RTT 与 offset 各自用独立指数滑动平均（默认 alpha=0.125 平滑 RTT、alpha=0.1 平滑 offset），
    抖动被平滑并体现在 `ClockSnapshot.rttSmoothedMs / offsetSmoothedMs`。
  - offset 移动超过平滑 RTT 的 1/4，或客户端时间戳本身回退，记为一次时钟调整
    （`adjustments` 计数）。
- **时钟回拨安全（关键）**：客户端到服务器的映射结果在该客户端上**单调不减**。
  当原始映射小于曾经给出的最大值时，结果被钳制到该最大值，`ClockSnapshot.clamped`
  置真。因此客户端时钟被回拨/调整，回放与回退使用的服务器时间轴既不倒流也不跳变。

## 3. 权威推进与回放一致性

- 每次推进只做一件事：排空**恰好**目标 tick 的完整输入集合，对当前快照执行一次纯
  函数式 `World.step`，原子发布一个不可变 `WorldSnapshot`。
- 快照不可变，`SnapshotHistory.at(tick)` 取回的就是当时步进产生的同一对象。因此
  “回放到任意保留时刻”与“直接重新推进到该时刻”结果逐位一致（位置、朝向、血量、
  RNG 状态、事件列表）。由
  `ReplayConsistencyTest.storedSnapshotAtEveryRetainedTickEqualsDirectRun` 覆盖。
- **确定性**：移动只依赖输入与常量；开火使用嵌入快照的 `DeterministicRng` 做微小
  散布。相同种子 + 相同输入 => 逐帧相同输出。RNG 状态被序列化进快照，`exportState`
  -> 以 `ServerState` 重启 -> 继续推进，结果与一次性长跑完全一致，重启无状态跳变、
  无重复 tick（见 `WorldDeterminismTest`、
  `ReplayConsistencyTest.replayFromRestoredStateMatchesContinuedDirectRun`）。

### 移动与命中模型

- 移动：按输入位移向量移动，位移模长被权威钳制在 `MAX_MOVE_PER_TICK = 2.0`
  （防作弊），结果再钳制到方形场地；死亡玩家不再移动。
- 开火（在 tick T 应用）：把所有身体回退到射手感知时刻（tick T-1 快照），射线从
  射手回退位置发出、使用其 tick T 朝向（叠加确定性散布），命中最近的存活圆目标；
  伤害计入 tick T。事件写入快照 `events`，可追溯。

## 4. 回退窗口（延迟补偿）

`LagCompensator.evaluate(query, history)` 的结论是**可区分的枚举**，超出窗口的请求
既不被静默接受、也不被静默丢弃：

| 条件 | 结论 `HitVerdict` |
| --- | --- |
| 映射 tick 小于 当前 tick - maxRewindTicks，或早于仍保留的最老快照，或窗口内快照缺失 | `REJECTED_BEYOND_WINDOW` |
| 映射 tick 大于等于 当前 tick（未来） | `REJECTED_FUTURE` |
| 在窗口内且射线命中 | `HIT`（含 `targetId / rewoundTick / rewindAgeTicks / hitPoint`） |
| 在窗口内但未命中 | `MISS` |

每个结果都带人类可读 `reason` 与数值化回退年龄（tick 数），可审计。默认
`maxRewindTicks = 10`（500 ms）。

## 5. 插值输出边界

`SnapshotInterpolator` 的渲染目标时间默认比最新快照晚 `interpolationDelayTicks`
（默认 2 tick），使查询时间通常被两个真实快照夹住。边界行为**确定**：

| 情形 | `InterpStatus` | 是否给位置 |
| --- | --- | --- |
| 在相邻两快照之间 | `OK`，线性插值位置/角度 | 是 |
| 查询时间 >= 最新快照 | `AWAITING_SNAPSHOT`，**永不外推**、不冻结在旧点 | 否（空 map） |
| 查询时间 < 仍保留的最老快照 | `BEHIND_HISTORY` | 否（空 map） |
| 夹取快照缺失（丢包/乱序造成中间空洞） | `GAP` | 否（空 map） |

**缺失绝不被当成静止或零值**：一旦夹取对不齐，整帧不给任何玩家位置并给出原因。
玩家只在两端快照都存在时才插值，只出现在一端的玩家不会被凭空放到原点。

`SnapshotHistory` 的丢弃边界是单一显式规则：追加 tick N 后，删除严格老于
`N - snapshotRetainTicks` 的快照；恰好落在保留下界的快照仍保留（窗口大小 =
`retainTicks + 1`）。默认保留 100 tick（约 5 s）。

## 6. 乱序 / 迟到 / 重复输入

- **乱序**：输入按服务器量化的 `targetTick` 入每客户端有序队列，与到达顺序无关；
  推进某 tick 时才排空该 tick，所以先到的“未来”输入与后到的“当前”输入都会在正确
  tick 被应用。
- **重复**：以 `(clientId, sequence)` 去重，重复包返回 `DUPLICATE`，绝不应用两次；
  重复携带的不同载荷也被忽略。
- **迟到**：目标 tick 小于等于 已发布 tick 的输入返回 `LATE_REJECTED`，**永不改写已
  对外发布的权威结果**（`SnapshotHistory.append` 也会拒绝同 tick 二次发布）。
- **过远未来**：`targetTick > currentTick + maxFutureInputTicks` 返回
  `TOO_FAR_FUTURE`（默认容差 10 tick / 500 ms）。
- **可解释、可追溯**：每次摄入都写入 `AuditLog`（`InputAudit`：当前 tick、目标
  tick、处置、明细原因）。乱序“修正”前后的差异因此可逐条解释。

## 7. 并发模型

- 任意客户端线程可并发调用 `ingest` / 时钟采样；它们只触碰线程安全的 `InputMailbox`、
  每客户端 `ClockEstimator` 与 `AuditLog`，从不修改已发布快照。
- 固定步长推进在 `advanceLock` 上**串行化**：逐步排空输入 -> 步进 -> 原子发布。
  外部任意时刻读到的 `currentSnapshot()` 都是一个自洽快照，不会出现“部分输入已
  应用”的状态。
- 邮箱是**按客户端有界**的：某客户端积压超过 `maxInputQueuePerClient`（默认 128）
  返回 `BACKLOG_FULL`，只隔离该客户端，不阻塞其他客户端推进。

## 8. 进程内回环管道

`InMemoryTransport` 用调用方注入的 `DeterministicRng` 决定丢包、抖动、重复与乱序，
RNG 流由场景持有：同一种子 => 每次运行、包括重启后，交付序列完全一致。每个接收者
有独立收件箱，慢对端不会阻塞他人。测试可借此复现“乱序+抖动+丢包”的完整链路
（见 `InMemoryTransportTest`）。

## 9. 配置项（`ServerConfig.defaults()`）

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `tickDurationMs` | 50 ms | 固定步长（20 Hz） |
| `interpolationDelayTicks` | 2 | 观察者插值延迟（渲染目标落后最新快照的 tick 数） |
| `snapshotRetainTicks` | 100 | 快照保留 tick 数（约 5 s）；回退与回放的可用深度 |
| `maxRewindTicks` | 10 | 延迟补偿回退窗口硬上界（500 ms） |
| `maxFutureInputTicks` | 10 | 可接受的未来输入前看 tick 数（500 ms） |
| `maxInputQueuePerClient` | 128 | 每客户端输入积压上界，超出仅隔离该客户端 |
| `arenaHalfSize` | 100.0 | 方形场地半边长，位置钳制到 [-100,100] |
| `playerRadius` | 1.0 | 玩家碰撞半径 |
| `hitRadius` | 0.8 | 命中判定附加半径（有效命中半径 = 两者之和） |
| `fireDamage` | 25.0 | 每次命中伤害 |
| `rngSeed` | 0x9E3779B97F4A7C15 | 确定性仿真种子 |

时钟平滑系数在 `GameServer` 内构造 `ClockEstimator` 时给出（RTT alpha=0.125、offset
alpha=0.1），传输层的延迟/抖动/丢包/重复/乱序参数在 `InMemoryTransport` 构造器给出。

## 10. 测试覆盖

| 测试类 | 覆盖场景 |
| --- | --- |
| `ClockEstimatorTest` | RTT/offset 平滑、时钟回拨不逆流、单调映射、跳变计数 |
| `WorldDeterminismTest` | 同种子同输入逐帧一致、异种子发散、从保存状态重启无跳变 |
| `ReplayConsistencyTest` | 回放 == 直接推进、保留边界精确、重启后续跑一致 |
| `InputOrderingTest` | 乱序、重复只应用一次、迟到不改写历史、过远未来拒绝、审计可追溯 |
| `LagCompensationTest` | 回退命中、按射手时刻命中移动目标、窗口外/未来可区分拒绝 |
| `SnapshotInterpolatorTest` | OK 插值、GAP/BEHIND/AWAITING 边界、缺失不当静止/零值 |
| `InMemoryTransportTest` | 同种子交付序列一致、丢包/重复/乱序、收件箱隔离 |
| `ConcurrentAdvanceTest` | 并发摄入 + 串行推进只见自洽快照、重复计数、慢客户端不阻塞他人 |
