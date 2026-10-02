# netcode-lag-compensation-java

进程内实时对战服务端：固定步长权威世界、可回放状态快照、时钟估计、有界回退的
延迟补偿命中判定与观察者插值。全部链路（含模拟抖动/丢包/乱序/重复的回环管道）
均在本进程内完成，不依赖任何外部服务。

## 快速开始

```bash
mvn test      # 30 个自动化测试
mvn package   # 构建
```

## 能力对照

| 需求 | 实现入口 |
| --- | --- |
| 时间戳输入 / 权威快照 / 固定步长 / 任意时刻回放 | `simulation.World`, `snapshot.SnapshotHistory`, `engine.GameServer` |
| 时钟偏移与 RTT 平滑、回拨不逆流 | `time.ClockEstimator` |
| 有界回退延迟补偿、窗口外可区分结论 | `lagcomp.LagCompensator`（`HitVerdict`） |
| 两快照间插值、缺失不当静止/零值 | `snapshot.SnapshotInterpolator`（`InterpStatus`） |
| 乱序/迟到/重复输入、迟到不改写历史、审计可追溯 | `input.InputMailbox`, `input.AuditLog` |
| 并发摄入与串行推进、只见自洽快照、慢客户端隔离 | `engine.GameServer`（`advanceLock` + 每客户端有界邮箱） |
| 固定种子逐帧一致、重启无跳变 | `simulation.DeterministicRng`, `engine.ServerState` |
| 进程内回环网络（抖动/丢包/重复/乱序） | `transport.InMemoryTransport` |

详细的**时间模型、回退窗口、插值边界、并发模型与全部配置项**见
[`docs/lag-compensation.md`](docs/lag-compensation.md)。
