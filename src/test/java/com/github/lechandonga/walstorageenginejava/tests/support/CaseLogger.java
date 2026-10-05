package com.github.lechandonga.walstorageenginejava.tests.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试日志扩展：打印每个业务用例的开始与结论，便于从日志查看覆盖情况。
 */
public class CaseLogger implements BeforeEachCallback, AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-CASE");

    @Override
    public void beforeEach(ExtensionContext context) {
        log.info("【用例开始】业务[{}] 用例[{}]",
                context.getTestClass().map(Class::getSimpleName).orElse("?"),
                context.getDisplayName());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        boolean passed = context.getExecutionException().isEmpty();
        log.info("【用例{}】业务[{}] 用例[{}]",
                passed ? "通过" : "失败",
                context.getTestClass().map(Class::getSimpleName).orElse("?"),
                context.getDisplayName());
    }
}
