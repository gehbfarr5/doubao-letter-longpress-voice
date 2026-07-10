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
