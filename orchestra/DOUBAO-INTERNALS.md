# Doubao IME 内部 Hook 点文档

分析对象：豆包输入法 v1.3.11 (`com.bytedance.android.doubaoime`)。APK 来自本机恢复备份：
`/Users/jin/Desktop/oneplus15-reset-20260618/03_apps/apk/com.bytedance.android.doubaoime/base.apk`，
已复制为 `/tmp/doubao.apk`，JADX 产物在 `/tmp/doubao-jadx`。直接 ADB/AndroMeld 取包在本次会话不可用，但备份 APK 与项目 README 记录的实测版本一致。

## ASR Manager

### t(int, long) 内部机制

类：`com.bytedance.android.input.speech.AsrManager`

签名：`public final void t(final int enterActionOrdinal, final long startTs)`

这是空格长按右侧按钮的真实发送入口。链路为：

1. `AsrLongPressView` 右侧 action-up 回调 `com.bytedance.android.input.speech.view.j.invoke()`
2. `InputView.R(false)` 收起/整理长按 UI
3. `AsrManager.a.t(asrLongPressView.f3162c, System.currentTimeMillis())`
4. `AsrManager.t()` 等 ASR all-back 完成后执行 `a0(ordinal, startTs)`

`t()` 的等待逻辑：

- 如果 `IInputSettings.a.d().u()` 为 false，直接 `p0(true, "send")` 后 `a0(...)`，不等待 all-back。
- 默认等待路径会读 `IInputSettings.a.d().v()` 作为最大等待时间。
- `AsrContext.a.m()` 是当前 all-back 状态，内部看最后一个 `AsrContext.b` 记录的 `c()`。
- 如果 `AsrContext.a.m()` 已 true，或 `mHaveVoiceText` 为 false，立即执行 `A.run()`。
- 否则调用私有静态 `AsrManager.b`（类型 `com.bytedance.android.input.speech.z`，即 AsrProcess）的：
  - `A()`：看起来是清理/准备当前 listener 状态
  - `w(L.a)`：注册 all-back listener
  - `Handler G.postDelayed(A, maxWaitMs)`：超时兜底

完成后执行的 `a0(int, long)`：

- ordinal `4` (`kIME_ACTION_SEND`)：`KeyboardJni.getService().q().performEditorAction(4)`
- ordinal `8` (`kIME_ACTION_SEND_EXPRESSION`)：走特殊分支，不进入普通 `doSendAction()`
- 其它 ordinal：`KeyboardJni.doSendAction()`

注意：因为 `a0()` 对 ordinal `1/5/6/7` 不是发 `KEYCODE_ENTER`，不能直接把 newline 场景改成 `AsrManager.t(1, now)`。

### ASR Complete 信号

权威信号不是 `KeyboardJni.onAsrSetPreedit()` 静默，而是 AsrProcess 的 all-back listener：

- 接口：`com.bytedance.android.input.speech.L.a`
- 方法：`void a(com.bytedance.android.input.speech.s asrCallBackInfo)`
- 注册点：`com.bytedance.android.input.speech.z.w(L.a listener)`
- `AsrManager.t()` 内部 listener 类：`com.bytedance.android.input.speech.AsrManager$b`
- 完成判断：`asrCallBackInfo.g() == true`

`s.g()` 对应 `AsrCallbackInfo` 最后一个 boolean 字段。构造来源在 `com.bytedance.android.input.speech.A` 的 stream callback：SDK callback 的 `isFinish` 为 true 时，构造 `new s(..., isStreamFinish, ..., isFinish)`，并调用 `AsrContext.a.T(1, true)`。二段结果到齐时另有 `AsrContext.a.T(2, true)`。

`AsrManager$b#a(s)` 在 `s.g()` 为 true 时：

