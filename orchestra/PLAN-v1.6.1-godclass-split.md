# PLAN v1.6.1 — god-class 拆分（Batch B）

> 目标：把 ~1990 行的 `DoubaoLetterLongPressHook` 按职责拆成多个内聚类，**逐步、无行为变更、每步真机验收**。
> 前置：[DIRECTION](DIRECTION-v1.6.x-perf-code-ui.md) C1。v1.6.0(P1+P2) 已上线。

## 铁律（每一步都适用）
- **零行为变更**：只搬代码、改可见性、加类名前缀。不改任何逻辑、阈值、顺序、commit/cancel/dispatch。
- **字段随访问器整体迁移**：一个 `static` 字段连同它的所有读写处一起搬，杜绝跨类 desync。跨边界仍需读的字段用 package-private 或 getter 暴露。
- 每步 `assembleDebug` 必须过；subagent 不 commit；主会话审 diff + 装机；**用户真机验收通过才进下一步**。

## 拆分顺序（失败代价低 → 高）

| 步 | 抽出类 | 内容 | 失败代价 | 状态 |
|---|---|---|---|---|
| **B-1** | `OverlayBadge` | overlay 字段/方法 + label/drawable 映射 + overlay 常量 | 低（仅视觉异常，不碰文字） | ← 本轮 |
| **B-2** | `DoubaoRefs` | 豆包类名常量 + 反射 readers + 资源访问器 + 懒解析单例 | 中（反射读取，回退即"功能不可用不崩"） | 待 B-1 过 |
| **B-3** | `ZoneTracker` | `Zone` enum + computeZone + maybeUpdateZone + zone 状态 | 中 | 待 B-2 过 |
| **B-4** | `AsrController` | trigger/commit/cancel/settle + all-back 订阅 | 高（核心） | 待 B-3 过，**最谨慎** |

主类最终保留：5 个 hook 安装 + onTouch/handleMessage 编排 + per-session volatile 状态。

> 任何一步若发现耦合超预期、风险/收益不划算，**可在该步停下**——前面已落地的拆分仍是净收益。

---

## B-1（本轮执行）= 抽出 `OverlayBadge`

新建 `OverlayBadge.java`（同包 `com.jin.doubaolongpressvoice`）。

**迁入字段**：`sOverlay, sOverlayIcon, sOverlayLabel, sCurrentOverlayColor, sColorAnimator, sOverlayParent`。
**迁入常量**：所有 `OVERLAY_*`、`COLOR_SEND/COLOR_CANCEL/COLOR_TRANSPARENT`、`SELECTION_*`、`SELECTION_ANIM_MS`、`HIDE_ANIM_MS`、`OVERLAY_CORNER_RADIUS_DP`、`OVERLAY_ELEVATION_DP`、`DIMEN_NAME_OVERLAY_MARGIN`，以及 label/drawable 映射常量 `TEXT_NEWLINE/TEXT_CANCEL/RES_NAME_*/FALLBACK_*/DRW_NAME_*`。
**迁入方法**：`ensureOverlay, updateOverlayLayout, updateOverlayForZone, applyOverlayState, setOverlayBackgroundColor, labelForEnterOrdinal, drawableNameForEnterOrdinal`。

**临时回调主类（本步保留，B-2 再改到 `DoubaoRefs`）**：`getInputView`、`resolveDoubaoString`、`resolveDoubaoDrawable`、`resolveDoubaoDimenPx` 在主类改为 package-private（去掉 `private`），`OverlayBadge` 以 `DoubaoLetterLongPressHook.xxx(...)` 调用。`Zone` enum 改为 package-private（`enum Zone` 去掉 `private`）。

**主类改动**：
- `ensureOverlay(...)` / `updateOverlayForZone(...)` 调用点 → `OverlayBadge.ensureOverlay(...)` / `OverlayBadge.updateOverlayForZone(...)`。
- `resetVolatileState` 里把 overlay 置 GONE 的那段 → 调 `OverlayBadge.hideForReset()`（在 OverlayBadge 里实现同样的 GONE 逻辑，保留 view 引用复用）。

**验收点（用户）**：滑到工具栏 overlay 正常显示（图标+文案+蓝底）、滑出显示红底「撤回输入」、zone 切换动画/颜色过渡正常、松手 overlay 渐隐；文字上屏/发送/换行/取消全部不受影响。

版本：暂不 bump（B 系列全部拆完再统一 `versionName 1.6.1`），或每步 build 不发版，最后收尾一次。**本步不改 build.gradle 版本号。**
