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
