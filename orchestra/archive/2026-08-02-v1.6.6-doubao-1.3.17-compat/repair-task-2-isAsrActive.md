# 修复任务 (真机测试发现新问题 · 回 Executor · 轮次 1/2)

- **轮次**：1（这是继"stop()签名"修复轮之后的新一轮，独立计数——那一轮的问题已解决且真机验证过；这是真机测试暴露的另一个问题）
- **背景**：真机（OnePlus15 + 豆包1.3.17）安装后测试：长按字母键有振动+录音 UI ✅，原地松手上屏 ✅，但**滑到工具栏发送**和**滑出键盘撤回**这两个手势都"有延迟且无法正常完成"。
- **真机 logcat 取证**（`/data/adb/lspd/log/verbose_*.log`，已拉到本地 `/private/tmp/claude-501/-Users-jin/1e8a9fec-cdbf-48ab-bdeb-15f5238b19d1/scratchpad/doubao-re/verbose.log`）：
  ```
  gesture start id=6 family=V1_3_17 ... ordinal=3
  zone id=6 LETTER -> TOOLBAR ...
  ASR start timeout id=6 -> abort takeover     ← 就在到达 TOOLBAR 区域后 124ms 内触发
  ```
  9 次手势里 7 次因 `ASR start timeout ... -> abort takeover` 放弃接管（仅原地快速松手不受影响，因为 commit 路径不经过这个轮询）。

## 根因（本轮 RE，交叉验证 1.3.17 反编译源码）

`DoubaoLetterLongPressHook.verifyAsrStart()`（L1286-1316）在长按开始后 300ms 起、每 150ms 轮询一次 `sCompat.isAsrActive(mgr)`，6 次（总窗口约 1.2s）都拿不到 `true` 就调用 `session.finish(ABORTED)`，之后 `session.isActive()` 恒为 false，导致工具栏分发（L611/641/672 一带的 `session.isActive()` 判断）和滑出撤回**全部**被跳过。

`DoubaoCompatAdapter.isAsrActive()` 对 V1_3_17 family 复用的是 V1_3_15 检测块算出来的 `activeJ`("J") / `activeF`("F") ——这两个方法**名字**在 1.3.17 依然存在（签名匹配，所以 resolve() 把它们当"复用"塞进了 V1_3_17 分支），但**混淆字母被重新分配给了完全不同的语义**：

```java
// jadx-1.3.17/.../AsrManager.java
public final boolean F() {              // 不再是"是否激活"，现在是"是否处于错误展示态"
    return h == SpeechStatus.KErrorShowState;
}
public final boolean J() {               // 不是"是否激活"，是 u()/doUndo 设置的无关标志位 f3093d
    return f3093d;
}
public final boolean G() {               // ★ 这才是真正的"是否正在说话"检查
    return h == SpeechStatus.KTryStart || h == SpeechStatus.KStart;
}
```

`h` 是 `AsrManager` 里的 `private static SpeechStatus h`（状态机：`KStop → KTryStart → KStart → [KStoping] → KStop`，`KErrorShowState` 是错误分支）。`G()` 方法体反编译产物里带着字节跳动自己的调试字符串 `"[hand_write] isAsrSpeechingStatus mCurrentUIStatus = "`，可以确认这就是官方代码判断"正在说话/录音中"的权威方法，只是混淆后恰好落在字母 `G` 上（跟 1.3.15 时代 `J`/`F` 代表"激活"完全是两回事——**混淆字母在版本间没有语义延续性，只是恰好同名**，这是本次 RE 的一个重要教训，之前只按"名字+签名匹配"判断 family 是不够的，还要核对方法体语义）。

## 收窄后的修复指令

只改 `DoubaoCompatAdapter.java` 的 `resolve()` 方法里 V1_3_17 探测块（不动 V1_3_15/V1_3_14 探测块）：

1. **不要复用** V1_3_15 检测块里算出来的 `activeJ`/`activeF`。改为单独探测方法名 `"G"`（无参、boolean 返回，虽然 `getDeclaredMethod` 不检查返回类型，只按名字+参数匹配，行为不变）：
   ```java
   Method activeActive = optional(manager, "G");
   ```
2. V1_3_17 分支的探测条件改成依赖 `activeActive`（不再依赖 `activeJ`/`activeF`）：
   ```java
   Method newStop = optional(manager, "w0", boolean.class, String.class);
   if (surface != null && activeActive != null
           && undo != null && newStop != null && dispatch != null) {
       return new DoubaoCompatAdapter(Family.V1_3_17, manager, surface,
               activeActive, null, undo, newStop, dispatch,
               "v1.3.17 capabilities: surface/G/u/w0(bool,String)/t");
   }
   ```
   即：`activePrimary` 字段传 `activeActive`（对应新 `G()`），`activeSecondary` 传 `null`（`G()` 内部已经 OR 了 `KTryStart`/`KStart` 两种子状态，不需要第二个探针；`isAsrActive()` 方法本身对 `activeSecondary==null` 有防御——`activeSecondary != null && ...`——不用额外改动那个方法）。
3. **不要动**：`hasNativeSurface()`、`cancel(Object)`、`stop(Object,boolean,String)`、`commit(Object)` 四个方法（上一轮已验证通过，这次改动跟它们无关——它们操作的是 `cancel`/`commit` 字段即 `u()`/`w0(boolean,String)`，跟这次改的 `activePrimary`/`activeSecondary`（即 `J`/`F`→`G`）完全独立）；`V1_3_14`/`V1_3_15` 探测块和分支逻辑；`DoubaoLetterLongPressHook.java`（`verifyAsrStart()` 的轮询/超时逻辑本身没问题，问题在于它依赖的 `isAsrActive()` 底层探针查错了方法）。

## 验收

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 全过。
- `git diff` 只改 `resolve()` 方法内 V1_3_17 探测块，其余零改动。
- 代码审查：V1_3_17 分支不再引用 `activeJ`/`activeF` 变量；新探测用的是独立的 `optional(manager, "G")`。
- 真机复测（主会话执行，不在本轮 Executor/Verifier 范围）：重装后 logcat 应能看到 `ASR active id=... family=V1_3_17`（而不是 `ASR start timeout ... abort takeover`），滑到工具栏发送、滑出撤回都应在 1.2s 窗口内正常完成。
