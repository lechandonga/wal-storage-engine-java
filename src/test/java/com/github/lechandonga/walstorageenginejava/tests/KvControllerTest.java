package com.github.lechandonga.walstorageenginejava.tests;

import com.github.lechandonga.walstorageenginejava.engine.KeyValueStore;
import com.github.lechandonga.walstorageenginejava.engine.StoreConfig;
import com.github.lechandonga.walstorageenginejava.tests.support.CaseLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务：REST 接口（写入/读取/删除/批量/备份/恢复结论）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(CaseLogger.class)
class KvControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kv.store.data-dir", () -> tempDir.toString());
        registry.add("kv.store.max-key-bytes", () -> 64);
        registry.add("kv.store.max-value-bytes", () -> 64);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeyValueStore store;

    @Test
    @DisplayName("PUT/GET/DELETE 全链路：存在、已删除结论可区分")
    void putGetDeleteOverHttp() throws Exception {
        mockMvc.perform(put("/api/kv/alpha").contentType("application/json")
                        .content("{\"value\":\"one\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        mockMvc.perform(get("/api/kv/alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.value").value("one"));

        mockMvc.perform(delete("/api/kv/alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        mockMvc.perform(get("/api/kv/alpha"))
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.reason").value("键存在过但已被删除"));

        mockMvc.perform(get("/api/kv/missing"))
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.reason").value("键从未写入"));
    }

    @Test
    @DisplayName("超长值写入通过 HTTP 返回 REJECTED，键未创建")
    void rejectedOverHttp() throws Exception {
        String huge = "x".repeat(200);
        mockMvc.perform(put("/api/kv/huge").contentType("application/json")
                        .content("{\"value\":\"" + huge + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(get("/api/kv/huge"))
                .andExpect(jsonPath("$.reason").value("键从未写入"));
    }

    @Test
    @DisplayName("批量接口整体成功；含非法操作时整体拒绝")
    void batchOverHttp() throws Exception {
        mockMvc.perform(post("/api/kv/batch").contentType("application/json")
                        .content("{\"operations\":["
                                + "{\"op\":\"PUT\",\"key\":\"b1\",\"value\":\"1\"},"
                                + "{\"op\":\"DELETE\",\"key\":\"b1\"},"
                                + "{\"op\":\"PUT\",\"key\":\"b2\",\"value\":\"2\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedOps").value(3));

        mockMvc.perform(get("/api/kv/b2"))
                .andExpect(jsonPath("$.value").value("2"));
        mockMvc.perform(get("/api/kv/b1"))
                .andExpect(jsonPath("$.reason").value("键存在过但已被删除"));

        String huge = "y".repeat(200);
        mockMvc.perform(post("/api/kv/batch").contentType("application/json")
                        .content("{\"operations\":["
                                + "{\"op\":\"PUT\",\"key\":\"b3\",\"value\":\"3\"},"
                                + "{\"op\":\"PUT\",\"key\":\"bad\",\"value\":\"" + huge + "\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(get("/api/kv/b3"))
                .andExpect(jsonPath("$.reason").value("键从未写入"));
    }

    @Test
    @DisplayName("备份接口返回备份文件路径，恢复结论接口可查询")
    void snapshotAndReportOverHttp() throws Exception {
        mockMvc.perform(put("/api/kv/snap-key").contentType("application/json")
                        .content("{\"value\":\"snap\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/kv/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backupFile").exists());

        mockMvc.perform(get("/api/kv/recovery-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").exists());
    }
}
