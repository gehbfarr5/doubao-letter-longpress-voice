# v1.7.0 自适配验收

状态：主机门禁 PASS；豆包 1.4.6 核心长按功能实机验收 PASS（2026-09-24）。未来版本及未测场景不据此宣称兼容。

## 实现和主机证据

- 用户批准方案后引入 DexKit 2.3.0，普通 DexKitBridge；未使用实验性 CacheBridge。顶层 Apache-2.0，native Core 为 LGPL-3.0；已纠正设计记录的单一许可描述，随包保留许可证和固定源码链接。尚未公开发布。
- SemanticResolver 输出 12 个方法、4 个字段；不含版本号/混淆方法名表。稳定类名、枚举项名和 native JNI 接口仍是明确兼容边界。
- 后台扫描、整套反射验证、主线程安装完成后开放手势入口。缓存键包含包名、versionCode、规则版本、base/split APK 内容 SHA-256。缓存异常明确记录后重新解析；解析失败不进入接管。
- 完成回调超时会中止后续动作；删除了新增路径中按时间猜测完成的处理。诊断不记录输入正文。
- Android 最低版本从 23 调整为 26，与当前宿主最低版本一致；native ARM64 范围保持。
- 2026-09-24：15 个 JVM 测试 PASS、0 skipped，包括真实 1.4.5/1.4.6 APK 共用规则、原创 DEX 全能力方法改名、重复/缺失特征拒绝、反射缓存缺项/类型错误拒绝、APK 内容/版本/split 缓存失效。
- lintDebug PASS（0 errors，16 warnings）：Kotlin 版本沿用 DexKit 声明版本；ChromeOS/x86 不在 ARM64 范围；旧 SDK 判断可保留；宿主资源只能反射；静态 overlay 引用在 IME 生命周期复位；手势 overlay 不是普通点击按钮。未添加 baseline 或压制错误。
- assembleDebug PASS；DexKit ARM64 ELF LOAD 对齐 0x4000；debug 签名与既有模块一致（证书 SHA-256 2c85274da7a878af651b29c24bfe0667fc6310dbfd3b99ad069b48b3323455d0）。
- 主机完整输出与设备原始证据存放项目外 `../doubao-adaptation-evidence/2026-09-24/`；包含个人截图时不公开。

## 最终产物与设备

最终 APK：`/Users/jin/Documents/AI-Agent-Workspace/mobile/doubao-adaptation-evidence/2026-09-24/doubao-longpress-voice-v1.7.0-accepted.apk`

SHA-256：`8ad1c08c3275bfd5a845f8d8cacc59bfcd91a05fd24d3a2366450399a6209663`。已通过 root PackageManager 安装；设备 base.apk 哈希读回一致。v1.7.0 / versionCode 17。

PLK110 / 3B166Q00SX000000，Android 16/API 36，ColorOS PLK110_16.0.9.400(CN01)。豆包 1.4.6 / 100406010；显式连接 `192.168.31.42:5555`，未修改无线调试配置。

用户明确授权接管手机后，使用 Appium 会话 `39783365-cc11-49ed-a584-2767289d59f0` 验收。测试场景为系统设置搜索，不向联系人发送消息。验收前默认 IME 实际为 Typeless；测试结束恢复 Typeless。豆包布局恢复原自然码双拼 26 键。

## 最终哈希功能证据

安装更新后曾观察到旧的取消诊断仍出现，怀疑框架仍加载上一候选代码；通过 Vector CLI 停用/启用本模块并重启宿主后，在 PID 21094 重新执行以下测试。未把此前候选的手势记录直接计入本表。

| 场景 | 结果 | 实机证据（设备本地时间） |
| --- | --- | --- |
| 双拼 26 键长按启动 | PASS | 14:07:09 ASR active；accepted-26key-recording.png 显示正在倾听 |
| 双拼松手结束 | PASS | 14:07:10 graceful commit / terminal=COMMIT |
| 双拼上滑取消 | PASS | 14:01:56–57 ASR active / terminal=CANCEL |
| 普通字母点按 | PASS | 14:08 截图 accepted-26key-tap.png 显示 e 和候选词 |
| 九键长按与松手 | PASS | 14:03:03–05 ASR active / COMMIT，accepted-9key-recording.png |
| 九键上滑取消 | PASS | 14:03:22–23 terminal=CANCEL；无旧取消超时诊断 |
| 工具栏搜索动作 | PASS | 14:07:23 ordinal=3 / TOOLBAR_ACTION；键盘关闭 |
| 安装态与作用域 | PASS | 设备 APK 哈希一致；Vector enabled；豆包/user0 与模块自身/user0 |

原始记录：项目外证据目录 `accepted-runtime.log`、上述截图、`host-build-accepted.log`。截图含手机个人内容，保留本地，不公开。

冷解析 READY 在本轮较早候选 PID 11643 的 13:56:28 观察到；缓存命中在 PID 16773 的 13:58:54 为 392ms。最终刷新进程 PID 21094 的启动 READY 行未在框架日志中捕获，因此这两项仅作为相同解析代码的候选级补充证据，不列为最终哈希独立冷启动/缓存性能 PASS。最终进程已实际执行依赖完整能力绑定的手势流程。

较早候选曾识别环境语音并上屏，但最终刷新进程没有受控朗读文本断言；不宣称最终包语音识别准确率或有文本取消撤回已单独通过。未测试跨应用消息发送、所有布局、长时稳定性或未来豆包版本。1.4.5 仅有本版主机 APK 回归。

## 修正、边界与收尾

最终一轮删除了取消路径中无终态动作的旧诊断监听，避免无意义的超时日志和监听器替换；随后重新通过 15 项主机测试、lint 和构建，再安装上述最终哈希。

新解析器可应对已验证的混淆方法改名，并在宿主 APK 更新后自动重新解析；稳定类名、日志特征、枚举或调用结构大改仍可能需要更新规则。候选不唯一或能力不完整时明确报错并停止接管，不猜测方法。

Appium 会话已删除，默认输入法和原布局已恢复；临时解冻仅用于 UiAutomator2 验收，有界解冻循环已自然退出（exit 0），结束后恢复父 cgroup freeze=1 并读回确认。模块保持 enabled，无手机重启、无清数据。系统设置可能保留一条本次语音搜索历史，未清空用户原搜索历史。

回滚保留 v1.6.8 原签名包；它不兼容 1.4.6，故出现问题时优先通过 Vector 停用本模块、保留原生输入。不回退豆包。源码和产物保持本地，尚未发布 Release。
