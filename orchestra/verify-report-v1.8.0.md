# v1.8.0 跨应用发送验收（截至2026-09-25）

当前状态：rc8本轮约定实机验收 PASS，保持安装；最终哈希及七入口结果见文末。以下早期章节保留失败与迭代历史，不代表当前状态。Claude Code独立入口仍受账号权限限制，未计入普通Claude对话的通过范围。未发布Release。

## 实现

- 语义规则版本 2：新增 sendFinal、contextAllBack、context 单例。真实 1.4.5 / 1.4.6 的最终出口分别为 y0 / z0，按字符串、签名、调用关系解析，不硬编码名字，旧缓存自动失效。
- 无障碍路线复用宿主完整 dispatch。截获本次手势 timestamp 的最终动作，并核验完成状态、同 EditorInfo/InputConnection、完整且非空的最终文本。超时、空文本和能力异常不发送；旧请求标记留在进程内，迟到/重复回调不能逃回原生发送。
- PREPARE / READY 绑定同包、同窗口、同焦点编辑器。最终文本与无障碍编辑器一致，且最近共同祖先下只有一个可用发送按钮时点击一次。界面事件驱动，无固定等待；返回 true 仅代表动作接受，输入框清空也不等于服务端送达；未知结果不重发。
- 精确发送标签替代全屏模糊包含 send；不在错误包继续尝试。查找最多向上 16 层、扫描 600 节点，超出明确中止。
- Telegram 加入 Nekogram 的 IME_ACTION_SEND 路线。Gemini 实际属于 Google App，仅识别 assistant_robin_input_* 编辑器；Google 普通搜索不接管。ChatGPT Remote 已定位为 ChatGPT 包内页面。
- 前台优先广播避开额外排队。仅有 pending 请求时处理无障碍事件；notificationTimeout=0。
- 新无障碍协议要求 Android 14+ 发送方身份共享与验证。Android 8–13 保留长按语音上屏，发送请求明确提示并保留文本；整个 APK minSdk 仍为26。这是候选版兼容范围变化，尚未发布。

## 主机门禁与产物

20 JVM tests PASS、0 skipped；包括真实 APK、原创改名/歧义/缺失 DEX、反射缓存、最终文本/按钮就绪、重复请求、窗口切换、超时、候选歧义和 Google 搜索隔离。lint 0 errors / 18 warnings、assembleDebug、git diff --check PASS。

Warnings 为依赖版本、ARM64 范围、旧 SDK 分支、资源反射、IME overlay 引用/触摸组件，以及短期 pending 编辑器引用。Google 编辑器资源反射失败会记录并不接管；pending 在取消或30秒截止释放。未添加 baseline 掩盖错误。

产物目录：`/Users/jin/Documents/AI-Agent-Workspace/mobile/doubao-adaptation-evidence/2026-09-24/`。

| 候选 | APK | SHA256 |
| --- | --- | --- |
| rc1 | doubao-v1.8.0-rc1.apk | 602d68be0f501bf5f6ad43188f15f538c9e01579fc645ec6566eff3dcc9f2eca |
| rc2 | doubao-v1.8.0-rc2.apk | 0ac1f2de740dd3719134a0048c0b0da59688bf78e77061d9500b0f62aba7cfd6 |

rc2 只在 rc1 基础增加前台优先广播；设备 base.apk 哈希已读回一致。主机日志 v180-host-build-rc2.log、v180-test-results；个人页面 XML 和过滤日志只保存本地证据目录。

## 实机事实

PLK110 / 3B166Q00SX000000，Android16/API36；豆包1.4.6。显式 transport `adb-3B166Q00SX000000-PQxgyM._adb-tls-connect._tcp`。本轮开始默认输入法实际为豆包，不能沿用上一轮 Typeless 的历史默认。

助手初始未启用；只追加本服务后 Bound，其他服务保留。Appium 默认会抑制其他无障碍服务，核对已安装 driver README 后使用 `disableSuppressAccessibilityService=true` 重建会话，确认测试期间助手 Bound。

