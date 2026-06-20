# HANDOFF Batch 5 — Layer 2 保活 + BootReceiver 修复 v1.5.5

## 任务类型
代码改动，走完整流程。改动文件：
- `DoubaoVoiceSendA11yService.java`
- `BootRestoreReceiver.java`
- `AndroidManifest.xml`

## 背景

### #6 a11y 服务保活问题

当前 `DoubaoVoiceSendA11yService` 没有 `startForeground`。
ColorOS/OxygenOS 后台管理激进，服务进程被杀后 a11y 服务失效，用户必须手动重新进设置开启。
前台服务（通知常驻）可以提升进程优先级，减少被系统杀的概率。

### #14 BootRestoreReceiver grep 正则误匹配

当前 SCRIPT 里用 `grep -q "$comp"`，`$comp` 里的点（`.`）在 grep 正则模式里是通配符，
可能误匹配含类似结构的其他组件名，导致误判"已包含"跳过真正的添加。

---

## 改动清单（3 个文件）

### 1. #6 DoubaoVoiceSendA11yService.java — startForeground 保活

**修改 import 区**，加（如果没有）：
```java
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
```

**修改 `onServiceConnected()`**：

当前（只有 `registerSendReceiver()`）：
```java
@Override
protected void onServiceConnected() {
    super.onServiceConnected();
    registerSendReceiver();
}
```

改为：
```java
@Override
protected void onServiceConnected() {
    super.onServiceConnected();
    registerSendReceiver();
    startKeepAliveForeground();
}
```

**新增 `startKeepAliveForeground()` 方法**（在 `registerSendReceiver` 附近）：

```java
private void startKeepAliveForeground() {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    "doubao_voice_send",
                    "豆包语音发送",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, "doubao_voice_send");
        } else {
            builder = new Notification.Builder(this);
        }
        Notification n = builder
                .setContentTitle("豆包语音发送助手运行中")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setOngoing(true)
                .build();
        startForeground(1, n);
        Log.i(TAG, "startForeground ok");
    } catch (Throwable t) {
        Log.w(TAG, "ERR startForeground: " + t.getClass().getSimpleName());
    }
}
```

**修改 `onUnbind()`**：

```java
@Override
public boolean onUnbind(Intent intent) {
    stopForeground(true);
    unregisterSendReceiver();
    return super.onUnbind(intent);
}
```

**修改 `onDestroy()`**：

```java
@Override
public void onDestroy() {
    try {
        stopForeground(true);
    } catch (Throwable t) {
        // ignore
    }
    unregisterSendReceiver();
    super.onDestroy();
}
```

---

### 2. #14 BootRestoreReceiver.java — grep -qF 修复

当前 SCRIPT 里含（约 line 15）：
```java
"if echo \\\"$cur\\\" | grep -q \\\"$comp\\\"; then exit 0; fi; "
```

把 `grep -q` 改为 `grep -qF`（fixed string，不把 `.` 当正则通配）；
同时在 `grep` 命令末尾加 `2>/dev/null`（抑制非 root 场景权限报错）：

改后这行应为：
```java
"if echo \\\"$cur\\\" | grep -qF \\\"$comp\\\" 2>/dev/null; then exit 0; fi; "
```

---

### 3. AndroidManifest.xml — FOREGROUND_SERVICE 权限

在 `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />` 之后，新增：
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

---

## 验收条件

1. `grep -c "startForeground" DoubaoVoiceSendA11yService.java` → ≥ 2（startKeepAliveForeground 内调用 + stopForeground）

2. `grep -c "startKeepAliveForeground" DoubaoVoiceSendA11yService.java` → ≥ 2（定义 + 调用）

3. `grep -c "IMPORTANCE_MIN" DoubaoVoiceSendA11yService.java` → 1

4. `grep -c "grep -qF" BootRestoreReceiver.java` → 1

5. `grep -c "FOREGROUND_SERVICE" AndroidManifest.xml` → 1

6. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

---

## 约束

- 只改上述三个文件
- 不 git add / git commit / 自测
