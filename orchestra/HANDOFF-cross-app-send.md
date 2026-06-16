# 交接文件 — 跨 App 发送适配（v1.2 候选）

> 上一会话（cwd 在 /Users/jin，未走编排）已完成方案讨论。
> 本文件是给**新会话（在本项目目录里启动，自动走 Orchestra 编排）的开工输入**。
> 阅读后请按 Planner → Executor → Verifier 流程推进；git 操作（branch / commit / push / tag）全部交由本会话 Claude 处理。

---

## 一、用户需求（原始）

豆包输入法长按字母 → 语音输入 → **滑到工具栏发送** 这套体验：
- ✅ 国产 App（微信/QQ 等）正常发送
- ❌ 海外 App（Claude / ChatGPT / Telegram-Nekogram 等）**发不出去 / 行为异常**

**目标**：让"滑到工具栏发送"在海外 App 里也能正确触发"发送"动作。

---

## 二、问题根因（已诊断）

当前 `commitAndDispatchToolbarAction()` 的路由：

```
specific (SEND/SEARCH/GO/SEND_EXPRESSION) → AsrManager.t(ord, now)
NONE / DONE / NEXT / PREVIOUS              → p0(false,"") + KEYCODE_ENTER
```

- `AsrManager.t(ord)` 本质上让豆包内部调 **`InputConnection.performEditorAction(actionId)`**
- 国产 IM 在 `EditorInfo.imeOptions` 上声明了 `IME_ACTION_SEND` 且实现了 action 处理 → 走通
- 海外 App（WebView / 自定义 input）多数情况：
  - `imeOptions = NONE / UNSPECIFIED / IME_FLAG_NO_ENTER_ACTION`
  - 实际的"发送"由 JS 监听 `keydown Enter` 或硬件回车驱动
  - → `performEditorAction()` 无效，必须用 `KEYCODE_ENTER` 才能触发

---

## 三、方案备选 & 边界分析

### 方案 A：包级硬编码黑名单
匹配到指定 package（Claude/ChatGPT/Nekogram）→ 强制走 KEYCODE_ENTER。

### 方案 B：双发兜底
先 `t()` 再延迟检测，没生效就补 Enter。**已淘汰**（双发风险高、检测窗口难定）。

### 方案 C：运行时按 EditorInfo 路由
读 `getCurrentInputEditorInfo().imeOptions`，actionId 是 NONE/UNSPECIFIED → 走 KEYCODE_ENTER；否则保留 `t(ord)`。

### 方案 D：用户在 LSPosed 设置内 per-app 配置
**已淘汰**（需要做 settings UI，工作量过大）。

### 关键边界（**Nekogram 同 App 内多场景**）

| 场景 | imeOptions 实际值 | 真"发送"入口 | 方案 C | 方案 A（包级黑名单） |
|---|---|---|---|---|
| Nekogram 聊天输入 | NONE / NO_ENTER_ACTION | 依用户 "Send by Enter" 设置：开→Enter，关→点按钮 | NONE → KEYCODE_ENTER（用户开了就通，没开只插换行 ❌）| 强制 KEYCODE_ENTER（同 C）|
| Nekogram **搜索栏（搜会话）** | IME_ACTION_SEARCH | performEditorAction(SEARCH) | SEARCH → `t(SEARCH)` → ✅ **语义正确** | 强制 KEYCODE_ENTER → 多数能搜到但**语义降级** ⚠️ |
| Claude / ChatGPT WebView | 看 `enterkeyhint`，常见 NONE/SEND | JS 监听 keydown Enter | NONE→ENTER ✅；SEND→`t(SEND)` ⚠️ 可能哑火 | 强制 ENTER ✅ |
| 微信/QQ/钉钉 | IME_ACTION_SEND | performEditorAction(SEND) | `t(SEND)` ✅ | 不在名单走默认 → `t(SEND)` ✅ |

### 关键观察

1. **同 App 内多场景** 是 C 完胜 A 的核心理由 —— Nekogram 一个 package 既有聊天又有搜索，C 自动区分，A 一刀切。
2. **WebView 内的 SEND action 可能不响应** —— Claude/ChatGPT 即使写了 `enterkeyhint=send`，WebView 对 `performEditorAction(SEND)` 的桥接在版本间不一致，C 在这里反而有坑。
3. **不可解决的盲区**：Telegram 聊天 + 用户关闭 "Enter sends"。两个方案都搞不定（要解决得动 Accessibility Service，下个版本范畴）。

---

## 四、推荐方案（用户已认可方向，待 Planner 细化）

### **方案 C（主） + WebView 黑名单（补丁，不是 A）**

