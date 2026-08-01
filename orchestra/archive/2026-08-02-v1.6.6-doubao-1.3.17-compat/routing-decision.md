# Routing Decision — 适配豆包 1.3.17（DoubaoCompatAdapter 新增 Family.V1_3_17）

| 项 | 内容 |
|---|---|
| 日期 | 2026-08-01 |
| 需求 | 豆包输入法更新到 1.3.17 后长按字母键语音完全无反应 → 根因 = `DoubaoCompatAdapter.resolve()` 匹配不到任何已知 family（`w0` 签名从无参变 `(boolean,String)`），`install()` 直接 return，一个 hook 都不装 |
| 类型 | 代码改动 → 完整流程 |
| 档位 | **质量优先**（复杂任务） |
| **判档理由** | 核心功能修复：长按语音是本模块唯一主功能，当前 1.3.17 下完全失效；改动是反射方法签名匹配 + 三路 family 分支改造（`cancel()`/`stop()`/`commit()` 三处调用点都要同步改，遗漏一处会在真机 hook 进程里抛 `IllegalArgumentException`/`NoSuchMethodException`），波及豆包输入法整个键盘进程稳定性，命中"核心"触发词，走质量优先档，Verifier 同步升 Opus |
| **研究外包** | **不派**。已本机对 1.3.17 base.apk（真机 `com.bytedance.android.doubaoime` versionCode 100317008）做 JADX 反编译 + 人工比对 `AsrManager` 方法签名，拿到确定性根因（见 `task-current.md`「RE 结论」）；豆包私有混淆符号 GitHub/web 搜不到，外包无意义 |
| Executor model | `codex -m gpt-5.6-sol`（xhigh）（撞车检测：vs reasonix deepseek-v4-flash = 不撞） |
| Verifier | 独立子 Agent，**Opus**（核心功能修复升档），build + lint + grep 取证 |
| Task file | `orchestra/task-current.md` |
| 项目目录 | `/Users/jin/Desktop/doubao-letter-longpress-voice` |
| Sandbox | `-s workspace-write -C <项目>`（强制） |
| 是否兜底 | 否（EXEC_QUOTA 时按 ROUTING 兜底链降级 Reasonix） |

## 真机/RE 取证摘要（主会话完成，不占执行器）

- `pm list packages` 确认豆包 `com.bytedance.android.doubaoime` 已从 1.3.15 静默更新到 **1.3.17**（`lastUpdateTime=2026-07-28`），语音模块本身仍是已安装的 v1.6.5（针对 1.3.15 构建）
- logcat 实测：长按字母键期间无任何本模块 hook 相关日志输出，用户确认"长按完全无反应"
- 拉取真机 base.apk（155MB，WiFi ADB，首次 pull 因超时截断成 21MB 坏包，重拉后 zip 校验通过）→ JADX 反编译（9401 类，23 类反编译有误但不影响签名可信度）
- 逐一比对 `AsrManager` 关键方法：`J()`/`F()`/`u()`/`t(int,long)`/`AsrManager.a`/`KeyboardView.nativeTouch(JIIIJ)V` 均未变，仅 `w0` 从无参变 `(boolean,String)` → 精确定位到 `DoubaoCompatAdapter` 的 family 匹配失败

## 后续人工验证门（收口后，主会话自己做 + 用户复测，不外包）

1. Codex 完成后先本机 `assembleDebug`/`lint`/单测跑绿（Verifier 已覆盖），主会话再装真机
2. 卸载真机旧 v1.6.5 → 装新构建 debug APK → **强制停止豆包输入法一次**（README 已知坑）
3. logcat 确认 `capability probe family=V1_3_17` 且 `isSupported=true`
4. 用户实测 P0：长按触发语音+振动、原地松手上屏、滑工具栏发送/搜索/换行、滑出撤回不残留
5. 全部通过 → 更新 README 兼容性表 + versionCode 13→14 / versionName 1.6.5→1.6.6 → commit（可选 push）