| 项目 | 状态与证据 |
| --- | --- |
| rc1 Gemini 原生请求与助手 | PASS：18:51:20.235 请求→20.745 PREPARED；21.762 因完成标志不足中止→22.263 收到取消，无点击 |
| rc2 Gemini 控制广播 | PASS：18:57:26.438→26.451（13ms）、18:57:43.078→43.084（6ms）；无有效结果时中止 |
| rc2 ChatGPT Remote 控制广播 | PASS：18:58:15.246→15.255（9ms）；无有效结果时中止 |
| 延迟改善 | 部分 PASS：普通广播1样本510ms，前台广播3样本6/9/13ms；仅控制广播到达耗时，不是完整语音发送耗时 |
| 外部伪造广播 | PASS，rc1：18:55:24 shell READY 被 unverified sender 拒绝，无点击 |
| Remote 控件取证 | PASS：Compose EditText、“发送消息”；最近共同祖先2层、35节点、唯一匹配。草稿已清空，未启动远程任务 |
| Gemini 控件取证 | PASS：assistant_robin_input_collapsed_text_half_sheet、“发送”；共同祖先4层、27节点、唯一匹配。只做草稿取证，未提交对话 |
| Claude 官方 Code | BLOCKED：1.260921.19 显示 Upgrade for Claude Code，未进入编辑器；没有操作订阅/支付 |
| Muse | BLOCKED：仍需确认实际应用或页面入口，不将它等同官方 Claude |
| 普通 ChatGPT / Claude、Telegram / Nekogram 发送 | 未验收：Remote、上游源码及用户旧版测试不代替新包验收 |
| 短长语音→整理→发送 | BLOCKED：电脑 TTS 未被手机采集；临时手机音频进程被系统 Killed，未绕过。没有受控有效语音，不能宣称完整发送成功、准确率或整体耗时通过 |

## 收尾与继续

本任务 Appium 会话已删除；Remote 草稿读回为空。Claude 的 Return to chat 实际进入聊天搜索，测试短句误填搜索框后使用 Clear 清理；下轮仍需再次读回，不能把该树当作 Claude composer 证据。设备音频测试 dex/wav 和安装暂存 APK 已删除。

手机已恢复已验收 v1.7.0，SHA256 `8ad1c08c3275bfd5a845f8d8cacc59bfcd91a05fd24d3a2366450399a6209663`，模块 enabled；默认输入法保持本轮原值豆包。无障碍服务保持启用以修复缺失，其他服务不变。临时 UiAutomator2 解冻循环上限180秒；收尾复查前 mDNS transport 断开，ADB 进程 exit255。尚未核实远端循环退出和恢复 freeze=1，不能宣称完整收尾 PASS；重连后先查该循环，再恢复并读回。详见证据目录 v180-cleanup.txt。

继续：安装 rc2 确切哈希，核验 scope/Bound/当前 IME；使用上述 Appium capability。用户配合真实语音后先验证一个 Gemini 或 ChatGPT 新对话的最终文本、单次点击、提交，再测长句、窗口切换、其他目标页面并记录分段耗时。Muse 需入口、Claude Code 需能进入编辑器的账户状态。任务尚未完成，不发布 Release。


## 21:47–21:58 继续验收

状态：补充控件与清理核验 PASS；真实语音完整发送仍 BLOCKED，没有新增端到端成功结论。源码和 rc2 哈希未改变，不重复主机测试。

- 旧 TLS 45937 拒绝连接；发现过的 192.168.31.42:5555 连接成功，实测身份 PLK110 / 3B166Q00SX000000。未重启共享 ADB。
- 先核实上次回滚包 SHA256 与 v1.7.0 完全一致，助手 Bound、默认输入法豆包；旧临时循环已退出，freeze 从0恢复1，补齐上轮 INTERRUPTED 清理。
- 同一 rc2 再安装，手机 base.apk SHA256 为 0ac1f2de740dd3719134a0048c0b0da59688bf78e77061d9500b0f62aba7cfd6。Vector 启用、助手 Bound，Appium 使用保留其他无障碍服务的能力参数。已按 profile 解锁。
- Claude 搜索框测试文字已读回为空。打开 New chat 后确认普通 composer，非搜索。测试草稿对应 Send 标签，最近共同祖先2层、36节点、唯一匹配；清理后 get_text 为空。Claude Code 账户限制未解除，不以普通 composer 替代 Code 验收。
- ChatGPT 普通聊天 composer 实测发送消息标签，最近共同祖先2层、41节点、唯一匹配；清理后 get_text 为空。未发送消息、未启动 Remote 任务。
- Telegram 12.10.3 的自存入口为聊天页更多选项→我的收藏；空输入框 inputType=0x24001、imeOptions=0x50000006、fieldId=-1。只确认输入现场，未发送；仍需验证模块强制 SEND 路径。搜索测试已清空，未进入联系人会话。
- 手机再次恢复 v1.7.0，读回 SHA256 8ad1c08c3275bfd5a845f8d8cacc59bfcd91a05fd24d3a2366450399a6209663。助手 Bound、默认豆包；任务 Appium 会话已删除，600次有界解冻循环退出0，freeze=1读回成功；安装暂存文件删除。
- 仍缺实际可采集语音及 Muse 明确入口。已询问用户，未收到回答。下次应先获得可测试语音条件，再安装候选测试，避免重复无声录音与安装循环。

