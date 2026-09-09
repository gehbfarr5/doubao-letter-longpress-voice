# 任务：适配豆包输入法 1.4.4（模块 v1.6.6 → v1.6.7）

状态：待实施。目标版本：豆包输入法 `1.4.4`（`versionCode=100404006`，内部构建
`1.4.4.10`，`buildTime=20260907.1307`）。当前模块最后适配的是 `1.3.17`
（`versionCode=100317008`），跨度较大，方法名重新混淆，字母没有语义延续性
（历史教训见 `orchestra/DOUBAO-INTERNALS.md` 2026-08-02 条目，务必先读）。

真机现状：`com.jin.doubaolongpressvoice` 已在 PLK110（Vector）启用，但适配的
`DoubaoCompatAdapter.resolve()` 找不到任何已知签名族 → 落入 `Family.UNSUPPORTED`
→ 长按语音手势完全失效。这是本次要修的根因。

新版豆包 base.apk 已拉取并反编译，SHA-256（供 orchestra/DOUBAO-INTERNALS.md
记录用）：`0a905c8f00c7c70d8e91ea3408db6abc1eb13038f9241f9666459f62b33c8b5d`。

## 已完成的静态分析（orchestrator 直接读 jadx 反编译产物得出，勿重新摸索）

参考源码已复制到 `orchestra/re-v1.4.4/`（只读，仅供比对，不要改，也不要当作
项目源码的一部分编译）：
- `AsrManager.java`（完整 2225 行，`com.bytedance.android.input.speech.AsrManager`）
- `AsrLongPressView.java`（`com.bytedance.android.input.speech.view.AsrLongPressView`）
- `g_rollbackHover.java` / `h_rollbackClick.java` / `i_sendHover.java` / `j_sendClick.java`
  （原包名分别是同目录下的顶层类 `g`/`h`/`i`/`j`，是 `AsrNotchedEllipseView` 四个
  action 回调的实现，文件名后缀是我加的语义提示，类名本身在文件内不变）

**确认的方法映射（v1.3.17 → v1.4.4，均在 `AsrManager` 类，包名/类名本身未变）：**

| 能力 | v1.3.17 签名 | v1.4.4 签名 | 证据 |
|---|---|---|---|
| 右侧"发送"分发（specific dispatch） | `t(int, long)` | `E(int, long, boolean)` — **新增第三个 boolean 参数**，真实调用点（`j_sendClick.java`）传 `true`：`AsrManager.a.E(this.a.f4373c, jCurrentTimeMillis, true)` | `AsrManager.java:1377` `public final void E(final int i2, final long j2, boolean z2)`；内部走 `Q0(true,"send")` 后 `w0(i2,j2)`（`w0` 在 1.4.4 里已经不是旧语义，见下方陷阱） |
| ASR 是否激活（active probe） | `G()`：`h==KTryStart\|\|h==KStart` | `T()`：同样 `f4202i==SpeechStatus.KTryStart\|\|f4202i==SpeechStatus.KStart`，且带着同一条字节跳动调试字符串 `"[hand_write] isAsrSpeechingStatus mCurrentUIStatus = "` | `AsrManager.java:1673-1678`，字符串锚点是最强证据，不是仅凭签名猜的 |
| 左侧"撤回/取消"（cancel/undo） | `u()` | `F()` 无参 | `AsrManager.java:1426`，方法体日志 `"doUndo mHavePreEdit = "`，内部走 `Q0(true,"undo")`；调用点 `h_rollbackClick.java`：`AsrManager.a.F()` |
| 中/下方松手正常提交（commit / stop，等价旧 `w0(boolean,String)`） | `w0(boolean noWaitResult, String from)` | `R0()` 无参 | `AsrManager.java:1654`，方法体做 `F0(...,"LongPressStop",...)` 后 `postDelayed(150ms) → Q0(false,"send")`，和旧版"150ms 后提交"的模式一致；调用点是 `AsrLongPressView.java` 的私有 `d()`（`onTouchEvent(ACTION_UP/ACTION_CANCEL)` 触发）：`AsrManager.a.R0()` |
| forceVad（右侧 hover，当前模块未使用，仅供参考） | 未在旧 Family 记录 | `I()` | `AsrManager.java:1486`，调用点 `AsrLongPressView.a()`（`i_sendHover.java` 间接调用） |

**关键陷阱（务必写进 `DoubaoCompatAdapter` 的实现，不要踩）：**

1. `w0` 这个字母在 1.4.4 里被**重新分配**给了旧 `t(int,long)`/`a0(int,long)` 那一层
   的 ordinal-dispatch 私有实现（`AsrManager.java:1111` `private final void w0(int r3, long r4)`），
   和 1.3.17 时代 `w0(boolean,String)` 是完全不同的方法——**同名不同义的教训又发生了一次**，
   这次是跨版本字母复用（`w0` 从"commit"变成"内部 ordinal dispatch"）。不要被历史
   `DoubaoCompatAdapter.java` 里 `optional(manager, "w0", boolean.class, String.class)`
   这行的字面量误导去找 1.4.4 的 `w0(boolean,String)`——它不存在，正确的新方法是
   上表的 `R0()`。
2. `Q0(boolean, String)` 才是真正的底层统一 stop/commit/cancel 入口（旧
   `s0`/`w0(bool,String)` 的等价物），但 `F()` 和 `R0()` 已经各自包好了正确的
   `Q0(...)` 调用与副作用（`mDontCommit`、`LongPressStop` 计时、150ms 延迟等），
   适配层**不需要**也**不应该**绕过 `F()`/`R0()` 直接反射调 `Q0`——直接调 `Q0`
   会丢失这些副作用，行为会和真实 UI 手势不一致。
