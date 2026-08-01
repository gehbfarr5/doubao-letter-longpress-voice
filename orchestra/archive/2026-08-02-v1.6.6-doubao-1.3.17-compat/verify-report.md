# Verify Report — 适配豆包 1.3.17（DoubaoCompatAdapter 新增 Family.V1_3_17）

- 日期：2026-08-01
- 分支：`fix/doubao-1.3.17-compat`（base `7abebf9`）
- 任务规格：`orchestra/task-current.md`
- Verifier：独立子 Agent（fresh context，不采信 Executor 自述，只取机器证据）
- Executor 声称改动：`DoubaoCompatAdapter.java` 单文件

## 结论

**PASS（可机检 4 条全部通过）** + **1 条 HIGH 级独立审查风险，建议主会话在真机测试前先决策**。

Executor 的实现与 `task-current.md` 方案逐条一致，构建/测试/lint 全绿，产物为纯 arm64 debug APK，1.3.14/1.3.15 既有路径行为未变。风险不在"是否照规格实现"，而在"规格本身第 5 条对 1.3.17 撤回语义的处理与 1.3.15 不等价"（详见「独立代码审查」第 3 节）。

## 复现命令

```bash
cd /Users/jin/Desktop/doubao-letter-longpress-voice
git diff
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
grep -n "V1_3_17" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -oE "lib/[^/]+/" | sort -u
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
```

## 可机检验收逐条核对

### 1. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — PASS（一级证据）

```
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
> Task :app:lintDebug
BUILD SUCCESSFUL in 17s
51 actionable tasks: 17 executed, 34 up-to-date
```

- 单元测试：`app/build/test-results/testDebugUnitTest/` 解析结果 `tests 9 failures 0 errors 0 skipped 0`
  （`GestureSessionTest` 6 例、`ZoneResolverTest` 3 例）。
- Lint：`app/build/reports/lint-results-debug.xml` 解析 `{'Warning': 15}`，**Error = 0**；15 条 warning 全部不在 `DoubaoCompatAdapter.java`（脚本按 location/file 过滤，命中数 0），即本次改动未新增 lint 问题。
- 编译期唯一提示是既有的 `DoubaoLetterLongPressHook.java 使用或覆盖了已过时的 API`（改动前既有，与本任务无关）。
- `git diff --check`：OK（无空白/冲突标记问题）。

### 2. `grep -n "V1_3_17" DoubaoCompatAdapter.java` 五处齐全 — PASS（一级证据）

```
11:    enum Family { V1_3_17, V1_3_15, V1_3_14, UNSUPPORTED }
62:                return new DoubaoCompatAdapter(Family.V1_3_17, manager, surface,   ← resolve()
106:                && (family == Family.V1_3_15 || family == Family.V1_3_17);        ← hasNativeSurface()
140:        } else if (family == Family.V1_3_17) {                                    ← cancel()
155:        } else if (family == Family.V1_3_17) {                                    ← stop()
163:        if (family == Family.V1_3_17) {                                           ← commit()
```

要求的 resolve / hasNativeSurface / cancel / stop / commit 五处全部命中，另加 enum 声明，共 6 处，无遗漏。

### 3. `invoke(commit, manager)` 无参调用不在 V1_3_17 路径上 — PASS（一级证据）

全文仅两处无参 `invoke(commit, manager)`：

- L153：`stop()` 的 `family == V1_3_15` 分支内（1.3.15 专属，不可达 V1_3_17）
- L166：`commit()` 的 `else` 分支（V1_3_17 已被 L163 的 `if` 提前拦截）

V1_3_17 三个终态路径全部为带参调用：
- `cancel()` → `invoke(cancel, manager)`（`cancel` 字段在 V1_3_17 装的是无参 `u()`，参数个数正确）
- `stop()` → `invoke(commit, manager, noWaitResult, from)`（双参 `w0(boolean,String)`）
- `commit()` → `invoke(commit, manager, false, "send")`（双参）

不存在 `IllegalArgumentException: wrong number of arguments` 风险。

### 4. 1.3.14 / 1.3.15 既有分支行为未变 — PASS（一级证据 + 静态推理）

`git diff --stat` 只有 2 个文件：

```
app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java | 23 +++++++++++++++++++---
orchestra/cost-ledger.tsv                                              |  1 +
```

diff 全为**新增**，唯一"修改行"是三处：

