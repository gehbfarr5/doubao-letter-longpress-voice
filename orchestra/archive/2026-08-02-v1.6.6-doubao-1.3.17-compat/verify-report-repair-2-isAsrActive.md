# 验收报告 · 修复轮（isAsrActive 探针查错方法）

- **Verifier**：独立 Verifier（fresh context，未参与规划/实现）
- **日期**：2026-08-02
- **验收对象**：`orchestra/repair-task.md` 规定的 `DoubaoCompatAdapter.resolve()` V1_3_17 探测块修复
- **结论**：**PASS**（静态验收全通过；真机行为待主会话复测）

---

## 1. 实际改动核对

`git diff --stat`（追踪文件）：

```
 app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java | 7 ++++---
 orchestra/cost-ledger.tsv                                              | 1 +
 2 files changed, 5 insertions(+), 3 deletions(-)
```

代码改动仅 1 处连续 hunk（`git diff` 默认上下文下为 1 个 hunk；`-U0` 拆成 3 个零上下文片段，全部落在 `resolve()` 的 V1_3_17 探测块 L59-L65 内）：

```diff
+            Method activeActive = optional(manager, "G");
             Method newStop = optional(manager, "w0", boolean.class, String.class);
-            if (surface != null && activeJ != null && activeF != null
+            if (surface != null && activeActive != null
                     && undo != null && newStop != null && dispatch != null) {
                 return new DoubaoCompatAdapter(Family.V1_3_17, manager, surface,
-                        activeJ, activeF, undo, newStop, dispatch,
-                        "v1.3.17 capabilities: surface/J/F/u/w0(bool,String)/t");
+                        activeActive, null, undo, newStop, dispatch,
+                        "v1.3.17 capabilities: surface/G/u/w0(bool,String)/t");
             }
```

`orchestra/cost-ledger.tsv` 仅追加一行 Executor 计量记录，非代码。

---

## 2. 逐条验收标准

| # | 标准 | 结果 | 证据 |
|---|------|------|------|
| 1 | `:app:testDebugUnitTest` 通过 | ✅ | `BUILD SUCCESSFUL in 2s`；`Task :app:testDebugUnitTest` 实际执行（非 UP-TO-DATE）。测试结果 XML：`GestureSessionTest` tests=3 failures=0 errors=0，`ZoneResolverTest` tests=6 failures=0 errors=0（共 9 例全绿） |
| 2 | `:app:lintDebug` 通过 | ✅ | `lint-results-debug.xml` 中 severity 统计：Warning×15、Error×0、Fatal×0 |
| 3 | `:app:assembleDebug` 通过 | ✅ | 产物 `app/build/outputs/apk/debug/app-debug.apk`（630K，2026-08-02 02:18） |
| 4 | 只改 `resolve()` 内 V1_3_17 探测块 | ✅ | diff 只触及 L59-L65；`app/` 下无其他文件被修改 |
| 5 | V1_3_15 / V1_3_14 探测块零改动 | ✅ | V1_3_15 块（L48-L57）与 V1_3_14 块（L68-L81）不在 diff 范围内 |
| 6 | `hasNativeSurface()` / `cancel()` / `stop()` / `commit()` 零改动 | ✅ | 四个方法（L105-L173）不在 diff 范围内；上一轮 `stop()` 签名修复保持原样 |
| 7 | V1_3_17 分支不再引用 `activeJ`/`activeF` | ✅ | `grep` 结果：`activeJ`/`activeF` 只出现在 L48/49/52/55（全部属于 V1_3_15 块）；V1_3_17 分支只用 `activeActive` |
| 8 | 新探针为独立 `optional(manager, "G")` | ✅ | L59 `Method activeActive = optional(manager, "G");`（零参，与 `G()` 声明匹配） |
| 9 | `isAsrActive()` 对 `activeSecondary == null` 有空值防御 | ✅ | 见下节 |

### 9 详解：`isAsrActive()` 空值安全性（该方法本身未改动）

