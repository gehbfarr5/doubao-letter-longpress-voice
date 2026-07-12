# TASK v1.6.4 — a11y 授权被 ColorOS 清除导致 ChatGPT 发送失效：Layer 3 root 保活 + 失效时可见报错

## 用户报告

> 在 ChatGPT 里多轮对话或长段输入的时候，会导致从字母划到工具栏触发发送失效。

## 真机取证结论（2026-07-12，PLK110 / 192.168.31.42:5555）

已确认的根因（第一性原理，非猜测）：

1. `settings get secure enabled_accessibility_services` = **空**，`accessibility_enabled` = **0**
   —— `DoubaoVoiceSendA11yService` 的授权被系统整个清掉。ChatGPT 属于
   `A11Y_SEND_PACKAGES`，发送 100% 依赖该服务点击按钮；服务没了 = 每次发送都
   静默失效（hook 广播发出去无人接收，无任何用户可见报错）。
2. `dumpsys package com.jin.doubaolongpressvoice` → **`stopped=true`**
   —— stopped 状态的应用收不到 `BOOT_COMPLETED`，因此现有 Layer 1
   （`BootRestoreReceiver` + su）在这个状态下**永远没有机会执行**，无法自愈。
3. 时间线：07-10 12:57 装 v1.6.3 → 07-11 用户实测 ChatGPT 发送 OK（commit 3a289ac）
   → 07-12 12:12 设备重启 → 15:54 取证时授权已被清空。非 AOSP hibernation
   （`cmd app_hibernation get-state` = false），是 ColorOS 自有清理行为。
4. 已临时恢复：root `settings put` 重新授权，服务已起（logcat
   `a11y send receiver registered` + `startForeground ok`，`stopped=false`）。
   **但 ColorOS 随时可能再清，必须做 Layer 3。**

「多轮对话/长输入才失效」的表象解释：长会话期间 ColorOS 后台清理更容易触发，
清掉授权后所有发送都失效；用户感知上与会话长度相关。
（次要假说：长输入时 ASR 收尾超 `NEWLINE_ASR_MAX_WAIT_MS`=2000ms 上限、
timeout 提前广播点击——**待确认**，等服务恢复后用户复测长输入，若复现由
DoubaoVoiceSend logcat 判别，本任务不实现。）

## 改动清单

### 1. 新增 KernelSU/SukiSU 保活模块 `keepalive-module/`（Layer 3，主修复）

不依赖应用进程/广播/su 授权，root 层直接守护 settings。参考本机已验收模式
`/Users/jin/Desktop/adbkeepalive-module-review/source/current/`（只参考结构与
写法，不要抄它的 TCP/网络逻辑）。

新文件：

**`keepalive-module/module.prop`**：
```
id=doubaovoicesend_keepalive
name=Doubao Voice Send A11y Keepalive
version=v1.0
versionCode=1
author=jin
description=Keeps DoubaoVoiceSendA11yService enabled in secure settings (ColorOS strips it on reboot/cleanup)
```

**`keepalive-module/service.sh`**（late_start service，root）逻辑要求：
- `COMP="com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService"`
- 等 `sys.boot_completed=1` 后进入无限循环，间隔 `120` 秒（变量 `INTERVAL`）
- 每轮：读 `settings get secure enabled_accessibility_services`：
  - 已含 `$COMP`（字符串包含判断，勿用正则 grep 裸点号）→ 只补
    `accessibility_enabled`：若 `settings get secure accessibility_enabled` ≠ 1
    则 `settings put secure accessibility_enabled 1` 并记日志
  - 为空或 `null` → `settings put secure enabled_accessibility_services "$COMP"`
    + `settings put secure accessibility_enabled 1`
  - 非空且不含 → 追加 `"${cur}:${COMP}"`（保留其他已授权服务）
    + `settings put secure accessibility_enabled 1`
  - 发生修复动作时写一行带时间戳日志到
    `/data/local/tmp/doubaovoicesend_keepalive.log`；日志超过 64KB 截断重写
    （防无限增长）
- 纯 POSIX sh（`/system/bin/sh`），不用 bash 特性
- 修复动作发生与否都不得让循环退出；单轮任何命令失败不退出（失败信息写
  日志，**不要**盲目 `2>/dev/null` 吞掉所有 stderr）

**`keepalive-module/README.md`**：模块用途 3-5 行 + 安装命令
（`zip -j` 打包 → `ksud module install` → reboot）+ 验证命令。

### 2. Hook 端失效可见化（诚实报错，非兜底）

`DoubaoLetterLongPressHook.java` 的 `dispatchViaA11ySend(...)` 开头：
- 通过 `mImeService` Context 读
  `Settings.Secure.getString(resolver, "enabled_accessibility_services")`
- 若为 null 或不含 `"com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService"`：
  在主线程 Toast「豆包语音发送：无障碍服务未启用，发送可能失败」+ `log(...)`
  记录；**广播仍照常发出**（settings 读数可能滞后于实际绑定状态，不据此中断
  流程——这是提示，不是拦截）
- 读取失败（Throwable）→ 只 log，不 Toast，不影响原流程
- 组件字符串抽成常量（hook 与 BootRestoreReceiver 不同进程，各自持有常量，
  不引入跨类依赖）

### 3. 版本号

`app/build.gradle`：versionCode 11→12，versionName "1.6.3"→"1.6.4"。

## 不改的东西

- 不动 `DoubaoVoiceSendA11yService` 的选择器/评分逻辑（次要假说未证实，不猜）
- 不动 `NEWLINE_ASR_MAX_WAIT_MS` 等时序常数
- 不删 Layer 1 `BootRestoreReceiver`（多一层无害，成本为零）
- 执行器不 git add/commit、不装真机

## 可机检验收标准

1. `test -f keepalive-module/module.prop && grep -c '^id=doubaovoicesend_keepalive$' keepalive-module/module.prop` → 1
2. `sh -n keepalive-module/service.sh` → exit 0（语法合法）
3. `grep -c 'DoubaoVoiceSendA11yService' keepalive-module/service.sh` → ≥1
4. `grep -c 'boot_completed' keepalive-module/service.sh` → ≥1
5. `grep -c 'accessibility_enabled' keepalive-module/service.sh` → ≥2（读+写）
6. `grep -c 'enabled_accessibility_services' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → ≥1
7. `grep -c 'Toast' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → ≥1
8. `grep -c 'versionName "1.6.4"' app/build.gradle` → 1
9. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
10. `test -f keepalive-module/README.md` → 存在

## 涉及文件

- 新增 `keepalive-module/module.prop`、`keepalive-module/service.sh`、`keepalive-module/README.md`
- 修改 `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`
- 修改 `app/build.gradle`
