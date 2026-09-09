# 验收报告 v1.6.7（豆包输入法 1.4.4 适配）

- 验收人：独立 Verifier（fresh context，不采信 Codex 自述）
- 日期：2026-09-10
- 基线 commit：`9b10315 plan: 豆包输入法 1.4.4 适配（v1.6.6 → v1.6.7）`
- 工作区状态：`M README.md`、`M app/build.gradle`、`M DoubaoCompatAdapter.java`、`M orchestra/DOUBAO-INTERNALS.md`、`M orchestra/cost-ledger.tsv`（无未跟踪新文件）

**判决：PASS —— 可以 commit。**（附 3 条非阻塞观察，见文末）

---

## 1. 改动范围核对 — PASS

`git status --porcelain` 输出：

```
 M README.md
 M app/build.gradle
 M app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java
 M orchestra/DOUBAO-INTERNALS.md
 M orchestra/cost-ledger.tsv
```

与 task-current.md「要改的文件」一致（`cost-ledger.tsv` 是编排框架自身记账，非代码改动）。

`git status --porcelain -- orchestra/re-v1.4.4/` 输出为空 → 参考反编译源码**未被修改**，也未被移入 `app/src/main/java`（`find app/src/main/java -name '*.java'` 只列出模块自身文件），符合「不要做」约束。

---

## 2. `Family.V1_4_4` 存在 — PASS

```
11:    enum Family { V1_4_4, V1_3_17, V1_3_15, V1_3_14, UNSUPPORTED }
```

`grep -c "V1_4_4" DoubaoCompatAdapter.java` = **7**，分布：

```
11  enum 定义
53  resolve() 返回分支
118 hasNativeSurface()
151 cancel()
161 stop()
185 commit()
195 dispatch()
```

覆盖验收标准 2 要求的「Family 定义 + resolve 分支 + dispatch/cancel/stop/commit 各至少一处」。

---

## 3. `resolve()` 新分支方法名 — PASS（未踩 `w0` 陷阱）

实际代码（`DoubaoCompatAdapter.java:47-56`）：

```java
Method v144Dispatch = exact(manager, "E", int.class, long.class, boolean.class);
Method activeT = optional(manager, "T");
Method undoF = optional(manager, "F");
Method longPressStopR0 = optional(manager, "R0");
if (surface != null && activeT != null && undoF != null
        && longPressStopR0 != null && v144Dispatch != null) {
    return new DoubaoCompatAdapter(Family.V1_4_4, manager, surface,
            activeT, null, undoF, longPressStopR0, v144Dispatch,
            "v1.4.4 capabilities: surface/T/F/R0/E(bool)");
}
```

- 四个方法名为 `E(int,long,boolean)` / `T()` / `F()` / `R0()`，**没有**出现 `w0(boolean,String)`。
  全文件搜 `"w0"` 仅命中第 62 行、71 行两处，均在旧 V1_3_15 / V1_3_17 分支内，未被改动。
  **陷阱 1 未踩。**
- 全文件搜 `"Q0"` 无命中 → 适配层没有绕过包装方法直调 `Q0`。**陷阱 2 未踩。**
- 构造器形参顺序为 `(family, asrManagerClass, nativeSurfaceClass, activePrimary,
  activeSecondary, cancel, commit, dispatch, diagnostic)`（第 27-30 行），
  逐位对齐实参：`activePrimary=T()`、`activeSecondary=null`、`cancel=F()`、
  `commit=R0()`、`dispatch=E(int,long,boolean)` —— 与 task 映射表**完全一致**，
  没有把 cancel/commit 接反。
- 新分支放在整条探测链**最前面**，符合「新版本优先探测」的要求。

---

## 4. `dispatch()` 多传 `true` — PASS

```java
void dispatch(Object manager, int ordinal, long now) throws ReflectiveOperationException {
    if (family == Family.V1_4_4) {
        invoke(dispatch, manager, ordinal, now, true);
    } else {
        invoke(dispatch, manager, ordinal, now);
    }
}
```

三参调用 `E(ordinal, now, true)`，第三参 `true` 与真实调用点一致（见第 6 节交叉证据）。旧 family 走原 `invoke(dispatch, manager, ordinal, now)`，路径未变。

---

## 5. `cancel()` / `stop()` / `commit()` 逻辑自洽 — PASS