```java
boolean isAsrActive(Object manager) throws ReflectiveOperationException {
    boolean primary = Boolean.TRUE.equals(invoke(activePrimary, manager));
    boolean secondary = activeSecondary != null
            && Boolean.TRUE.equals(invoke(activeSecondary, manager));
    return primary || secondary;
}
```

- `activeSecondary` 为 null 时被 `&&` 短路，**不会调用 `invoke(null, ...)`**，因此不会抛 `NoSuchMethodException`。
- 构造器里 `accessible(activeSecondary)` 对 null 入参有守卫（`if (method != null)`），不会 NPE。
- `secondary` 退化为 `false`，返回值等于 `primary`，即完全由 `G()` 决定——语义正确，不误判。
- `activePrimary` 在 V1_3_17 分支由 `activeActive != null` 条件保证非 null，`invoke` 的 null 分支不可达。
- V1_3_14 分支本来就传 `null` 作 secondary，此模式在既有代码中已有先例，不是新引入的形态。

---

## 3. 独立交叉核实反编译产物

来源：`/private/tmp/claude-501/-Users-jin/1e8a9fec-cdbf-48ab-bdeb-15f5238b19d1/scratchpad/doubao-re/jadx-1.3.17/sources/com/bytedance/android/input/speech/AsrManager.java`（93K，jadx 产物）

我**未依赖任务文件的转述**，自行读取了方法体与字段赋值点，结论如下（全部独立复现）：

### 3.1 三个方法的真实语义

- **L1065 `public final boolean G()`**：
  ```java
  StringBuilder sbM0 = e.a.a.a.a.m0("[hand_write] isAsrSpeechingStatus mCurrentUIStatus = ");
  ...
  return h == SpeechStatus.KTryStart || h == SpeechStatus.KStart;
  ```
  字节自带调试串 `isAsrSpeechingStatus` + 状态判定，**确为"正在录音/说话中"的权威方法**。✅ 与主会话结论一致。

- **L1061 `public final boolean F()`**：`return h == SpeechStatus.KErrorShowState;` —— 错误展示态，录音正常进行时恒为 `false`。✅ 一致。

- **L1080 `public final boolean J()`**：`return f3093d;` ——
  `f3093d` 的语义由 L1866 的调试串 `", mDontCommit = "` + `sbM0.append(f3093d)` **直接确认为 `mDontCommit`（不要上屏）标志**。

### 3.2 关键补强证据（比任务文件的说法更强）

`f3093d` 赋值点全表（`grep`）：
- L70 声明初值 `= true`
- **L1930 `f3093d = false;`** —— 位于 `startAsr` 成功路径内，紧跟 L1923 `C0(this, SpeechStatus.KTryStart, str, false, 4)`
- L1859 `f3093d = true;` —— 在 `u()`（doUndo）开头
- L873 `f3093d = true;` —— stop/复位路径

**因此在"正在录音"这个窗口内 `f3093d == false`，`J()` 恒返回 `false`**。旧代码 `isAsrActive = J() || F()` 在录音中就是 `false || false = false`——这与真机日志"6 次轮询全部拿不到 true → `ASR start timeout -> abort takeover`"**完全吻合**。根因结论不仅"站得住"，而且是被赋值点严格证明的（`J()` 与"激活"不是无关，而是**语义反相**）。

### 3.3 状态机与 `G()` 覆盖窗口

`SpeechStatus`（L117 内部 enum）包含 `KStart`(L118) / `KTryStart`(L123) / `KStop` / `KStoping` / `KErrorShowState`。
- L1923 启动 → `KTryStart`
- L1400 → `KStart`
- L2068 → `KStoping`；L872 → `KStop`；L1093/1204/1497/1527 → `KErrorShowState`

`G()` 覆盖 `KTryStart ∪ KStart`，正好是 hook 轮询窗口（300ms 起、150ms 间隔、6 次 ≈1.2s）需要命中的两个状态。`h` 是 `private static SpeechStatus h`（L80），`G()` 为实例方法读静态字段，用 `AsrManager.a` 单例做 receiver 反射调用无问题。

### 3.4 family 判定路径未被影响（回归风险检查，任务文件未要求但我做了）

