# v1.6.5 修复方案：豆包 1.3.15 原生 ASR 手势面适配

状态：已实施；v1.6.5 已构建、安装并在真机完成 1.3.15 核心手势回归。跨 App 真消息发送和真人语音转写仍保留为人工验收项。

目标版本：

- 豆包输入法 `1.3.15`，`versionCode=100315010`，内部构建 `1.3.15.10`。
- 当前模块 `1.6.5`，`versionCode=13`。
- 测试设备 OnePlus 15 / PLK110 / Android 16 / Vector-SR。

## 1. 目标与验收标准

修复后必须同时满足：

1. 长按字母仍能启动豆包 ASR。
2. 录音中移动到视觉工具栏区域时显示蓝色动作提示，内容与当前编辑器动作一致，例如“发送”“搜索”“换行”。
3. 录音中移出键盘与工具栏联合区域时显示红色“撤回输入”。
4. 在工具栏松手只执行一次对应动作；移出后松手不上屏、不发送；字母区松手只提交一次。
5. Claude、ChatGPT、Nekogram 的既有分流不回归。
6. Shift、Backspace、Space、数字/符号层、数字输入框、浮动键盘和单手键盘不被误触发。
7. 不用静默 fallback 掩盖未知豆包版本。能力探测失败时记录明确的“不支持”原因并停止接管手势。

## 2. 当前实测基线

### 2.1 真机结果

| 项目 | 结果 | 证据与结论 |
|---|---|---|
| 模块注入 | PASS | Vector-SR 模块日志出现 `DoubaoLongPress: loaded into com.bytedance.android.doubaoime`。 |
| 默认输入法 | PASS | `com.bytedance.android.doubaoime/.ImeService`。 |
| 无障碍服务 | PASS | `DoubaoVoiceSendA11yService` 当前已启用。 |
| 长按字母启动 ASR | PASS | ADB 连续触摸可启动 ASR，屏幕进入“轻触结束/确定”录音态。 |
| 视觉工具栏 zone | FAIL | 手指实际位于视觉工具栏时，截图显示红色“撤回输入”，不是蓝色动作提示。 |
| 移出撤回 | FAIL | 结束取消手势后，ASR 文本仍提交到 ChatGPT 输入框；未发送，但取消语义失效。 |
| 300ms 后继续跟踪 | FAIL（静态已确认） | `AsrManager.E()` 在 1.3.15 中只表示 `KErrorShowState`，正常录音时为 false；当前模块会错误清空 `sSuppressNextUp`。 |
| ChatGPT 真发送 | 未执行 | 输入框已有未发送草稿，为避免外部写入，本轮没有触发真实发送。 |
| Claude/Nekogram 端到端 | 未执行 | 上游 zone 状态机已失败，先修上游再做跨 App 发送验收。 |

本轮未发送任何消息，也未删除 ChatGPT 中出现的草稿文本。

### 2.2 APK 静态证据

豆包 `base.apk` SHA-256：

```text
f512328f157e5b91236ca1076f8ea93c384cd38b0d1940dbea8599cdd748d069
```

仍存在、可继续复用的能力：

- `KeyboardView` 长按消息仍为 `MSG_LONGPRESS=1`，坐标仍放在 `arg1/arg2`。
- `KeyboardJni.DoFunctionKey(6)` 仍是 `KEY_START_SPEECH`。
- `KeyboardView.nativeTouch(long,int,int,int,long)` 仍存在。
- `InputView` 仍由 `ImeService.y` 持有并继承 `FrameLayout`。
- `native_candidate_bar`、`ime_inputview`、发送/搜索/换行文案资源仍存在。
- `InputView.T(boolean)`、`AsrManager.t(int,long)` 仍存在。
- 1.3.15 新增/启用了完整的 `AsrLongPressView` 原生长按手势面，并由 `KeyboardView.preHandleTouchEvent()` 转发录音后的 MOVE/UP。

已经漂移的能力：

| 语义 | 1.3.14 当前模块 | 1.3.15 实际实现 |
|---|---|---|
| ASR 运行状态 | `E()` 被当成 running | `E()` 现在只表示错误提示态；`F()` 是正在启动/录音，`J()` 是 ASR active state |
| 撤回 | `s0(true,"cancel")` / `p0(...)` | 官方原生撤回按钮调用 `AsrManager.u()` |
| 长按松手提交 | `t0()` / `q0()` | 官方原生松手路径调用 `AsrManager.w0()` |
| 特定动作 | `t(ordinal, now)` | 仍为 `t(ordinal, now)` |
| 启动 | `DoFunctionKey(6)` | 仍为 `DoFunctionKey(6)`，内部最终进入 `t0("tool")` |

## 3. 三个已确认根因

