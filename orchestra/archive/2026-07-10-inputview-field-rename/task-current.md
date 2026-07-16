# 当前任务计划 (v1.6.2 — getInputView 字段改名修复)

## Task — 修复 getInputView() 对 Doubao 1.3.14 的 InputView 字段改名（x→y）

**档位**：默认（`gpt-5.4`）
**类型**：代码改动，走完整流程
**HANDOFF**：`orchestra/HANDOFF-v1.3.14-inputview-field-rename.md`（已含根因分析 + 完整实现代码，直接照做）

### 背景

用户实机报告：豆包 1.3.14 上长按语音后滑到工具栏 / 滑出取消，徽章提示都不显示。
已通过 `apktool d` 反编译设备上真实的 1.3.14 `base.apk` 确认根因：`ImeService`
存 `InputView` 单例的静态字段从 `x` 改名成了 `y`，导致 `getInputView(cl)`
（`DoubaoLetterLongPressHook.java:1502-1508`）反射失败返回 `null`，连带
`ensureOverlay()`（徽章 attach）和 `callInputViewCloseAsrUi()` 两处失效。

### 涉及文件

- `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`（只改 `getInputView()` 这一个方法）

### 实现要求（HANDOFF 里已给出具体代码，照抄即可，不要另起炉灶）

不要简单把硬编码 `"x"` 换成 `"y"`（下次豆包再改名又炸）。改成：
1. 先按已知最新字段名 `"y"` 走快路径反射；
2. 失败则遍历 `ImeService` 的静态字段，找类型是 `FrameLayout`（或其子类，`InputView` 继承自 `FrameLayout`）且非 null 的字段作为兜底——这个模式项目里已有先例，照抄 `extractKeyboardView()`（`:1282-1296`，按 `SoftReference` 类型扫字段）的写法，只是换成按 `FrameLayout` 类型扫。
3. 两条路径都失败才返回 `null`，并保留原有的 `log("ERR getInputView: ...")` 风格。

顺手把版本号从 `1.6.1` bump 到 `1.6.2`（`app/build.gradle` 的 `versionCode`/`versionName`），并在 README.md 的兼容性/已知限制段落里补一句 1.3.14 的这个 InputView 字段修复说明（参考 README 里已有的 v1.3.14 AsrManager 方法名适配写法，同样的行文风格）。

**不要动**：`AsrManager.s0/t0` 相关代码、`effectiveToolbarHeight`、`KeyboardJni` 相关反射——这些已经用同一次反编译验证过是对的，不需要改。

### 机器可检验收条件

1. `grep -c 'getStaticObjectField(imeServiceCls, "x")' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → `0`（旧硬编码字段名必须被替换掉）
2. `grep -c 'FrameLayout.class.isAssignableFrom' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → `>= 1`（确认加了类型扫描兜底，不是简单换字符串）
3. `grep -c '"y"' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → `>= 1`
4. `./gradlew :app:lintDebug` 通过（exit 0）
5. `./gradlew :app:assembleDebug` 编译通过（exit 0）
6. `grep -c 'versionName "1.6.2"' app/build.gradle` → `1`

### 说明：本轮无法做真机烟测

这台设备重建后 LSPosed(Vector-SR) 本身在 Android 16 QPR2 上连不上 zygisk
daemon（另案，见 Codex skill `andromeld-oneplus-15-profile` 的
`references/oneplus-app-projects.md`），跟这个 bug 无关，但意味着这轮验收做
不了真机烟测，Verifier 只能做机器检查（lint/build/grep）+ 代码审查，实机确
认徽章真的出现留到 LSPosed 环境修好之后手动补测。
