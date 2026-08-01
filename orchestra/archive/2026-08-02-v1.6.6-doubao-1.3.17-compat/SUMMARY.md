# 收口汇总 — v1.6.6 豆包 1.3.17 适配（编排完成，真机验收通过）

## 概览

| 项目 | 结果 |
|------|------|
| **编排轮数** | 3（初版 + 修复轮1「stop()签名」+ 修复轮2「isAsrActive语义反相」） |
| **最终状态** | ✅ **真机验收 PASS**（OnePlus15 + 豆包1.3.17，用户实测 + logcat 交叉验证） |
| **分支** | `fix/doubao-1.3.17-compat`，基于 `origin/main` v1.6.5（ee42bb4） |
| **提交** | `5f07cd8`（新增family）→ `8bfe70b`（isAsrActive修复）→ `abba25b`（版本号/README） |
| **版本** | versionCode 13→14，versionName 1.6.5→1.6.6 |
| **Executor** | Codex `gpt-5.6-sol`(xhigh)：初版30348 + 修复1(网络中断重试)19151 + 修复2 30178 ≈ 79.7k tokens |
| **Verifier** | Opus：第一轮55567 + 修复轮1的42727 + 修复轮2的50102 ≈ 148.4k tokens |

## 问题定位（主会话 RE，两轮）

### 第一轮 RE：install() 完全不装 hook

**根因**：豆包输入法真机更新 1.3.15→1.3.17 后，长按字母键完全无反应。JADX 反编译真机 1.3.17 base.apk（155MB / 9401类）逐一比对 `AsrManager` 方法签名，定位到 `w0()` 从无参变成 `w0(boolean, String)`，导致 `DoubaoCompatAdapter.resolve()` 匹配不到任何已知 family，`install()` 直接 return，一个 hook 都不装（安全静默降级，非崩溃）。

### 第二轮 RE：真机测试后发现的深层语义问题

代码修复后真机测试：长按+原地松手 ✅，但**滑到工具栏发送**、**滑出撤回**均"延迟且无法完成"。分析真机 LSPosed 日志（`/data/adb/lspd/log/verbose_*.log`）定位到 `verifyAsrStart()` 在 1.2s 窗口内轮询 `isAsrActive()` 全部失败 → `abort takeover`。

深挖发现：V1_3_17 分支误复用了 V1_3_15 检测块算出的 `activeJ`("J")/`activeF`("F") 作为激活探针——这两个方法名在 1.3.17 依然存在（签名匹配），但**混淆字母被重新分配到完全不同且方向相反的语义**：
- `F()` 现在是 `h == KErrorShowState`（错误态检查，不是激活检查）
- `J()` 返回 `f3093d`，实为 `mDontCommit`（"不要上屏"）标志，**录音成功时会被置 false**——即录音期间 `J()||F()` 恒为 `false||false`，语义反相，不只是"查错方法"这么简单
- 真正的"正在录音"检查是 `G()`：`h == KTryStart || h == KStart`，反编译源码自带调试字符串 `"isAsrSpeechingStatus"` 佐证是字节跳动自己代码的判断依据

**教训**：混淆字母在版本间没有语义延续性，只按"方法名+签名"匹配不够，必须核对方法体语义。

## 实施方案（三轮编排）

### 初版（Executor 第一轮）

`DoubaoCompatAdapter.java` 新增 `Family.V1_3_17`，三路化 `cancel()`/`stop()`/`commit()`。

**验收**：4/4 机检通过，Verifier 额外发现"滑出撤回"语义风险（HIGH）→ 转修复轮1。

### 修复轮1：stop() 签名对齐

`stop()` 的 V1_3_17 分支从无条件 `w0(noWaitResult, from)` 改为二次分支（`noWaitResult` 时走 `u()`，否则走 `w0(false, from)`），与 V1_3_15 结构对称。首次派发因 Codex CLI WebSocket 网络故障中断（`EXEC_ERR`，非任务问题），同档重试成功。

**验收**：3/3 机检通过。

### 修复轮2：isAsrActive 探针语义修正

