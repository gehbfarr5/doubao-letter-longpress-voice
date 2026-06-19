# 当前任务计划 (v1.5.x)

## Task B — 无障碍保活 Layer 1（先执行）

**档位**：默认（`gpt-5.4`）
**类型**：代码改动，走完整流程
**HANDOFF**：`orchestra/HANDOFF-a11y-keepalive-layer1.md`

### 涉及文件

- `app/src/main/java/com/jin/doubaolongpressvoice/BootRestoreReceiver.java`（新建）
- `app/src/main/AndroidManifest.xml`（加 permission + receiver）

### 机器可检验收条件

1. `test -f app/src/main/java/com/jin/doubaolongpressvoice/BootRestoreReceiver.java`
2. `grep -c "RECEIVE_BOOT_COMPLETED" app/src/main/AndroidManifest.xml` → `1`
3. `grep -c "BootRestoreReceiver" app/src/main/AndroidManifest.xml` → `1`
4. `grep -c "LAUNCHER" app/src/main/AndroidManifest.xml` → `0`（保持无桌面图标）
5. `./gradlew :app:assembleDebug` 编译通过（exit 0）

---

## Task C — pollAsrSettleThen → L.a listener 替换（A 完成后执行）✅ 待执行

**档位**：质量优先（`gpt-5.5`）
**类型**：代码改动，走完整流程
**HANDOFF**：`orchestra/HANDOFF-poll-to-listener.md`

### 涉及文件
- `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`（只改这一个）

---

## Task A — 逆向豆包 ASR 完成机制（B 完成后执行）✅ 已完成

**档位**：质量优先（`gpt-5.5`）
**类型**：运行/分析迭代，不走完整验收/commit
**HANDOFF**：`orchestra/HANDOFF-doubao-internals.md`

### 涉及文件

- `orchestra/DOUBAO-INTERNALS.md`（产出，新建）
- `/tmp/doubao.apk`、`/tmp/doubao-jadx/`（临时，不进 git）

### 产出验收

- `test -f orchestra/DOUBAO-INTERNALS.md`
- 文件包含 `## ASR` 和 `## EnterActionType` 两个章节
- 如果部分类找不到，记录"未找到"并给出已发现内容，不空手而归
