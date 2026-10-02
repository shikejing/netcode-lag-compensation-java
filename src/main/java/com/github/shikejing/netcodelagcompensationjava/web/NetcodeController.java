package com.github.shikejing.netcodelagcompensationjava.web;

import com.github.shikejing.netcodelagcompensationjava.engine.ServerEngine;
import com.github.shikejing.netcodelagcompensationjava.input.InputQueue;
import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.HitRecord;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;
import com.github.shikejing.netcodelagcompensationjava.time.ClockEstimator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class NetcodeController {

    private final ServerEngine engine;

    public NetcodeController(ServerEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/players/{playerId}/inputs")
    public Map<String, Object> submitInput(@PathVariable String playerId, @RequestBody InputRequest req) {
        PlayerInput input = PlayerInput.of(req.sequence(), req.clientTimeNanos(),
                req.moveX(), req.moveY(), req.fire());
        InputQueue.InputDecision decision = engine.submitInput(playerId, input);
        return Map.of(
                "outcome", decision.outcome().name(),
                "sequence", decision.sequence(),
                "detail", decision.detail() == null ? "" : decision.detail());
    }

    @PostMapping("/players/{playerId}")
    public Map<String, Object> addPlayer(@PathVariable String playerId) {
        engine.addPlayer(playerId);
        return Map.of("playerId", playerId);
    }

    @GetMapping("/snapshots/latest")
    public WorldSnapshot latest() {
        return engine.latestSnapshot().orElse(null);
    }

    @GetMapping("/snapshots/{tick}")
    public WorldSnapshot snapshotAt(@PathVariable long tick) {
        return engine.snapshotAt(tick).orElse(null);
    }

    @GetMapping("/players/{playerId}/clock")
    public ClockEstimator.ClockEstimate clock(@PathVariable String playerId) {
        ClockEstimator c = engine.clockOf(playerId);
        return c == null ? null : c.estimate(engine.currentTick(), engine.properties().getTickRate());
    }

    @GetMapping("/hits")
    public List<HitRecord> hits() {
        return engine.hitRecords();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("currentTick", engine.currentTick());
    }
}
