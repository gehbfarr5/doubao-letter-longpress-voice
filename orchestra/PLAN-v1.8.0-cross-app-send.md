# v1.8.0 跨应用发送与延迟优化

> 2026-09-25 当前结果：rc8本轮实机验收完成并保留安装，最终路径为commit→原生完成事件→dispatch→同编辑器单次发送。rc3/rc7失败及修复证据见 verify-report-v1.8.0.md 最后章节；早期固定发送等待方案已被替换。

2026-09-25 更新：Muse 已确认是 com.facebook.aura（实机 label=Muse），已加入 rc3 并通过入口/空语音中止验收；Claude 为 com.anthropic.claude。真实语音完整发送仍待验证。历史计划如下。

2026-09-24 状态：用户已批准实施；v1.8.0 rc2 完成主机门禁及部分实机检查。完整语音发送验收等待真实语音；Muse 身份仍待确认。ChatGPT Remote 已实机定位，Claude 官方 Code 页面受账户升级提示阻挡。当前手机恢复 v1.7.0 已验收包，无障碍助手已修复启用。详见 verify-report-v1.8.0.md。

## 用户结果与范围

保持智能整理后完整内容，只发送一次，减少整理之外的响应延迟。覆盖 Telegram、Nekogram 回归、ChatGPT 普通与用户提到的远程控制对话框、Claude Code muse、Gemini。Muse 应用身份和远程控制页面入口待用户澄清；不得将 Claude 官方 App 自动等同 Muse。

## 当前事实

- 手机经当前 mDNS transport 确认为 PLK110 / 3B166Q00SX000000。
- ChatGPT 1.2026.258；Claude 官方 1.260921.19；Gemini 1.0.971139365；Telegram 12.10.3。
- enabled/bound accessibility services 不含 DoubaoVoiceSendA11yService（原始只读快照 /tmp/doubao-a11y-state.txt）。这是当前无障碍发送不可用的直接前提，不能把它全部归因于 ChatGPT 控件变更。尚未修复授权或测量 UI。
- Hook 仅将 com.anthropic.claude、com.openai.chatgpt 走无障碍发送；Nekogram 单独使用原生 IME_ACTION_SEND。
- service XML 也仅列 Claude/ChatGPT。onAccessibilityEvent 为空，接到 allBack 广播后只查找/点击一次；没等待 disabled→enabled 的发送按钮。当前选点在目标包与活动窗口不一致时仍尝试，这是本次必须修正的错误路径。
- 当前 allBack 等待上限 2000ms 是超时退出，不是固定睡眠；不能宣称删掉它就会快 2 秒。长文本整理可能超过该上限，需明确完成状态，而非超时强发。
- 1.4.6 原生 G(int,long,boolean) 已含 SmartOrganizeVoice 等待策略；U0() 长按松手含 150ms 延迟。当前适配不应直接改短宿主的网络/整理限时。
- 1.4.6 智能整理已经有独立 smart_organize 流程，旧 1.3.11 的“只是 ASR 二段整理”结论不能直接沿用。

## 两个候选

A：缩短固定等待或文本稳定一段时间就发送。实现少，但文本暂时稳定不等于整理完成，会发送旧稿、重复或丢失尾句，排除。

B（建议）：保留豆包原生完整发送编排，语义定位最后发送出口；仅将本次用户手势的最终发送替换为无障碍。发送准备与整理并行，在完成后按界面事件重新校验输入框、窗口、包名、发送按钮，再执行一次动作。优点是完成边界与宿主一致，减少额外串行等待；成本是需新增语义能力、事务身份校验和真机分段计时。不能预先承诺整理服务器本身变快。

原生出口候选：AsrManager 内 (int,long)void、字符串 DoAsrSend sendFinish costTime、asr_real_do_send、调用 performEditorAction，当前 1.4.6 为 z0；必须通过 1.4.5/1.4.6 字节码和实际回调顺序验证，不直接硬编码名字。JADX 完整反编译 /tmp/doubao-AsrManager-full.java 仍有不一致标记，不能仅凭伪 Java 判定所有分支。尤其宿主超时降级出口必须与正常完成区分，不能把“调用出口”无条件等同“整理成功”。

## reuse / adapt / avoid

reuse：保留 DexKit 解析、完整能力验证、APK 缓存失效、Nekogram 原生发送和豆包智能整理；复用 Android AccessibilityEvent / fresh node action 官方接口，无新增大依赖。
adapt：按应用+页面输入框绑定的选择器、一次性发送事务、编辑器切换取消、服务健康确认、按钮就绪事件、分段延迟日志（仅状态和时间，不记正文）。
avoid：固定坐标、全窗口含 send 字符串就点击、错误包仍点击、以点击返回 true 代替已发送、未确认结果自动重发、直接硬编码混淆方法名或缩短智能整理上限。

Telegram 上游 ChatActivityEnterView 的 OnEditorActionListener 对 IME_ACTION_SEND 调 sendMessage，支持复用 Nekogram 路径；发送按钮另设本地化 Send 描述。源码证据 https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java （本次读取 /tmp/doubao-Telegram-ChatActivityEnterView.java:5848）。仅为上游源码证据，不能代替安装版对话框实测。
Android 事件契约：https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent 。内容/控件启用变化可驱动重新检查；节点必须重新获取并验证可见、启用、目标窗口与编辑器关联。
GitHub 搜索经 gh code 搜索未命中，随后读取 Telegram 官方源码。没有发现可直接安装覆盖所有目标应用的成熟现成方案；采用本项目适配。未用子代理，因为问题需首先定位本地调用链且共享手机。

## 实施顺序与验收

1. 澄清 Muse/远程控制入口；仅读取目标页面无障碍树，记录实际包名、窗口、输入框及发送控件状态，避免泛化启用全应用。
2. 完成原生整理/发送出口时序验证，新增语义规则及真实 APK/改名/缺失/歧义测试。超时中止并保留文本。
3. 实现事务化无障碍发送：预备、整理完成、按钮可用、点击一次、结果确认/未知。更换会话、编辑器或前台窗口时取消，发送结果未知不重试。
4. 通过主机构建、lint、逻辑/映射/打包测试。冻结唯一 APK 与 SHA256 后实机安装。保留其他无障碍服务；修复本服务时只追加本组件并验证 Bound。
5. 对每个确认的页面用独立测试对话验证短句/长句、整理改写、连续两次、按钮延迟、应用切换、服务停用；Telegram 使用自己的 Saved Messages，不向联系人发送。远程控制测试内容仅用于无操作的发送验证，不触发远端执行。具体发送内容在点击前可审查。
6. 比较释放→整理完成→按钮就绪→点击→可观察提交的耗时；分别记录成功率和重复次数。若整理耗时主导且无法改善，诚实报告，不用提前发送制造提速数字。
7. 回滚到已验收 v1.7.0 APK，恢复原输入法/键盘布局，删除任务自己的 Appium 会话；不清数据、不批量修改服务、不发布未验收版本。
