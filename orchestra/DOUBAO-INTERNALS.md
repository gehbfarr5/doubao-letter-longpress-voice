# Doubao IME 内部 Hook 点文档

主要分析对象：豆包输入法 v1.3.11 (`com.bytedance.android.doubaoime`)。APK 来自本机恢复备份：
`/Users/jin/Desktop/oneplus15-reset-20260618/03_apps/apk/com.bytedance.android.doubaoime/base.apk`，
已复制为 `/tmp/doubao.apk`，JADX 产物在 `/tmp/doubao-jadx`。直接 ADB/AndroMeld 取包在本次会话不可用，但备份 APK 与项目 README 记录的实测版本一致。

2026-07-09 追加 v1.3.14 真机适配记录：手机包 `versionName=1.3.14`
(`versionCode=100314018`)，APK 拉取到 `/tmp/doubao-1.3.14.apk`，JADX
产物为 `/tmp/doubao-jadx-1.3.14`。该版本里旧 `p0(boolean,String)`
实例方法重命名为 `s0(boolean,String)`；中间/底部松手从旧 `q0()` 改为
`t0()`，其内部 150ms 后调用 `s0(false, "send")`；右侧发送入口
`t(int,long)` 仍有效。`KeyboardJni.getToolbarHeight()` 在实测 26 键界面
返回 0，因此模块需要用 KeyboardView 顶部比例兜底识别 ASR 工具栏区域。

2026-07-16 追加 v1.3.15 (`versionCode=100315010`) 适配记录：

- `KeyboardView.nativeTouch(JIIIJ)V` 仍存在，但由 `libkeyboard.so` 动态 `RegisterNatives`。Vector Java hook 能注册却无法截获真机长按哨兵；v1.6.5 改用 Vector/LSPosed Native API hook `JNIEnv->RegisterNatives`，只替换精确方法名和签名，`action=-1` 时回调 Java。
- `AsrManager.E()` 已变成错误提示态，不能作为 running probe；运行期使用 `J()`，启动期使用 `F()`，并做有限重试。
- 官方普通松手、撤回和特定动作分别为 `w0()`、`u()`、`t(int,long)`；`InputView.T(boolean)` 仍有效。
- `AsrLongPressView` 是录音后手势面；模块同时观察它与 `KeyboardView`，由 `GestureSession` 原子 owner/terminal 防止双消费。
- `getGlobalVisibleRect()` 在当前 ColorOS IME window 中相对 IME root，不能与 `MotionEvent.getRawY()` 比较；`getLocationOnScreen()` 与 native longpress 的 `rawY-localY` 真机一致，因此 zone Rect 必须用后者。
- 真机确认 26 键 Settings SEARCH 编辑器中三路终态：LETTER=`w0()`、TOOLBAR=`t(3, now)`、OUTSIDE=`u()`；框架 `ACTION_CANCEL` 始终安全取消。

2026-08-02 追加 v1.3.17 (`versionCode=100317008`) 适配记录：手机包拉取到
`doubao-1.3.17.apk`（155MB），JADX 产物在
`/private/tmp/claude-501/-Users-jin/.../scratchpad/doubao-re/jadx-1.3.17/sources/`。

- **核心教训：混淆字母在版本间没有语义延续性，只是恰好同名。** 1.3.15 时代
  `J()`/`F()` 的语义是"运行期激活探针/启动期激活探针"，但 1.3.17 重新混淆后，这两个
  字母被分配给了完全不同的方法体：`F()` 变成 `h == SpeechStatus.KErrorShowState`
  （是否处于错误展示态），`J()` 变成 `return f3093d`——即 `mDontCommit`（"是否禁止
  上屏"）标志，且**录音成功时会被置 `false`**，所以录音期间 `J()||F()` 恒为
  `false||false`，是**语义反相**而非单纯"改了名字"。适配新版本时，方法名+签名匹配
  只能证明"存在一个同名同签名的方法"，**必须额外核对方法体语义**（读 `h` 字段的
  赋值点、关联的 `SpeechStatus`/日志字符串），不能假设字母延续代表语义延续。
