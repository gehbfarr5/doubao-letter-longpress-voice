# PLAN — v1.3.0 ASR-settle newline path

> Planner: Claude Opus 主会话
> 实施模式: Claude 主会话直接落地（诊断迭代密集，不走 Codex executor — 由调试 ROI 决定）
> Verifier: 独立子 Agent (Sonnet, 编译 + 静态断言 + grep)
> 真机回归: 用户在一加 15 上已经在 diag 阶段反复验证（短/长文本各 5+ 次 0 重复）

---

## 一、范围（In Scope）

**核心 bug**：`dispatchNewlineFast` 在某些场景下把 ASR 文本**重复 commit 一次** —— 工具栏判定为"换行/UNSPECIFIED"类编辑器时（便签、Claude、ChatGPT、Nekogram 编辑模式等）出现"输入框里同一段话出现两次，中间夹换行"。

**根因（已诊断锁定）**：
- 旧实现 = `p0(false,"") + 200ms 后 KEYCODE_ENTER`
- `p0()` 触发豆包内部 ASR 异步 finalize（写 composing → polish → commit）
- 200ms 不够 ASR polish 跑完，ENTER 派发时仍有未 finalize 的 composing buffer
- IME 框架在 ENTER 派发前自动调 `finishComposingText()` → 把刚 polish 出来的 composing **又 commit 一次** → 重复
- 复现率随**文本长度增加而上升**：短文本 ~5-10%，长文本（一段话级别）可稳定复现

**修法（已 diag mode 0-8 全套消归对照实测）**：
固定延迟方案天然不稳——文本越长 ASR polish 越久。改为**事件驱动**：监听豆包自己的 ASR callback (`onAsrSetPreedit` / `onAsrCommitPreeditText`)，等 callback 静默 100ms 后才发 ENTER。两阶段 poll：
1. 等 dispatch 后**至少看到一次 ASR callback**（避免短文本时 ENTER 抢在 commit 前发出，造成 "ENTER 然后才上屏" 的逆序）
2. 等 callback 静默 ≥100ms（确认 ASR 真的不再 polish）
3. 2000ms maxWait 兜底，避免极端情况下挂死

## 二、非范围（Out of Scope）

- ❌ **Claude / ChatGPT WebView 适配** —— A 实验已验证 `performEditorAction(SEND)` 在 WebView 桥接不可靠；`KEYCODE_ENTER` 在 Claude 里也只插换行不发送。结论：IME 层路径都不通，需要 Accessibility Service。延后到 v1.4.0。
- ❌ **深 hook 豆包空格长按链路** —— 找豆包内部"ASR finalize"权威信号、作为 ASR-settle poll 的升级。延后到 v1.4.x（详见 FOLLOW-UPS.md #3）。
- ❌ **NEWLINE_KEY_DELAY_MS 常量** —— 已删除，被 NEWLINE_ASR_* 系列替换。
- ❌ **v1.2.0 force-send 名单（Nekogram）** —— 完全不动。
- ❌ **README** —— 暂不更新，等 v1.4.0 一起。

## 三、改动清单

文件 A：`app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`

### 3.1 新增 ASR-settle 状态变量（line ~254）

```java
/** Last time onAsrSetPreedit / onAsrCommitPreeditText fired — used by the ASR-settle poll. */
private static volatile long sLastAsrCallbackTs = 0L;
```

### 3.2 新增常量（替换原 `NEWLINE_KEY_DELAY_MS = 200L`）

```java
/** Newline-path ASR-settle parameters. */
private static final long NEWLINE_ASR_POLL_MS = 50L;
private static final long NEWLINE_ASR_SETTLE_MS = 100L;
private static final long NEWLINE_ASR_MAX_WAIT_MS = 2000L;
```

### 3.3 ASR callback timestamp 更新

`installAsrHooks` 内的两个 hook (`onAsrCommitPreeditText` / `onAsrSetPreedit`) 的 `beforeHookedMethod` 里，**正常路径**（非 cancel）更新 timestamp：

```java
sLastAsrCallbackTs = SystemClock.elapsedRealtime();
```

### 3.4 重写 `dispatchNewlineFast`

```java
private static void dispatchNewlineFast(final ClassLoader cl) {
    Object mgr = ensureAsrManager(cl);
    if (mgr != null) {
        try {
            XposedHelpers.callMethod(mgr, "p0", false, "");
            log("p0(false,\"\") fired (newline path)");
        } catch (Throwable e) {
            log("ERR p0(): " + e.getClass().getSimpleName());
        }
    }
    pollAsrSettleAndEnter(cl,
            NEWLINE_ASR_SETTLE_MS,
            NEWLINE_ASR_MAX_WAIT_MS,
            SystemClock.elapsedRealtime());
}
```

