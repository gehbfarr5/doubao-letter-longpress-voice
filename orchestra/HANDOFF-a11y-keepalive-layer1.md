# HANDOFF: 无障碍服务保活 Layer 1 — BOOT_COMPLETED 自恢复

## 任务类型
代码改动（走完整流程）。

## 背景

本项目（`com.jin.doubaolongpressvoice`）是一个 LSPosed 模块，附带一个 AccessibilityService
`DoubaoVoiceSendA11yService`，需要用户手动授权。

问题：一加 15 / ColorOS 可能在重启后或运行中杀掉 a11y 服务，需要自动保活。

本 HANDOFF 实现 Layer 1（开机自恢复）：
- 监听 `BOOT_COMPLETED`，用 root shell 确保服务在授权列表里
- 无 UI，不影响桌面图标（manifest 已无 LAUNCHER，保持原样）

## 要做的改动

### 1. 新建 BootRestoreReceiver.java

路径：`app/src/main/java/com/jin/doubaolongpressvoice/BootRestoreReceiver.java`

逻辑：
- 收到 `ACTION_BOOT_COMPLETED`
- 用 root shell 执行一条 sh 脚本：
  - 先读当前 `enabled_accessibility_services` 的值
  - 若已包含我们的 component，退出（不重复写）
  - 否则追加（不覆盖其他已授权的服务）
  - 同时确保 `accessibility_enabled = 1`
- 用 `Runtime.getRuntime().exec(new String[]{"su", "-c", "...shell script..."})` 执行
- 任何异常只 Log.w，不崩溃
- 不依赖任何 UI 组件

shell 脚本（单条 su -c 调用）：
```sh
cur=$(settings get secure enabled_accessibility_services); \
comp="com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService"; \
if echo "$cur" | grep -q "$comp"; then exit 0; fi; \
if [ -z "$cur" ] || [ "$cur" = "null" ]; then \
  settings put secure enabled_accessibility_services "$comp"; \
else \
  settings put secure enabled_accessibility_services "${cur}:${comp}"; \
fi; \
settings put secure accessibility_enabled 1
```

### 2. 更新 AndroidManifest.xml

在 `<manifest>` 下（`<application>` 之前）加：
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

在 `<application>` 内（`<service>` 之后）加：
```xml
<receiver
    android:name=".BootRestoreReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

注意：
- `android:exported="false"` — 只接受系统广播，不暴露给其他 App
- 不加 `android:directBootAware="true"`（需要解锁后，不是直接启动模式）
- **不要添加任何 Activity 或 LAUNCHER intent-filter**，保持 App 无界面

## 验收标准

1. `BootRestoreReceiver.java` 存在于正确路径
2. AndroidManifest.xml 包含 `RECEIVE_BOOT_COMPLETED` permission 和 receiver 声明
3. `./gradlew :app:assembleDebug` 编译通过（无错误）
4. `grep -c "BOOT_COMPLETED" app/src/main/AndroidManifest.xml` 输出 `1`
5. `grep -c "BootRestoreReceiver" app/src/main/AndroidManifest.xml` 输出 `1`
6. manifest 里没有新的 `LAUNCHER` intent-filter（保持无桌面图标）

## 约束

- 不修改 `DoubaoLetterLongPressHook.java`、`DoubaoVoiceSendA11yService.java`、`XposedEntry.java`
- 不添加 Activity、不添加 UI 资源
- BootRestoreReceiver 内不做任何 UI 操作
- 只有 Log.i / Log.w，无 Toast / Notification
- 版本号不需要改（Verifier 不检查版本号）
