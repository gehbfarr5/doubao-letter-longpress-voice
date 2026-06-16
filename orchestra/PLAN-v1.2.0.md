# PLAN — v1.2.0 跨 App 发送路由（Nekogram 适配）

> Planner: Claude Opus 主会话
> Executor target: Codex (`gpt-5.5`, 质量优先 — 核心 dispatch 逻辑改动)
> Verifier: 独立子 Agent (Opus, fresh context, 编译 + 静态断言)
> 真机回归：用户在一加 15 上手动跑（Verifier 无设备）

参见同目录 `HANDOFF-cross-app-send.md` 了解需求/方案讨论原始上下文。

---

## 一、范围（In Scope）

修改对象：**字母长按 → 滑动到工具栏松手** 这一条 dispatch 路径（我们自己 hook 的入口 `commitAndDispatchToolbarAction()`）。

新增能力：
1. 对白名单 App（v1.2.0 仅 `tw.nekomimi.nekogram`），当豆包将 enterActionType 判为"换行类"（NONE/UNSPECIFIED/NEXT/DONE/PREVIOUS）时，**强制走 `AsrManager.t(IME_ACTION_SEND=4, now)`**，触发该 App 输入框的 `OnEditorActionListener` 真实发送消息。
2. UI 标签/图标同步：工具栏右键在白名单覆盖生效时，显示"发送"标签和发送图标，**避免和实际行为 mismatch**。
3. 反射调用全程容错：任何取数失败 → 当作未命中名单 → 走原有换行路径，绝不崩。

## 二、非范围（Out of Scope）

- ❌ 豆包**原生**长按空格 → 工具栏发送 路径（不在我们 hook 入口里，需要额外 hook 点）→ v1.2.1 跟进
- ❌ Claude / ChatGPT / 其他 WebView 类 App（机制不同：WebView 桥接坑，需要 KEYCODE_ENTER 而非 performEditorAction）→ v1.3.0 计划
- ❌ README 更新（用户明确暂不动，等更多 App 适配后统一更新）
- ❌ 用户配置 UI（白名单写硬编码常量，加 App 走小版本发版）

## 三、根因摘要

经源码挖掘（`Nekogram/Nekogram` main 分支，`TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java` line 5717-5732）：

```java
messageEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
    public boolean onEditorAction(TextView v, int i, KeyEvent e) {
        if (i == EditorInfo.IME_ACTION_SEND) {
            sendMessage();
            return true;
        }
        // ... else branch handles IME_NULL + KeyEvent path
    }
});
```

Nekogram 消息输入框 **`OnEditorActionListener` 无条件响应 `IME_ACTION_SEND`**（不检查 sendByEnter 设置、不要求 metaState）。但同一文件 line 5646-5652：
```java
flags = EditorInfo.IME_FLAG_NO_EXTRACT_UI;                           // 没声明 action
setImeOptions(flags);
setInputType(... | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);            // 标为多行
```

`MULTI_LINE` + 没声明 action → 豆包按框架约定判为"换行编辑器"。**listener 实际响应能力 vs declared imeOptions 不匹配** 就是问题根源。

**修复策略**：豆包判定 specific (GO/SEARCH/SEND/SEND_EXPRESSION) 时尊重豆包；仅当判定为 newline-class **且** 包名命中白名单时，覆盖派发 ordinal 为 `IME_ACTION_SEND`。

Nekogram 搜索框不受影响：豆包对它判定为 `IME_ACTION_SEARCH (ord=3)`，`isSpecificSendOrdinal(3)==true`，走 specific 路径直返，**白名单分支不会触发** → 搜索语义保留。

## 四、改动清单（精确到函数/行号）

文件 A：`app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`

### 4.1 新增常量（建议放在 line ~130 现有常量块附近，紧挨 `NEWLINE_KEY_DELAY_MS`）

