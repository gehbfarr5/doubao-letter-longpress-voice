# PLAN v1.6.x — 非功能性质量（性能 / 代码 / UI）

> 前置：[DIRECTION-v1.6.x](DIRECTION-v1.6.x-perf-code-ui.md)。功能面已封顶，LLM 候选已取消，豆包原生面板不动（U2 取消）。
> 测试模式：subagent 实现 → 主会话 build+推设备 → **用户真机实测** → 通过后收尾推 GitHub。

## 批次与排期

| 批次 | 版本 | 内容 | 风险 | 执行 |
|---|---|---|---|---|
| **Batch A** | v1.6.0 | P1 日志门控 + P2 反射缓存(仅显示路径) + ~~U1 overlay 主题色~~ + C3 lint | 低 | ✅ 真机通过 (2026-06-21) |

> **U1 已回退**：真机实测 `asr_long_press_navigation_press` 解析出来不是品牌蓝（白色/状态高亮色），发送按钮变白。该资源不是可靠的蓝色来源，U1 前提不成立 → 删除 `resolveDoubaoColor` + `COLOR_NAME_SEND`，发送色保持硬编码 `COLOR_SEND` 蓝。**v1.6.0 最终 = P1 + P2（纯性能，零行为/UI 变更）。**
| **Batch B** | v1.6.1 | C1 god-class 拆分（含 P3 反射集中 + C2 去重） | 中-高 | **A 通过并上 GitHub 后**单独排期，小步+每步验收 |

### U3 取消说明
原 DIRECTION 的 U3（把 Java 兜底文案搬进 `strings.xml`）从 v1.6.0 移除：模块代码运行在**豆包进程内**，读自身 `R.string` 需 `XModuleResources` 模块资源桥接，复杂度/风险 > 收益。**进程内硬编码兜底常量是正确的务实选择**，保留现状。

---

## Batch A = v1.6.0（本轮执行）

执行细节见 [HANDOFF-v1.6.0-perf-ui](HANDOFF-v1.6.0-perf-ui.md)。原则：**无核心行为变更**，不碰 commit/cancel/触发判定/dispatch 路由/`USER_INPUT_SOURCES`。

- **P1 日志门控**：加 `static final boolean DEBUG=false`，`log()` 内 `if(!DEBUG)return;` 包住 `Log.i`+`XposedBridge.log`。88 处调用全部受益，热路径零日志开销。
- **P2 反射缓存（仅显示路径）**：长按触发时解析一次 `resolveEffectiveEnterOrdinal` 存 `sRecordingEnterOrdinal`，`maybeUpdateZone`（每 MOVE 热路径）读缓存；**`commitAndDispatchToolbarAction` 保持实时解析**（一次性、便宜，保证 dispatch 正确性）。`resetVolatileState` 清缓存。
- **U1 overlay 主题色**：新增 `resolveDoubaoColor(cl,resName,fallback)`，发送色优先读豆包 `asr_long_press_navigation_press`（解析失败回退现 `COLOR_SEND`）；取消红保持。
- **C3**：改完 `assembleDebug` 必须过；快速扫一遍新引入的 unused。
- 版本：`versionCode 8` / `versionName "1.6.0"`。

## Batch B = v1.6.1（A 通过后）
C1 god-class 拆分：`DoubaoRefs`(P3/C2) / `AsrController` / `ZoneTracker` / `OverlayBadge` + 编排壳。逐文件无行为变更小步，每步独立 build + 真机验收。**本计划不展开，待 A 收尾后另写 HANDOFF。**

## 测试关注点（交给用户的真机验收清单 — Batch A）
1. 长按字母键仍能触发语音（震动 + 面板）。
2. 原地松手上屏正常。
3. 滑工具栏：发送/换行标签正确、动作正确（重点验 P2 没让标签/动作错位）。
4. 滑出取消正常（不上屏）。
5. 跨应用（Claude/ChatGPT）发送仍正常。
6. overlay 颜色正常显示（U1 没把颜色搞坏；深/浅色都瞄一眼）。
7. （可选）`DEBUG=false` 下 logcat 安静；需要排障时改 `DEBUG=true` 重新装。
