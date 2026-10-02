package com.github.shikejing.netcodelagcompensationjava.tests;

import com.github.shikejing.netcodelagcompensationjava.engine.ServerEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST 接口冒烟测试：提交输入、查询快照、时钟与命中。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetcodeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServerEngine engine;

    @Test
    void submitInputAndQuerySnapshot() throws Exception {
        mockMvc.perform(post("/api/players/alice")
                        .contentType("application/json"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/players/alice/inputs")
                        .contentType("application/json")
                        .content("""
                                {"sequence":1,"clientTimeNanos":16666666,"moveX":1,"moveY":0,"fire":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("BUFFERED_OUT_OF_ORDER"));

        // 手动推进几 tick
        for (int i = 0; i < 5; i++) engine.tick();

        mockMvc.perform(get("/api/snapshots/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tick").isNumber())
                .andExpect(jsonPath("$.entities.alice").exists());
    }

    @Test
    void queryClockAndHits() throws Exception {
        mockMvc.perform(post("/api/players/bob")
                        .contentType("application/json"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/players/bob/clock"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/hits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void querySnapshotAtTick() throws Exception {
        mockMvc.perform(post("/api/players/carol")
                        .contentType("application/json"))
                .andExpect(status().isOk());
        for (int i = 0; i < 3; i++) engine.tick();

        mockMvc.perform(get("/api/snapshots/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tick").value(1));
    }
}
