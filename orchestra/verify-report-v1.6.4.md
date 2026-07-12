# v1.6.4 独立验收报告

验收人：独立 Verifier agent（不信任先前执行器自报结论，一切以本机跑出的命令输出为准）
项目：/Users/jin/Desktop/doubao-letter-longpress-voice
验收时间：2026-07-12

工作区状态（验收前，未做任何源码修改）：
```
Changes not staged for commit:
	modified:   app/build.gradle
	modified:   app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java
	modified:   orchestra/cost-ledger.tsv
Untracked files:
	.gradle-home/
	keepalive-module/
	orchestra/PLAN-v1.6.1-godclass-split.md
```

---

## 数字项 1–10

### 1. module.prop 存在且 id 精确匹配
命令：`test -f keepalive-module/module.prop && grep -c '^id=doubaovoicesend_keepalive$' keepalive-module/module.prop`
输出：`1`，`rc=0`
判定：**PASS**（期望 1，实得 1）

### 2. service.sh 语法（POSIX sh -n）
命令：`sh -n keepalive-module/service.sh; echo rc=$?`
输出：`rc=0`
判定：**PASS**

### 3. service.sh 引用 DoubaoVoiceSendA11yService
命令：`grep -c 'DoubaoVoiceSendA11yService' keepalive-module/service.sh`
输出：`1`
判定：**PASS**（期望 ≥1，实得 1；组件名写在 `COMP="com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService"`，脚本内通过变量 `$COMP` 复用，字面串只出现一次，符合“不重复硬编码”的合理写法）

### 4. service.sh 含 boot_completed 逻辑
命令：`grep -c 'boot_completed' keepalive-module/service.sh`
输出：`5`
判定：**PASS**（期望 ≥1，实得 5）

### 5. service.sh 含 accessibility_enabled 逻辑
命令：`grep -c 'accessibility_enabled' keepalive-module/service.sh`
输出：`9`
判定：**PASS**（期望 ≥2，实得 9；注意此计数同时匹配了 `accessibility_enabled` 和 `enabled_accessibility_services` 两个不同 key 中都含有的子串 "accessibility_enabled"/"enabled_accessibility"，属预期的宽松匹配，不影响判定）

### 6. Java hook 读取 enabled_accessibility_services
命令：`grep -c 'enabled_accessibility_services' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`
输出：`3`
判定：**PASS**（期望 ≥1，实得 3）

### 7. Java hook 含 Toast 提示
命令：`grep -c 'Toast' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`
输出：`2`
判定：**PASS**（期望 ≥1，实得 2；对应 import android.widget.Toast 与 Toast.makeText 调用）

### 8. build.gradle 版本号
命令：`grep -c 'versionName "1.6.4"' app/build.gradle`
输出：`1`
判定：**PASS**（diff 确认 versionCode 11→12，versionName "1.6.3"→"1.6.4" 同步变更）

### 9. Gradle 组装构建
命令：`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug --console=plain`
关键输出：
```
> Task :app:compileDebugJavaWithJavac
注: 某些输入文件使用或覆盖了已过时的 API。
...
> Task :app:assembleDebug

BUILD SUCCESSFUL in 6s
32 actionable tasks: 10 executed, 22 up-to-date
```
判定：**PASS**（BUILD SUCCESSFUL，仅有过时 API 的 note，无 warning/error 阻断）

### 10. README 存在
命令：`test -f keepalive-module/README.md && echo exists`
输出：`exists`
判定：**PASS**

---

## 语义抽查 A：keepalive-module/service.sh 全文审阅

全文已通读（133 行）。逐条核对：

**(a) 是否为纯 POSIX sh（无 bash 数组 / `[[ ]]` 等）**
- Shebang：`#!/system/bin/sh`
- 扫描命令：`grep -nE '\[\[|\]\]|local |function |\(\(|declare |readonly |=\(' service.sh` → 无匹配（grep rc=1，即未命中）
- 通篇使用 `[ ]` 测试、`case ... esac`、`name() { ... }` 函数定义（未用 `function` 关键字）、`$(...)` 命令替换 — 均为 POSIX 兼容写法。
- 未使用 `local` 关键字（Magisk busybox/toybox sh 环境下 `local` 支持不稳定，此脚本靠全局变量规避该风险）。
- 判定：**PASS**

**(b) 无限循环不会因单轮失败退出**
- 主循环（第 129–132 行）：
  ```sh
  while true; do
    repair_round
    sleep "$INTERVAL"
  done
  ```
  循环体不检查 `repair_round` 的返回值，无论修复成功/失败都继续 sleep 后下一轮，不会 `exit`/`break`。
- `repair_round` 内部各分支在失败时仅 `log_line` 记录并 `return 1`，不触发 `exit`。
- `wait_boot_completed`（第 73–85 行）同样是失败时记录日志、`sleep 1` 后继续 while 循环，不退出。
- 判定：**PASS**

**(c) 追加授权时保留原有列表**
- 第 111–113 行（`case "$cur" in *)` 分支，即 `cur` 非空且不含目标组件时）：
  ```sh
  new_value="${cur}:$COMP"
  put_secure enabled_accessibility_services "$new_value" >/dev/null
  ```
  确认使用 `${cur}:$COMP` 语义，原值 `cur` 被完整保留并以 `:` 分隔追加新组件，未覆盖原列表。
