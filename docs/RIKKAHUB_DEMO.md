# RikkaHub 复现：用注入代码给时钟应用加一个闹钟

这份文档是给 **RikkaHub 里的 Agent** 的任务提示词（正文在下面的代码块里，整段复制粘贴即可）。
它复现的是我在终端里做过的那次注入：不通过标准 Intent、不改 APK，而是把代码送进时钟应用的
进程，调用它**自己的**闹钟接口。

## 前置检查（做一次就够）

1. **PosEdMCP 服务在跑**：打开 PosEdMCP 应用，STATUS 里 Service 是 running，
   "Module processes" ≥ 1。如果 System bridge 显示 offline 不影响这个 demo（用不到）。
2. **RikkaHub 里加了这个 MCP 服务器**：设置 → MCP → +（Streamable HTTP）
   - URL：`http://127.0.0.1:8765/mcp`
   - 自定义请求头：`Authorization` = `Bearer <token>`（token 在 PosEdMCP 的 ENDPOINT 一栏）
   - 保存后应显示"已连接"
3. **给这个助手打开它**：助手设置里点进该助手 → 找到「MCP 服务器」一节 →
   把 PosEdMCP 的开关打开。**只加服务器不够，这一步不做的话模型看不到任何工具。**
4. **时钟应用在运行**：打开一次系统时钟应用，让模块加载进它的进程。

## 提示词（整段复制给 Agent）

```text
你的任务：向本机的系统时钟应用注入一小段代码，让它在闹钟列表里新增一个闹钟。
完成后要能指给我看，而不是只报告"成功了"。

# 背景事实（这些是我已经查证过的，你可以用只读工具复核，但不必从零摸索）

- 目标应用包名 `com.coloros.alarmclock`，已 root，LSPosed 作用域包含它。
- 它把面向语音助手的闹钟接口暴露在自己的 `AiSupportContentProvider` 上，
  authority 是 `com.coloros.alarmclock.ai`，通过
  `ContentResolver.call(method="add_alarm", extras)` 调用。
- 该接口解析 extras 时用的是 AOSP 标准闹钟常量：
  - `android.intent.extra.alarm.HOUR`（int，默认 -1；**不传就会落到应用自己的默认时间**）
  - `android.intent.extra.alarm.MINUTES`（int）
  - `android.intent.extra.alarm.DAYS_OF_WEEK`（byte，0 表示不重复）
  - `label`（string，闹钟的标签）
- 可加载的注入代码已经准备好：
  路径 `/data/user/0/dev.posedmcp/cache/dex/demo/alarm-plugin.dex`，
  类名 `posedmcp.plugin.demo.AlarmPlugin`，
  它做的就是我上面描述的调用。

# 执行步骤

第 1 步 · 先看，不要直接动手
  - `module_status`：确认 `com.coloros.alarmclock` 出现在 bridgePeers 里。
    如果不在，说明模块没在该应用进程里，告诉我需要重启时钟应用，然后停在这里。
  - `apk_info(package="com.coloros.alarmclock")`：看它的 providers 和 activities，
    把我上面说的 authority 对上。
  - 可选但推荐：`dex_search(package="com.coloros.alarmclock", kind="method",
    pattern="add_alarm")`，自己找到证据而不是信我一面之词。

第 2 步 · 注入
  `plugin_load(package="com.coloros.alarmclock",
              class_name="posedmcp.plugin.demo.AlarmPlugin",
              dex_path="/data/user/0/dev.posedmcp/cache/dex/demo/alarm-plugin.dex",
              reason="...")`
  这一步会弹出确认窗。reason 用中文写清楚：把这段代码加载进时钟应用的进程，
  用它自己的接口新增一个闹钟，不改动其他数据。

第 3 步 · 调用
  `plugin_invoke(package="com.coloros.alarmclock",
                class_name="posedmcp.plugin.demo.AlarmPlugin",
                method="addAlarm",
                args_json="{\"hour\":6,\"minute\":7,\"label\":\"PosEdMCP\"}",
                reason="...")`
  同样会弹窗。

第 4 步 · 读结果
  返回里看两个字段：
  - `route` 应该是 `app-provider`（走的是应用自己的接口，不是直接改数据库）
  - `attempts[0].attempts[0].reply.result` 应该是 `1`（应用自己确认创建成功）
  如果 route 是 `none`，把 attempts 里的错误原样念给我听，不要自己重试。

第 5 步 · 让我看到
  用 `root_shell_exec` 把时钟应用切到前台：
  `am start -n com.coloros.alarmclock/com.oplus.alarmclock.AlarmClock`
  然后告诉我列表顶部应该出现一个 06:07、标签是 PosEdMCP 的闹钟。
  或者直接说"请打开时钟应用看一下"，两者都行。

# 规则

- 每个 `reason` 都用中文写具体：用户是拿这句话决定要不要批准的。写清楚这段代码做什么、
  为什么需要，不要写"为了完成任务"这种废话。
- 弹窗被拒绝、或者超时，那是用户的决定，不是可以重试的错误。停下来问我。
- 不要为了绕开确认窗，改用 `root_shell_exec` 去做同一件事。那是在欺骗我。
- 不要重复调用第 3 步来"确认一下"，一次就够。

# 做完之后

如果需要撤销，`plugin_invoke(method="deleteAlarmByLabel", args_json="{\"label\":\"PosEdMCP\"}")`
会删掉标签里含 PosEdMCP 的闹钟。

**注意**：这个删除调用在本机上实测会超过 `plugin_invoke` 的 20 秒上限，于是返回一条
"timed out" —— **但闹钟确实已经删掉了**。别看到超时就以为没生效，去时钟应用里确认一下。
删除是两条 provider 调用（先 `get_alarm_list` 再 `delete_alarm`），慢在那一步。
新增（`addAlarm`）不受影响，几秒内就返回。
```

## 这个 demo 为什么能成立

值得说清楚，因为它是整个项目里最不显然的一点：

**注入的代码跑在时钟应用的进程里，用的是它自己的 uid 和 Context。** 所以它能调用该应用
的私有接口，而这些接口并没有导出——从外部应用根本调不到。`plugin_load` 把 DEX 送进去，
`InMemoryDexClassLoader` 在目标进程里加载它，之后那段代码和时钟应用自己的代码没有区别。

另外那条 `HOUR` 默认 -1 的坑也值得记住：我第一次猜 Bundle 键名时没传对，闹钟照样被创建了，
但时间是应用自己的默认值（23:00）。**"调用成功"和"做了我想做的事"是两回事**——所以第 4 步
要求核对 `route` 和 `result`，第 5 步要求让我亲眼看到。

## 想要更难一点的版本

上面的版本里，DEX 是我预先编译好的。如果你想让 Agent 真的自己"写代码"：

把第 2 步换成
`smali_disassemble(path="/data/user/0/dev.posedmcp/cache/dex/demo/alarm-plugin.dex",
                   filter="AlarmPlugin")`，
让 Agent 读出 smali、把里面 `const/16` 的时间常量改成别的值，再用
`smali_assemble(dir=<上一步的输出目录>)` 汇编成新 DEX，最后 `plugin_load(dex_path=<新 DEX>)`。

这条路今天已经通了（我和 Agent 都用过），但它要求 Agent 能读懂并修改 smali。这是当前
工具链的真实边界：**编译在设备上已经能做，但从零写一门高级语言的逻辑还不行。**