### 根因 A：ASR 存活探针语义漂移

当前代码在长按后 300ms 调用 `AsrManager.E()`；若返回 false，就清空 `sSuppressNextUp`。1.3.15 中 `E()` 的真实实现是：

```text
return currentStatus == KErrorShowState
```

因此正常录音必然被误判成“ASR 未启动”。用户稍后移动时，MOVE、提示更新、UP/CANCEL 三条路径都会因为共享门控已清空而失效。这是两个功能同时完全失效的主因。

### 根因 B：取消与普通提交入口漂移

1.3.15 不再提供当前模块调用的 `s0(boolean,String)`、`p0(boolean,String)`、`t0()`、`q0()` 签名。当前反射调用会全部失败，最终由豆包自身异步收尾，造成取消后文本仍可能上屏。

### 根因 C：视觉工具栏与 KeyboardView 坐标根分离

当前 zone 算法把 `MotionEvent.getX/getY` 当成整个输入法的坐标，并用 `KeyboardView` 的宽高判断：

```text
y < toolbarHeight -> TOOLBAR
超出 KeyboardView -> OUTSIDE
```

1.3.15 中工具栏/ASR 手势面已经成为 `KeyboardView` 的兄弟区域。移动到屏幕上真实可见的工具栏时，换算后的 KeyboardView 局部 y 为负数，所以被判成 OUTSIDE。真机截图中的红色“撤回输入”直接证明了这个错位。

## 4. 候选方案比较

### 方案 A：继续修补 KeyboardView.onTouchEvent

做法：

- 把 `E()` 改成 `F()/J()`。
- 把取消/提交改成 `u()/w0()`。
- 在当前 `KeyboardView.onTouchEvent` hook 中使用 `getRawX/getRawY`，并保存工具栏全局 Rect。

优点：

- 改动最小，能较快恢复 1.3.15。
- 不新增类和依赖。

缺点：

- 仍在豆包录音后的事件转发入口之前抢事件。
- 继续依赖一个大类中的共享布尔状态，未来生命周期或 View 路由变化仍容易整体失效。
- 无法充分复用 1.3.15 已存在的原生 `AsrLongPressView` 语义和事件所有权。

结论：只适合紧急短期 hotfix，不作为最终方向。

### 方案 B：native 触发桥 + 原生 ASR 手势面 + 官方动作（已采用）

做法：

1. 1.3.15 通过 Vector/LSPosed Native API hook `JNIEnv->RegisterNatives`，截获动态注册的 `KeyboardView.nativeTouch(JIIIJ)V`；只在 `action=-1` 时把真实长按哨兵交给 Java 门控。1.3.14 保留 Handler 入口。
2. 在长按触发前保存三组全局几何：
   - `KeyboardView` 的 global Rect；
   - `native_candidate_bar` 的 global Rect；
   - `InputView` 的 global Rect。
3. Hook 1.3.15 的 `AsrLongPressView.onTouchEvent(MotionEvent)`，它是豆包录音后实际接收 MOVE/UP 的原生手势面。
4. 使用 `MotionEvent.getRawX/getRawY` 与保存的 global Rect 判定：
   - 位于旧工具栏 Rect：`TOOLBAR`；
   - 位于旧 KeyboardView Rect：`LETTER`；
   - 两者都不在：`OUTSIDE`。
5. 使用豆包自己的语义动作：
   - `OUTSIDE`：`AsrManager.u()` + `InputView.T(false)`；
   - `LETTER`：`AsrManager.w0()`；
   - `TOOLBAR`：`AsrManager.t(effectiveOrdinal, now)`，并保留现有 A11y / Nekogram 分流。
6. 在 UP/CANCEL 上只允许一个 action token 消费手势，防止官方原生左右按钮和模块动作双发。

优点：

- 事件源与 1.3.15 的真实所有权一致。
- 使用屏幕坐标和真实 Rect，不再猜比例或把 sibling 当 child。
- 复用豆包官方撤回、松手、发送语义，减少对混淆 stop 方法的依赖。
- 真正覆盖 1.3.15 的动态 native 注册入口；C++ 只负责最小触发桥，zone、状态机和动作仍留在可测试的 Java 层。

缺点：

- 需要把当前 god class 拆成适配层、手势层、zone 层和动作层。
- 需要明确处理 1.3.14 与 1.3.15 的两套事件入口。
- 必须验证 Vector-SR 对目标方法的 Hook 与事件顺序，不能只靠反编译推断。

结论：采用此方案。

### 为什么采用 RegisterNatives，而不直接 inline hook libkeyboard.so

