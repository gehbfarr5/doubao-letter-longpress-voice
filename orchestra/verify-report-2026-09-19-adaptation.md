# 2026-09-19 新版豆包长按语音适配

当前状态（2026-09-22）：PASS，豆包 1.4.5 的 26 键长按启动语音已修复并实机验证，模块 v1.6.8 已安装。下文 9 月 19 日的 BLOCKED 为历史过程；最终验证范围见文末。

## 目标与当前证据

- 用户要求修复一加手机当前安装的新版豆包输入法：键盘字母区长按无法呼出语音。
- 本地与 GitHub main 均为 `59b8e7e5af671aef9f4d1e2ea47e1d65164075bf`，工作开始时干净；模块 v1.6.7 / versionCode 15。最近已确认适配的是豆包 1.4.4；本次手机版本、安装模块版本、作用域均未知。
- 2026-09-19 17:53 CST，ADB devices 与 mDNS 发现为空；Appium 无活跃会话；AndroMeld 控制 socket 连接被拒绝。未启动模拟器、未重启共享 ADB、未修改手机设置。
- 主机 mobile-automation-infra doctor 通过。
- 主机 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过，9 tests / 0 failures / 0 errors。日志：`/tmp/doubao-adapt-20260919-baseline.log`；报告：`app/build/reports/lint-results-debug.html`。这是原始代码基线，不是新版适配或实机 PASS。
- `DoubaoLetterLongPressHook.install()` 在 adapter 返回 UNSUPPORTED 时禁用手势接管。现有 adapter 依赖已知混淆签名；新版改名是待检验假设，不能作为已证明根因。

## 方案与研究

1. 首选：复用现有 nativeTouch / GestureSession 架构，以当前 APK 语义及调用点证据更新精确适配。范围小，保留现有布局和手势行为，需要新版本映射与实机验收。
2. 备选：动态扫描识别语义入口。适应混淆潜力更大，但引入依赖和误匹配风险，需要多个版本样本，本轮暂不选择。

GitHub 通过 gh 核对仓库、全部 issues/PR 列表、main 和 releases；没有发现比当前 main 更新的适配修复。项目为 Java、Apache-2.0、2 stars / 0 forks，最后推送 2026-09-10。主分支 v1.6.7 与最新 Release v1.6.1 不同，手机装机版本必须实查。

证据：https://github.com/gehbfarr5/doubao-letter-longpress-voice/commit/59b8e7e5af671aef9f4d1e2ea47e1d65164075bf

决策：reuse 现有架构与测试；adapt 当前 APK 的已证实签名和必要关联入口；avoid 猜方法名、同名即同义、以历史验收代替当前验收。问题集中于一个仓库且缺少当前 APK，未使用子代理。研究结论仅支持适配方向，当前根因证据不足。

## 恢复执行

用户已被请求连接 USB，或提供无线调试当前 IP 与连接端口。

1. 重新发现设备，使用明确 transport 与 OnePlus preflight 核对 PLK110 / 3B166Q00SX000000。
2. 读取豆包和模块版本、作用域、注入日志；拉取当前豆包 APK 和原模块 APK，记录 SHA-256，保留回退产物。
3. 反编译核对长按触发、ASR active/commit/cancel/dispatch 及相关反射入口；基于证据实施最小修复，补充有意义的兼容映射测试。
4. 通过主机编译、lint、单测和映射/打包/签名检查；记录最终候选 APK 绝对路径与 SHA-256。
5. 按当前 MOBILE_ROUTING 执行实机验收，安装精确候选、核对 Vector 作用域并重新注入；Appium 在不会向他人发送消息的测试输入框验证长按、松手、撤回及普通按键回归，保留截图和日志。回退只恢复本任务模块原产物，不清数据、不调整无关 root 配置。

结果行：BLOCKED；修复轮数 0；用户澄清请求 1（设备连接）；观测总耗时 unknown；模型 GPT-6 / effort unknown；可用额度 unknown。

## 用户确认无线调试开启后的连接复查

- 用户要求直接无线连接。尝试历史地址 `192.168.31.42:5555`，结果 Operation timed out。
- mDNS 随后短暂发现 `adb-3B166Q00SX000000-PQxgyM` 的连接服务：`192.168.31.42:43111` 与 `192.168.31.42:33541`。广播序列号匹配；尚未建立 shell，不能视为完整设备身份核验。
- ARP 缓存 MAC 与设备记录匹配，但两个广播端口的 TCP 探测均超时；33541 后续 ADB 连接返回 No route to host。随后 mDNS 列表再次为空，ping 两次均无响应。主机路由走 en0。
- 已终止本任务仍在等待的连接客户端，未重启共享 ADB server。现有证据只能证明网络连接不可达，不能证明具体原因。
- 下一步：用户保持手机亮屏并重新开启无线调试，确认与 Mac 网络互通后，再发现当前端口。修复与实机验收仍 BLOCKED。

### 用户提供端口 43111 后

