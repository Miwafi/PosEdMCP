# PosEdMCP

Android 上给本机 Agent 用的 MCP 服务器。LSPosed 模块 + root shell，让跑在手机里的
Agent 能真正操作这台设备。

不是"远程控制"：服务器跑在手机上，Agent 也在手机上，网络只走 `127.0.0.1`。

## 它做什么

| 能力 | 走哪条路 | 是否需要用户确认 |
|---|---|---|
| 设备/模块状态、应用列表、事件流 | 本进程 | 否 |
| 当前前台应用、亮灭屏 | system_server 模块 | 否 |
| 执行 shell 命令（uid 0） | `su` | **是，每次，不可关闭** |
| 截图 | system_server 特权 / `screencap` | 是（可关闭 system 路径） |
| 导出控件树 | `uiautomator`（root） | **是，不可关闭** |
| 注入点击/滑动/文本/按键 | system_server 特权 / `input` | 是（可关闭 system 路径） |
| 向第三方应用注入并调用代码 | LSPosed 作用域 + 内存 DEX | 是（可关闭） |
| 应用进程接入设备桥 | 应用内模块主动连接 | **是，每个包一次，不可关闭** |

## 设计上的三个要点

**一、确认弹窗是唯一把关点。** 每次特权操作都会弹出浮层，逐字显示即将执行的命令和
Agent 填写的理由，用户手动批准才执行。`root_shell_exec` 的确认不可关闭——这正是本
项目存在的理由：Agent 不能执行用户没读过的命令。无法弹出（既无悬浮窗权限也无通知
权限）时**拒绝执行**，而不是放行。

**二、不做自动 fallback 链。** 哪些工具走 shell、哪些走模块特权是显式指定的——否则
一个被放宽的设置可能悄悄降级成一条没人看过的 root 命令。

**三、system_server 里不 hook 任何系统方法。** 截图和输入注入走隐藏 API 反射（全部
包在 try/catch 里，失败退回 root 路径），前台应用用 2 秒轮询而非注册
`TaskStackListener`——注册需要伸进 `ActivityTaskManager` 的私有单例和 AIDL 接口，
写错就是 system_server 崩溃、手机无限重启。一个监控模块导致 bootloop 比少一个事件
严重得多。

## 安装

前置：已 root（Magisk / KernelSU）、已装 LSPosed、Android 9+。本项目在 Android 16 /
arm64 上开发验证。

