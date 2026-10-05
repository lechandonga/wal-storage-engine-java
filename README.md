# WAL 本地键值存储（Spring Boot）

纯本地磁盘的键值存储：写入先落盘并 `fsync`，再向调用方确认成功；进程异常退出后重启，
必然回到最近一次已确认写入的一致状态。全程只使用本地文件，不依赖任何外部服务或网络。

## 功能概览

- 单键写入 `put`、读取 `get`、删除 `delete`，以及原子批量写 `batch`（整体生效或整体不生效）。
- 读取结论三态可区分：`FOUND`（存在）、`DELETED`（写过又删）、`NOT_FOUND`（从未存在）。
- 幂等：重复写入相同值、重复删除均返回 `IDEMPOTENT_NOOP`，不产生错误。
- 写入被拒绝（空键、超长键、空批量、批量内键重复、已关闭实例、IO 故障）抛
  `WriteRejectedException` / 返回 `REJECTED`，与上述读取三态明确区分。
- 崩溃恢复：回放 WAL，尾部截断或 CRC 损坏时安全停在最后一个完好位置并截断修复，
  返回 `RecoveryReport`（截断字节数、生效批次数、可读原因）。
- 未知存储格式版本抛 `WalFormatException`，明确拒绝，绝不猜测解析。
- 并发安全：读操作无锁读取不可变快照，永远看不到半写状态；写操作串行化。
- 一致性备份：`backupTo(path)` 导出某一时刻完整快照；备份期间写入要么完整包含、要么完整不包含；
  备份文件可直接作为新实例的数据文件打开，读取结论与导出时刻一致。
- 日志超过阈值时自动压缩（用存活快照重写 WAL 并原子替换）。

## 存储格式

数据文件固定为数据目录下的 `kv.wal`。所有多字节整数使用小端序。

**文件头（12 字节）**

| 偏移 | 字段 | 长度 | 说明 |
| --- | --- | --- | --- |
| 0 | magic | 4 | `0x57414C4A`，ASCII `"WALJ"` |
| 4 | version | 4 | 格式版本，当前为 `1`；非 1 一律拒绝打开 |
| 8 | reserved | 4 | 保留，必须为 0 |

**帧（每次确认的写入 = 一帧，批量写也是一帧）**

| 字段 | 长度 | 说明 |
| --- | --- | --- |
| magic | 4 | `0x57414C46`，ASCII `"WALF"` |
| totalLength | 4 | 从 batchId 到帧尾（含 CRC）的字节数 |
| batchId | 8 | 单调递增的批次编号 |
| commit | 1 | `1`=已提交；`0`=未提交片段，恢复时一律丢弃 |
| opCount | 4 | 操作数（≥1） |
| ops[] | 变长 | 每个操作：`opType(1)` + `keyLen(4)` + key(UTF-8) + `valLen(4)` + value；DELETE 的 valLen = `-1` 且无 value |
| crc32 | 4 | 对 batchId 起到 CRC 前全部字节计算的 CRC32 |

单值上限 64 MiB、单键上限 1 MiB、单帧上限 256 MiB，超限记录在恢复时判定为损坏。

## 写入与恢复规则

1. 写入流程：参数校验 → 编码整帧 → 追加到 `kv.wal` → `FileChannel.force(true)`
   （数据与目录元数据落盘）→ 一次性替换内存快照 → 返回 `APPLIED`/`IDEMPOTENT_NOOP`。
   任何一步失败都不更新内存，调用方不会收到成功确认，即“整体不生效”。
2. 打开流程：读取文件 → 校验文件头（magic、版本）→ 顺序逐帧校验并回放。
3. 回放时遇到以下任一情况立即停止，并把文件截断到最后一个完好帧之后：
   帧 magic 不匹配、声明长度越界、帧体不完整（尾部截断）、CRC32 不匹配、操作类型/长度非法。
4. `commit=0` 的帧永不应用（未确认写入）；只有完整通过校验的已提交帧按顺序生效。
5. 截断属于就地修复：截断点之后的字节被丢弃，之后实例可继续正常写入；
   `RecoveryReport.reason` 用中文说明是“尾部截断 / CRC 损坏 / 未提交帧丢弃”及丢弃字节数。

## 一致性语义

- 已确认写入不丢：确认发生在 fsync 之后，崩溃不会回退已确认状态。
- 未确认写入不可见：内存视图只在 fsync 成功后切换，崩溃半截帧在恢复时被丢弃。
- 读已提交 + 快照可见性：内存是 `volatile` 不可变 `Map`，一次 `get` 要么看到整个旧版本、
  要么看到整个新版本；批量内多键同帧提交，要么全部可见要么全部不可见。
- 备份一致性：备份在读写锁的读锁内完成（与写入互斥），先写临时文件并 fsync，
  再原子 `rename`；备份文件 = 标准文件头 + 单帧存活快照（墓碑不导出，空库用内部标记帧占位）。
  因此恢复备份后，存活键为 `FOUND`，已删除/不存在键为 `NOT_FOUND`，与导出时刻的有效数据完全一致。

## 配置项（`application.properties`）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `kv.store.directory` | `${user.dir}/data/kvstore` | WAL 数据目录，仅本地磁盘 |
| `kv.store.max-log-bytes` | `67108864`（64 MiB） | WAL 超过该大小后，在写入间隙压缩为快照 |
| `kv.store.web-enabled` | `true` | 是否暴露本地 REST 接口 |
| `server.port` | `0` | Web 端口随机，仅供本地验证 |

直接嵌入使用：

```java
try (KeyValueStore store = new WalKeyValueStore(Path.of("data/kvstore"), 64 * 1024 * 1024)) {
    store.put("k", "v".getBytes(UTF_8));
    store.batch(List.of(KvOperation.put("a", new byte[]{1}), KvOperation.delete("k")));
    GetResult r = store.get("k");          // r.outcome() == DELETED
    store.backupTo(Path.of("backup.wal"));
}
```

REST（值用 Base64 传输）：`GET|PUT|DELETE /api/kv/{key}`、`POST /api/kv/batch`、
`POST /api/kv/backup`。

## 验证方法

沿用工程现有 Maven 构建，全程离线使用本地磁盘（JUnit `@TempDir`）：

```bash
mvn test
```

测试日志通过 `@DisplayName` 标注业务与用例，覆盖：

- 业务1 基础读写语义（`CrudSemanticsTest`）：三态区分、幂等重写/重删、拒绝场景、批量整体生效/整体不生效、批量重复键拒绝。
- 业务2 崩溃恢复（`CrashRecoveryTest`）：重启回到最后已确认状态、重启后继续写入、
  尾部半截帧截断恢复、CRC 损坏停在完好位置、垃圾 magic、未知版本拒绝、文件头截断拒绝。
- 业务3 并发与备份（`ConcurrencyAndBackupTest`）：8 写线程 + 8 读线程并发无撕裂值、
  并发备份为时刻快照（批次整体包含/不包含）、备份恢复到新实例读取结论一致、含删除快照恢复。
- Spring 装配冒烟（`ApplicationTests`）。
