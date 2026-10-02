package com.github.shikejing.netcodelagcompensationjava.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.netcodelagcompensationjava.input.PlayerInput;
import com.github.shikejing.netcodelagcompensationjava.lagcomp.HitRecord;
import com.github.shikejing.netcodelagcompensationjava.snapshot.WorldSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文件日志：每个 tick 一行 JSON（快照 + 输入 + 命中输出），追加写。
 * 重启后 loadLatest 返回最后一个 tick 的完整状态，引擎据此继续推进。
 */
public class FileJournal implements Journal {

    private final Path path;
    private final ObjectMapper mapper;

    public FileJournal(Path path, ObjectMapper mapper) {
        this.path = path;
        this.mapper = mapper;
    }

    @Override
    public synchronized void appendTick(long tick, WorldSnapshot snapshot,
                                        Map<String, PlayerInput> inputs, List<HitRecord> hits) {
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("tick", tick);
            line.put("snapshot", snapshot);
            line.put("inputs", inputs);
            line.put("hits", hits);
            Files.writeString(path, mapper.writeValueAsString(line) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new JournalException("failed to append tick " + tick, e);
        }
    }

    @Override
    public synchronized Optional<JournalState> loadLatest() {
        if (!Files.exists(path)) return Optional.empty();
        try {
            List<String> lines = Files.readAllLines(path);
            JournalState latest = null;
            for (String line : lines) {
                if (line.isBlank()) continue;
                var node = mapper.readTree(line);
                long tick = node.get("tick").asLong();
                WorldSnapshot snapshot = mapper.treeToValue(node.get("snapshot"), WorldSnapshot.class);
                Map<String, PlayerInput> inputs = new LinkedHashMap<>();
                if (node.has("inputs")) {
                    var it = node.get("inputs").fields();
                    while (it.hasNext()) {
                        var f = it.next();
                        inputs.put(f.getKey(), mapper.treeToValue(f.getValue(), PlayerInput.class));
                    }
                }
                List<HitRecord> hits = new ArrayList<>();
                if (node.has("hits")) {
                    for (var h : node.get("hits")) {
                        hits.add(mapper.treeToValue(h, HitRecord.class));
                    }
                }
                latest = new JournalState(tick, snapshot, inputs, hits);
            }
            return Optional.ofNullable(latest);
        } catch (IOException e) {
            throw new JournalException("failed to load journal from " + path, e);
        }
    }

    /** 读取日志中全部 tick（用于测试与追溯）。 */
    public synchronized List<JournalState> loadAll() {
        List<JournalState> out = new ArrayList<>();
        if (!Files.exists(path)) return out;
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank()) continue;
                var node = mapper.readTree(line);
                long tick = node.get("tick").asLong();
                WorldSnapshot snapshot = mapper.treeToValue(node.get("snapshot"), WorldSnapshot.class);
                Map<String, PlayerInput> inputs = new LinkedHashMap<>();
                if (node.has("inputs")) {
                    var it = node.get("inputs").fields();
                    while (it.hasNext()) {
                        var f = it.next();
                        inputs.put(f.getKey(), mapper.treeToValue(f.getValue(), PlayerInput.class));
                    }
                }
                List<HitRecord> hits = new ArrayList<>();
                if (node.has("hits")) {
                    for (var h : node.get("hits")) {
                        hits.add(mapper.treeToValue(h, HitRecord.class));
                    }
                }
                out.add(new JournalState(tick, snapshot, inputs, hits));
            }
            return out;
        } catch (IOException e) {
            throw new JournalException("failed to load journal from " + path, e);
        }
    }

    public static class JournalException extends RuntimeException {
        public JournalException(String message, Throwable cause) { super(message, cause); }
    }
}