```bash
./tools/bootstrap-gradle.sh     # 下载 Gradle（仅首次）
echo "sdk.dir=E:/SDK" > local.properties   # Android SDK 路径，按需改
./tools/gradle.sh assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

在 LSPosed 管理器里启用 PosEdMCP，作用域勾选 **系统框架**（System Framework）。第三方
应用按需勾选。**勾选后必须重启手机**——系统框架的 hook 在开机时注入。

> 改完 APK 重新安装后，**也要重启**才让 system_server 用上新代码：模块类在进程启动时
> 加载，而 system_server 只在开机时启动一次。

打开 PosEdMCP 应用，按界面上的 STATUS 一栏逐项处理：

1. **悬浮窗权限**——审批弹窗要用。缺失时会退化成通知，再缺失就直接拒绝。
2. **无障碍**——见下节。它同时负责保活和免 root 的截图/手势/控件树。
3. 应用会自动启动服务并常驻通知栏；"ENDPOINT" 一栏是地址和 token。

### 无障碍：必须做的一步

**没有它，这个服务器在最需要它的时候是死的。** 应用一离开屏幕，系统就会冻结它的进程
（实测 ColorOS 在 24 秒内就冻）。被冻结的进程不再 accept 任何连接，MCP 端点完全失联，
而且**在任何日志里都不留痕迹**——排查时极容易误判成崩溃或代码 bug。

而 Agent 通常跑在**另一个应用**里（RikkaHub 之类），这正是我们的应用在后台的时刻。

解决办法是启用无障碍服务：宿主应用会持有系统绑定，因此不会被冻结。ROM 自己的无障碍
设置页就写着这一条（"开启无障碍辅助功能后，应用将获得自启动权限，不受自启动管理页面
设置项的影响"）。

同一个服务还顺带提供了**不需要 root** 的截图、手势注入和控件树读取——在 Android 16 上
这不是锦上添花：`SurfaceControl` 已不再暴露 display token，system_server 那条截图路径
根本不存在了。

实测对照（应用在后台、另一个应用在前台）：

| | 修复前 | 启用无障碍后 |
|---|---|---|
| 冻结线程 | 全部（33/33 处于 `do_freezer_trap`） | 0/33 |
| MCP 端点 | 完全无响应 | 连续 90 秒正常应答 |

> **更新应用时不要用 `am force-stop`。** 它会把应用标记为停止状态，系统因此解除无障碍
> 绑定——你会顺手毁掉保活，然后困惑于它为什么又被冻了。用 `kill <pid>`：系统会因为绑定
> 而自动重启进程并重新绑定。
>
> ```bash
> adb shell su -c "kill $(adb shell pidof dev.posedmcp)"
> ```

电池无限制（应用里的 **Battery settings**）仍然建议做，ColorOS 可能还需要在
「设置 → 电池 → 应用电池管理」里额外允许后台活动，但**它单独不够**。

### 确认策略：什么该放宽，什么不该

每个 `input_inject` / `screen_capture` / `ui_dump` 默认都弹窗。做界面自动化时这没法用
——点一下弹一次。所以这三个（以及插件相关）可以在应用里关掉确认。

**`root_shell_exec` 永远弹窗，且不可关闭。** 这是有意的：放宽的只是模块/无障碍特权那条
路，真正的权限边界不会因为一个设置而消失。

### root 可用性

如果 root 管理器默认对应用隐藏 `su`（Magisk 的 SuList 模式、KernelSU 的类似机制），
`su` 在应用进程里会**直接不存在**（`No such file or directory`）。注意用 `adb shell`
或 `run-as` 验证会得到误导性结论——那两者继承的是 shell 的挂载命名空间，而普通应用
进程不是。`device_info` 的 `root.diagnostics` 会指明具体是哪种情况。

## 接上客户端

服务器监听 `127.0.0.1:8765`，端点 `/mcp`（MCP Streamable HTTP），Bearer token 认证。

同机客户端直接连 `http://127.0.0.1:8765/mcp`。PC 上的客户端先 `adb forward`。
客户端提示词见 [docs/MCP_PROMPT.md](docs/MCP_PROMPT.md)。`/health` 不需要认证，用来探活。

测试时建议绕开 `adb forward`——它在应用进程被替换后可能静默失效：

```bash
POSEDMCP_TOKEN=<token> ./tools/mcp-call.sh '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 工具

只读：`device_info`、`module_status`、`list_packages`、`foreground_app`、`events_poll`、
`plugin_list`、`apk_info`、`apk_list`、`dex_classes`、`dex_search`、`smali_disassemble`、
`smali_assemble`、`hook_records`。

需要确认：`root_shell_exec`、`screen_capture`、`ui_dump`、`input_inject`、`plugin_load`、
`plugin_invoke`、`hook_method`、`hook_clear`。

不确认但会改状态的只有一个：`launch_app`——把某个应用切到前台，等同于点它的图标。
放在这里说是因为它不弹窗，而它确实会改变你屏幕上的东西。

`smali_assemble` 写文件不弹窗——真正的边界是**执行**，而那一步在 `plugin_load` 上。

## 反编译与运行时观察

给在设备上做逆向的 Agent 用。产出 smali 汇编和应用元信息，不做 Java 源码；改动通过
**运行时注入**完成，不改 APK、不重签名。

```
apk_info / apk_list     应用是什么：清单、组件、权限、签名、包内文件
   ↓
dex_classes / dex_search 里面有什么：类、方法、字符串
   ↓
smali_disassemble       具体怎么写的
   ↓
hook_method             它运行时到底发生了什么（零 DEX，模块直接装钩子）
   ↓
   ├─ 改动能用「值」表达（固定返回 / 换参数 / 改字段）→ 还是 hook_method。
   │  它是数据不是代码：不用编译，不碰 DEX，记录里标 altered 证明生效过。
   └─ 改动是结构性的 → smali_assemble → plugin_load（在设备上写代码，不需要 PC）
