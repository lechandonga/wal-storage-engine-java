package com.github.lechandonga.walstorageenginejava.tests.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在测试日志中打印业务模块、用例名称及每个用例的通过/失败/跳过结论。
 */
public class BusinessLoggingExtension implements BeforeAllCallback, AfterAllCallback, TestWatcher {

    private static final String GREEN = "✅";
    private static final String RED = "❌";
    private static final String SKIP = "⏭️";

    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    @Override
    public void beforeAll(ExtensionContext context) {
        System.out.println();
        System.out.println("========== 业务模块：" + businessName(context) + " ==========");
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        passed.incrementAndGet();
        System.out.println(GREEN + " [用例通过] " + caseName(context));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        failed.incrementAndGet();
        System.out.println(RED + " [用例失败] " + caseName(context) + " -> " + cause);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        System.out.println(SKIP + " [用例中止] " + caseName(context));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        System.out.println(SKIP + " [用例跳过] " + caseName(context));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        System.out.println("---------- " + businessName(context) + " 小结：通过 "
                + passed.get() + "，失败 " + failed.get() + " ----------");
    }

    private static String businessName(ExtensionContext context) {
        return context.getDisplayName();
    }

    private static String caseName(ExtensionContext context) {
        return context.getDisplayName();
    }
}
