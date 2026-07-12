# REPAIR v1.6.4-r1 — 组件名规范化不匹配（真机验证暴露，静态验收查不出）

## 背景：为什么要修

真机安装模块后实测发现：Android 的 `AccessibilityManagerService` 在服务绑定时
会把 `enabled_accessibility_services` 里的组件名**规范化成全限定名**：

- 写入短名：`com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService`
- 框架存成全名：`com.jin.doubaolongpressvoice/com.jin.doubaolongpressvoice.DoubaoVoiceSendA11yService`

（真机 `settings get secure enabled_accessibility_services` 实测确认存的是全名。）

当前 v1.6.4 的两处代码都用**短名**做 `contains` 子串判断，全名里不含短名子串
（全名是 `/com.jin...`，短名是 `/.`），导致：

1. **`keepalive-module/service.sh`**：`case *"$COMP"*)`（短名）匹配不到框架存的
   全名 → 每轮落到 `*)` 追加分支，把短名追加进去 → 变成 `全名:短名`。框架不
   去重（真机实测），稳定停在一个重复项，不是无限增长，但污染了设置值，且逻辑
   是错的（本意"已存在就跳过"从未生效）。
2. **`DoubaoLetterLongPressHook.java` `dispatchViaA11ySend`**：
   `enabledServices.contains(A11Y_SERVICE_COMPONENT)`（短名）在服务**正常启用**
   （框架存全名）时也返回 false → **每次发送都误弹** Toast「无障碍服务未启用」，
   即使服务完全正常。这是比原 bug 更烦人的回归。

## 要改的东西

### 1. `keepalive-module/service.sh`

把单个短名 `COMP` 改为同时持有两种形态，判断用"含任一形态"，写入用**全名**
（写全名可避免框架再规范化产生的抖动）：

```sh
COMP_FULL="com.jin.doubaolongpressvoice/com.jin.doubaolongpressvoice.DoubaoVoiceSendA11yService"
COMP_SHORT="com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService"
```

`repair_round` 的分支改为：
- 判断"已含"：`cur` 含 `$COMP_FULL` **或** 含 `$COMP_SHORT` → 只补
  `accessibility_enabled`（保持现有 `ensure_accessibility_enabled`）。
  POSIX sh 的 `case` 不支持 `|` 跨模式变量，用 if + 两个 `case ... in *"$X"*)`
  或 `if echo "$cur" | grep -qF "$COMP_FULL" || echo "$cur" | grep -qF "$COMP_SHORT"`
  实现（grep -qF 定串，避免正则裸点号）。
- 空/null → 写 `"$COMP_FULL"`（不是短名）。
- 非空且两种形态都不含 → 追加 `"${cur}:$COMP_FULL"`（用全名）。
- 日志里的 component 值统一打印 `$COMP_FULL`。
- 启动那行 `log_line "service started ... component=$COMP"` 同步改成 `$COMP_FULL`。

其余结构（POSIX、无限循环不因单轮失败退出、64KB 截断、run_cmd 记 stderr）保持不变。

### 2. `DoubaoLetterLongPressHook.java`

新增全名常量，检查改为"两种形态都不含才判定缺失"：

```java
private static final String A11Y_SERVICE_COMPONENT =
        "com.jin.doubaolongpressvoice/.DoubaoVoiceSendA11yService";
private static final String A11Y_SERVICE_COMPONENT_FULL =
        "com.jin.doubaolongpressvoice/com.jin.doubaolongpressvoice.DoubaoVoiceSendA11yService";
```

`dispatchViaA11ySend` 里的判断从：
```java
if (enabledServices == null || !enabledServices.contains(A11Y_SERVICE_COMPONENT)) {
```
改为（缺失 = null 或两种形态都不含）：
```java
boolean present = enabledServices != null
        && (enabledServices.contains(A11Y_SERVICE_COMPONENT_FULL)
            || enabledServices.contains(A11Y_SERVICE_COMPONENT));
if (!present) {
```
其余（主线程 Toast、异常只 log 不拦截、广播照常发出）保持不变。

### 3. 不改的东西

- 不动 `BootRestoreReceiver.java`（它开机一次性写短名，框架随后规范化，
  行为可接受，且不在本 repair 范围）。
- 不动版本号（仍 1.6.4，未发布）。
- 不 git add/commit、不装真机。

## 可机检验收

1. `sh -n keepalive-module/service.sh; echo rc=$?` → rc=0
2. `grep -c 'COMP_FULL=' keepalive-module/service.sh` → 1
3. `grep -c 'com.jin.doubaolongpressvoice/com.jin.doubaolongpressvoice.DoubaoVoiceSendA11yService' keepalive-module/service.sh` → ≥1
4. keepalive-module/service.sh 中不再存在"写入短名"的 put：
   `grep -c 'put_secure enabled_accessibility_services "\$COMP_SHORT"' keepalive-module/service.sh` → 0
5. `grep -c 'A11Y_SERVICE_COMPONENT_FULL' app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java` → ≥2（定义+使用）
6. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
   （注意：sandbox 内若 gradle 无法起 daemon，标记为"沙箱限制、待 orchestrator 补编译"，不算 FAIL）

## 涉及文件
- 修改 `keepalive-module/service.sh`
- 修改 `app/src/main/java/com/jin/doubaolongpressvoice/DoubaoLetterLongPressHook.java`