```

- 反汇编/汇编用 **baksmali/smali**，纯 Java，直接跑在 ART 上（apktool 不行，它的资源
  解码要调用宿主机原生的 aapt2）
- 清单解析用系统自己的 `PackageManager`，比任何重实现都准
- 引擎跑在 `android:process=":dex"` 的独立进程里：大 APK 反编译吃内存，OOM 时只死这个
  进程，MCP 端点和你正在看的确认弹窗不受影响
- 大输出一律落盘、返回路径与统计数字；只有单个小类才内联
- **hook 是每进程状态**，所以 `hook_method` / `hook_clear` / `plugin_load` 会作用于该包
  的**所有**进程，`hook_records` 合并各进程结果并标注来源。一个应用常有多个进程，
  只问其中一个会得到"没有 hook"这种误导性答案

## 架构

```
┌──────────────── 应用进程 (dev.posedmcp) ────────────────┐
│  McpService (前台服务)                                  │
│    ├── HttpTransport  127.0.0.1:8765  /mcp              │
│    ├── McpServer      JSON-RPC, 工具分发                │
│    ├── ToolRegistry   12 个工具 + 确认策略               │
│    ├── ConfirmationGate ──> ConfirmOverlay (应用浮层)   │
│    ├── BridgeServer   127.0.0.1:8766  (进程间桥)         │
│    ├── RootShell      su, 管道 stdio（非 pty）           │
│    └── EventStore     环形事件缓冲 + seq 游标            │
└──────────────────────────────────────────────────────────┘
             ▲ TCP + token / 首次连接由用户批准
             │