- 判定：**PASS**

**(d) 已含组件时不会重复追加**
- 第 95–99 行：
  ```sh
  case "$cur" in
    *"$COMP"*)
      ensure_accessibility_enabled
      return 0
      ;;
  ```
  当 `cur` 中已包含 `$COMP` 子串时，直接跳到 `ensure_accessibility_enabled` 并 return，不执行 `put_secure` 追加操作，避免重复写入。
- 判定：**PASS**

**(e) 日志有 64KB 截断逻辑**
- `LOG_LIMIT=65536`（第 6 行，即 64KB）
- `trim_log_if_needed`（第 8–22 行）：读取当前日志文件大小（`wc -c`），做非数字防御性归零处理，若 `size -gt LOG_LIMIT` 则 `: > "$LOG"` 清空文件并写入一条截断记录。
- 该函数在每次 `log_line` 调用开头都会执行（第 25 行 `trim_log_if_needed`），确保日志不会无限增长。
- 判定：**PASS**

**A 项总判定：PASS（5/5 子项通过）**

---

## 语义抽查 B：DoubaoLetterLongPressHook.java — dispatchViaA11ySend 及新增检查代码

已读取 `dispatchViaA11ySend`（第 744–774 行）及新增辅助方法 `getImeContext`（第 895–902 行）、`broadcastA11ySend` 改造（第 878–893 行）。核对如下：

**(a) 检查失败只 Toast + log，广播仍照常发出（不拦截）**
- 第 744–774 行结构：
  ```java
  private static void dispatchViaA11ySend(final ClassLoader cl, final String pkg) {
      try {
          ... // 读取 enabled_accessibility_services，缺失时 log + sMainHandler.post(Toast)
      } catch (Throwable t) {
          log("ERR read enabled_accessibility_services: " + ...);
      }
      // 无论上面 if/else/catch 走哪条分支，都会继续执行到这里：
      subscribeAsrAllBackThen(cl, NEWLINE_ASR_MAX_WAIT_MS, () -> broadcastA11ySend(cl, pkg));
      Object mgr = ensureAsrManager(cl);
      if (mgr != null) {
          callAsrStop(mgr, false, "", "a11y send path pkg=" + pkg);
      }
  }
  ```
  新增的检查代码整体包在一个独立 `try/catch` 块内，无论检查结果是“组件缺失”“ctx 为 null”还是抛异常，该 try/catch 块结束后代码都无条件继续往下执行 `subscribeAsrAllBackThen(...)`（进而触发 `broadcastA11ySend`）和 `callAsrStop(...)`。检查块内没有 `return`/`throw` 语句会中断后续流程。
- 判定：**PASS**（检查失败不拦截原有发送路径）

**(b) 读取异常被 catch 且不影响原流程**
- 外层 `catch (Throwable t)` 捕获了 `getImeContext(cl)` 调用（内部用了反射 `XposedHelpers.findClass`/`getStaticObjectField`，可能抛异常）以及 `Settings.Secure.getString(...)` 可能抛出的任何异常，仅记录日志 `log("ERR read enabled_accessibility_services: " + Log.getStackTraceString(t))`，不重新抛出、不调用 `return`，方法会继续往下走到 `subscribeAsrAllBackThen`。
- 内层 Toast 弹出也单独包了一层 `try { Toast.makeText(...).show(); } catch (Throwable toastErr) { log(...); }`，双重保险，Toast 失败也不会导致 `sMainHandler.post` 的 Runnable 抛出未捕获异常。
- 判定：**PASS**

**(c) Toast 在主线程**
- 第 318 行：`private static final Handler sMainHandler = new Handler(Looper.getMainLooper());`
- Toast 调用被包在 `sMainHandler.post(() -> { ... Toast.makeText(...).show(); ... })` 内（第 753–760 行），`sMainHandler` 绑定的是 `Looper.getMainLooper()`，因此 `post` 的 Runnable 保证在主线程执行，`Toast.makeText().show()` 满足 Android 对 Toast 必须在主线程调用的要求。
- 判定：**PASS**

**B 项总判定：PASS（3/3 子项通过）**

---

## 总判定

| 项目 | 结果 |
|---|---|
| 1. module.prop id | PASS |
| 2. service.sh 语法 | PASS |
| 3. 组件名引用 | PASS |
| 4. boot_completed 逻辑 | PASS |
| 5. accessibility_enabled 逻辑 | PASS |
| 6. Java 读取 enabled_accessibility_services | PASS |
| 7. Java Toast 提示 | PASS |
| 8. 版本号 1.6.4 | PASS |
| 9. Gradle assembleDebug | PASS |
| 10. README 存在 | PASS |
| A. service.sh 语义抽查 (5 子项) | PASS |
| B. dispatchViaA11ySend 语义抽查 (3 子项) | PASS |

**全部 12 大项（含数字项 1–10 与语义抽查 A/B）均为 PASS。**

**总判定：PASS**

无发现任何 FAIL 项。构建产物、脚本语法、组件命名、版本号、日志截断、幂等追加、异常兜底、主线程 Toast 等关键行为均以实际命令输出/源码文本核实，未依赖任何先前执行器的自报结论。
