# ADAPT-PLAYBOOK — 第三方 App 发送适配可复用流程

> 沉淀自 v1.2.0 Nekogram 适配。下次给新 App 加白名单时按此流程走。

---

## 触发场景

用户报告："在 XXX App 里，字母长按 → 语音 → 滑工具栏发送 → 不工作 / 行为异常"。

---

## Step 1：拿目标 App 包名

```bash
adb shell pm list packages | grep -iE '<关键词>'
```
或让用户在「设置 → 应用」里查看 App info。

---

## Step 2：在豆包里实测当前行为，看工具栏右键标签

| 显示 | 含义 | 下一步 |
|---|---|---|
| "发送" / "搜索" / "GO" / "发送表情" | 豆包识别正确 | 问题不在判定，在 dispatch 链路 → 翻 logcat 看 `AsrManager.t()` 是否被对端接住 |
| "换行" | 豆包认为这是普通多行编辑器 | **需要白名单覆盖**，进入 Step 3 |

---

## Step 3：定位目标 App 源码（如开源）

主输入框类常见命名（按生态）：
- Telegram 系：`ChatActivityEnterView`
- 通用 IM：`MessageInputView` / `InputBar` / `MessageComposer`
- 微博/小红书类：`PublishContentView` / `EditorView`
- WebView 类（Claude/ChatGPT/Discord-web）：通常源码不可得 → 走启发式 + 真机 logcat

定位技巧：
```bash
# GitHub Code Search via gh CLI
gh api -X GET "search/code" -f q="repo:<owner>/<repo> filename:<候选类名>"
```

---

## Step 4：源码扫描决策树

在主输入框 EditText 设置代码附近 grep：

```
setOnEditorActionListener  ← 最高优先级：看是否响应 IME_ACTION_SEND / GO / SEARCH
setOnKeyListener           ← 看 KEYCODE_ENTER 触发条件（metaState / 设置开关）
onCreateInputConnection    ← 看是否有自定义 InputConnection 拦截
performPrivateCommand /
onPrivateIMECommand        ← 私有协议通道（极少 App 实现）
setImeOptions              ← 看 declare 了什么 action（解释为什么豆包误判）
setInputType               ← 看 MULTI_LINE / NUMBER 等
```

判定结果对照：

| 发现 | 适配路径 | 落地文件 |
|---|---|---|
| `OnEditorActionListener` 无条件响应 `IME_ACTION_SEND` | **加入 `FORCE_SEND_PACKAGES`** → `AsrManager.t(4, now)` 触发 | `DoubaoLetterLongPressHook.java` 常量 |
| 只响应 `KEYCODE_ENTER + 特定 metaState`（如 Ctrl）| 合成 KeyEvent 走 `InputConnection.sendKeyEvent`（需要新 helper）| 待 v1.3.0 / 新 helper |
| WebView 内 JS 监听 keydown Enter | **强制 `KEYCODE_ENTER`** 路径，新建 `FORCE_KEYCODE_ENTER_PACKAGES` 名单 | 待 v1.3.0 |
| 都不行（App 把"发送"只挂 UI 按钮 onClick 上）| IME 层无解 | README 标注限制 / Accessibility Service 方案另立 |

---

## Step 5：实施改动（参考 v1.2.0 范式）

1. 在 `FORCE_SEND_PACKAGES` 加新包名，**注释里附 source URL + 行号锚点**
2. 不改其他逻辑（`resolveEffectiveEnterOrdinal` 已经处理好判定层）
3. 跑 `./gradlew :app:assembleDebug` 确认编译过
4. 用户实机验收（按 Step 6 模板）

---

## Step 6：实测矩阵模板（每个新 App 都建一份）

**P0**（必通过）：
- [ ] 目标 App 主输入框：滑发送 → 右键显示"发送" → 真发出
- [ ] 目标 App 搜索框（如有）：滑发送 → 右键显示"搜索" → 真搜索（不应误覆盖）
- [ ] 微信回归：行为不变
- [ ] QQ 回归：行为不变

**P1**：
- [ ] 编辑 / 草稿 / 转发评论等次要输入：行为可接受（即使不发也是良性 fallback）
- [ ] App 间切换 5 次：无状态污染

**P2**：
- [ ] 取消手势仍正常
- [ ] logcat `force-send override` 在预期场景出现、非预期场景不出现

---

## Step 7：版本管理

- **加一个 App** = bump 小版本（v1.2.0 → v1.2.1）
- **改变路径策略**（加新名单类型，如 FORCE_KEYCODE_ENTER）= bump 中间位（v1.2.x → v1.3.0）
- **改判定函数本身** = bump 中间位

---

## Step 8：注释与文档维护

- 每个 `FORCE_*_PACKAGES` 常量旁注释附：
  - 该 App 在哪个文件的哪行响应/不响应 IME action
  - source URL（GitHub raw / 永久链接）
  - 加入名单的时间和原因
- 测试矩阵保留：`orchestra/test-matrix-<app>.md`（或一份汇总 `test-matrix.md`）

---

## 常见陷阱（必查）

1. **UI 标签 mismatch**：覆盖 dispatch 必须同步覆盖标签。范式：用 `resolveEffectiveEnterOrdinal` 统一来源（`commitAndDispatchToolbarAction` + `maybeUpdateZone` 双调用点）。
2. **同包多场景**（聊天 vs 搜索）：**只在豆包判定 newline-class 时**覆盖，specific 时尊重豆包。
3. **反射容错**：取 packageName 全链 null + try-catch + Throwable 兜底，不能让 hook 崩。
4. **状态污染**：每次现取 packageName，**不缓存**。
5. **静默失败**：人工实测 P0 项必须挂明确"成功标志"（消息真的出现在对方聊天里、文本不残留在输入框）。仅看 logcat 不够。
6. **WebView vs 原生混淆**：源码读不到的就是 WebView 概率高（特别是 LLM 客户端），不要硬塞进 `FORCE_SEND_PACKAGES` —— 它们的修复路径是 `KEYCODE_ENTER`。

---

## Anti-pattern（不要做）

- ❌ 不要在 `commitAndDispatchToolbarAction` 入口直接强制 `t(4)`，跳过 specific 判定 —— 会破坏 Nekogram 搜索框等 SEARCH/GO 场景
- ❌ 不要把整个 `FORCE_SEND_PACKAGES` 改成启发式判定（如"凡是 MULTI_LINE 都强制"）—— 会误伤微信富文本、备忘录类应用
- ❌ 不要做"用户配置 UI" / per-app 设置 —— 当前规模不值得；issue 反馈 + 小版本发版足够
- ❌ 不要双发 `t(4)` + `KEYCODE_ENTER` 兜底 —— 双发风险高，分流互斥即可