```
1. 主路径：读 EditorInfo.imeOptions
   - SEND/SEARCH/GO/SEND_EXPRESSION → AsrManager.t(ord)
   - NONE/UNSPECIFIED/NO_ENTER_ACTION/DONE/NEXT/PREVIOUS → KEYCODE_ENTER

2. WebView 黑名单覆盖层（针对 Claude/ChatGPT 等已知 WebView 应用）：
   - 命中名单时，**忽略 imeOptions，强制 KEYCODE_ENTER**
   - 规避 WebView 对 performEditorAction(SEND) 的桥接坑

3. Nekogram 不进黑名单 —— C 已能正确区分聊天/搜索

4. README 加"已知限制"：Telegram 类客户端需在 App 内开启 "Enter sends"
```

### 初始 WebView 黑名单候选

- `com.anthropic.claude` (Claude Android)
- `com.openai.chatgpt` (ChatGPT Android)
- 其他确认 WebView 场景的 package 后续补

Planner 需要确认这些 package 的真实包名（用 `adb shell pm list packages | grep -iE 'claude|openai|chatgpt'` 实测）。

---

## 五、可行性 & 工作量

| 维度 | 评估 |
|---|---|
| 取数 | ✅ InputMethodService 的 `getCurrentInputEditorInfo()` 现成可用 |
| 路由开销 | ✅ 一次 if，无成本 |
| 回归风险 | ⚠️ 必须回归微信/QQ，确保现有路径不破 |
| 工作量 | 半天内（含名单 + 测试） |
| 兼容性 | minSdk 23 已 cover，无新 API 需求 |

---

## 六、待 Planner 细化的实施点

1. **代码改动位置**
   - 主文件：`app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`
   - 函数：`commitAndDispatchToolbarAction()` 及周边 dispatch helpers
   - 新增：读 EditorInfo 的辅助函数（拿到当前 IME 服务实例 → `getCurrentInputEditorInfo()`）

2. **EditorInfo 获取路径**
   - 豆包 IME service 实例可通过 hook 注入时缓存的 `XC_MethodHook.MethodHookParam.thisObject`（已有缓存机制）
   - 或者反射 `InputMethodService.getCurrentInputEditorInfo()`

3. **黑名单数据结构**
   - 简单 `Set<String>` 硬编码常量数组即可，无需做设置 UI

4. **EnterActionType 与 imeOptions actionId 的映射**
   - 已知映射表（之前会话已确认）：
     ```
     0=kUnknow, 1=NONE, 2=GO, 3=SEARCH, 4=SEND, 5=NEXT, 6=DONE, 7=PREVIOUS, 8=SEND_EXPRESSION
     ```
   - imeOptions actionId 来自 `EditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION`
   - 对应 `IME_ACTION_UNSPECIFIED=0, NONE=1, GO=2, SEARCH=3, SEND=4, NEXT=5, DONE=6, PREVIOUS=7`
   - 注意：`IME_FLAG_NO_ENTER_ACTION` 是 flag 不是 action，要单独检测 → 命中时按 NONE 处理

5. **回归测试矩阵**
   - 必测：微信、QQ、Nekogram 聊天、Nekogram 搜索栏、Claude App、ChatGPT App
   - 选测：钉钉、微博、知乎、Twitter/X

6. **版本号**
   - v1.2.0（minor bump，因为新增功能而非 fix）
   - app/build.gradle：versionCode 2 → 3，versionName "1.1.0" → "1.2.0"

7. **README 更新点**
   - "✨ 特性" 段加一条"跨 App 智能路由"
   - 新增"⚠️ 已知限制"段，说明 Telegram 类客户端的 Enter-sends 设置

8. **Git 流程**（由本会话 Claude 全权负责）
   - 新分支：`feat/cross-app-send-routing`
   - 单 commit 或按需拆，commit message 风格沿用之前
   - tag v1.2.0 推送触发 CI → release

---

## 七、Executor 边界

- **只允许改 .java 和 .md 和 build.gradle**
- **不允许碰**：`.github/workflows/*`、`gradle/*`、`gradlew*`、`settings.gradle`、`LICENSE`、`libs/*`
- 改前由 Planner 创建 git 基线 checkpoint（tag 或临时 commit）
- Verifier 验收点：编译过、APK 产出、回归功能矩阵全通过的本机验证记录

---

## 八、给 Planner 的开工指令（粘进新会话即可）

```
读 orchestra/HANDOFF-cross-app-send.md，按其中"推荐方案 C + WebView 黑名单"做 v1.2.0 实施规划。
规划完写到 orchestra/PLAN-v1.2.0.md，交给 Executor 跑。
git 全流程由你（主会话）执行：建分支 → 验收通过后 commit → push → tag → 触发 CI release。
```