```java
/**
 * v1.2.0: Force-send package list — apps whose chat EditText DOES respond to
 * {@code IME_ACTION_SEND} via OnEditorActionListener, but whose declared
 * imeOptions/inputType make Doubao misclassify the editor as newline-class.
 *
 * Source for Nekogram entry: ChatActivityEnterView line 5717-5732
 * (https://github.com/Nekogram/Nekogram, main branch).
 *
 * When detected enterActionType is non-specific AND current editor's package
 * is in this set, override the dispatch ordinal to IME_ACTION_SEND so
 * AsrManager.t(...) routes through InputConnection.performEditorAction
 * (IME_ACTION_SEND) — which the App's registered listener catches and treats
 * as "send message".
 *
 * Adding a new app requires (1) confirming via source/runtime that its
 * OnEditorActionListener unconditionally responds to IME_ACTION_SEND,
 * (2) appending its package name here, (3) bumping minor version.
 * See orchestra/ADAPT-PLAYBOOK.md for the full adaptation flow.
 */
private static final java.util.Set<String> FORCE_SEND_PACKAGES =
        java.util.Collections.singleton("tw.nekomimi.nekogram");

/** {@code EditorInfo.IME_ACTION_SEND} ordinal — used for force-send override. */
private static final int IME_ACTION_SEND_ORDINAL = 4;
```

### 4.2 新增 helper `currentEditorPackageName`（建议放在 `readInputClass` 旁，line ~975 之后）

沿用现有 `readInputClass` 的取数模式（line 959-974）：

```java
/**
 * Reads the current editor's package name via the IME service. Returns null
 * on any failure (no service, no editor info, missing field) — callers must
 * treat null as "not in any whitelist".
 */
private static String currentEditorPackageName(ClassLoader cl) {
    try {
        Class<?> jni = XposedHelpers.findClass(KEYBOARD_JNI, cl);
        Object ime = XposedHelpers.getStaticObjectField(jni, "mImeService");
        if (ime == null) {
            return null;
        }
        Object ei = XposedHelpers.callMethod(ime, "getCurrentInputEditorInfo");
        if (!(ei instanceof EditorInfo)) {
            return null;
        }
        return ((EditorInfo) ei).packageName;
    } catch (Throwable t) {
        return null;
    }
}
```

### 4.3 新增 helper `resolveEffectiveEnterOrdinal`（建议放在 `resolveEnterOrdinal` 之后，line ~1178）

**这是关键判定函数** —— dispatch 路径和 UI 标签都用它，保证一致：

```java
/**
 * Returns the ordinal Doubao should use for BOTH dispatch AND UI label/icon.
 *
 * Layered judgement:
 * <ol>
 *   <li>If {@link #resolveEnterOrdinal} detected a specific send action
 *       (GO/SEARCH/SEND/SEND_EXPRESSION), trust it — no override.</li>
 *   <li>Else if current editor's package is in {@link #FORCE_SEND_PACKAGES},
 *       override to {@link #IME_ACTION_SEND_ORDINAL} so the App's listener
 *       receives the semantic send command.</li>
 *   <li>Else return the original (newline-class) ordinal unchanged.</li>
 * </ol>
 *
 * Used by both {@link #commitAndDispatchToolbarAction} and
 * {@link #maybeUpdateZone} so the overlay label NEVER says "换行" while
 * behavior is actually "send".
 */
private static int resolveEffectiveEnterOrdinal(ClassLoader cl) {
    int ord = resolveEnterOrdinal(cl);
    if (isSpecificSendOrdinal(ord)) {
        return ord;
    }
    String pkg = currentEditorPackageName(cl);
    if (pkg != null && FORCE_SEND_PACKAGES.contains(pkg)) {
        log("force-send override: pkg=" + pkg + " original ord=" + ord
                + " -> IME_ACTION_SEND");
        return IME_ACTION_SEND_ORDINAL;
    }
    return ord;
}
```

### 4.4 修改 `commitAndDispatchToolbarAction` (line 609-627)

**唯一改动**：line 614 的 `resolveEnterOrdinal(cl)` 替换为 `resolveEffectiveEnterOrdinal(cl)`。

```diff
-        int enterOrdinal = resolveEnterOrdinal(cl);
+        int enterOrdinal = resolveEffectiveEnterOrdinal(cl);
```