┌────────────┴───────────┐   ┌────────────────────────────┐
│ system_server (role=   │   │ 被作用域覆盖的应用          │
│   system)              │   │  AppHost                   │
│  SystemHooks           │   │   ├ 内存 DEX 加载          │
│   ├ 截图 / 输入注入     │   │   └ 插件调用               │
│   ├ 前台应用轮询        │   │                            │
│   └ 亮灭屏广播          │   │                            │
└────────────────────────┘   └────────────────────────────┘
```

插件以字节流经桥接送进目标进程，用 `InMemoryDexClassLoader` 加载、**不落盘**——否则
每次注入都要先经 root 命令推文件，等于每次多一次弹窗。

多个进程的应用（闹钟有主进程和 `:clockWidget`）以 `包名:pid` 分别登记；插件装在哪个
进程，调用就路由到哪个。

## 凭据是怎么送到模块手里的

这是本项目里最绕的一段，因为 **Android 把带外通道全堵死了**：

| 通道 | 结果 |
|---|---|
| 抽象 Unix socket | SELinux 拒绝 `connectto`（`untrusted_app` → `untrusted_app`，安全类别不同）——平台设计边界，不是配置问题 |
| ContentProvider | 包可见性：`Unknown authority`。宿主应用的 manifest 不是我们能改的 |
| 显式 `bindService` | 同样被包可见性挡住，`bindService` 直接返回 false（系统应用和 uid 1000 不受影响，所以 systemui 反而连得上） |
| 直接读文件 | Android 16 把 prefs 移到 `/data/misc/<uuid>/prefs/`，跨 uid 进不去；即使 `chcon` 去掉类别，`untrusted_app` 读 `app_data_file` 仍受限 |
| `XSharedPreferences` | 框架自己的机制，依赖守护进程在开机时放权，实测未生效 |

于是改成**在应用已经建立的那条连接上发放凭据**：应用连上桥但不带 token 时，弹窗问
用户"某个包要接入"，批准后把 token 交给他并记住。每个包问一次；被拒绝的包在 10 分钟内
不再重复打扰。

这不是密码学意义上的强身份——批准的是"声称自己是这个包的那条连接"。它换来的是**完整性**
（防止别的进程伪造事件、抢答伪造截图），而不是机密性；真正的权限边界始终是那个确认弹窗。
对作用域内的应用，token 本来也藏不住：模块就跑在人家进程里。

保留的其它通道作为优化路径：外部媒体目录 `Android/media/<pkg>/`、Binder 服务、
ContentProvider——能通就用，省掉一次弹窗。

## 开发

```bash
./tools/gradle.sh assembleDebug
./tools/build-plugin.sh          # 示例插件 → tools/plugin-demo/build/plugin.b64
```

`tools/gradle.sh` 把 `GRADLE_USER_HOME` 重定向到仓库内的 `.gradle-home/`：Windows 用户
目录含非 ASCII 字符时部分工具链会出问题。代理设置放在 `.gradle-home/gradle.properties`，
不进版本库。

需要 JDK 17+（本项目用 JDK 22 验证）。

## 已知限制

- **system 路径的截图在本机不可用**。Android 16 移除了 `SurfaceControl.getPhysicalDisplayToken`
  和 `getPhysicalDisplayIds`——运行时枚举确认这两个方法在该设备的 framework 里根本不存在，
  不是反射写法问题。`ScreenCapture.captureDisplay` 需要一个 display token，而没有公开的
  途径拿到它。root 的 `screencap` 路径覆盖了这个能力：`screen_capture` 的 `mode=auto`
  会先问 system_server 上一次的失败原因，跳过这条死路，直接走 root（一次确认）。
  其余 system 能力（前台追踪、事件、输入注入）均正常。
- 没有单元测试。所有验证都是在真机上按行为做的。

### 调试隐藏 API 时的两个坑

- **`Class.getDeclaredMethods()` 会被隐藏 API 过滤**：返回的列表里只有公开成员，看起来像
  "这个方法不存在"。必须先在进程内装好豁免（`VMRuntime.setHiddenApiExemptions`，
  见 `HiddenApi.java`），否则整条反射链会静默地什么都找不到。
- 设备上的 `/system/framework/framework.jar` 是**桩**，里面的 dex 没有真实实现，不能用来
  查方法签名。用 `device_info` 的 `displayProbe` 在运行时枚举才准。

## 状态

已在 OnePlus PLR110 / Android 16 / arm64-v8a / Magisk v27.2-kitsune-4 /
Zygisk-LSPosed 1.10.2 (7182) 上验证：

- 模块被 LSPosed 正确加载；system_server 走 `SystemHooks` 分支并连上桥（`role=system`）
- MCP 握手、`tools/list`、鉴权（含 401 拒绝路径）
- root shell 执行与逐条确认弹窗；中文理由渲染逐字正确
- `ui_dump` 端到端跑通
- system 路径的**前台应用查询**与**输入注入**（`InputManagerGlobal.injectInputEvent`，
  不经 shell），以及 `foreground.changed` / `screen.on|off` 事件流
- `screen_capture` 的 `mode=auto` 在 system 路径不可用时正确回退到 root
- **向 `com.coloros.alarmclock` 注入插件并 hook 到 `Activity.onResume`**，
  按行为验证（返回了该应用真实的 Activity 生命周期）
- 多进程应用的 peer 登记与路由
- **静态分析链路**：`apk_info` / `dex_classes` / `dex_search` 字段与 `dumpsys package`
  对得上；`smali_disassemble` → `smali_assemble` 往返后方法签名与原始一致
- **运行时观察链路**：在时钟进程里 hook `Activity.onResume`，切前后台后
  `hook_records` 读到 3 条真实调用（线程与时间戳均正确）
- **端到端注入**：反汇编应用的闹钟解析函数拿到正式的 Bundle 契约，注入插件调用
  应用自己的 `add_alarm` 接口，在时钟应用里创建出一个 **06:07 / 标签 "PosEdMCP" /
  已启用** 的闹钟，并用 `delete_alarm` 清理了过程中的临时闹钟
- **保活**：启用无障碍后，应用在后台、另一个应用在前台时，实测 90 秒内**零冻结线程**
  且 MCP 端点持续应答（修复前是 33/33 线程处于 `do_freezer_trap`、端点完全失联）
- **无需 root 的界面操作**：`launch_app` 成功把 GitHub 应用切到前台（前台窗口为
  `com.github.android/.main.MainActivity`）

### 尚未验证

- 无障碍路由的 `ui_dump` / `screen_capture` / `input_inject` —— 实现完成、编译通过、
  路由已接，但还没在真机上跑过完整一轮（每次都需要人工点确认弹窗）。
  非 root 的界面自动化正是这条路的重点，值得先跑一遍
  [docs/GITHUB_STAR_DEMO.md](docs/GITHUB_STAR_DEMO.md)。
- 有一次读取工具返回值的实验里看到中文变成 U+FFFD。同一份数据在应用自己的日志里是
  完好的，所以最可能出在测试客户端而不是服务端；但在查清之前，任何**从服务端读回中文**
  的地方都值得留意。

## 许可

无。自用项目。