```java
void cancel(Object manager) {
    if (family == V1_4_4 || family == V1_3_15 || family == V1_3_17) invoke(cancel, manager);   // F()
    else invoke(cancel, manager, true, "cancel");
}

void stop(Object manager, boolean noWaitResult, String from) {
    if (family == V1_4_4) {
        if (noWaitResult) invoke(cancel, manager);   // F()
        else              invoke(commit, manager);   // R0()
    } else if (...) { /* 原逻辑 */ }
}

void commit(Object manager) {
    if (family == V1_4_4) invoke(commit, manager);   // R0()
    else if (family == V1_3_17) invoke(commit, manager, false, "send");
    else invoke(commit, manager);
}
```

等价性核对：

| 断言 | 展开 | 结论 |
|---|---|---|
| `stop(noWaitResult=true)` ≡ `cancel()` | 两者都是 `invoke(cancel /* F() */, manager)` | ✅ 逐字等价 |
| `stop(noWaitResult=false)` ≡ `commit()` | 两者都是 `invoke(commit /* R0() */, manager)` | ✅ 逐字等价 |

`R0()` 无参、不传 `from`，与 task「新 API 本身的限度」说明一致，不是偷懒。

---

## 6. 旧 family 分支未被破坏 — PASS

逐条审 `git diff`，对旧 family 的**全部**影响：

1. `resolve()`：`Method dispatch = exact(manager, "t", int.class, long.class);` 从原第 45 行下移到新第 58 行（V1_4_4 块之后），`Class<?> surface = ...` 相应上移。
   - 三个旧分支（V1_3_15 / V1_3_17 / V1_3_14）的 **if 条件与构造器实参一字未改**；
   - `dispatch`、`undo` 等变量的声明位置仍在**所有**使用点之前（旧分支起于第 63 行），无前向引用，编译已证（第 8 节）。
2. `hasNativeSurface()`：仅在 `||` 链上追加 `family == Family.V1_4_4`，旧两项保留。
3. `cancel()`：原为 `if (V1_3_15) {invoke(cancel, manager);} else if (V1_3_17) {invoke(cancel, manager);}` —— **两分支体逐字相同**，合并为一个 `||` 条件后对 V1_3_15/V1_3_17 是**语义恒等变换**，V1_3_14/UNSUPPORTED 落 `else` 不变。
4. `stop()` / `commit()` / `dispatch()`：V1_4_4 分支**前插**，旧 `else if` 链原样保留（`V1_3_15` 的 if/else、`V1_3_17` 的 `invoke(commit, manager, false, from)`、`V1_3_17` 的 `invoke(commit, manager, false, "send")` 均未改动）。

结论：无一处旧 family 的调用路径发生行为变化。

**误命中风险评估**：旧版本若要错误落入 V1_4_4 分支，需同时具备 `E(int,long,boolean)`（精确三参签名）+ `T()` + `R0()` + `surface`。1.3.14 的 `E` 是无参 active probe（第 79 行 `optional(manager, "E")` 无参），签名不同，`getDeclaredMethod` 不会匹配。风险判定为低。（本仓库未存 1.3.x 反编译源码，无法机器穷证；仅列为残留风险，见文末观察 ③。）

---

## 7. 反编译证据交叉核对 — PASS（四个方法名 + 方法体语义 + 调用点三重印证）

`grep -n` on `orchestra/re-v1.4.4/AsrManager.java`：

```
1111:    private final void w0(int r3, long r4) {
1377:    public final void E(final int i2, final long j2, boolean z2) {
1426:    public final void F() {
1486:    public final void I() {
1638:    public final void Q0(boolean r15, java.lang.String r16) {
1654:    public final void R0() {
1673:    public final boolean T() {
```

四个签名**逐字存在**（`T()` 返回类型确为 `boolean`，`E/F/R0` 确为 `void`，均 `public final`、均声明在 `AsrManager` 自身 → `getDeclaredMethod` 可取）。

**方法体语义（读正文，非只信名）：**

- `E(int,long,boolean)` @1377 —— 发送分发。体内 `Q0(true, "send")` 后 `w0(i2, j2)`（@1386-1387），
  并按 `z2` 决定 `smart_organize` 延迟路径。✅ 对应「右侧发送」。
- `F()` @1426 —— 撤回。首行 `f4198e = true;`（mDontCommit），日志字符串
  `"doUndo mHavePreEdit = "`，条件成立后 `Q0(true, "undo")` + `lVar.A("", false)`。✅ 对应「撤回/取消」。
