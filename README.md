# 服务端延迟补偿与状态快照系统

面向实时对战的进程内 netcode 库：客户端移动/开火输入通过本地回环或内存管道送达，
服务端在输入乱序、抖动、丢包下给出可复现、自洽的命中判定。全链路不依赖外部服务。

## 核心能力

| 需求 | 实现 |
|------|------|
| 固定步长 + 权威快照 | `ServerEngine` 按 `tickRate` 固定步长推进；每 tick 发布不可变 `WorldSnapshot` |
| 快照回放一致性 | `SnapshotHistory` 保存每 tick 快照与输入；`rewindTo(t)` 优先取已存快照，缺失则从最近快照确定性重放，结果与直接推进一致 |
| 时钟估计与平滑 | `ClockEstimator` 用 EMA 平滑时钟偏移与 RTT 抖动；单样本修正幅度有上限；正向时间轴映射单调不减 |
| 延迟补偿命中 | `LagCompensator` 按射手视角回退到其输入时刻做命中扫描；回退窗口有上界，超界返回可区分的 `TOO_LATE` |
| 插值输出 | `Interpolator` 在两个权威快照间线性插值；边界 `CLAMPED_OLDEST/LATEST`；实体缺失标记 `CLAMPED_SINGLE`，绝不按零值处理 |
| 乱序/迟到/重复输入 | `InputQueue` 按 sequence 去重；迟到输入不回改已发布快照；所有判定可计数、可追溯 |
| 并发推进 | 输入线程只写并发队列；唯一推进线程发布不可变快照；慢客户端不阻塞其他玩家 |
| 逐帧一致 + 重启不跳变 | 固定种子；`FileJournal` 每 tick 追加快照/输入/命中；重启后重建历史，tick 与命中 id 连续不重复 |
| 内存管道 | `InMemoryChannel` 模拟丢包、抖动、乱序、重复；固定种子可复现 |

## 时间模型

- **tick**：服务端以固定步长推进，`tickRate` 默认 60Hz。tick 从 1 开始编号，tick 0 为空初始快照。
- **客户端时间戳**：每个输入携带客户端时钟纳秒数 `clientTimeNanos`。
- **时钟偏移**：服务端收到输入时采样 `offset = serverRecvNanos - clientTimeNanos`，用 EMA 平滑（`clockSmoothingAlpha`）。
  - 假设上下行对称，`RTT ≈ 2 * 单向偏移`，抖动为 `|样本 - 平滑偏移|` 的 EMA。
  - 单样本对偏移的修正幅度有上限（`clockClampNanos`），时钟跳变/回拨不会一次性反映到时间轴。
- **时间戳 → tick 映射**（`Tick.nanosToTick` 四舍五入，避免 1/60s 除不尽导致整体错位）：
  - `targetTick(clientTime)`：输入调度用，不 clamp；大于当前 tick 表示未来输入（等待），小于表示迟到。
  - `mapToServerTick(clientTime, currentTick)`：正向时间轴（渲染/插值）用，单调不减且落在 `[0, currentTick]`，时钟回拨时回放时间轴不倒流。
  - `rewindTick(clientTime, currentTick)`：延迟补偿用，允许回到过去时刻（射手输入时刻），clamp 到 `[0, currentTick]`。

## 回退窗口

- `maxRewindTicks`（默认 12，约 200ms @ 60Hz）：延迟补偿允许回退的最大 tick 数。
- 开火判定时计算 `age = currentTick - rewindTick`：
  - `age <= maxRewindTicks`：回退到 `rewindTick` 做命中扫描（`HIT` / `MISS`）。
  - `age > maxRewindTicks`：返回 `TOO_LATE`，明确区分，不静默接受也不静默拒绝。
  - 射手不存在/死亡：返回 `INVALID`。
- 命中扫描为纯几何判定：射线 vs 命中圆（`World.hitscan`），无随机因素。

## 输入处理规则

- 每个输入有单调递增 `sequence`（每玩家）。
- `InputQueue.submit` 判定：
  - `DUPLICATE`：sequence 已存在（已应用或已缓冲），不重复应用。
  - `BUFFERED_OUT_OF_ORDER`：sequence 超前，缓冲等待。
  - `LATE_ALREADY_PUBLISHED`：sequence 已发布，不回改。
- 每 tick 推进线程：
  1. `dueFireInputs`：取出映射时刻 ≤ 当前 tick 的开火输入（去重），按射手视角回退判定。
  2. `pollDue`：取出映射时刻 == 当前 tick 的输入应用移动；迟到输入丢弃并记录。
  3. 生成不可变快照并发布。
- 同一 tick 映射到多个输入时，保留最新 sequence，其余标记 `superseded`。

## 插值边界

- `renderTick = currentTick - interpolationDelayTicks`（默认延迟 2 tick）。
- `Interpolator.interpolate(history, renderTick)`：
  - 无快照 → `NO_DATA`。
  - renderTick ≤ 最旧快照 → `CLAMPED_OLDEST`（alpha=0）。
  - renderTick ≥ 最新快照 → `CLAMPED_LATEST`（alpha=1）。
  - 否则在 `floor` 与 `ceil` 快照间线性插值位置；离散状态（血量/存活）取 floor 侧。
  - 实体只在一侧存在 → `CLAMPED_SINGLE` 取该侧值，**不按零值处理**。

## 配置项（`application.properties`）

```properties
netcode.tick-rate=60
netcode.max-rewind-ticks=12
netcode.snapshot-history-ticks=256
netcode.interpolation-delay-ticks=2
netcode.fixed-seed=42
netcode.clock-smoothing-alpha=0.1
netcode.clock-clamp-nanos=250000000
# 日志路径（留空则不持久化，重启不恢复）
netcode.journal-path=
# 内存管道（可复现网络模拟）
netcode.network.base-delay-nanos=5000000
netcode.network.jitter-nanos=15000000
netcode.network.loss-rate=0.05
netcode.network.duplicate-rate=0.02
netcode.network.seed=7
```

## REST 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/players/{id}` | 加入玩家 |
| POST | `/api/players/{id}/inputs` | 提交输入（sequence/clientTimeNanos/moveX/moveY/fire） |
| GET | `/api/snapshots/latest` | 最新权威快照 |
| GET | `/api/snapshots/{tick}` | 指定 tick 快照（可回放） |
| GET | `/api/players/{id}/clock` | 时钟估计（offset/jitter/rtt/mappedTick） |
| GET | `/api/hits` | 命中记录 |
| GET | `/api/stats` | 统计信息 |

## 测试覆盖

- `SnapshotReplayTest`：回放一致性（剪枝后重放 == 直接推进）、逐帧一致。
- `InputOrderingTest`：乱序、重复、迟到、慢客户端不阻塞。
- `ClockEstimatorTest`：偏移收敛、抖动平滑、回拨单调、跳变钳制。
- `InterpolationTest`：双快照插值、边界、缺失实体、乱序到达。
- `LagCompensationTest`：命中、未命中、超窗 TOO_LATE、回退位置判定、重复开火。
- `ConcurrencyTest`：并发提交快照自洽、慢客户端不阻塞、快照不可变。
- `DeterminismAndRestartTest`：固定种子逐帧一致、重启不跳变/不重复、重启后回放。
- `NetworkChannelTest`：丢包、抖动乱序、重复、固定种子可复现。
- `NetcodeControllerTest`：REST 接口冒烟。

## 运行

```bash
mvn test          # 运行全部测试
mvn spring-boot:run  # 启动服务（默认不持久化）
```