1. 保存当前 `s` 到 `currentAsrInfo`
2. post 到主 Handler
3. 打日志 `DoAsrSend IAllAsrBackListener onBack`
4. `AsrManager.b.A()`
5. remove timeout runnable
6. run `AsrManager.A`，进入 `AsrManager.J(...)`
7. `J(...)` 再 `p0(true, "send")` 并执行 `a0(...)`

### 建议 Hook 方案（替换 pollAsrSettleAndEnter）

不要再把 `onAsrSetPreedit` / `onAsrCommitPreeditText` 的 quiet-window 当完成条件。更接近豆包内部机制的方案：

1. 在 newline path 调 `p0(false, "")` 前，注册一个一次性 `L.a` proxy 到 `AsrManager.b.w(listener)`。
2. listener 收到 `s.g()==true` 后，主线程发 `KEYCODE_ENTER`，并调用 `z.w(null)` 清理 listener。
3. 保留 `maxWaitMs` 超时兜底，避免 ASR 异常不回调。

Xposed 形态示例：

```java
Class<?> asrManagerCls = XposedHelpers.findClass(
        "com.bytedance.android.input.speech.AsrManager", cl);
Object asrProcess = XposedHelpers.getStaticObjectField(asrManagerCls, "b");
Class<?> listenerCls = XposedHelpers.findClass(
        "com.bytedance.android.input.speech.L.a", cl);
Class<?> infoCls = XposedHelpers.findClass(
        "com.bytedance.android.input.speech.s", cl);

Object listener = java.lang.reflect.Proxy.newProxyInstance(
        cl,
        new Class<?>[]{listenerCls},
        (proxy, method, args) -> {
            if ("a".equals(method.getName()) && args != null && args.length == 1) {
                Object info = args[0];
                boolean allBack = (Boolean) XposedHelpers.callMethod(info, "g");
                if (allBack) {
                    XposedHelpers.callMethod(asrProcess, "w",
                            new Class<?>[]{listenerCls}, new Object[]{null});
                    // post/send KEYCODE_ENTER here
                }
            }
            return null;
        });

XposedHelpers.callMethod(asrProcess, "w",
        new Class<?>[]{listenerCls}, listener);
```

可观测 hook 点：

```java
XposedHelpers.findAndHookMethod(
        "com.bytedance.android.input.speech.AsrManager$b",
        cl,
        "a",
        XposedHelpers.findClass("com.bytedance.android.input.speech.s", cl),
        hook);
```

这个 hook 只覆盖 `AsrManager.t()` 自己创建的 listener，适合验证内部完成时序；若要替换 newline poll，应主动注册自己的 `L.a` listener。

## EnterActionType 权威来源

`AsrLongPressView` 的单一来源是：

- 类：`com.bytedance.android.input.speech.view.o`
- 含义：`EditorViewInfo`
- 单例字段：`private static o f3189f`
- 获取：`o.e()`
- enter ordinal 字段：`private int a`
- getter：`d()`
- setter：`i(int)`

`AsrLongPressView.onVisibilityChanged(...)` 每次可见性变化都会：

```java
this.f3162c = o.e().d();
```

右侧 action-up 再使用这个缓存值：

```java
AsrManager.a.t(this.a.f3162c, System.currentTimeMillis());
```

`KeyboardJni.checkEnterType(EditorInfo)` 负责基础映射并写 `mCurrentEnterType`：

- `0` `kUnknow`
- `1` `kIME_ACTION_NONE`
- `2` `kIME_ACTION_GO`
- `3` `kIME_ACTION_SEARCH`
- `4` `kIME_ACTION_SEND`
- `5` `kIME_ACTION_NEXT`
- `6` `kIME_ACTION_DONE`
- `7` `kIME_ACTION_PREVIOUS`
- `8` `kIME_ACTION_SEND_EXPRESSION`

`KeyboardJni` 在 start-input 处理里把最终判定写入 `EditorViewInfo`：