- 真正表示"正在录音/ASR 激活"的是 `G()`：`h == SpeechStatus.KTryStart || h == SpeechStatus.KStart`
  （`h` 是 `AsrManager` 内的 `private static SpeechStatus h`，状态机
  `KStop → KTryStart → KStart → [KStoping] → KStop`，`KErrorShowState` 是错误分支）。
  `G()` 方法体反编译产物里带着字节跳动自己的调试字符串
  `"[hand_write] isAsrSpeechingStatus mCurrentUIStatus = "`，可确认是官方代码自己
  判断"正在说话"的权威依据，不是逆向猜测。
- `w0` 从 1.3.15 的无参 `w0()`（松手 commit）变成 **`w0(boolean noWaitResult, String from)`**
  双参统一 stop/commit/cancel 入口（内部日志 `[ASR-Flow][stopAsr][Android]`），
  形状类似更早的 1.3.14 族 `s0(boolean,String)`。`u()`（doUndo，撤回/丢弃语义）签名
  未变；调用侧发现 `u()` 实际是 `w0(true,"undo")` 的严格超集（多做清 pre-edit、
  复位 view 等），二者不能互相替代。
- `t(int,long)`（工具栏 send/search/换行等分发）、`AsrManager.a`（单例静态字段）、
  `KeyboardView.nativeTouch(long,int,int,int,long)`（`(JIIIJ)V`，仍是 native 方法）
  均未变，native 层不受本次版本升级影响。
- 适配落地：`DoubaoCompatAdapter` 新增 `Family.V1_3_17`，`activePrimary` 独立探测
  `G()`（不复用 1.3.15 分支算出的 `J`/`F`），`activeSecondary` 传 `null`；
  `cancel()`/`stop()`/`commit()` 按 `noWaitResult` 二次分支，`noWaitResult=true` 走
  `u()`，否则走 `w0(false, from)`，结构与 1.3.15 分支对称。真机验证：
  `capability probe family=V1_3_17`、`ASR active id=N attempt=0`（首次轮询即成功，
  不再因 1.2s 超时 `abort takeover`），工具栏发送/滑出撤回/原地提交三种终态均正常。

2026-09-10 追加 v1.4.4 (`versionCode=100404006`，内部构建 `1.4.4.10`，
`buildTime=20260907.1307`) 适配记录：base.apk SHA-256 为
`0a905c8f00c7c70d8e91ea3408db6abc1eb13038f9241f9666459f62b33c8b5d`，参考反编译源码
位于 `orchestra/re-v1.4.4/`。

- **混淆陷阱再次出现：方法字母跨版本复用不代表语义延续。** 1.3.17 的提交入口
  `w0(boolean,String)` 在 1.4.4 已不存在；新版本的 `w0(int,long)` 是右侧“发送”
  分发中的私有 ordinal-dispatch 实现，不能当作 commit。证据来自
  `AsrManager.java:1111` 的方法体及真实调用链。统一底层入口虽为
  `Q0(boolean,String)`，但直接调用会跳过 `F()`/`R0()` 包含的 `mDontCommit`、
  `LongPressStop` 计时和 150ms 延迟等副作用，因此适配层只调用官方手势包装方法。
- v1.3.17 → v1.4.4 的能力映射为：右侧发送 `t(int,long)` →
  `E(int,long,boolean)`（`j_sendClick.java` 的真实调用点第三参传 `true`）；激活探针
  `G()` → `T()`（仍检查 `KTryStart || KStart`，并保留
  `"[hand_write] isAsrSpeechingStatus mCurrentUIStatus = "` 字符串锚点）；撤回
  `u()` → `F()`；普通松手提交 `w0(boolean,String)` → `R0()`。`R0()` 先记录
  `LongPressStop`，再延迟 150ms 调用 `Q0(false,"send")`；`F()` 调用
  `Q0(true,"undo")`。右侧 hover 的 `forceVad` 为 `I()`，当前模块不使用。
  `AsrLongPressView` 全限定名和 `KeyboardView.nativeTouch(long,int,int,int,long)`
  签名未变。