新增证据：v180-resume-claude-composer.xml、v180-resume-chatgpt-composer.xml、v180-resume-telegram-saved.xml、v180-resume-preflight.txt、v180-resume-final-a11y.txt。截图另保存 v180-resume-claude-composer.png / v180-resume-chatgpt-composer.png。个人界面证据仅本地保存。


## 2026-09-25 Muse / Claude 明确范围与 rc3

用户明确两个目标包为 com.facebook.aura 和 com.anthropic.claude。Muse 身份 BLOCKED 已解除：手机 com.facebook.aura 的 APK application-label 为 Muse（8.0.0.21.168 / 1061301140），实际页面同名，不能按传统包名误认 Messenger。Claude 为1.260921.19。豆包仍1.4.6 /100406010。

### 方案与实现

对比 A 泛化“发消息”标签并接管整个包，B 复用事务发送但限制 Muse 专用输入框/按钮。选择 B：发送助手要求 hatch-message-input，按钮要求 hatch-send-button 且标签为发消息或已有精确发送标签。无专用控件的旧 Messenger/搜索等页面不会进入助手发送。Hook 层 EditorInfo.fieldId=-1 无法确认虚拟 WebView id，最终校验由无障碍树完成。Claude 继续原有精确 Send 路线。无新增权限、依赖、外部通信或坐标发送逻辑。

reuse：已有 PREPARE/READY/一次点击流程与官方无障碍节点接口。adapt：本机 Muse 的真实虚拟资源标识与中文标签；XML 增补事件包。avoid：按包名猜应用、泛化所有“发消息”操作、以空语音中止代替完整发送成功。
GitHub skill 的精准公开代码搜索 `"com.facebook.aura" "send" "AccessibilityNodeInfo"` 无结果；已知本机包名并非传统 Messenger，公开 Messenger 例子不足以支撑 Muse，采用本地实机证据。不使用子代理，因为变更窄且共享一台设备。

### 主机与产物

rc3：/Users/jin/Documents/AI-Agent-Workspace/mobile/doubao-adaptation-evidence/2026-09-25/doubao-v1.8.0-rc3.apk
SHA256 e1b1c21b73a00c20ba2fed4054f471412f884a1a6e8682f3c2b4185abb497add，手机安装后 base.apk 一致。
21 tests PASS，0 skipped，0 failures/errors；新增 Muse 专用 editor/control 正反例及 Claude/ChatGPT 标签回归。lint 0 errors/18 warnings（与 rc2 同类），assembleDebug PASS，git diff --check PASS。
通用 Material 审计产生3 ERROR（主题、edge-to-edge、WindowInsets）和2 WARNING（IME resize、instrumented tests）。适用性核查：Manifest 无 Activity，项目是纯 hook/service，未增加屏幕；按 Android skill 的 pure module without user-facing app UI 明确排除 Material UI 要求，3项不适用，不算审计 PASS，不添加无用 Activity/主题来消除误报。主机与实机门禁照常执行。

### 实机证据

PLK110 /3B166Q00SX000000；显式 mDNS transport，preflight与health通过。安装相同rc3，Vector enabled，助手Bound，Appium保留其他服务。
- Muse 文本草稿：hatch-message-input focused，输入法inputType=0x2c001、imeOptions=0x42000000、fieldId=-1；hatch-send-button 描述发消息，可用可点击。草稿清理后读回空。
- Muse 真正长按→滑入发送区（空语音）：01:00:53.435创建id1790269253435，53.449助手PREPARED（14ms），53.451宿主完成不足取消，53.452助手结束。无click attempted，无消息发送；输入框读回空。
- Claude 同一rc3长按空语音：01:01:29.368创建id1790269289368，29.376助手PREPARED（8ms），29.381取消。无click attempted，输入框读回空。
以上 PASS 仅覆盖入口识别、请求传递和没有有效语音时拒绝发送，不证明有内容READY、最终文本匹配、单次点击或服务端送达。真实语音完整发送仍 BLOCKED：本轮询问语音配合未获回答。Claude Code账号限制历史项不替代普通Claude结果；本轮用户澄清的是包名，没有要求处理订阅。