- 普通非 0：`o.e().i(gVarCheckEnterType.d().intValue())`
- 命中特殊可发送表达/小红书等规则：改写为 8
- `enable_key_enter_send_msg` 命中后：改为 4，并同步 `setCurrentEditboxActionType(...)` 和 `mCurrentEnterType`
- 最后调用 `o.e().j(editorInfo)` 写入 page/package/scene/extra

结论：如果目标是“匹配 AsrLongPressView 显示/行为”，优先读 `EditorViewInfo.e().d()`。`KeyboardJni.mCurrentEnterType` 和 `mCurrentEditboxAction` 是诊断/兜底来源，不是长按面板的权威来源。

## AsrLongPressView

类：`com.bytedance.android.input.speech.view.AsrLongPressView`

布局：`res/layout/layout_asr_long_press.xml`

关键字段：

- `f3162c`：缓存的 enterAction ordinal
- `f3163d`：`AsrNotchedEllipseView`，左右 action 按钮
- `f3164e`：`AsrEllipseView`，底部区域
- `f3165f`：`AsrWaveView`

安装位置：

- `InputViewRoot.B()` 创建 `new AsrLongPressView(...)`
- `InputViewRoot.r0(true)` 显示长按视图，并 `KeyboardView.setAsrLongPressView(this.L)`
- `KeyboardView.preHandleTouchEvent` 会把 touch 转发给 `mAsrLongPressView.dispatchTouchEvent(...)`

touch/action 路径：

- 中间/底部松手：`AsrLongPressView.onTouchEvent(ACTION_UP)` 调 `InputView.R(false)` + `AsrManager.a.q0()`
- 左侧 rollback：`h.invoke()` 调 `AsrManager.a.u()` + `InputView.R(false)`
- 右侧 send：`j.invoke()` 调 `InputView.R(false)` + `AsrManager.a.t(f3162c, now)`
- 右侧 hover/move：`i.invoke()` 震动并节流调用 `AsrManager.a.x()` forceVad

标签选择在 `onVisibilityChanged` 中完成：

- 4/8：`asr_long_press_send_text`
- 3：`asr_long_press_search_text`
- 6：`asr_long_press_done_text`
- 5：`asr_long_press_next_text`（资源文案是“继续”）
- 2：`asr_long_press_go_text`
- 7：`asr_long_press_previous_text`（资源文案是“后退”）
- 默认：`asr_long_press_enter_text`

## 其它 Hook 点（按价值排序）

1. `com.bytedance.android.input.speech.z.w(L.a)`：ASR all-back listener 注册点。适合替换 newline 的静默轮询。
2. `com.bytedance.android.input.speech.AsrManager$b#a(s)`：验证 `AsrManager.t()` 完成时序的最小 hook 点。
3. `com.bytedance.android.input.speech.AsrContext.m()`：当前 all-back 状态，直接读最后一条 ASR content 的完成标记。
4. `com.bytedance.android.input.speech.AsrContext.T(int, boolean)`：完成标记写入点，`1` 是 stream finish，`2` 是二段/second result finish。
5. `com.bytedance.android.input.speech.view.o.i(int)`：EnterActionType 写入点，可 hook 观察所有 editor 的最终 enter ordinal。
6. `KeyboardJni.checkEnterType(EditorInfo)`：原始 `EditorInfo.imeOptions` 到 `EnterActionType` 的基础映射点。
7. `KeyboardJni.onAsrSetPreedit(String)` / `onAsrCommitPreeditText()`：仍是 commit/preedit 抑制的有效 hook 点，但不应再作为 ASR complete 的权威信号。
8. `KeyboardJni.performLLMRequest(int)`、`com.bytedance.android.input.llm.a` (`LLMCandidate.updateCandidateList`) 和 `LLMRequest`：LLM candidate window 入口，后续做候选窗/改写功能时有价值。
9. `AsrEditorLayoutView`：普通语音面板入口，stop button、backspace swipe、ASR 编辑区 UI 都在这里。
