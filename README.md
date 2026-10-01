# 豆包字母键长按语音 / Doubao Letter Long-Press Voice

> v1.8.0 rc8 已在一加15／豆包1.4.6完成本轮跨应用实机验收，手机保留该确切安装包；尚未发布 GitHub Release。验收范围、哈希和限制见 [v1.8.0 验收记录](orchestra/verify-report-v1.8.0.md)。


[![Build & Release](https://github.com/gehbfarr5/doubao-letter-longpress-voice/actions/workflows/build.yml/badge.svg)](https://github.com/gehbfarr5/doubao-letter-longpress-voice/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![GitHub release](https://img.shields.io/github/v/release/gehbfarr5/doubao-letter-longpress-voice)](../../releases/latest)

**把豆包输入法的任意字母键变成语音键。**

这是一个适用于 root 设备的 LSPosed 模块。长按字母键即可调用豆包输入法原有的语音输入；松手上屏、滑到工具栏执行发送或换行、滑出键盘撤回输入。

> An LSPosed module that starts Doubao IME voice input by long-pressing any letter key.

<p align="center">
  <img src="docs/media/demo.gif" alt="长按语音上屏、滑到工具栏发送、滑出键盘撤回" width="320">
</p>

> 蓝色圆点表示手指位置。演示依次展示：原地松手上屏、滑到工具栏发送、滑出键盘撤回。

## 主要功能

- 长按 26 键或 9 宫格中的字母键，约半秒后开始语音输入。
- 原地松手后，将识别结果上屏。
- 滑到工具栏再松手，执行当前输入框对应的发送、搜索、换行、完成等操作。
- 向上或向下滑出键盘再松手，撤回本次语音输入。
- 保留 Shift、退格、空格、数字层等按键原本的长按行为。
- 可选启用无障碍服务，在 ChatGPT（含 Remote）、Claude、Muse 和 Gemini 中自动点击发送按钮。

## 安装要求

- Android 8.0（API 26）或更高版本，与当前豆包输入法要求一致。
- ARM64 设备。
- 设备已 root，并安装可用的 LSPosed 环境。
- 已安装豆包输入法。

## 安装

1. 从 [Releases](../../releases) 下载已发布的 APK；需要测试 `main` 最新代码时，也可以从 [Actions](../../actions/workflows/build.yml) 的成功构建中下载 artifact。
2. 安装 APK，在 LSPosed 管理器中启用 **「豆包字母键长按语音」**。
3. 将模块作用域勾选为 **豆包输入法**（`com.bytedance.android.doubaoime`）。
4. 强制停止豆包输入法，或重启设备，让模块重新加载。
5. 打开任意输入框，切换到豆包 26 键或 9 宫格，长按字母键测试。

如果需要在 ChatGPT（含 Remote）、Claude、Muse 或 Gemini 中自动发送，再到系统的无障碍设置中启用 **「豆包语音发送助手」**。不启用它也不影响语音上屏、换行和撤回功能。

## 使用方法

| 操作 | 结果 |
|---|---|
| 长按字母键约 500 ms | 开始语音输入并振动提示 |
| 录音时原地松手 | 将识别结果上屏 |
| 录音时滑到工具栏再松手 | 执行发送、搜索、换行、完成等当前输入框动作 |
| 录音时向上或向下滑出键盘再松手 | 撤回本次输入，不提交识别结果 |
| 长按 Shift、退格、空格或数字 | 不触发模块，继续使用豆包原有行为 |

撤回手势请向键盘上方或下方滑出。常规全宽键盘会占满屏幕宽度，因此横向滑到屏幕边缘通常不会触发撤回。

## 兼容性

| 项目 | 当前支持情况 |
|---|---|
| 豆包输入法 | v1.7.0 同一套语义规则通过 1.4.5、1.4.6 APK 主机验证；1.4.6 在一加实测双拼 26 键/九键长按、松手、取消及工具栏搜索 |
| 键盘布局 | 26 键 QWERTY、9 宫格拼音 |
| 屏幕方向 | 竖屏全宽键盘 |
| 普通输入框 | 支持输入、发送、搜索、换行、完成等标准动作 |
| ChatGPT（普通 / Remote）、Claude普通对话、Muse、Gemini | 启用「豆包语音发送助手」，原生整理完成后单次发送；rc8实机通过 |
| Telegram / Nekogram | 整理完成后调用输入法原生发送动作；rc8本人收藏实机通过 |

暂不支持浮动键盘、单手模式、手写键盘和横屏键盘。电话、验证码、金额、日期等数字类输入框会自动跳过，避免影响正常输入。

v1.7.0 使用 [DexKit](https://github.com/LuckyPray/DexKit) 联合日志字符串、参数类型、字段类型和调用关系识别语音能力，不再按版本号硬编码混淆方法名。解析在后台执行，成功后才接管手势；结果按 APK 内容指纹和规则版本缓存，应用更新后自动重新解析。

自适配主要覆盖方法/字段改名和保持这些特征的小幅更新。若出现歧义、特征被删除或流程重写，模块记录 `resolver FAILED` 并保留原生输入，不能保证所有未来版本零维护。首次解析尚未完成时，长按仍执行豆包原生行为。

旧版本实机记录属于旧模块：v1.6.8 曾验证豆包 1.4.5，不能直接作为 v1.7.0 的验收。1.3.x、1.4.4 未经过新解析器完整 APK 回归，不列为当前已验证支持版本。完整验证状态见 [v1.7.0 验收记录](orchestra/verify-report-v1.7.0.md)。

## 常见问题

### 长按字母键没有反应

依次检查：

1. 模块是否已在 LSPosed 中启用。
2. 作用域是否包含豆包输入法。
3. 安装或更新模块后，是否强制停止过豆包输入法或重启设备。
4. 当前是否为受支持的竖屏 26 键 / 9 宫格，而不是数字层、浮动或单手键盘。
5. LSPosed 日志中是否出现 `semantic capabilities READY`；如果出现 `resolver FAILED`，提供版本信息及不含输入内容的错误摘要。

### 滑到工具栏后没有自动发送

普通应用需要输入框本身支持对应的动作。ChatGPT（含 Remote）、Claude、Muse 和 Gemini 使用自定义按钮，需要启用 **「豆包语音发送助手」**。需要智能整理时，也要打开豆包设置里的 **「智能文字整理」**；模块复用豆包功能，不自行改写内容。短句可能由豆包保留原识别文本。发送等待原生完成事件，切换应用或取消会终止发送，等待超时也不会误发。

### 必须授予无障碍权限吗？

不需要。无障碍权限只用于上述自定义发送按钮应用的自动点击发送。语音上屏、标准输入框动作和撤回功能不依赖该权限。

### 模块会单独上传语音数据吗？

不会。模块没有自己的语音识别服务，它调用的是豆包输入法原有语音能力。语音处理方式、联网行为和隐私规则与豆包输入法本身保持一致。

### 为什么 README 的功能比最新 Release 新？

README 描述的是 `main` 分支当前状态，正式 Release 可能稍晚。希望使用稳定发布版时请以 [Releases](../../releases) 为准；测试最新代码可下载 Actions 构建。

## 工作原理（简述）

模块通过 LSPosed 接收豆包键盘的长按和触摸事件，并在录音过程中判断手指位于字母区、工具栏还是键盘外。松手时，它调用豆包原有的语音结束、输入框动作或撤回流程，因此不会替换豆包的语音识别引擎。豆包输入法 v1.3.15 改用了 native 长按入口，本模块通过 ARM64 native bridge 完成兼容。

## 构建与技术资料

推荐使用 JDK 21 和 Android NDK `29.0.13846066`：

```bash
git clone https://github.com/gehbfarr5/doubao-letter-longpress-voice.git
cd doubao-letter-longpress-voice
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

深入资料：

- [豆包内部结构与适配记录](orchestra/DOUBAO-INTERNALS.md)
- [回归测试矩阵](orchestra/REGRESSION-MATRIX.md)
- [新版豆包适配流程](orchestra/ADAPT-PLAYBOOK.md)

## 贡献与许可

欢迎通过 Issue 或 Pull Request 提交新版豆包适配、键盘布局支持和使用体验改进。

本项目使用 [Apache License 2.0](LICENSE)。

## 免责声明

本模块仅供个人学习与研究使用，与豆包输入法及字节跳动没有关联。使用本模块可能违反豆包输入法的用户协议，请自行评估风险。

### 自适配规则主机回归

`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 执行普通门禁。完整 DEX 回归还需将 `DEXKIT_HOST_LIBRARY` 指向 DexKit 2.3.0 源码编译的主机 native 库，将 `DOUBAO_APK_FIXTURES` 设为合法持有的 1.4.5、1.4.6 APK 绝对路径（冒号分隔）。缺少环境变量时 DEX 测试会明确标记 skipped，不能当作完整回归通过。

用 `python3 scripts/build-semantic-fixtures.py /tmp/doubao-semantic-fixtures /path/to/build-tools/d8` 生成原创合成 DEX，并设置 `DOUBAO_SYNTHETIC_FIXTURES=/tmp/doubao-semantic-fixtures`。这些测试覆盖改名后仍能定位、相同签名干扰方法、重复特征和特征缺失。第三方 APK 不提交到仓库。