- 适配落地：`DoubaoCompatAdapter` 新增 `Family.V1_4_4` 并优先探测
  `surface/T/F/R0/E(int,long,boolean)`；发送调用 `E(ordinal,now,true)`，取消调用
  `F()`，提交调用无参 `R0()`，不绕过包装方法直调 `Q0`。模块版本由 v1.6.6
  升至 v1.6.7。真机验证（PLK110 / 3B166Q00SX000000，2026-09-10）：root pm install
  升级到位后 `am force-stop com.bytedance.android.doubaoime` 触发重新注入，logcat
  确认 `capability probe family=V1_4_4 detail=v1.4.4 capabilities: surface/T/F/R0/E(bool)`，
  四个 hook 点（`KeyboardView$c#handleMessage`、`KeyboardView#onTouchEvent`、
  native nativeTouch、`AsrLongPressView` native surface）全部挂载成功。实际长按
  字母键 → `gesture start family=V1_4_4` → `ASR active id=1 attempt=0 family=V1_4_4`
  （确认 `T()` 探针运行时读数正确）→ 原地松手 → `ASR graceful commit family=V1_4_4`
  → `gesture finish terminal=COMMIT`，全程无 `ERR` 行；同步截图确认豆包"正在倾听"
  语音面板与状态栏录音图标正常出现。用户本人另行手动测试确认无问题。

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

- 接口：`com.bytedance.android.input.speech.L.a`（`L` 是包名，`a` 是类名，**不是** `L$a` 内部类）
- 方法：`void a(com.bytedance.android.input.speech.s asrCallBackInfo)`
- 注册点：`com.bytedance.android.input.speech.z.w(L.a listener)`
- `AsrManager.t()` 内部 listener 类：`com.bytedance.android.input.speech.AsrManager$b`
- 完成判断：`asrCallBackInfo.g() == true`
- **注意**：`XposedHelpers.findClass("com.bytedance.android.input.speech.L.a", cl)` 直接按全限定名查找即可；运行时已验证（2026-06-20 一加 15 v1.3.11）

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

v1.3.14 差异：

- 中间/底部松手：`AsrLongPressView.onTouchEvent(ACTION_UP)` 调 `InputView.T(false)` + `AsrManager.a.t0()`
- `t0()` 记录 `LongPressStop`，调用 `AsrProcess.u()`，150ms 后 `AsrManager.a.s0(false, "send")`
- stop/cancel 入口统一为 `s0(boolean noWaitResult, String from)`

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

## 2026-09-22：豆包 1.4.5（100405008）/ 模块 v1.6.8

当前安装包 SHA-256：`542cf3bdb3718ad00f5e35352e5fab71bde02bcef2bd196fd10633c9c46dec34`。

v1.6.7 注入成功但探测为 UNSUPPORTED，直接禁用手势接管；并非作用域未启用。
按 APK 方法体和真实 UI 调用点核对的新映射：

| 能力 | 1.4.5 入口 | 语义证据 |
|---|---|---|
| active | AsrManager.U() → boolean | KTryStart 或 KStart；T() 已变成 KErrorShowState 判定 |
| cancel | AsrManager.G() | doUndo，先设置不提交标志，再 S0(true,"undo") |
| commit | AsrManager.T0() | AsrLongPressView.d() 调用；LongPressStop，150 ms 后 S0(false,"send") |
| dispatch | AsrManager.F(int,long,boolean) | speech.view.j 点击入口传 true；不得复用旧 F() 撤回语义 |
| close UI | InputView.e0(boolean) | AsrLongPressView.d() 在 T0() 前传 false |
| all-back status | AsrContext.V(int,boolean) | AsrProcess 在结果 g() 为 true 时调用 V(2,true) |
| process / listener | AsrManager.e / speech.L.y(speech.Y.a) | e 为 L 实例，y 保存 listener，回调 a(result)，result.g() 表示 all-back |

KeyboardView.nativeTouch(JIIIJ)V、UserInteractiveManagerNext.g(...)、EditorViewInfo.e().d() 保持可用。
新 family 检查精确参数及返回类型；旧 family 的提交/撤回参数路径保留。
反编译存在其它方法失败（jadx 全包报告 103 errors），上表依赖的方法体与调用点均可读；未根据失败方法的占位异常推断运行行为。

PLK110 / Android 16 实机使用 Appium 执行 26 键测试，已看到录音 UI、识别文字上屏，日志记录 COMMIT / CANCEL / TOOLBAR_ACTION 三种终态和正确 V1_4_5 active probe。普通 E 点按输入 e。详细范围、哈希及测试环境限制见 `verify-report-2026-09-19-adaptation.md` 续记。