### 收尾

两个任务Appium会话均删除，临时解冻循环退出0，freeze恢复1。回滚已验收v1.7.0，设备SHA256再次匹配8ad1c08c3275bfd5a845f8d8cacc59bfcd91a05fd24d3a2366450399a6209663；Vector enabled，默认输入法豆包，助手Bound。安装暂存APK删除。候选rc3保留本地，未发布。


## 2026-09-25 01:07 用户真实语音反馈核查

用户报告一分钟前真实语音完整发送成功，但未确认智能整理触发。只读核查当前模块1.7.0；Vector日志确认01:01:56 PID25391从回滚APK加载。01:05:40.112开始手势1、01:05:41.821 TOOLBAR_ACTION并ASR dispatch ordinal=3；01:05:46.611开始手势2、01:05:48.378同一路线发送。证明长按→滑入发送区松手进入原生dispatch；消息成功为用户报告，未读取聊天正文或服务端结果。
本次不能用于rc3验收：不是1.8候选，且不是新无障碍事务路线。最近30000条logcat没有这两次SmartOrganizeVoice开始/完成证据；日志缺失不证明未触发。当前结论为稳定版发送成功（用户观察）、智能整理状态未知、rc3端到端仍未验收。未更改设备或应用版本。


## 2026-09-25T01:10:02.769867 用户协同验收准备

用户明确要求安装并保留1.8候选版供真实测试。已安装rc3，手机base.apk SHA256=e1b1c21b73a00c20ba2fed4054f471412f884a1a6e8682f3c2b4185abb497add，versionName=1.8.0；Vector启用且scope仍仅豆包/模块，助手Bound，默认豆包。未创建Appium会话，不接管用户触摸。启动20分钟有界日志采集，exec session 96116，脚本与user-test-live.log位于../doubao-adaptation-evidence/2026-09-25/。同时读取宿主公开logcat与Vector模块追加日志；如果宿主不输出SmartOrganizeVoice，不能仅凭native ready推断智能整理触发。等待用户测试结果；此次不自动回滚。


## 2026-09-25 01:10–01:12 用户实测：FAIL

rc3真实语音端到端由用户报告两个应用均未发送，日志已定位共因。Claude主测试id1790269855317：01:10:55.317请求，55.349助手PREPARED，56.830宿主最终出口被模块以host reached send without complete result中止（1513ms）；随后三次短手势相同原因中止。Muse主测试id1790269925861：01:12:05.861请求，05.864助手PREPARED，07.372相同原因中止（1511ms）；随后短手势同样中止。六个请求均无native completed、native ready或click attempted。

确认：手势识别、目标包/输入框匹配与跨进程PREPARE通路正常；失败发生在Hook的contextAllBack检查，尚未进入最终文本提取和按钮点击，不能归因于Muse/Claude发送按钮失配。约1500ms等待与宿主等待超时行为相符，但现有日志不含宿主实际timeout值、智能整理开关/请求结果，不能断言根因是服务器慢或未开启整理。

智能整理状态：UNKNOWN，不等同“没有触发”。现有采集未观察SmartOrganizeVoice事件。源码G包含正常/无文本/超时出口，不能删除完成检查把原生最终出口一律当成功；T0在最终出口前会结束语音流程，其状态读取时机仍需有界诊断。原生长按发送j.invoke也是closePanel(false)→G(...,true)，所以未证实缺少U0；不凭猜测补停录调用。

证据rc3-user-test-failed.log。未改代码、未安装新包、未回滚；手机保持用户要求的1.8 rc3。下一步应增加不记录正文的宿主阶段诊断（整理是否允许/跳过/开始/结果、原生等待入口及完成回调前后），再据证据修复主路径。当前候选不得标记已验收或发布。


## 2026-09-25 01:16 起：自主扬声器语音验收与根因修复（进行中）

