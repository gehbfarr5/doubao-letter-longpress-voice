# 当前任务 (Planner 填写) — 适配豆包 1.3.17（DoubaoCompatAdapter 新增 Family）

- **目标**：修复长按字母键语音功能在豆包输入法 1.3.17 下完全无反应的问题。根因已通过 JADX 反编译 1.3.17 base.apk 定位：`DoubaoCompatAdapter.resolve()`（`app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java`）在 1.3.17 上匹配不到 `Family.V1_3_15` 或 `Family.V1_3_14` 中任何一族，导致 `DoubaoLetterLongPressHook.install()` 在 `!sCompat.isSupported()` 分支直接 return，一个 hook 都不装（不是崩溃，是设计内的"安全静默降级"，与真机实测"长按完全无反应"完全吻合）。

## RE 结论（1.3.17，`com.bytedance.android.input.speech.AsrManager`）

对比 1.3.15 族要求的 `surface / J() / F() / u() / w0()(无参) / t(int,long)`：

- ✅ `com.bytedance.android.input.speech.view.AsrLongPressView` 类仍存在（原生长按面板 surface）
- ✅ `public final boolean J()` 不变（`activePrimary`）
- ✅ `public final boolean F()` 不变（`activeSecondary`）
- ✅ `public final void u() throws JSONException` 不变（当前实现是"doUndo"：置位 `f3093d`/discard 语义）
- ✅ `public final void t(final int i2, final long j2) throws JSONException`（工具栏 send/search/... 分发）不变
- ❌ **`w0` 签名变了**：1.3.15 是 `w0()` 无参（"松手 commit"）；1.3.17 变成 **`public final void w0(boolean z2, String str) throws JSONException`**（内部日志 `[ASR-Flow][stopAsr][Android] noWaitResult=...from=...`，语义上是统一的 stop/commit/cancel 入口，形状类似旧 1.3.14 族的 `s0(boolean,String)`）
- `AsrManager.a`（单例静态字段）、`KeyboardView.nativeTouch(long,int,int,int,long)`（`(JIIIJ)V`，仍是 native 方法）均未变，native 层不受影响，本任务**只改 Java 反射层**。

反编译产物：`/private/tmp/claude-501/-Users-jin/1e8a9fec-cdbf-48ab-bdeb-15f5238b19d1/scratchpad/doubao-re/jadx-1.3.17/sources/com/bytedance/android/input/speech/AsrManager.java`（源 APK 从真机 `com.bytedance.android.doubaoime` v1.3.17/versionCode 100317008 拉取，JADX 反编译 23/9401 类有小错误，`AsrManager.t()` 方法体因反编译不完整需谨慎，但方法**签名**可信，反射匹配只看签名不看实现）。

## 实现方案（选定：per-family 显式分支，不引入新抽象）

`DoubaoCompatAdapter.java` 现有结构是 `family == V1_3_15 ? A : B` 两路分支（`cancel()`/`stop()`/`commit()` 三个方法内）。**方案 A（选用）**：加第三个 `Family.V1_3_17` 分支，改二路 if/else 为三路 `switch`/`if-else if`，直接复用现有字段结构（`activePrimary/activeSecondary/cancel/commit/dispatch`）。**方案 B（不选）**：给每个 family 挂一个函数式 invoker/策略对象——对当前只有 3 个具体 family 的规模属于过度抽象，不符合"禁重造轮子/不过度设计"纪律，弃用。

具体改动：

1. `enum Family` 加 `V1_3_17`。
2. `resolve()` 新增第三个探测分支：`surface != null && J != null && F != null && undo(u) != null && dispatch(t,int,long) != null && newStop(w0,boolean,String) != null` → 返回 `new DoubaoCompatAdapter(Family.V1_3_17, manager, surface, activeJ, activeF, /*cancel=*/undo, /*commit=*/newStop, dispatch, "v1.3.17 capabilities: surface/J/F/u/w0(bool,String)/t")`。注意：`commit` 字段现在装的是**双参** `w0(boolean,String)`，与 1.3.15 分支里 `commit` 字段是**无参** `w0()` 不同，`invoke(commit, manager)`（无参调用）对 1.3.17 会抛 `IllegalArgumentException`（参数个数不对），因此第 3 步必须同步改三个调用点。
3. `hasNativeSurface()`：从 `family == Family.V1_3_15` 改为 `family == Family.V1_3_15 || family == Family.V1_3_17`（1.3.17 native surface 类仍存在，未变化）。
4. `cancel(Object manager)`：三路化 —
   - `V1_3_15`：`invoke(cancel, manager)`（不变，无参 u）
   - `V1_3_17`：`invoke(cancel, manager)`（不变，无参 u，语义一致——u() 签名两版本相同）
   - 其它（1.3.14 族）：`invoke(cancel, manager, true, "cancel")`（不变）