用户提供 `192.168.31.42` 和 `172.18.0.1:43111`。分别做了有界 ADB 连接：`192.168.31.42:43111` 返回 No route to host；`172.18.0.1:43111` 在 10 秒超时。ADB devices 与 mDNS 均为空。Mac 到前者走 en0 直连，到后者走 en0 / 网关 192.168.31.1。没有修改网络或手机配置，仍未取得当前 APK；下一步需要可达的手机 WLAN 地址或 USB 连接。

## 2026-09-22 完成适配与装机

### 身份、版本与产物

- PLK110 / 3B166Q00SX000000，Android 16 / API 36，PLK110_16.0.9.400(CN01)，1272×2772 / 560 dpi，root/Vector 健康。
- 豆包 1.4.5 / 100405008；原模块 1.6.7 / 15；最终模块 1.6.8 / 16。
- 原模块备份：`/Users/jin/Documents/AI-Agent-Workspace/mobile/doubao-adaptation-evidence/2026-09-22/module-original-1.6.7.apk`，SHA-256 `9ef7dab71635890b0a65a19ac8062d5b0c1f635c0e0009dec141876a8a1abb66`。
- 最终 APK：`/Users/jin/Documents/AI-Agent-Workspace/mobile/doubao-adaptation-evidence/2026-09-22/doubao-longpress-voice-v1.6.8.apk`，SHA-256 `aa7ea1f4413ce87fbb4556ceda4eca2b9a1d58e4dc7aedaff6ac94bedbd04a01`。安装后设备 base.apk 的 SHA-256 完全一致，版本和 Vector 作用域已复核。
- 签名校验通过，证书与原模块一致。apksigner 对 Gradle META-INF 元数据的警告与原 APK 相同；不是签名不匹配。

### 根因和修复

注入日志明确显示原模块 `family=UNSUPPORTED`，随后 `gesture takeover disabled`。当前 APK 的 ASR 方法及关联入口重新混淆。新增 V1_4_5 精确签名族 U/G/T0/F(int,long,boolean)，补齐 InputView.e0、AsrContext.V、AsrManager.e 和 speech.L.y(speech.Y.a) 的映射。按真实方法体与调用点确定语义，没有按旧方法名字母推测。

保留 nativeTouch、手势状态机、旧 family 路径。新增 5 个 JVM 用例覆盖新 active 状态、commit/cancel 分离、dispatch 参数、拒绝不完整/错误类型签名，以及 1.4.4 回归。编译、lint、14 项单测全部通过。

### 实机测试结果（Appium 实际执行）

时间：2026-09-22 13:34–13:37 CST。安全输入场景为系统设置搜索框，没有发送联系人消息。

| 场景 | 结果与证据 |
|---|---|
| 26 键字母长按 | PASS：约 500 ms 接管；第 0 次探测即 ASR active；屏幕显示正在倾听和麦克风标记 |
| 原地松手 | PASS：InputView.e0(false)、ASR graceful commit、terminal=COMMIT；随后读取到已识别文字上屏 |
| 向上滑出键盘 | PASS（手势生命周期）：LETTER→TOOLBAR→OUTSIDE，noWait=true，terminal=CANCEL。未单独覆盖长句部分上屏后的完整文字撤销 |
| 滑工具栏松手 | PASS（分发链）：ordinal=3，ASR dispatch，terminal=TOOLBAR_ACTION；此为设置搜索，未验收聊天发送 |
| 普通字母点按 | PASS：点按 E，输入框内容为 e，没有长按语音接管 |
| 9 宫格、其它方向/布局、聊天跨应用发送 | 未在本轮复测，不能将上述结果扩展为全矩阵 PASS |

证据目录：`/Users/jin/Documents/AI-Agent-Workspace/mobile/doubao-adaptation-evidence/2026-09-22/`。包含 build-v1.6.8.log、candidate-lsposed.txt、26key-recording.png、26key-released.png、26key-cancelled.png、normal-key.png、toolbar-search.png。原始 UI/日志仅本地保存，不提交带识别内容的截图到公共仓库。

### 测试环境与清理

Appium 首轮读取超时，进程处于 do_freezer_trap。确认其 UID 10464 的父 cgroup.freeze=1；只解冻该测试 UID 后读取恢复，随后又被外部机制冻结。为完成有界验收，仅对该 UID 临时保持解冻（360 次、每次间隔 0.5 秒），没有改豆包或全局冻结策略。循环已结束，测试服务已停止，UID 冻结值恢复为原始 1。该测试环境处理不属于模块代码修复，冻结的具体管理者未定位。

期间无线端口由 41967 变为 37255，uptime 证明手机未重启。核验身份后改用同设备明确 mDNS serial。旧端口的 Appium 会话删除报离线错误，之后已停旧 instrumentation；最终活动测试会话删除成功，无测试服务残留进程。镜像仅辅助解锁，不计入 Appium 验收结果。

测试前默认输入法为 Typeless，验收期间切换豆包，结束已恢复 Typeless。测试输入框已清空。最终候选安装后未再改代码或重建 APK，仅补充文档。

最终结论：用户报告的长按无法呼出语音问题已修复并验证；其余验证边界如上。修复轮数 1；实际模型/effort unknown；额度 unknown。