真机探针证明 Java 层 `nativeTouch` hook 已注册但没有收到长按调用；目标又由 `libkeyboard.so` 动态 `RegisterNatives`，且符号表不可作为稳定接口。最终使用 Vector 已提供的 Native API/Dobby 能力 hook `JNIEnv->RegisterNatives`，按方法名和精确签名替换函数指针。这样不依赖被 strip 的 native 符号，也不额外引入 ShadowHook。录音后的 MOVE/UP 仍由 Java 层 `KeyboardView` / `AsrLongPressView` 会话接管，native 层不承担手势状态机。

## 5. 推荐实现结构

建议把现有 `DoubaoLetterLongPressHook.java` 拆成以下职责，避免继续扩大共享状态：

```text
DoubaoHookInstaller
  -> DoubaoCapabilityProbe
  -> DoubaoApiAdapterV1314 / DoubaoApiAdapterV1315
  -> LongPressTriggerHook
  -> NativeAsrSurfaceGestureHook
  -> GestureSession
  -> ZoneResolver
  -> AsrActionDispatcher
  -> ZoneOverlayController
  -> DoubaoDiagnostics
```

关键对象：

- `GestureSession`：不可变 session id + 原子状态，替代散落的 `sSuppressNextUp`、`sCurrentZone`、`sRecordingEnterOrdinal`。
- `GeometrySnapshot`：在 ASR 切换布局前保存 keyboard/toolbar/inputView 的 global Rect。
- `DoubaoApiAdapter`：以明确版本和精确签名选择实现；未知签名 fail loud，不用连环 try/catch 静默降级。
- `ActionToken`：每次手势只能从 ACTIVE 原子转成 COMMIT/CANCEL/SEND 之一，阻止双发。

## 6. 分阶段实施

### Phase 0：诊断探针，不改变行为

- 读取并记录豆包 `versionName/versionCode`、APK hash、进程名、适配器选择结果。
- 对每个关键方法记录“类 + 方法 + 参数 + 返回值”能力矩阵。
- 临时 Hook `AsrLongPressView.onTouchEvent` 和 `KeyboardView.nativeTouch`，只计数、不消费事件。
- 每条事件记录 action、local/raw 坐标、receiver class、三个 global Rect、session id。
- 记录所有状态清空的调用方和原因。

通过条件：一次长按手势能看到完整的 DOWN -> MOVE -> UP/CANCEL 事件归属，确认 1.3.15 后续事件确实进入 `AsrLongPressView`。

### Phase 1：1.3.15 API 适配器

- 删除 1.3.15 对 `E()`、`s0(boolean,String)`、`t0()` 的错误使用。
- running 判定使用 `J()` 为主、`F()` 为启动期辅助，并用有限时间状态机等待，不再把单次 false 当失败。
- 撤回使用 `u()`；普通松手使用 `w0()`；特定动作继续使用 `t(int,long)`。
- 1.3.14 适配器保留其现有已验证入口，两个适配器不在运行时互相静默 fallback。

通过条件：日志明确显示选中 v1.3.15 adapter，三种 action 均只调用一次官方语义方法。

### Phase 2：原生 ASR 手势面与 global zone

- 在长按触发前抓取 `GeometrySnapshot`。
- 在 `AsrLongPressView.onTouchEvent` 中用 raw screen coordinates 计算 zone。
- 工具栏 Rect 失效时不使用 `h*0.30` 猜测；记录 unsupported geometry 并停止接管该次手势。
- overlay 只做显示，不再承担 zone 来源。
- `ACTION_CANCEL` 无条件进入安全取消终态；只有 `ACTION_UP` 才根据最后 raw point 选择 LETTER/TOOLBAR/OUTSIDE。

通过条件：

- 视觉工具栏任何位置都稳定显示蓝色动作提示。
- 上/下/左/右移出联合区域都显示红色撤回。
- 临界线往返 20 次无抖动、无误提交。

### Phase 3：动作层与跨 App 分流

- `LETTER`：官方 `w0()`，只上屏一次。
- `OUTSIDE`：官方 `u()`，preedit 清空且不上屏。
- `TOOLBAR`：保持 `resolveEffectiveEnterOrdinal`；Claude/ChatGPT 继续走 A11y，Nekogram 聊天走 `IME_ACTION_SEND`，搜索框尊重 SEARCH。
- 发送与撤回都必须以 `ActionToken.compareAndSet(ACTIVE, target)` 成功为前提。

通过条件：日志与 UI 都只出现一个终态；没有双 commit、双 send 或取消后迟到 commit。

### Phase 4：测试自动化与发布

- 提取纯 Java `ZoneResolver`，增加边界和 global Rect 单元测试。
- 为版本能力矩阵增加反射签名单元测试 fixture。
- 为 A11y selector 增加节点树 fixture 测试。
- 依次运行 `lintDebug`、`assembleDebug`，不要并行。
- 安装后重新确认 LSPosed scope 和无障碍服务。
- 真机完成 P0/P1 矩阵后再 bump 到 `1.6.5`，更新 README、DOUBAO-INTERNALS、REGRESSION-MATRIX 和 verify report。

