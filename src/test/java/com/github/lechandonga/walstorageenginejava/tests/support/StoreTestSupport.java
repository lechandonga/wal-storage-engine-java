package com.github.lechandonga.walstorageenginejava.tests.support;

import com.github.lechandonga.walstorageenginejava.engine.KeyValueStore;
import com.github.lechandonga.walstorageenginejava.engine.StoreConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 测试辅助：在临时目录中创建引擎、模拟崩溃、破坏文件。
 */
public final class StoreTestSupport {

    private StoreTestSupport() {
    }

    public static StoreConfig config(Path dir) {
        return new StoreConfig(dir.toString(), 128, 128, 100);
    }

    /** 模拟进程异常退出：仅关闭底层文件句柄，不执行 {@link KeyValueStore#close()} 的优雅收尾。 */
    public static void hardCrash(KeyValueStore store) throws Exception {
        java.lang.reflect.Field channelField = KeyValueStore.class.getDeclaredField("channel");
        channelField.setAccessible(true);
        var channel = (java.nio.channels.FileChannel) channelField.get(store);
        if (channel != null) {
            channel.close();
        }
    }

    public static Path walFile(Path dir) {
        return dir.resolve("wal.log");
    }

    public static void truncateFile(Path file, long newSize) throws IOException {
        try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            ((java.nio.channels.FileChannel) channel).truncate(newSize);
        }
    }

    public static void corruptBytes(Path file, long position, int count) throws IOException {
        try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            var fileChannel = (java.nio.channels.FileChannel) channel;
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(count);
            for (int i = 0; i < count; i++) {
                buffer.put((byte) 0xFF);
            }
            buffer.flip();
            fileChannel.write(buffer, position);
        }
    }

    public static void rewriteVersionByte(Path file, int version) throws IOException {
        try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            var fileChannel = (java.nio.channels.FileChannel) channel;
            fileChannel.write(java.nio.ByteBuffer.wrap(new byte[]{(byte) version}), 4);
        }
    }
}