- `T()` @1673 —— 激活探测。日志锚点 `"[hand_write] isAsrSpeechingStatus mCurrentUIStatus = "`，
  `return f4202i == SpeechStatus.KTryStart || f4202i == SpeechStatus.KStart;`。
  ✅ 与 1.3.17 `G()` 的 `KTryStart||KStart` 判据同构，字符串锚点为强证据。
- `R0()` @1654 —— 正常松手提交。`F0(this,"AsrStopTime",...)`、`F0(this,"LongPressStop",1L,...)`、
  `f4196c.u()`，然后 `E.postDelayed(... AsrManager.a.Q0(false, "send"), 150L)`。
  ✅ 对应「150ms 后正常提交」。

**陷阱 1 独立证实**：`grep -n " w0(" AsrManager.java` 仅两行 —— `1111: private final void w0(int r3, long r4)` 与 `1387: w0(i2, j2)`。1.4.4 **不存在** `w0(boolean,String)`，且 `w0` 已是私有 ordinal-dispatch。task 的警告属实。

**旧签名在 1.4.4 的缺席（解释真机 UNSUPPORTED 根因）**：

```
grep "final void t("  → 只有 1046: private final void t(String, String)  # 非 t(int,long)
grep " u() {"         → 无命中
grep " J() {"         → 无命中
```

即 1.4.4 无 `t(int,long)`、无 `u()`、无 `J()` → 旧探测链必然全部落空 → `Family.UNSUPPORTED`，与 task 描述的真机现状吻合。同时反证：V1_4_4 分支即使排在最前，也不存在「抢走旧版本」的通路，因为旧版本设备根本走不到这些新签名。

**真实调用点（交叉证据）**：

```
orchestra/re-v1.4.4/j_sendClick.java:29:    AsrManager.a.E(this.a.f4373c, jCurrentTimeMillis, true);
orchestra/re-v1.4.4/h_rollbackClick.java:23: AsrManager.a.F();
orchestra/re-v1.4.4/AsrLongPressView.java:97: AsrManager.a.R0();
orchestra/re-v1.4.4/AsrLongPressView.java:88: AsrManager.a.I();   // forceVad，模块未用
```

`j_sendClick` 第三参**实传 `true`** → 直接支撑第 4 节的 `invoke(dispatch, manager, ordinal, now, true)`。
`AsrLongPressView.d()` 调 `R0()` → 支撑「松手正常提交」映射。

---

## 8. 实际编译 — PASS

环境：`ANDROID_HOME=/Users/jin/Library/Android/sdk`（环境变量已设，无 `local.properties`，无需补），JDK 17.0.19 (Homebrew)。

```
$ ./gradlew :app:assembleDebug --no-daemon
> Task :app:compileDebugJavaWithJavac
注: 某些输入文件使用或覆盖了已过时的 API。
> Task :app:packageDebug
> Task :app:assembleDebug
BUILD SUCCESSFUL in 6s
36 actionable tasks: 36 executed
```

产物：

```
$ ls -la app/build/outputs/apk/debug/
.rw-r--r--@ 342k jin 10 Sep 00:43 app-debug.apk
.rw-r--r--@  413 jin 10 Sep 00:43 output-metadata.json
```

APK 存在，时间戳为本次验收构建时刻（验收开始前该目录不存在，为全新产出，非陈旧缓存；`36 actionable tasks: 36 executed` 也表明没有走 UP-TO-DATE 假通过）。

---

## 9. 版本号 — PASS

`app/build.gradle` diff：

```
-        versionCode 14
-        versionName "1.6.6"
+        versionCode 15
+        versionName "1.6.7"
```

**从产物二次确认**（不只看源码）：

```
$ $ANDROID_HOME/build-tools/34.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk
package: name='com.jin.doubaolongpressvoice' versionCode='15' versionName='1.6.7'
        compileSdkVersion='36' ... sdkVersion:'23'
```

versionCode=15、versionName=1.6.7 ✅。

---

## 10. 编译警告基线 — PASS（未引入新警告）

独立复跑 `javac -Xlint:deprecation,unchecked`（classpath = `app/libs/api-82.jar` + `android-36/android.jar`）定位每条警告归属：

```
DoubaoVoiceSendA11yService.java:75   [deprecation] stopForeground(boolean)
DoubaoVoiceSendA11yService.java:83   [deprecation] stopForeground(boolean)
DoubaoVoiceSendA11yService.java:154  [deprecation] Notification.Builder(Context)
DoubaoLetterLongPressHook.java:2077  [deprecation] Resources.getDrawable(int)
4 个警告
```