| 位置 | 旧 | 新 | 对 1.3.14/1.3.15 的影响 |
|---|---|---|---|
| L11 enum | `{V1_3_15, V1_3_14, UNSUPPORTED}` | `{V1_3_17, V1_3_15, V1_3_14, UNSUPPORTED}` | 无。全仓 `grep -rn "Family" app/src` 除 `DoubaoCompatAdapter.java` 外**零命中**，无 `ordinal()` / `values()` / 序列化依赖，重排安全 |
| L104-106 `hasNativeSurface()` | `family == V1_3_15` | `family == V1_3_15 \|\| family == V1_3_17` | 无。V1_3_15 结果不变；V1_3_14 仍为 false（其 `nativeSurfaceClass` 传的是 `null`，双重保险） |
| L162-167 `commit()` | 无条件 `invoke(commit, manager)` | `if V1_3_17 → 带参，else → 原式` | 无。`else` 分支即旧函数体原文 |

`resolve()` 中 V1_3_17 探测块（L59-65）插在 V1_3_15 块（L48-57）**之后**、V1_3_14 块（L67-80）之前：
- 1.3.15 设备先命中 V1_3_15，不会走到新块 → 不受影响。
- `cancel()` / `stop()` 新增的是 `else if` 分支，V1_3_15 的 `if` 在前、V1_3_14/UNSUPPORTED 落到最后的 `else`，两者判定条件与函数体逐字未动。

`DoubaoLetterLongPressHook.java` 的 `git diff --stat` 为空 → **完全未触碰**，类名常量（`KEYBOARD_VIEW`/`KEYBOARD_JNI`/`IME_SERVICE`/`ASR_MANAGER`）、native hook（`installNativeTouchProbe`/`nativeTouch`）、`AsrLongPressView` UI 层代码均在"不要动"范围内且确实未动。

### 5. 产物为 arm64 debug APK — PASS（一级证据）

```
app/build/outputs/apk/debug/app-debug.apk   630 KB
ABI:  lib/arm64-v8a/            ← 唯一 ABI，无 x86/x86_64/armeabi-v7a
      lib/arm64-v8a/libdoubaolongpress_native.so   280512 B
assets/native_init   29 B
assets/xposed_init   41 B
SHA-256: 67de9170cd93875986f318c29c1bfeeee74e8a67029c8147f9518cd044c72056
```

`buildNdkBuildDebug[arm64-v8a]` 实际执行（非 UP-TO-DATE），native 库随本次构建重建。

## 独立代码审查（不依赖任务文件的第二遍通读）

### 1. 字段语义未搞反 — PASS

构造器形参顺序：`(family, asrManagerClass, nativeSurfaceClass, activePrimary, activeSecondary, cancel, commit, dispatch, diagnostic)`。

V1_3_17 实参（L62-64）：
```java
new DoubaoCompatAdapter(Family.V1_3_17, manager, surface,
        activeJ, activeF, undo, newStop, dispatch,
        "v1.3.17 capabilities: surface/J/F/u/w0(bool,String)/t");
```
逐位映射：`activePrimary=J()`、`activeSecondary=F()`、`cancel=undo=u()`（无参）、**`commit=newStop=w0(boolean,String)`（双参）**、`dispatch=t(int,long)`。

`newStop` 的来源是 `optional(manager, "w0", boolean.class, String.class)`，`optional`→`exact`→`getDeclaredMethod`，**精确签名匹配**，不会误绑到别的方法。确认 `commit` 字段在 V1_3_17 分支里装的确实是双参 `w0(boolean,String)`，未被误用成 `u()`、`t()` 或 1.3.14 的 `s0/p0/t0/q0`。`diagnostic` 字符串与实际能力集一致。

### 2. resolve() 探测顺序对 1.3.17 真机可达 — PASS（用反编译产物交叉验证）

对 `.../scratchpad/doubao-re/jadx-1.3.17/sources/.../AsrManager.java`（真机 1.3.17 base.apk 反编译）实测：

```
50:  public final class AsrManager {
63:      public static final AsrManager a = new AsrManager();     ← 单例字段未变
1061: public final boolean F()
1080: public final boolean J()
1809: public final void t(final int i2, final long j2)
1858: public final void u()
1994: public final void w0(boolean z2, String str)               ← 全文唯一 w0 声明
```
`com/bytedance/android/input/speech/view/AsrLongPressView.java` 存在。

关键点：全文**只有一个** `w0` 声明，**不存在无参重载**。因此 1.3.15 探测块的 `optional(manager,"w0")` 在 1.3.17 上必然返回 `null`，不会误判为 V1_3_15，会正确落到新增的 V1_3_17 块。六个能力（surface/J/F/u/w0(bool,String)/t）全部满足 → 1.3.17 真机应产出 `family=V1_3_17, isSupported=true`。

### 3. ⚠️ HIGH — V1_3_17 的"撤回/取消"语义与 V1_3_15 不等价（规格层面的问题，非 Executor 违规）

调用链事实（`DoubaoLetterLongPressHook.java`）：

- L1212：`callAsrStop(mgr, true, ASR_CANCEL_REASON, "cancel")` → `sCompat.stop(mgr, true, "cancel")` —— 这是"滑出键盘撤回"的**唯一**入口。
- `DoubaoCompatAdapter.cancel(Object)` 在全仓**无任何调用点**（`grep -rn "Compat.cancel\|\.cancel(mgr\|\.cancel(manager" app/src` 零命中），是死代码。

