package com.github.lechandonga.walstorageenginejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 上下文装配冒烟测试：验证 KeyValueStore Bean 与 REST 层能正常启动。
 */
@SpringBootTest(properties = "kv.store.directory=${java.io.tmpdir}/wal-kv-spring-smoke")
class ApplicationTests {

    @Test
    void contextLoads() {
    }
}
