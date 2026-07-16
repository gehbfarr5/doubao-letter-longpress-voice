# v1.6.5 真机验证报告

- 日期：2026-07-16
- 设备：OnePlus 15 / PLK110 / Android 16 / Vector-SR
- 目标：豆包输入法 1.3.15 (`100315010`)
- 模块：豆包字母键长按语音 1.6.5 (`versionCode=13`)

## 结论

豆包 1.3.15 的核心回归已修复并在 26 键真机路径闭环：长按字母启动 ASR、移入工具栏显示当前编辑器动作、移出键盘显示“撤回输入”，三种松手与 `ACTION_CANCEL` 均只有一个终态。

## 修复实现

- 通过 Vector/LSPosed Native API hook `JNIEnv->RegisterNatives`，截获动态注册的 `KeyboardView.nativeTouch(JIIIJ)V`；只处理 `action=-1` 长按哨兵。
- 以精确签名选择 1.3.14 / 1.3.15 adapter；1.3.15 使用 `J/F/u/w0/t`，未知版本 fail-loud。
- 使用 `GestureSession` 原子 owner/terminal，防止 Java/native 事件路径双提交。
- 使用 `getLocationOnScreen()` 保存 keyboard/toolbar/input Rect，并与 `MotionEvent.rawX/rawY` 比较。实机证明 `getGlobalVisibleRect()` 在 ColorOS IME window 下存在坐标根偏移。
- `ACTION_CANCEL` 固定映射安全取消；`ACTION_UP` 才按 LETTER/TOOLBAR/OUTSIDE 选择终态。

## 真机证据

| 检查 | 结果 | 关键证据 |
|---|---|---|
| Native API | PASS | `LSPosed native API attached`、`RegisterNatives hook installed` |
| 动态 native 注册 | PASS | `captured nativeTouch(JIIIJ)V registration` |
| 长按触发 | PASS | `gesture start ... family=V1_3_15`、`ASR active` |
| 工具栏视觉态 | PASS | 蓝色“搜索”截图；zone `LETTER -> TOOLBAR` |
| 工具栏动作 | PASS | `ASR dispatch ... ordinal=3`、唯一 `terminal=TOOLBAR_ACTION` |
| 移出视觉态 | PASS | 红色“撤回输入”截图；zone `LETTER -> OUTSIDE` |
| 移出撤回 | PASS | `ASR stop ... context=cancel`、唯一 `terminal=CANCEL` |
| 原地松手 | PASS | `ASR graceful commit`、唯一 `terminal=COMMIT` |
| ACTION_CANCEL | PASS | 唯一 `terminal=CANCEL action=CANCEL` |
| 普通输入 | PASS | 短按字母、退格正常；无 gesture session |
| 重复动作 | PASS | 已测手势均无 `DUP_ACTION` |

## 构建验证

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`：PASS（lint 0 errors，保留既有 warnings）。
- `git diff --check`：PASS。
- APK 必须包含 `lib/arm64-v8a/libdoubaolongpress_native.so`、`assets/native_init`、`assets/xposed_init`。
- 最终 APK SHA-256：`a62a73866634ef6dcf3037b2bec70b1d045e8ccd31f2fdb059166877d8deaf00`。

## Wi-Fi 旁路故障

测试中手机 Wi-Fi 开关曾在开启约 1 秒后自动回落。USB ADB 诊断显示 `wlan0` 无 carrier、Wi-Fi power stats timeout，但未发现模块或 root 脚本主动关闭 Wi-Fi。原地重启 `wpa_supplicant`、`wificond`、Oplus Wi-Fi AIDL 与 vendor Wi-Fi HAL 后，`wlan0` 恢复 UP，稳定获得 `192.168.31.42/24`，无线 ADB 与网关连通恢复。该故障属于 Wi-Fi service/HAL 栈卡死，与本模块 native hook 无因果证据；未通过整机重启掩盖。

## 尚未覆盖

- 9 键、数字/符号层、数字输入框、浮动/单手/横屏。
- ChatGPT / Claude / Nekogram 真消息发送；本轮没有触碰或发送用户既有草稿。
- 真人语音转写、长文本、重启后恢复、30 分钟压力与连续 30 次长按。
- arm64 之外的 ABI。

## 回滚

安装前 APK 与 LSPosed 数据库快照保存在 `/tmp/doubao-longpress-20260716-v165-preinstall/`。如出现 IME crash 或 native 回归，先恢复旧模块 APK，再恢复 scope；不要删除用户输入数据。
