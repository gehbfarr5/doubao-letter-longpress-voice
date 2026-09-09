# 路由决策：豆包 1.4.4 适配

## ①判档理由

**复杂任务档**（`gpt-5.6-sol` · `xhigh`）。理由：这是对闭源 App
（`com.bytedance.android.doubaoime`）反混淆签名做核心反射 hook 目标的适配，跨版本
（1.3.17 → 1.4.4）字母重新混淆且历史上多次出现"同名不同义"的坑（本次 `w0` 又是一例），
属于路由表里的"核心/架构"类信号，且改错会导致 LSPosed 模块在真机上直接失效或行为
不一致（用户高频日用工具），质量优先于速度。Verifier 按"核心/架构/重构→Opus"规则用
Opus。

## ②研究外包

**不外包**。这不是"找现成库/查 GitHub issue/查官方文档"类问题——目标是一个私有闭源
App 的内部反混淆字节码，网上没有可查的参考资料，唯一信息源是本机反编译产物。
orchestrator（本会话）已经直接读 jadx 反编译出的 `AsrManager.java` /
`AsrLongPressView.java` 等源码完成了全部关键方法定位（见 `task-current.md` 的映射表
和证据锚点），Codex 执行器只需要照映射表落地代码改动 + 更新文档 + 编译验证，不需要
再做探索性研究。

## 实际调用记录

- Executor: `codex -m gpt-5.6-sol`，`reasoning_effort=xhigh`
- Verifier: Opus（核心 hook 改动）
- 撞车检测：无并发 orchestra 任务在此项目目录下运行