4 条**全部位于本次未改动的文件**，`DoubaoCompatAdapter.java` 零警告，无 unchecked 警告。基线未收紧也未恶化。

---

## 11. `orchestra/DOUBAO-INTERNALS.md` 新增段落 — PASS（无编造）

新增 `2026-09-10 追加 v1.4.4 (versionCode=100404006)` 段落，逐句抽查：

| 文档表述 | 机器证据 | 结论 |
|---|---|---|
| `w0(boolean,String)` 在 1.4.4 已不存在 | `grep " w0("` 只有 `(int,long)` | ✅ |
| 新 `w0(int,long)` 是私有 ordinal-dispatch | `AsrManager.java:1111 private final void w0(int, long)`，被 `E()` @1387 调用 | ✅ |
| `t(int,long)` → `E(int,long,boolean)`，调用点传 `true` | `j_sendClick.java:29` | ✅ |
| `G()` → `T()`，仍查 `KTryStart\|\|KStart`，带 `[hand_write]` 字符串锚点 | `AsrManager.java:1673-1678` | ✅ |
| `u()` → `F()`，`Q0(true,"undo")` | `AsrManager.java:1426,1443` | ✅ |
| `w0(bool,String)` → `R0()`，先记 `LongPressStop` 再延迟 150ms `Q0(false,"send")` | `AsrManager.java:1654-1665` | ✅ |
| forceVad = `I()` | `AsrManager.java:1486`，`AsrLongPressView.java:88` | ✅ |
| 落地描述（优先探测 `surface/T/F/R0/E`、v1.6.6→v1.6.7） | 与源码/build.gradle 一致 | ✅ |
| 「真机验证：待 orchestrator 回填」占位 | 已写入 | ✅ |

APK SHA-256 `0a905c...c8b5d` 与 task-current.md 记载一致（本次未重新下载 APK 校验，属转录一致性核对）。未发现编造的方法名或伪证据。

`README.md` 相应改为「已静态适配 v1.4.4，待真机验证」，措辞未夸大为「已实测」，与实际状态相符。

---

## 判决

**PASS —— 可以 commit。**

验收标准 1-5 全部通过；用户点名的四个重点（`Family.V1_4_4` 存在、四个新方法名正确且未混淆 `w0`、`dispatch` 多传 `true`、`cancel`/`stop`/`commit` 等价性、旧 family 未被破坏）全部通过，且方法体语义与真实调用点三重交叉印证成立。

### 非阻塞观察（建议纳入真机验收清单，不构成 FAIL）

① **`R0()` 是异步提交（postDelayed 150ms → `Q0(false,"send")`），不是同步返回即完成。**
若 `DoubaoLetterLongPressHook` 在 `commit()` 返回后立即做后续动作（清理 UI / 判定终态），真机上可能出现 150ms 竞态。task 说明这与旧版模式一致，静态层面无从证伪，**必须在真机回归中观察「原地提交」终态**。

② **hook 层仍有绕过 adapter 的硬编码反射（本次任务范围外，但对 1.4.4 是残留风险）**：
- `DoubaoLetterLongPressHook.java:858-860` hook `AsrContext.T(int, boolean)`
- `:1901` / `:1908` `InputView.T(z)` → 失败回退 `InputView.R(z)`
- `:1463` / `:1542` `AsrProcess.w(...)`；`:1258` / `:1271` `UserInteractiveManagerNext.g(...)`

这些**都不是 `AsrManager` 的方法**，故不违反 task 的「如实报告」条款所指情形；且均包在 try/catch 内、失败仅打日志降级。但 `AsrContext` / `InputView` / `AsrProcess` 在 1.4.4 的混淆映射本轮**未做静态核对**，真机上可能静默降级（日志会出现 `ERR hook AsrContext#T` / `ERR InputView.T()`）。建议真机验收时**抓 LSPosed 日志确认这几条是否报错**。

③ **1.3.x 设备是否会误命中 V1_4_4 分支，未做机器穷证。**
仓库内无 1.3.14/1.3.15/1.3.17 的反编译源码可比对。推理上安全（需同时具备 `E(int,long,boolean)` 精确三参 + `T()` + `R0()`，而 1.3.14 的 `E` 是无参），且新链条上的 `t(int,long)`/`u()`/`J()` 在 1.4.4 全部缺席，双向不重叠。若日后需要多版本共存保障，建议在旧版本设备上抓一次 `capability probe family=` 日志确认仍为 V1_3_1x。