真机测试暴露 stop()修复未覆盖的深层问题后，V1_3_17 探测块改为独立查 `optional(manager, "G")` 而非复用 `activeJ`/`activeF`，`activePrimary` 传新探针 `activeSecondary` 传 `null`。

**验收**：3/3 机检通过 + Verifier 独立反编译复核，补强证据（`f3093d`=`mDontCommit`，语义反相而非单纯无关）。

## 真机验收证据（最终，主会话执行）

logcat 修复前后对比（`verbose_2026-08-02T00:51:10.09615.log`）：

**修复前**（01:06-01:07，仅有 stop() 修复，isAsrActive 仍用 J/F）：
```
capability probe family=V1_3_17 detail=...surface/J/F/u/w0(bool,String)/t
ASR start timeout id=1 -> abort takeover
ASR start timeout id=3 -> abort takeover
ASR start timeout id=4 -> abort takeover
...（9次尝试中7次因超时abort，仅原地快速松手的COMMIT路径不受影响）
```

**修复后**（02:23-02:24，isAsrActive 改用 G）：
```
capability probe family=V1_3_17 detail=...surface/G/u/w0(bool,String)/t
ASR active id=1 attempt=0 family=V1_3_17          ← 首次轮询(300ms)即成功，不再超时
gesture finish id=1 terminal=TOOLBAR_ACTION        ← 工具栏发送成功
ASR active id=2 attempt=0 family=V1_3_17
gesture finish id=2 terminal=CANCEL                ← 滑出撤回成功
ASR active id=3 attempt=0 family=V1_3_17
gesture finish id=3 terminal=COMMIT                ← 原地松手成功
...（连续6次全部attempt=0成功，TOOLBAR_ACTION/CANCEL/COMMIT三种终态均正常，零abort takeover）
```

用户真机 P0 实测（OnePlus15 + 豆包1.3.17）：**全部通过**。

## 文件变更（最终）

### 业务代码

- `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoCompatAdapter.java`
  - `enum Family`：+ V1_3_17
  - `resolve()`：+ V1_3_17 探测块（G 探针，非 J/F）
  - `hasNativeSurface()`：|| Family.V1_3_17
  - `cancel()`/`stop()`/`commit()`：+ V1_3_17 三路分支
- `README.md`：兼容性表 + v1.3.17
- `app/build.gradle`：versionCode 13→14，versionName 1.6.5→1.6.6

### 不动清单（完全兼容性保护）

- ✅ `DoubaoLetterLongPressHook.java`：零改动（`verifyAsrStart()` 轮询/超时逻辑本身没问题，问题在其依赖的底层探针）
- ✅ native hook 代码：零改动
- ✅ `Family.V1_3_14`/`V1_3_15` 判定条件与函数体：逐字未动（三轮编排全程回归保护）

## 遗留非阻塞项

1. `adapter.cancel(Object)` 死代码（全仓零调用点，V1_3_15/V1_3_17 分支逻辑相同可合并）→ 留给后续整洁性任务
2. 1.3.14 探测块本机无真机验证，三级推断无问题
3. `DoubaoLetterLongPressHook.java` 里的"swipe gate"（`gate=swipe maxDisp=...`）在整场测试里出现过2次误判（用户手指过早开始滑动，长按哨兵触发时位移已超70px阈值），属既有的、有意的"避免误伤真实滑动打字"防护，非本次改动范围，未处理

## 参考资料

- 反编译产物：`/private/tmp/claude-501/.../scratchpad/doubao-re/jadx-1.3.17/sources/com/bytedance/android/input/speech/`
- 真机日志：`/private/tmp/claude-501/.../scratchpad/doubao-re/verbose.log`、`verbose2.log`
- 三轮任务文档：`task.md`、`repair-task.md`（两版历史见 archive）、`verify-report.md`、`verify-report-repair.md`（两版历史见 archive）

---

**收口时间**：2026-08-02  
**状态**：已完成，真机验收通过，可以 push 到 origin 走 PR（用户尚未要求 push，保留在本地分支）