3. `AsrLongPressView` 类本身仍在同一全限定名下，字段结构不同（`f4373c` 是新的
   enterOrdinal 缓存字段，对应旧 `f3162c`），但适配层不反射这个类的字段，不受影响。
4. `KeyboardView.nativeTouch(long,int,int,int,long)` 签名未变（原生层这次也没动），
   `hasNativeSurface()` 的判断条件不需要额外改动，只需要把 `Family.V1_4_4` 加入
   现有 `family == Family.V1_3_15 || family == Family.V1_3_17` 的判断里。

## 要改的文件

### 1. `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java`

- `Family` enum 加 `V1_4_4`。
- `resolve()` 里在现有 `V1_3_15`/`V1_3_17`/`V1_3_14` 探测链的**最前面**（新版本优先探测，
  避免旧版本的宽松探测误命中新版本的方法集）插入 v1.4.4 探测分支：
  - `dispatch = exact(manager, "E", int.class, long.class, boolean.class)`
  - `activePrimary = optional(manager, "T")`，`activeSecondary = null`
  - `cancel = optional(manager, "F")`（无参）
  - `commit = optional(manager, "R0")`（无参）
  - 全部非空 + `surface != null` → 返回 `Family.V1_4_4`
- `hasNativeSurface()` 的 family 判断加上 `Family.V1_4_4`。
- `dispatch(Object manager, int ordinal, long now)`：v1.4.4 需要额外传 `true`
  （调用 `E(ordinal, now, true)`），其它 family 不变——按 family 分支，不要改
  已有 family 的调用路径。
- `cancel(Object manager)`：v1.4.4 分支直接 `invoke(cancel, manager)`（`F()` 无参，
  和 V1_3_15/V1_3_17 现有分支的结构一致，能合并的话合并，但不要动其它 family 的分支）。
- `stop(Object manager, boolean noWaitResult, String from)`：v1.4.4 分支——
  `noWaitResult==true` → `invoke(cancel, manager)`（`F()`）；`noWaitResult==false`
  → `invoke(commit, manager)`（`R0()` 无参，**不传** `from`，因为新方法没有这个参数，
  这是新 API 本身的限度，不是适配层偷懒）。
- `commit(Object manager)`：v1.4.4 分支 `invoke(commit, manager)`（`R0()` 无参）。
- `diagnostic` 字符串按现有风格写一句，例如
  `"v1.4.4 capabilities: surface/T/F/R0/E(bool)"`。

### 2. `orchestra/DOUBAO-INTERNALS.md`

按现有 2026-08-02 条目的写法（先讲混淆陷阱证据，再讲方法映射，再讲落地结果）追加一段
`2026-09-10 追加 v1.4.4 (versionCode=100404006) 适配记录`，把上面表格和陷阱浓缩写进去，
真机验证结果等 orchestrator 收到 Verifier/真机验收后再回填（先写占位句"真机验证：待
orchestrator 回填"）。

### 3. `app/build.gradle`

`versionCode 14 → 15`，`versionName "1.6.6" → "1.6.7"`。

### 4. `README.md`（如果里面记录了"当前适配版本"之类的版本号，一并更新；没有就不用改）

## 不要做

- 不要改 `orchestra/re-v1.4.4/` 下的参考文件（只读对照用，不参与编译，不要移进
  `app/src/main/java`）。
- 不要碰 `DoubaoLetterLongPressHook.java` 里除了"确实需要因签名变化而联动"之外的逻辑；
  目前已确认 `dispatchViaAsrManagerT`（调 `sCompat.dispatch`）、`callAsrStop`（调
  `sCompat.stop`）、`callAsrGracefulCommit`（调 `sCompat.commit`）、`ensureAsrManager`
  （调 `sCompat.managerInstance/isSupported`）都是通过 `DoubaoCompatAdapter` 这层间接
  调用的，只要 adapter 改对，这个文件本身不需要动。如果实施中发现某处绕过了 adapter
  直接反射 `AsrManager` 的旧方法名（比如直接 hardcode "t"/"u"/"w0" 字符串），要如实
  报告在 `.codex-last.md`，不要自作主张改 hook 文件的其它行为逻辑。
- 不要静默 fallback：如果新签名探测在实际反射时失败（比如方法确实存在但
  `getDeclaredMethod` 因某种原因拿不到），要让 `resolve()` 按现有模式落到
  `Family.UNSUPPORTED` 并写清 `diagnostic`，不要用 try/catch 吞掉异常后随便猜一个
  可能不对的方法继续跑。

## 验收标准（机器可查）

1. `./gradlew :app:assembleDebug` 编译通过，产出 `app/build/outputs/apk/debug/app-debug.apk`。
2. `grep -n "V1_4_4" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java`
   命中 `Family` 定义 + `resolve()` 分支 + `dispatch/cancel/stop/commit` 里各至少一处分支。
3. `app/build.gradle` 的 `versionCode` 为 `15`、`versionName` 为 `"1.6.7"`。
4. `orchestra/DOUBAO-INTERNALS.md` 新增 v1.4.4 段落，包含上表四个方法名
   （`E`、`T`、`F`、`R0`）。
5. 不引入新的编译警告级别的 unchecked/deprecation（沿用项目现有 lint 基线，不需要
   额外收紧）。

真机装机验证（Vector CLI enable/scope、实际长按语音手势回归）由 orchestrator 在
Codex 完成后自己做，不属于 Codex 这一轮任务范围。