`AsrManager` 中 `w0` 只有 **L1994 `public final void w0(boolean z2, String str)`** 一个重载，**不存在零参 `w0()`**。因此 1.3.17 上 V1_3_15 分支的 `optional(manager, "w0")` 返回 null，条件不成立，必然落入 V1_3_17 分支——family 解析路径与改动前一致，真机日志 `family=V1_3_17` 也印证了这点。另确认 `u()`(L1858)、`t(int,long)`(L1809) 均存在，其余能力项不受影响。

注意但无害：`AsrManager` 里同时存在字段 `private static Runnable G`（L109）与方法 `G()`（L1065）。Java 反射的字段/方法命名空间独立，`getDeclaredMethod("G")` 只会命中方法，无歧义。

### 3.5 调用侧确认

`DoubaoLetterLongPressHook.java` L1286-1316 `verifyAsrStart()` 逻辑未改：L1293 `if (sCompat.isAsrActive(mgr) || sAsrStartConfirmed)` → 命中打 `ASR active id=...`，6 次失败打 `ASR start timeout ... -> abort takeover`。修复后 `isAsrActive` 应在 `KTryStart` 出现后首次轮询即返回 true。

---

## 4. 复现命令

```bash
cd /Users/jin/Desktop/doubao-letter-longpress-voice

# 构建门禁
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# 改动范围
git diff --stat
git diff app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java
grep -n "activeJ\|activeF\|activeActive" app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java

# 反编译交叉核实
D=/private/tmp/claude-501/-Users-jin/1e8a9fec-cdbf-48ab-bdeb-15f5238b19d1/scratchpad/doubao-re
sed -n '1061,1082p' $D/jadx-1.3.17/sources/com/bytedance/android/input/speech/AsrManager.java
grep -n "h = SpeechStatus\|f3093d\|isAsrSpeechingStatus" $D/jadx-1.3.17/sources/com/bytedance/android/input/speech/AsrManager.java
```

---

## 5. 结论与证据等级

**结论：PASS**

改动与 `repair-task.md` 规格逐字一致，范围最小（1 hunk / 5 增 3 删），禁改区域（V1_3_15、V1_3_14 探测块，`hasNativeSurface()`/`cancel()`/`stop()`/`commit()`）零改动，构建三件套全绿，空值防御经代码审查确认成立。反编译语义结论经我独立复核，并额外补到了 `f3093d = mDontCommit` 的赋值点级证明——旧探针 `J()` 在录音期恒为 false，是超时的充分解释。

**证据等级**

| 项 | 等级 | 说明 |
|---|---|---|
| 构建/测试/lint | **强（直接执行）** | 本机实跑，退出码与产物均已核验 |
| 改动范围与禁改区 | **强（直接执行）** | `git diff` 全量比对 |
| 空值防御 | **强（代码审查）** | 短路求值 + `accessible()` null 守卫，路径穷举 |
| 反编译语义（G/F/J/h/f3093d） | **强（一手产物独立复核）** | 自读方法体 + 全量赋值点 grep，不依赖转述；jadx 产物本身有反编译失真风险，但本处逻辑简单、且有官方调试字符串自证 |
| 修复能否消除真机超时 | **中（推理，未真机验证）** | 逻辑链完整闭合，但 `DoubaoCompatAdapter` 无单元测试覆盖（反射依赖宿主类），最终需真机 logcat 确认出现 `ASR active id=... family=V1_3_17` |

**遗留 / 建议（不阻塞本轮）**

1. 真机复测由主会话执行：重装后 logcat 应见 `ASR active id=... family=V1_3_17`，且滑到工具栏发送、滑出撤回在 1.2s 窗口内完成。
2. `G()` 每次调用都会写一行 `[ASR-Flow]-AsrManager` 日志，一次手势最多轮询 6 次，日志噪音可接受，无功能影响。
3. 本轮教训值得沉淀：**混淆字母在版本间无语义延续性**，family 探测不能只靠"方法名+参数签名匹配"，必须核对方法体语义。当前 `resolve()` 仍是纯签名匹配，未来新版本仍可能踩同类坑（本轮不改）。