### 3.5 新增 `pollAsrSettleAndEnter`

两阶段 poll：先确认 ASR 有响应（避免 ENTER 抢先），再等静默 100ms。每 50ms re-schedule，2000ms 兜底。

### 3.6 移除项

- `NEWLINE_KEY_DELAY_MS` 常量（被 NEWLINE_ASR_* 替换）
- 所有 v1.3.0-diag 内容：`getDiagMode`、`diagInspect`、`diagFinishComposing`、`diagQuote`、mode 0-8 分支、`DIAG[...]` log

文件 B：`app/build.gradle`

```diff
- versionCode 3
- versionName "1.2.0"
+ versionCode 4
+ versionName "1.3.0"
```

## 四、Verifier 验收点

### 4.1 编译 + APK 产出

```bash
./gradlew :app:assembleDebug
```
- exit 0
- `app/build/outputs/apk/debug/*.apk` 存在

### 4.2 静态断言 grep

- `sLastAsrCallbackTs` 字段存在，标记 volatile
- 两个 ASR callback hook (`onAsrCommitPreeditText` + `onAsrSetPreedit`) 都包含 `sLastAsrCallbackTs = SystemClock.elapsedRealtime();`
- `dispatchNewlineFast` 函数体调用 `pollAsrSettleAndEnter`，**不再**包含 `postDelayed.*sendEnterKey.*NEWLINE_KEY_DELAY_MS`
- `pollAsrSettleAndEnter` 函数存在 + 包含 `asrRespondedAfterDispatch` 变量 + 包含 `NEWLINE_ASR_MAX_WAIT_MS` 时 timeout fallback
- `NEWLINE_KEY_DELAY_MS` 完全删除（grep 全文件应该 0 命中）
- 所有 diag 残留 (`getDiagMode` / `diagInspect` / `diagFinishComposing` / `diagQuote` / `debug.dispatch_diag` / `DIAG[`) 全部删除
- `app/build.gradle`：`versionCode 4` + `versionName "1.3.0"`

### 4.3 回归 grep（确认 v1.2.0 关键代码不被改动）

- `FORCE_SEND_PACKAGES` 仍是 `Collections.singleton("tw.nekomimi.nekogram")`（**只 nekogram**，没有 claude）
- `resolveEffectiveEnterOrdinal`、`currentEditorPackageName`、`isSpecificSendOrdinal` 函数体未改
- `dispatchViaAsrManagerT`、`sendEnterKey`、`commitVoice` 函数体未改

### 4.4 不在范围

- ❌ 真机测试（用户在 diag 阶段已完成）

## 五、人工实测矩阵（已在 diag 阶段验证）

| 场景 | 期望 | 状态 |
|---|---|---|
| 便签 短文本 | 不重复 + 顺序正确（先文本后换行）| ✅ 已验证 |
| 便签 长文本 | 不重复 | ✅ 已验证 |
| Nekogram 聊天框（v1.2.0 force-send 路径不变） | 正常发送 | ⚠️ 收口后回归一次 |
| Nekogram 搜索框 | 正常搜索 | ⚠️ 收口后回归一次 |
| 微信 聊天框 | 正常发送 | ⚠️ 收口后回归一次 |
| 微信 搜索框 | 正常搜索 | ⚠️ 收口后回归一次 |
| Claude | 文本上屏 + 换行（**不能发送**，已知限制）| ⚠️ 收口后回归一次 |
| ChatGPT | 同 Claude | ⚠️ 收口后回归一次 |

## 六、Git 流程

1. 分支 `feat/asr-settle-newline-fix`（已切，由 `wip/diag-newline-bug` 重命名）
2. **plan commit** 含 PLAN-v1.3.0.md + FOLLOW-UPS.md
3. **code commit** 含 .java + build.gradle 清理后改动
4. Verifier 子 Agent PASS → push -u origin
5. 用户实测 P0 → 合并 main + tag v1.3.0 → CI release

## 七、风险与限制

- **2 秒 maxWait 兜底**：极端场景（网络极慢 + 极长文本 + ASR 引擎挂死）可能触底强制 ENTER，仍有重复风险。当前用户使用模式下评估为极低概率。
- **依赖豆包 ASR callback API 稳定**：豆包后续大版本如果改 `onAsrSetPreedit` / `onAsrCommitPreeditText` 签名，hook 会失效，fallback 到 v1.1.0 兜底 (forceVad + DoFunctionKey(7))。未跨豆包大版本测试。
- **本质上是 workaround**：根本性优化（深 hook 豆包空格长按拿权威 ASR-finalize 信号）见 FOLLOW-UPS.md #3。
