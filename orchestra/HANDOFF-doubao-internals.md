# HANDOFF: 逆向豆包 ASR 完成机制 → DOUBAO-INTERNALS.md

## 任务类型
运行/分析迭代（不走完整 commit 流程）。产出 `orchestra/DOUBAO-INTERNALS.md`。

## 背景

这是一个 LSPosed 模块（`com.jin.doubaolongpressvoice`），hook 豆包输入法
（`com.bytedance.android.doubaoime`）的 `KeyboardView` 来实现长按字母键触发语音输入。

当前有两个关键 workaround，都是因为没找到豆包内部的权威信号而不得不用轮询/静默探测替代：

### Workaround 1: pollAsrSettleAndEnter（最重要）
位于 `DoubaoLetterLongPressHook.java`。

豆包空格长按 → 滑到"发送"松手时，豆包自己有一套"等 ASR 整理结果 → 执行动作"的机制。
我们不知道豆包用的哪个内部信号，所以用了一个 100ms 静默探测替代：
- 每 50ms 检查一次 `sLastAsrCallbackTs`
- 连续 100ms 没有 `onAsrSetPreedit` / `onAsrCommitPreeditText` callback 到来
- 就认为 ASR 已经整理完了，然后发 KEYCODE_ENTER

这个方案偶尔 race，特别是长文本整理慢的时候。

### Workaround 2: resolveEnterOrdinal（三来源交叉）
同样在 `DoubaoLetterLongPressHook.java`。

为了知道当前输入框的 EnterActionType（发送/搜索/换行等），我们同时读三个来源并取"最高优先级"：
1. `EditorViewInfo.e().d()` （obfuscated class `com.bytedance.android.input.speech.view.o`）
2. `KeyboardJni.mCurrentEnterType.ordinal()`
3. `KeyboardJni.mCurrentEditboxAction & 0xFF`

但豆包的 `AsrLongPressView`（空格长按面板）肯定有自己的单一权威来源。我们想找到它。

## 任务目标

1. **拿到豆包 APK** 并反编译
2. **定位 `AsrLongPressView`**，追踪"滑到发送 → 松手 → 等 ASR → 执行动作"完整调用链
3. **找到 ASR complete 的权威信号**（callback interface / state field / latch / 任何机制）
4. **找到 `AsrLongPressView` 读 EnterActionType 的单一来源**
5. **顺带记录**沿路发现的其它有价值的 hook 点
6. 产出 `orchestra/DOUBAO-INTERNALS.md`

## 执行步骤

### Step 1: 拿 APK

```bash
# 设备：192.168.31.25:5555（一加 15）
adb -s 192.168.31.25:5555 shell pm path com.bytedance.android.doubaoime
# 拿到路径后 pull base.apk
adb -s 192.168.31.25:5555 pull <base_apk_path> /tmp/doubao.apk
```

### Step 2: 反编译

```bash
jadx -d /tmp/doubao-jadx /tmp/doubao.apk --show-bad-code 2>&1 | tail -5
# jadx 在 /opt/homebrew/bin/jadx
# 注：反编译大 APK 可能需要 2-5 分钟，请耐心等待
```

### Step 3: 定位关键类

已知的类名（obfuscated，但可以搜 string literal / method signature 来交叉定位）：

| 符号 | 已知 obfuscated 名 | 用途 |
|---|---|---|
| `AsrManager` | `com.bytedance.android.input.speech.AsrManager` | ASR 管理器单例 |
| `AsrManager.p0(boolean, String)` | 已知 | stop/cancel ASR |
| `AsrManager.q0()` | 已知 | graceful stop (commit) |
| `AsrManager.t(int, long)` | 已知 | "等 ASR 整理 + 执行动作" |
| `AsrManager.E()` | 已知 | isRunning() |
| `AsrManager.x()` | 已知 | forceVad() |
| `KeyboardJni.onAsrSetPreedit(String)` | 已知 | ASR 流式 preedit update |
| `KeyboardJni.onAsrCommitPreeditText()` | 已知 | ASR final commit |
| `AsrLongPressView` | **未知** | 空格长按面板 View |

搜索思路（jadx 产出在 `/tmp/doubao-jadx/sources/`）：

```bash
# 找 AsrLongPressView 或类似名字（可能被混淆）
grep -r "AsrLongPress\|asr_long_press\|long_press_voice\|LongPressVoice" \
  /tmp/doubao-jadx/sources/ --include="*.java" -l

# 找引用 asr_long_press_send_text 资源的类（这个 string 在我们代码里已知是豆包的）
grep -r "asr_long_press_send_text\|asr_long_press_go_text\|asr_long_press_search_text" \
  /tmp/doubao-jadx/sources/ --include="*.java" -l

# 找 AsrManager.t() 的调用方
grep -r "\.t(" /tmp/doubao-jadx/sources/ --include="*.java" -l | head -20

# 找可能的 ASR complete callback interface（含 onFinish/onComplete/onStop/onEnd）
grep -r "onAsrFinish\|onAsrComplete\|onAsrStop\|onAsrEnd\|onSpeechEnd\|onRecognizeEnd" \
  /tmp/doubao-jadx/sources/ --include="*.java" | head -30
```

### Step 4: 分析 AsrManager.t() 内部

`t(int ordinal, long ts)` 是"wait for ASR finish then perform action"的入口。分析：
- 它如何等待 ASR 完成？是 postDelayed / Handler / callback / Observer?
- 它内部注册了什么 listener？
- 完成后怎么触发 action（调了什么方法，用了什么参数）？

### Step 5: 追踪完整链路

从 `AsrLongPressView` 的 touch 事件处理（onTouch / dispatchTouchEvent）：
- 找"手指从 send 区域松开"的处理分支
- 追踪到 `t()` 或等效调用
- 找 `t()` 内部 ASR 完成的 callback

### Step 6: 找 EnterActionType 来源

`AsrLongPressView` 要显示"发送 / 搜索 / 换行"标签，它肯定读了 EnterActionType。找：
- 读的哪个字段？
- 哪个类/方法提供这个值？
- 和我们现在用的三来源比，有什么不同？

### Step 7: 顺带记录其它 hook 点

沿路看到的任何有价值的东西都记下来：
- 新的可 hook 的 callback interface
- 有用的 state 字段
- 其他功能的入口（比如 LLM candidate window、翻译 toolbar 等）

## 产出格式

写到 `orchestra/DOUBAO-INTERNALS.md`，结构：

```markdown
# Doubao IME 内部 Hook 点文档

## ASR Manager

### t(int, long) 内部机制
...（类名、方法签名、等待机制描述）

### ASR Complete 信号
...（具体 callback interface / field name，如何 hook）

### 建议 Hook 方案（替换 pollAsrSettleAndEnter）
...（完整的 XposedHelpers.findAndHookMethod 签名）

## EnterActionType 权威来源
...（类名、字段名、如何读）

## AsrLongPressView
...（发现的类名（混淆后）、touch 处理路径）

## 其它 Hook 点（按价值排序）
...
```

## 约束

- 只分析，不修改项目源码
- APK 和反编译产物放 `/tmp/`（不进 git）
- `orchestra/DOUBAO-INTERNALS.md` 是唯一落进项目的产出文件
- 如果 jadx 超时或某类找不到，记录"未找到"并给出已找到的内容，不要空手而归