其余分支逻辑 (`enterOrdinal < 0` 兜底 / `isSpecificSendOrdinal` 检查 / `dispatchViaAsrManagerT` 与 `dispatchNewlineFast` 分流) **完全不动**。新的 ordinal 在 specific 集合 (2/3/4/8) 内时走 `dispatchViaAsrManagerT(cl, 4)`，正是我们想要的发送路径。

### 4.5 修改 `maybeUpdateZone` (line 1082-1113)

**唯一改动**：line 1098 的 `resolveEnterOrdinal(cl)` 替换为 `resolveEffectiveEnterOrdinal(cl)`。

```diff
-        int enterOrdinal = resolveEnterOrdinal(cl);
+        int enterOrdinal = resolveEffectiveEnterOrdinal(cl);
         updateOverlayForZone(next, enterOrdinal, cl);
```

这一步确保 **工具栏右键 UI 标签/图标 与 dispatch 行为同步**：在 Nekogram 聊天框里，右键显示"发送"图标与文字，而不是"换行"。

诊断 log（line 1103-1112，输出原始三路 ordinal source `srcA/srcB/rawAct` + `resolved`）**保持不变** —— 它打的是 `resolveEnterOrdinal` 的输出（用于排查豆包内部状态），不应被覆盖污染。

文件 B：`app/build.gradle`

### 4.6 版本号 bump

```diff
-        versionCode 2
-        versionName "1.1.0"
+        versionCode 3
+        versionName "1.2.0"
```

## 五、边界处理（盲点已覆盖）

| # | 风险 | 处理 |
|---|---|---|
| 1 | UI 标签和实际行为 mismatch（最严重，私聊误发风险） | 4.5：`maybeUpdateZone` 也走 `resolveEffectiveEnterOrdinal`，标签 + dispatch 共用同一 ordinal |
| 2 | 反射链 null/异常 | 4.2 全程 try-catch；任何环节失败返回 null → 4.3 判定为未命中名单 → 走原有路径 |
| 3 | Nekogram 编辑老消息模式良性 fallback | listener 内 `editingMessageObject != null` 时返回 false → 框架默认 = 无响应；文本仍 commit 到 EditText（**良性，列入 P1 测试矩阵**不当 bug） |
| 4 | App 切换状态污染 | 不修改 `resolveEnterOrdinal` 本体；package name 每次现取无缓存。测试矩阵覆盖 Nekogram ↔ 微信 切换 |
| 5 | `AsrManager.t(4)` 在 Nekogram 真实运行失败的静默风险 | 此版本不预先实现 fallback；若实测失败，v1.2.0-rc 即终止，回 Planner 评估改用 `InputConnection.sendKeyEvent` 路径（合成 Ctrl+Enter）。**已知静默失败风险，由用户实测确认** |

## 六、Verifier 验收点（机器可检 + 静态断言）

Verifier（独立子 Agent，Opus）在 fresh context 中验证：

### 6.1 编译 + APK 产出

```bash
./gradlew :app:assembleDebug
```
- exit code 0
- `app/build/outputs/apk/debug/*.apk` 文件存在

### 6.2 静态代码断言（grep 取证）

对 `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`：
- `FORCE_SEND_PACKAGES` 常量存在 + 包含字面量 `"tw.nekomimi.nekogram"`
- `currentEditorPackageName` 函数存在 + 包含 `try` + `catch (Throwable`
- `resolveEffectiveEnterOrdinal` 函数存在 + 内部调用 `isSpecificSendOrdinal` + `FORCE_SEND_PACKAGES.contains`
- `commitAndDispatchToolbarAction` 内**只调** `resolveEffectiveEnterOrdinal`，**不再直接**调 `resolveEnterOrdinal`
- `maybeUpdateZone` 内**只调** `resolveEffectiveEnterOrdinal`，**不再直接**调 `resolveEnterOrdinal`
- `resolveEnterOrdinal` 函数本体未被修改（仍只被 `resolveEffectiveEnterOrdinal` 内部使用，及诊断 log 中 `resolved=` 上下文）