## 7. 完整真机回归矩阵

### P0 核心路径

| 场景 | 期望 | 必须采集的证据 |
|---|---|---|
| 26 键字母长按 | 500ms 左右启动 ASR | 起始截图 + session 日志 |
| 9 键字母长按 | 同上 | 起始截图 + session 日志 |
| 字母区松手 | 只上屏一次 | 输入框前后截图 + commit 计数 |
| 工具栏发送提示 | 蓝色且文案正确 | MOVE 持续态截图 + TOOLBAR raw point/Rect |
| 移出撤回提示 | 红色“撤回输入” | MOVE 持续态截图 + OUTSIDE raw point/Rect |
| 撤回不上屏 | 输入框保持原样 | 前后截图 + `u()` 一次 + 无迟到 commit |
| ChatGPT 发送 | 文本只发送一次 | 消息出现 + 输入框清空 + A11y 成功日志 |
| Claude 发送 | 同上 | 同上 |
| Nekogram 聊天 | `IME_ACTION_SEND` 一次 | 消息出现 + `t(4, now)` 一次 |
| Nekogram 搜索 | 保持 SEARCH，不误发消息 | 搜索结果 + ordinal 日志 |
| 重启后恢复 | Hook、scope、A11y 均正常 | 重启后状态检查 + 一次端到端发送 |

### P1 边界与负向路径

- Shift、Backspace、Space 长按不触发。
- `?123` 数字/符号层不触发。
- NUMBER / PHONE / DATETIME 输入框不触发。
- 浮动、单手模式按产品定义跳过。
- 长按前位移超过 20dp 保留原生光标滑动。
- toolbar 边界上下各 1px、屏幕四边移出、快速往返、慢速停留、ACTION_CANCEL。
- 横竖屏、键盘高度变化、候选栏有/无内容、深浅色模式。
- 连续 30 次长按；App 间切换 10 次；无状态串台、无 overlay 残留。
- 关闭 A11y 时 Claude/ChatGPT 明确提示，不崩溃、不假报发送成功。

### P2 稳定性

- 豆包进程 force-stop 后恢复。
- 模块重装后 scope / A11y 恢复检查。
- 30 分钟连续输入无 IME ANR、无 Vector-SR crash、无 native crash。
- 未知豆包版本只报告 unsupported capability，不接管输入事件。

## 8. 回滚与安全门

- 实装前保存当前模块 APK、版本、LSPosed scope、A11y service 状态。
- 诊断 APK 与修复 APK 都必须可用原 `1.6.4` APK 原位回装。
- 每次安装后先确认模块启用与 scope，再重启豆包进程；需要整机重启时再执行。
- 不在有真实待发送文本的聊天框里验证发送；使用专用测试对话和固定测试文本。
- 不删除用户现有草稿；本轮 ChatGPT 草稿保持未发送状态。

## 9. 当前结论

本次不是单点混淆名漂移，而是“状态探针语义 + 动作 API + 坐标根”同时变化。只替换 `s0/t0` 会留下 300ms 状态清空和工具栏错判；只修坐标也会留下取消后上屏。因此必须按推荐的原生 ASR 手势面方案一次修完整，再做跨 App 回归。

## 10. 2026-07-16 执行结果

已完成：

- v1.3.15 能力探测、精确适配器与未知版本 fail-loud。
- Vector/LSPosed Native API `RegisterNatives` bridge；日志确认捕获 `nativeTouch(JIIIJ)V`。
- `GestureSession` 单 owner / 单 terminal、`ZoneResolver` 纯 Java 边界测试。
- `getLocationOnScreen()` + `rawX/rawY` 同坐标系几何；修正了 ColorOS IME root 下 `getGlobalVisibleRect()` 的纵向偏移。
- 真机验证 LETTER -> `COMMIT`、TOOLBAR -> `TOOLBAR_ACTION`、OUTSIDE -> `CANCEL`、框架 `ACTION_CANCEL` -> `CANCEL`，每次均只有一个终态且无 `DUP_ACTION`。
- Settings 搜索框持续态截图确认蓝色“搜索”和红色“撤回输入”；短按字母与退格保持正常。
- `testDebugUnitTest`、`lintDebug`、`assembleDebug` 与 `git diff --check` 通过。

仍需人工/专项验收：9 键、浮动/单手/横屏、真人语音转写、ChatGPT/Claude/Nekogram 真消息闭环、重启后与长时间压力测试。为保护用户现有草稿，本轮没有发送外部消息。