用户授权全程修改、安装与真实语音验收。MacBook Pro 扬声器56%音量的中文TTS现已可被手机采集；此前“无语音条件”的限制不再成立。设备为 PLK110 / 3B166Q00SX000000 / Android16，显式连接192.168.31.42:5555。测试文本只请求回复“收到”，不执行任务；Telegram系只使用本人收藏。

根因分两部分：原生录音结束/末帧提交未完成，直接dispatch会在宿主1500ms期限后仍contextAllBack=false；此外实测豆包“智能文字整理”开关原本关闭，现按用户需求开启并读回true。rc4补不记录正文的原生阶段诊断；rc5试验forceVad仍失败，已撤掉。原生空格长按松手对照证明commit路径会提交末帧，获得最终识别与整理结果。rc6改为native commit→立即dispatch，保持原生150ms收尾和结果完成检查，不删除保护，不用定时猜测文本已完成。Claude、Muse、普通ChatGPT均实际收到整理后的测试文字并回复。

Remote rc6仍FAIL：完成识别整理后READY，但标签到可点击按钮有4层非点击包装，旧循环只检查0至3层。保存失败UI树证实真正按钮位于第4层。rc7适配现有语义查找，允许最多8层包装，禁止把搜索范围的composer边界提升为按钮。只采用唯一语义发送动作，保留同包/窗口/输入框、最终文本完全一致和单击限制；不引入坐标发送或新依赖。与扩大整页盲搜相比，此方式复用原架构、修改范围小。局部证据已明确，按比例省略新的库选型；前期GitHub检索及原生APK研究见前文。

最终候选：../doubao-adaptation-evidence/2026-09-25/doubao-v1.8.0-rc7.apk，SHA256 `9cf8d69c5655f486fcb3f2e94a3b5cc59c0fc2738e9ffc7c18ffbe8e598a13b5`。01:46安装后设备base.apk哈希相同，助手Bound，语义解析缓存规则3。安装后停止IME引起系统临时切换Typeless，已显式恢复豆包并验证新进程进入原生语音。测试过程中不自动回滚。

主机门禁：22项JVM测试通过、0跳过，包含1.4.5/1.4.6真实APK语义映射和合成混淆夹具、commit先于dispatch顺序、事务取消和单击、隐私过滤。lint/build PASS；UI审计原始结果3 ERROR/2 WARNING，均为无Activity纯Hook/服务模块不适用的Material主题、edge-to-edge、insets/IME界面及instrumentation要求，不伪称原始审计无错误。宿主界面实机证据另列。


### rc7长语音边界与rc8调整

rc7各应用中等语音均成功，但34.35秒长语音01:55:32.707松手后，01:55:34.018才返回识别阶段，1500ms发送期限在整理尚未完成时触发。模块保留文本并取消发送，FAIL；不把前面的短中语音成功泛化为长语音通过。

选择“结束录音→原生完成事件→发送”，而非延长宿主固定1.5秒等待：沿用已有AsrContext完成回调，回调后在主线程确认contextAllBack、同一编辑器和InputConnection，再调用宿主dispatch。短句不加固定停顿，长句按实际完成时刻发送，30秒整体期限仅作取消保护。原生动作和无障碍路径统一使用该门禁；最终出口保留重复调用墓碑。Adapter在contextAllBack=false时明确抛错，不允许未来调用方重新进入未完成发送路径。

rc8产物 `/Users/jin/Documents/AI-Agent-Workspace/mobile/doubao-adaptation-evidence/2026-09-25/doubao-v1.8.0-rc8.apk`，SHA256 `d7b10e4d58fadc59d20599ebf41e8e118d8dc1479a00441edc86ea79552e3f3c`，实机base.apk哈希一致。主机23项测试0失败0跳过、lint/assemble通过。此包替换rc7，需重新实机验收，不沿用rc7通过表。


## 最终验收结论：rc8（2026-09-25 02:00–02:10）

**本轮目标 PASS**：一加15，豆包1.4.6，竖屏全宽26键，以MacBook Pro扬声器播放受控中文语音，Appium执行长按字母→滑入工具栏松手。最终安装包SHA256 `d7b10e4d58fadc59d20599ebf41e8e118d8dc1479a00441edc86ea79552e3f3c`。安装后到结束未重建/重签APK。