于是两族的撤回实际执行路径为：

| family | stop(noWait=true, "cancel") 实际调用 | 效果 |
|---|---|---|
| V1_3_15 | `invoke(cancel, …)` = **`u()` doUndo** | 置 `f3093d=true`(mDontCommit)、`B().a("")` 清 pre-edit、内部再调 `w0(true,"undo")`、`view.A("", false)` 复位 |
| V1_3_17 | `invoke(commit, …, true, "cancel")` = **裸 `w0(true,"cancel")`** | 仅停止 ASR。**不置 mDontCommit、不清 pre-edit、不复位 view** |

`u()` 反编译体（L1858-1878）证实它是 `w0(true,"undo")` 的严格超集。因此在 1.3.17 上，「滑出键盘撤回」很可能出现**残留 pre-edit 文本或仍然上屏**——正好是 task-current.md「实施后步骤」P0 第 3 条要验的项。

任务规格第 4 条本意是让 V1_3_17 的取消走 `u()`，但由于 hook 从不调用 `adapter.cancel()`，这个意图在运行期落不了地。

**建议（供主会话决策，Verifier 不改业务代码）**：把 `stop()` 的 V1_3_17 分支改成与 V1_3_15 同构——
```java
} else if (family == Family.V1_3_17) {
    if (noWaitResult) {
        invoke(cancel, manager);              // u() doUndo，与 1.3.15 撤回语义一致
    } else {
        invoke(commit, manager, false, from); // w0(false, from) 上屏
    }
}
```
若维持现状，请在真机 P0 第 3 项（滑出撤回）重点观察是否残留文本；出现残留即按上式修正。

### 4. `commit()` 的 `(false, "send")` 取值 — 合理（低风险）

1.3.15 的无参 `w0()` 语义 = "停止并等待结果后上屏"；1.3.17 的 `w0(noWaitResult, from)` 中 `noWaitResult=false` 即"等结果"，与之对齐。反编译内 `k()` 用 `w0(false, "")`（等结果分支）、`t()` 的 fast-path 用 `w0(true,"send")`（明确关掉 waitAllAsrBack 时才 noWait），佐证 `false` 是"要结果"的正确取值。`from="send"` 仅进日志（`[ASR-Flow][stopAsr][Android] ... from=`），不影响控制流。

### 5. 残余不确定项（本机无法机检）

- **1.3.14 设备是否会被新 V1_3_17 块误捕**：需同时满足 `AsrLongPressView` 类存在 + `J/F/u/w0(boolean,String)/t` 全在。本机无 1.3.14 APK 可反编译。间接证据：改动前 `hasNativeSurface()` 仅对 V1_3_15 放行，且 1.3.14 构造时 surface 传 `null`，说明 native surface 是 1.3.15 才引入 → 1.3.14 上 `surface == null`，新块的第一个条件即短路。判定为**低风险**，但属于未经机器验证的推断。
- 真机行为（family 识别、四种终态、振动/录音 UI）不在 Verifier 范围，由主会话在 OnePlus 15 + 豆包 1.3.17 上执行 task-current.md「实施后步骤」。

## 越界检查

- 未 commit（`git status` 仍为 unstaged，工作区状态保持 Executor 交付原样）。
- Verifier 未修改任何业务代码；本次仅新增/覆盖本报告，并产生 `app/build/` 构建产物（预期内）。
- `orchestra/cost-ledger.tsv` 新增 1 行 Executor 记账（`2026-08-01 19:13:55 codex gpt-5.6-sol EXEC_DONE 18791`），属流程文件，非业务代码。
- 未触及 `~/.codex/config.toml`。

## 证据等级

| 项 | 等级 | 说明 |
|---|---|---|
| 构建/测试/lint 通过 | **一级（机器执行）** | 本地 gradle 实跑，非复述 |
| grep 五处命中 | **一级（机器执行）** | 实跑 grep，输出附上 |
| APK arm64 + SHA-256 | **一级（机器执行）** | `unzip -l` + `shasum` |
| 1.3.14/1.3.15 未变 | **一级 + 二级** | diff 为一级；"无 ordinal 依赖"由 `grep -rn Family` 零命中支撑 |
| 1.3.17 签名匹配 | **一级（交叉验证）** | 对真机 APK 反编译产物实跑 grep，非采信任务文件描述 |
| 撤回语义风险（第 3 节） | **二级（静态推理）** | 基于反编译源码 + 调用链 grep 的逻辑推断，未经真机复现 |
| 1.3.14 不被误捕 | **三级（推断）** | 无 1.3.14 APK，仅靠现有代码结构反推 |
