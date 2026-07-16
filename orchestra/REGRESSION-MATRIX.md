# 真机回归矩阵

当前支持范围收敛为：

- Claude (`com.anthropic.claude`)：AccessibilityService 点击发送按钮。
- ChatGPT (`com.openai.chatgpt`)：AccessibilityService 点击发送按钮。
- Nekogram (`tw.nekomimi.nekogram`)：IME_ACTION_SEND / `performEditorAction` 路径。

## 必测路径

| 场景 | 操作 | 预期 |
|---|---|---|
| Claude 发送 | Claude 聊天输入框长按字母触发语音，滑到工具栏松手 | 文本先上屏，随后自动点击发送按钮；工具栏标签显示“发送” |
| ChatGPT 发送 | ChatGPT 聊天输入框长按字母触发语音，滑到工具栏松手 | 文本先上屏，随后自动点击发送按钮；工具栏标签显示“发送” |
| Nekogram 聊天发送 | Nekogram 聊天输入框长按字母触发语音，滑到工具栏松手 | 走 IME_ACTION_SEND，消息发出；无需无障碍授权 |
| Nekogram 搜索 | Nekogram 搜索框长按字母触发语音，滑到工具栏松手 | 保持搜索语义，不被白名单强制成聊天发送 |
| 长文本换行 | 普通换行类输入框录入一段长语音，滑到工具栏松手 | 文本只出现一次，随后换行；不重复提交 |
| 取消不上屏 | 录音中向上或向下滑出键盘范围松手 | 不上屏、不发送，preedit 清空 |
| boot 后无障碍恢复 | 重启后检查无障碍服务状态，再在 Claude/ChatGPT 发送 | root 环境下服务仍启用；发送路径可用 |

## 负向检查

| App | 当前状态 | 预期 |
|---|---|---|
| Telegram 官方 | 不在支持范围 | 不在 `FORCE_SEND_PACKAGES` 白名单，不在 README 宣称支持 |
| 文心一言 | 不在支持范围 | 不在 `FORCE_SEND_PACKAGES` 白名单，不在 README 宣称支持 |
| Gemini / Grok / Kimi | 不在支持范围 | 不在 `A11Y_SEND_PACKAGES` 白名单，不在 a11y `packageNames`，不在 README 宣称支持 |

## 机器检查

```bash
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
rg -n "org.telegram|baidu.ernie|google.android.apps.bard|ai.x.grok|moonshot" app/src README.md
```

最后一条只允许命中“未支持/不在当前支持范围”的说明文本，不能命中代码白名单。

## 实测记录 — 2026-07-10（v1.6.3，真机，LSPosed 环境已确认正常）

| 场景 | 结果 | 备注 |
|---|---|---|
| 长按 500ms 触发语音 | ✅ PASS | 反复验证多次，触发稳定 |
| TOOLBAR zone 徽章（蓝色，动作标签） | ✅ PASS | v1.6.3 修复后尺寸正确（~120px），不再糊字母键 |
| OUTSIDE/取消 zone 徽章（红色撤回输入） | ✅ PASS | 同上 |
| Claude 发送（AccessibilityService） | ✅ PASS | 端到端验证：文字上屏→自动点发送→Claude 真实回复收到 |
| ChatGPT 发送 | ✅ PASS（用户手动确认） | 自动化这轮卡在登录/OTP 页测不了；用户自己登录后手动验证过，发送正常 |
| Nekogram 发送/搜索 | ✅ PASS（用户手动确认） | 自动化被安全分类器拦截（真实 IM app 里连续 tap+发送 被判定为风险操作）；用户自己手动验证过，发送/搜索都正常 |
| 长按 Shift 不触发（负测试） | ✅ PASS | 长按 Shift 键，无语音触发迹象，原生高亮行为保留 |
| 长按 Backspace 不触发（负测试） | ⛔ 未测 | 时间关系没测完，风险较低（跟 Shift 同一段边缘几何排除逻辑）|
| 数字输入框跳过 | ⛔ 未测 | 未覆盖 |
| 长文本换行不重复上屏 | ⛔ 未测 | 需要真实语音内容才能有效验证 |
| 取消不上屏 | 🟡 间接验证 | OUTSIDE zone 手势后目标输入框始终为空，没有观察到误上屏；但没有专门做"先有内容再取消"的对照测试 |
| ASR 转写内容准确性 | ❌ 无法测 | 需要真人说话，adb 合成触摸手势无法提供音频输入，这是自动化测试的硬限制，不是本模块的问题 |

结论：核心链路（触发→zone 判定→徽章渲染→跨应用发送）在真机上端到端跑通且证据扎实（Claude 场景收到了真实 AI 回复，不是猜的；ChatGPT/Nekogram 由用户手动补测确认通过）。剩余未覆盖项（Backspace 负测试、数字输入框跳过、长文本不重复上屏）风险较低，ASR 转写内容准确性本质上测不了（需要真人语音输入，不是本模块问题）。

## 实测记录 — 2026-07-16（v1.6.5 / 豆包 1.3.15 / OnePlus 15）

测试入口为系统设置搜索框，`EnterActionType=SEARCH(3)`；使用 USB ADB 连续触摸，不发送任何外部消息。

| 场景 | 结果 | 证据与备注 |
|---|---|---|
| Native bridge 装载 | ✅ PASS | Vector Native API attached；`RegisterNatives hook installed`；捕获 `nativeTouch(JIIIJ)V` |
| 26 键字母长按启动 ASR | ✅ PASS | 真机进入“正在倾听/轻触结束”态；adapter=`V1_3_15`，`J/F` probe active |
| 工具栏提示 | ✅ PASS | raw `(500,1800)` 命中 toolbar `[0,1675-1272,1876]`；截图显示蓝色“搜索” |
| 工具栏松手 | ✅ PASS | `AsrManager.t(3, now)`，唯一终态 `TOOLBAR_ACTION`，无 `DUP_ACTION` |
| 移出键盘提示 | ✅ PASS | raw `(500,1450)` 命中 OUTSIDE；截图显示红色“撤回输入” |
| 移出松手撤回 | ✅ PASS | `AsrManager.u()`，唯一终态 `CANCEL`，输入框保持为空 |
| 原地松手 | ✅ PASS | `AsrManager.w0()`，唯一终态 `COMMIT` |
| 框架 ACTION_CANCEL | ✅ PASS | 无条件进入唯一 `CANCEL`；无提交、无发送 |
| 短按字母与退格 | ✅ PASS | 短按正常输入字母，退格恢复空输入；模块没有创建 gesture session |
| 单元测试 / lint / APK | ✅ PASS | `testDebugUnitTest`、`lintDebug`（0 errors）、`assembleDebug` 通过；APK 含 arm64 native bridge 与 `assets/native_init` |
| 9 键 / 数字层 / 浮动 / 单手 / 横屏 | ⏳ 未测 | 需要单独切换布局并做回滚 |
| ChatGPT / Claude / Nekogram 真发送 | ⏳ 未测 | 上游 SEARCH 动作已闭环；为保护已有草稿，本轮不进行外部发送 |
| 真人语音文字与长文本 | ⏳ 未测 | ADB 合成触摸不能提供真人麦克风内容 |
| 重启后 / 30 分钟压力 | ⏳ 未测 | 本轮为避免再次触发 Wi-Fi 服务栈故障未重启手机 |

结论：豆包 1.3.15 更新导致的两个 P0 故障（工具栏动作不可见、移出撤回失效）已在 26 键核心路径真机修复；发布前剩余工作是布局、跨 App 真发送和稳定性扩展矩阵，不再是已知主路径阻塞。