| 入口 / 时间 | 原生结果证据 | 发送结果 / 松手至触发动作 |
|---|---|---|
| ChatGPT普通对话 02:00:10，34.35秒长语音 | use organized result 159→162；phase2 done；all-back后dispatch | 单次点击，输入清空且收到回复；2.477秒 |
| Claude普通对话 02:01:10 | use organized result 39→41 | 单次点击，实际回复“收到”；1.360秒 |
| Muse（com.facebook.aura）02:02:02 | use organized result 39→41 | 单次点击，实际回复“收到”；1.175秒 |
| Gemini（Google App承载）02:02:56 | use organized result 39→41 | 单次点击，实际回复“收到”；约1.17秒 |
| Telegram本人收藏 02:03:49 | use organized result 39→41 | 原生SEND出口1.185秒；新增一条消息，已发送/已读，输入为空 |
| Nekogram本人收藏 02:04:33 | use organized result 39→41 | 原生SEND出口1.133秒；新增一条消息，已发送/已读，输入为空 |
| ChatGPT Remote新聊天 02:05:47 | use organized result 39→41 | 单次点击，远端实际回复“收到”；1.400秒 |
| Claude 3.59秒短语音 02:08:26 | keep second pass length=14，phase2完成，原生选择保留识别文本 | 0.485秒单次点击，收到回复；不标为采用整理改写 |

中等样本音频实际长度11.50秒（之前口头约15秒为估计）；请求正文仅要求回复收到，不创建提醒或执行项目动作。长样本原生整理增加标点/分段，不能从接口调用推出必然消除每个口误；语义改写质量归豆包原生策略。以上耗时是本次受控样本的松手至发送动作，不是服务器答复耗时或生产p95承诺。

防误发验收：
- 02:06:31 滑出键盘取消：terminal=CANCEL，迟到phase1回调后仍无发送请求，编辑器为空；PASS。
- 02:07:05 松手后立即从Claude切到Muse：请求1790273225293在PREPARED阶段以window changed结束，IME生命周期取消，无READY/点击；目标Muse输入保持空，原Claude保留未发送识别草稿。随后只清理本次测试文本；PASS。
- 所有rc8正向无障碍请求均恰好一次click attempted accepted=true，正常输入清空/焦点变化后的“aborted”记录是事务清理，不是消息发送失败。Telegram/Neko的native action completed日志位于原生动作前，单独不证明送达，已用实际收藏新增消息证实。

证据根目录：`/Users/jin/Documents/AI-Agent-Workspace/mobile/doubao-adaptation-evidence/2026-09-25/`。
- `rc8-native-trace.log`：原生整理、完成事件与dispatch的顺序，仅记录事件/长度/状态。
- `final-service-trace.log`：带版本迭代时间的PREPARE/READY/单次点击/取消；rc8进程26387。
- `rc8-*-pass.xml`、`rc8-gemini-pass.png`、`rc8-chatgpt-long-pass.png`：具体界面与实际回复/已读证据；图片按实机坐标保存。
- `rc8-host-build.log`、23项JUnit XML、`rc8-lint.xml`、`rc8-ui-audit.txt`：主机门禁。lint0错误18警告为已有依赖版本/ARM64限制/旧SDK分支/Hook资源反射及IME静态引用与触摸/备份配置，未添加baseline。Material审计纯Hook例外见上文。

收尾 PASS：Muse原71字符草稿、Claude原103字符草稿已恢复并逐字对比一致（Claude恢复到新对话草稿），原始备份和读回XML均保留。测试会话ccf46947-dcb4-44e5-bf30-93f07093a17f已删除；本任务有界解冻进程31660及日志采集64345已终止，复查无循环残留，uid10464 freeze=1。默认输入法仍豆包，发送助手Bound，GKD/Jev原启用配置保留；手机保持rc8，不自动回滚1.7。Mac音量未修改。测试消息保留作证据，未向第三方联系人发送。

限制：本轮验证上述已登录应用版本和竖屏26键；不是所有未来版本或所有网络条件的保证。Claude Code独立页面需升级订阅，BLOCKED未操作账户，不混同Claude普通对话PASS。9键和其他键盘布局的旧版手势记录不能替代rc8新增发送流程全布局验收。30秒结束后的总等待期限及异常路径仍会保留文本取消发送。未知未来IME结构变化保持能力解析失败可见，不自动猜方法。没有发布GitHub Release或提交远端。