对 `app/build.gradle`：
- `versionCode 3`
- `versionName "1.2.0"`

### 6.3 回归 grep（确认无意外改动）

- `AsrManager.t` 调用签名仍是 `(int, long)`
- `dispatchViaAsrManagerT`、`dispatchNewlineFast`、`sendEnterKey` 函数体未被无关改动
- `isSpecificSendOrdinal` 函数体未被修改（仍是 `ord == 2 || ord == 3 || ord == 4 || ord == 8`）

### 6.4 不在 Verifier 验收范围

- ❌ 真机测试（Verifier 无设备 / 跨 App 行为靠用户手测）
- ❌ 单元测试（项目无 unit tests）

Verifier 输出到 `orchestra/verify-report.md`：PASS / FAIL + 每项证据 grep 命中行号 / 编译输出末 30 行。

## 七、人工实测矩阵（用户一加 15 上跑）

**P0**（必通过，决定能否合并到 main）：
- [ ] Nekogram **聊天框**：长按字母 → 语音 → 滑到工具栏 → 工具栏右键显示"发送"图标 + 文字 → 松手 → **消息发出**
- [ ] Nekogram **搜索框**：长按字母 → 语音 → 滑到工具栏 → 工具栏右键显示"搜索"图标 + 文字 → 松手 → **正常搜索**（不应变成发消息）
- [ ] **微信** 聊天框：行为完全不变（仍发送）
- [ ] **微信** 搜索框：行为完全不变（仍搜索）

**P1**（已知良性 / 信息性）：
- [ ] Nekogram 长按消息 → "编辑" 模式下，再滑工具栏：文本插入到编辑框，**不会触发编辑保存**（这是已知良性 fallback，**不当 bug**）
- [ ] Nekogram ↔ 微信 来回切换 5 次：双侧行为稳定，无污染

**P2**（顺手观察）：
- [ ] 长按字母滑动**取消手势**（向左滑出）在 Nekogram 内仍能取消（不发任何东西）
- [ ] logcat 里 `force-send override: pkg=tw.nekomimi.nekogram` 这条 log 在 Nekogram 聊天框场景出现；在搜索框 / 微信场景不出现

## 八、Git 流程（主会话 Claude 负责，Executor 不碰 git）

1. 基线 tag（已在 main 上打）：`pre-v1.2.0-baseline`
2. 分支（已切）：`feat/cross-app-send-routing`
3. **plan commit**：`docs(orchestra): v1.2.0 plan + adapt playbook + handoff` —— 包含 `orchestra/HANDOFF-cross-app-send.md` + `PLAN-v1.2.0.md` + `ADAPT-PLAYBOOK.md` + `routing-decision.md` + `.gitignore` 追加
4. **code commit**（Executor + Verifier 完成后）：`feat: force-send routing for whitelisted apps (Nekogram)` —— 单 commit，含 `.java` + `build.gradle` 改动
5. **推送 + 用户实测**：`git push -u origin feat/cross-app-send-routing` → 用户在手机上跑 P0/P1/P2 矩阵
6. **PASS**：合并到 main → `git tag v1.2.0` → `git push --tags` → 触发 CI release

## 九、Executor 边界（硬约束 — Codex 必须遵守）

允许改：
- ✅ `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`
- ✅ `app/build.gradle`

**禁止改**：
- ❌ `.github/workflows/*`
- ❌ `gradle/*`、`gradlew*`、`settings.gradle*`
- ❌ `LICENSE`、`libs/*`
- ❌ `README.md`、`README*` 任何变体
- ❌ 任何 `orchestra/*` 文件（Planner / Verifier 专属域）
- ❌ `app/src/main/java/com/jin/doubaolongpressvoice/XposedEntry.java`（不在范围内）

**禁止做**：
- ❌ `git add` / `git commit` / `git push`（主会话串行收口）
- ❌ 跑测试 / 构建（Verifier 域）
- ❌ 删任何现有函数 / 重构无关代码

完成即停。
