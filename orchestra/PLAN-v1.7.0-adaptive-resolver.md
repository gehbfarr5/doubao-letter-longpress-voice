# 豆包更新后的自适配方案（2026-09-23）

状态：用户已批准方案；v1.7.0 已实现并通过主机门禁，实机验收等待手机可接管窗口。见 verify-report-v1.7.0.md。

## 当前事实与根因

目标：字母区长按开始语音，松手结束并保留识别文本，取消可撤回；普通点击、工具栏及原生输入不受影响。希望应用更新后的混淆改名无需重新发模块。

只读核实 OnePlus PLK110 / 3B166Q00SX000000，Android 16/API 36。当前豆包 1.4.6 / 100406010。从当前安装提取 APK，SHA-256：`fb6483b01151bfb07c6ef1f02127bd597eebf39742a88e2ce123ff3a65bb5416`。

证据位于项目外 `../doubao-adaptation-evidence/2026-09-23/`：原 APK、AsrManager.java、AsrLongPressView.java、jadx 日志。不得将第三方 APK 或个人 UI/日志作为公开仓库附件。

| 能力 | 1.4.5 | 1.4.6 | 1.4.6 证据 |
|---|---|---|---|
| 正在语音 | U() | V() | isAsrSpeechingStatus 字符串及 KTryStart/KStart 判断 |
| 撤回 | G() | H() | doUndo mHavePreEdit 字符串 |
| 松手结束 | T0() | U0() | LongPressStop，延迟 150ms；原生长按视图调用 |
| 分发 | F(int,long,boolean) | G(int,long,boolean) | DoAsrSend 日志及原生发送流程 |
| 关闭面板 | InputView.e0(boolean) | InputView.f0(boolean) | 原生 AsrLongPressView.d() |

旧 U() 在 1.4.6 仍存在但表示 KErrorShowState；同名同签名不能证明语义。当前版本族探测与其他硬编码反射依赖会随混淆变动失效。以上是静态根因证据，本轮没有重新执行 UI 复现，也没有宣称实机修复 PASS。

## 候选与选择

1. **推荐：DexKit 语义解析器 + 能力契约。** 组合字符串、签名、字段类型及调用关系定位；方法名仅作结果，不作识别条件。保留现有手势状态机和 nativeTouch 入口。成本是增加 native/Kotlin 依赖、扫描与缓存管理；能覆盖混淆重命名和保留语义结构的小更新。
2. **转发原生语音视图触摸事件。** 复用 onTouchEvent 等框架入口可减少直接 ASR 调用，但仍依赖视图实例、可见状态、坐标和触摸所有权；取消、收尾与工具栏仍需适配。当前没有跨版本完整验证证据，不作为主方案。

继续追加 V1_4_6 映射只适合应急，不能满足本次目标。没有证据表明豆包提供公开稳定的长按语音 API。

## 复用证据与依赖边界

GitHub CLI 搜索 repos（dexkit）、issues（obfuscation）、code（usingStrings），并读取官方示例、缓存说明、release 和 LICENSE。问题集中于一个解析器和本地 ASR 流程，未分派子代理。

- [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit)：1040 Stars / 116 forks，主语言 Kotlin，最近推送 2026-09-22；提供 C++ DEX 分析与 Kotlin/Java 查询接口。按匹配度选它，不以 Stars 替代验证。
- [官方组合查询示例](https://github.com/LuckyPray/DexKit/blob/master/doc-source/src/en/guide/example.md)：支持 usingStrings、paramTypes、returnType、invokeMethods，与本地已保留特征吻合。
- [2.3.0 发布](https://github.com/LuckyPray/DexKit/releases/tag/2.3.0)：2026-09-22 发布，候选锁定版本；正式引入前验证 Maven 产物、arm64/API 36/native 装载及 ELF 页面兼容性，不仅依据“最新”选版本。
- [2.3.0 LICENSE](https://github.com/LuckyPray/DexKit/blob/2.3.0/LICENSE)：顶层 Apache-2.0；2026-09-24 深查发现 Core/LICENSE 另为 LGPL-3.0，不能简单视作 README 徽章陈旧。模块保留两份许可证、固定版本源码链接；公开分发前需一并提供适用源码/构建材料。
- [缓存文档](https://github.com/LuckyPray/DexKit/blob/master/doc-source/src/en/guide/cache-bridge.md)：CacheBridge 明确为 experimental；本项目查询一次即可，优先复用普通 DexKitBridge 和描述符序列化，避免依赖实验性缓存生命周期。

**reuse**：成熟 DEX 查询、现有 nativeTouch/手势状态机、主机测试与设备验收流程。**adapt**：豆包独有语义约束、Java 能力接口、APK 指纹、解析报告。**avoid**：版本号猜方法名、任取第一个匹配、运行时试调用候选、远程下载执行代码、无声兜底。没有现成豆包自适配补丁证据，规则部分属于本地设计。

## MVP 架构及实施顺序

1. 引入 SemanticResolver，输出带证据的 CapabilitySet；移除运行路径按版本族选混淆名称。需要的能力包括 start/active/commit/cancel/dispatch、管理器实例、识别完成回调、面板关闭及发送类型。完整盘点 Hook 中每一个反射点，避免只改 AsrManager。
2. 多条件匹配：例如 active 必须 boolean/零参数、具有状态日志和相关枚举读取；commit 必须 void/零参数、LongPressStop 且由原生松手路径到达。LongPressStop 也用于埋点初始化，不能单靠字符串。候选必须唯一；相关能力归属及调用关系须一致。
3. 一次后台解析，触摸回调只用已解析对象；解决初始化竞态，原子发布完整能力集。解析未完成或失败时不接管手势，记录明确失败原因，保留豆包原生输入。不得把失败当成功或静默选择旧映射。
4. 持久化方法/字段描述符与规则版本；以包名、versionCode、base/split 内容指纹标识宿主。更新/降级使缓存分离，缓存命中仍校验反射类型与完整性。扫描和哈希不阻塞键盘主线程；耗时用实测确定。
5. 诊断只保存版本、指纹、候选数量、规则和耗时，不保存输入内容或音频。无需网络服务。

## 验收与回滚

- 主机：Gradle 构建/lint/JVM 测试；DEX 解析检查与最终 APK/native 打包检查。对已有 1.4.4 反编译证据、1.4.5 和 1.4.6 APK 交叉检验；1.4.4 完整 APK 尚未确认可用，不能声称完整回归。
- 证明自适配：1.4.5 建立规则，1.4.6 不增加名称特判；另用方法/字段改名夹具、重复特征、缺失特征、缓存更新失效验证。已看过 1.4.6，不能称为严格盲测。
- 实机：主机门禁通过后记录最终 APK 绝对路径与 SHA-256，按 MOBILE_ROUTING 显式 serial 安装；Appium 验证 26 键/9 键、按住/松手/取消、普通点击、工具栏，并测冷启动扫描、缓存命中和失败诊断。跨应用自动发送仅在原本验收范围内测试，避免误发真实消息。
- 回滚：保留已签名 v1.6.8 APK 和工作区基线，支持恢复模块；v1.6.8 已知不支持 1.4.6，回滚不等于恢复长按能力。必要时仅停用本模块接管，保留原生输入；不清应用数据、不回退豆包或改系统配置。

边界：自适配目标是混淆名称变更和兼容的小幅结构调整。日志裁剪、流程重写、动态加密 DEX、native ABI/触摸入口变更仍可能要求更新解析规则。失败应可诊断，不能承诺未来所有版本零维护。