5. `stop(Object manager, boolean noWaitResult, String from)`：三路化 —
   - `V1_3_15`：不变（`noWaitResult` 走无参 `cancel`=u，否则无参 `commit`=w0()）
   - `V1_3_17`（新增分支）：**不再走无参 commit**，统一调用 `invoke(commit, manager, noWaitResult, from)`（即双参 `w0(noWaitResult, from)`，把原始 `from` 原样传下去，不要硬编码成 "send"/"cancel"）
   - 其它（1.3.14 族）：不变（`invoke(cancel, manager, noWaitResult, from)`——**注意 1.3.14 族用的是 `cancel` 字段**，与 1.3.17 新分支用 `commit` 字段是两回事，不要混）
6. `commit(Object manager)`：
   - `V1_3_17`：不能再无参调用；改为 `invoke(commit, manager, false, "send")`（对齐旧版本"松手上屏"的默认语义：`noWaitResult=false, from="send"`）
   - 其它 family 保持 `invoke(commit, manager)` 不变
7. `diagnostic` 字符串按上面 resolve() 里写的更新，方便真机 logcat 排障。

**不要动**：`DoubaoLetterLongPressHook.java` 里的 `KEYBOARD_VIEW`/`KEYBOARD_JNI`/`IME_SERVICE`/`ASR_MANAGER` 等类名常量（均验证未变）、native hook 相关代码（`installNativeTouchProbe`/`nativeTouch` 签名不变）、`AsrLongPressView` 相关 UI 层代码（类存在，字段访问方式若有硬编码字段名如 `f3162c` 需要单独核实但**不在本次改动范围**，除非编译期报字段找不到再处理）。

## 可机检验收（Verifier）

1. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 全部通过。
2. `grep -n "V1_3_17" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java` 命中新增分支（resolve/hasNativeSurface/cancel/stop/commit 五处都要出现，不能漏改）。
3. 代码审查：`invoke(commit, manager)` 无参调用**不再**出现在 `V1_3_17` 分支路径上（防止 IllegalArgumentException 参数个数不对崩溃）；1.3.14/1.3.15 两个既有分支的行为逐字节未变（回归保护）。
4. 产物为 arm64 debug APK。

（真机验收由主会话在 OnePlus15 / 豆包 1.3.17 上做，不在 Verifier 范围内，见下方"实施后步骤"。）

## 实施后步骤（主会话，Codex/Verifier 完成后执行，不外包）

1. 卸载真机旧版本模块，安装新构建的 debug APK；**强制停止豆包输入法一次**（README 已知坑：更新模块后不强停/重启，LSPosed hook 不会重新生效）。
2. logcat 确认 `capability probe family=V1_3_17` 且 `isSupported=true`（不再是 `unsupported Doubao build; gesture takeover disabled`）。
3. 用户实测 P0：长按字母键触发语音有振动+录音 UI；原地松手上屏识别文本；滑到工具栏发送/搜索/换行按当前输入框类型正确执行；滑出键盘撤回（不上屏，不残留）。
4. 全部通过后：更新 `README.md` 兼容性表加入 `v1.3.17`；`build.gradle` versionCode 13→14，versionName `1.6.5`→`1.6.6`；提交并可选 push。

- **约束**：Executor 只改文件、不自 commit、不自跑真机测试；不改 `~/.codex/config.toml`。
- **风险档**：质量优先（核心功能，反射签名错配会导致运行时异常或行为错乱，需要严谨）。
- **是否需研究**：否（已通过本机 JADX 反编译+人工比对拿到确定性结论，不是"找现成方案"类问题，不适合外包 GitHub/web 研究）。
