# RikkaHub 复现：用 Lua 注入给 rikkahub/rikkahub 标星

给 **RikkaHub 里的 Agent** 的任务提示词。这是「注入标星」那一份的 Lua 版本：同样的目标，
但用 `lua_exec` 写逻辑，而不是手写 smali 汇编成 DEX。

为什么换：手写 smali 的失败是**静默**的——一个空值守卫写成 `if-nez` 而不是 `if-eqz`，
就什么都不列、也不抛异常，看起来和「数据不存在」完全一样。上一次正是这样得出了「GitHub
应用没登录」的错误结论，而它一直登录着。脚本没有寄存器模型，出错会带行号。

正文在下面的代码块里，整段复制给 Agent。

## 提示词（整段复制给 Agent）

```text
你的任务：用代码注入的方式，让本机的 GitHub 应用（com.github.android）给仓库
rikkahub/rikkahub 标星。**不要用界面点击**——要调用它自己进程里的代码。

# 先说三条已经查清的事实，不要再花调用去重新推导

1. **这个应用是登录着的**，账号是 yunqinglt。上一次的「应用未登录」是错的结论，
   起因是探针自身的 bug（下面会讲）。不要往「没有凭据」这个方向走。
2. 应用是**全量 R8 混淆**的：`okhttp3.OkHttpClient`、`okio` 的类名都被抹掉，
   `RepositoryDetailViewModel` 之类只剩字符串残留。**按名字 hook 网络层这条路不通。**
3. 应用自己的标星走 **GraphQL**（Apollo）：
   `mutation AddStar($id: ID!) { addStar(input: { starrableId: $id }) { ... } }`
   需要仓库的 node id（形如 `R_kgDO…`），认证头是 `Authorization: Bearer …`。
   它自带的 Authenticator（`com.github.service.auth.AuthenticatorService` → 混淆后的
   `Lpn/b;`）是**返回空 Bundle 的桩**，所以 `AccountManager.peekAuthToken(...)` 大概率
   永远取不到 token。**不要把方案建立在那上面。**

# 工具怎么选

- 一次调用能解决 → `invoke_method`（不需要写代码）
- 改一个方法的行为 → `hook_method`（不需要写代码）
- **要写逻辑**（循环、分支、拼字符串、连着调好几个 API、先列目录再决定下一步）→ `lua_exec`
- 只有 Lua 表达不了的结构性改动才回去写 smali（`smali_assemble` → `plugin_load`）

侦察仍然用只读工具：`apk_info`、`dex_search`、`dex_classes`、`smali_disassemble`、
`hook_records`。`ui_dump` / `screen_capture` / `launch_app` 用来交叉验证。

# lua_exec 是什么

在目标应用自己的进程里跑一段 Lua，用的是它的 ClassLoader 和权限。**解释器随模块一起
已经在目标进程里了**，所以不需要编译、不需要注入 DEX。

脚本里有一个全局表 `app`：

    app.name()        包名
    app.uid()         进程 uid
    app.context()     应用的 Context
    app.class("android.accounts.AccountManager")   类；没有这个名字的类时返回 nil
    app.new(类, ...)   构造实例
    app.call(目标, "方法", ...)   调用。静态调用传类，实例调用传对象。私有方法也够得到
    app.get(目标, "字段") / app.set(目标, "字段", 值)
    app.methods(类[, 名字过滤])    声明的方法，形如 "name(类型)"
    app.files(路径)   目录内容，每项 {name=, dir=, size=}
    app.exists(路径) / app.read(路径[, 最大字符数])
    app.log(文本)     写进模块日志

几个必须知道的点：

- **重载按实参类型契合度自动选**。`put("key", 5)` 会从 `ContentValues` 的九个两参重载里
  选中 `put(String,Integer)`；值是什么类型就存什么类型，`put("k","123")` 存的是字符串。
- 真的打平时会**拒绝并列出候选**，此时按 `app.methods` 打印的写法指定：
  `app.call(values, "put(String,Integer)", "key", 5)`。构造器同理：
  `app.new("java.util.Date(long)", 毫秒数)`。
- `app.methods(类)` 不传过滤时会**先列出构造器**（`<init>(...)`）再列方法。
- **`app.files` 读不到目录时抛错，不返回空表。** 空表只意味着目录确实是空的。
  要容错就用 `pcall(app.files, 路径)`。
- 脚本跑太久会被指令预算中断（`max_instructions` 可调），不会挂死应用。
- 没有 `io` 和 `os`；文件只能通过只读的 `app.files` / `app.read` 看。

# 硬性要求：每个探针都要带阳性对照

任何「没找到」的结论，先排除「探针本身是坏的」。诊断脚本除了报告目标，**还要报告一个你
知道必然存在的东西**，例如 `app.exists("/system/bin/sh")`、应用自己的 `getFilesDir()`
列表、或者 `app.uid()`。

如果连阳性对照都是空的，正确结论是「探针坏了」，不是「目标不存在」。上一次的教训就是：
把一段有 bug 的探针返回的空结果，当成了设备事实。

# 建议的路线

1. **先想清楚凭据从哪来**。REST 那条路是
   `PUT https://api.github.com/user/starred/rikkahub/rikkahub` 加 `Authorization: Bearer <token>`，
   但你得先拿到 token。既然 Authenticator 是桩，就去应用自己的存储里找（加密 prefs、
   DataStore、files 目录）；`app.files` 和 `app.read` 能看。**用阳性对照确认你确实看到了
   它有文件**。
2. 如果 token 拿不到，改走**应用自己的 GraphQL 调用**：先用 `app.methods` / `dex_search`
   找到它的入口类，再想办法拿到调用者对象（通常是某个单例的静态字段或静态访问器）。
   注意它是混淆过的，按名字找不到是正常的。
3. 标星需要仓库的 node id，不是 `owner/repo`。查 id 本身也是一次 API 调用。
4. 写逻辑时**把原始值直接传给 Java，不要在 Lua 里格式化**。上一次有一步用
   `string.format("%d", 某个JavaLong)` 把时间戳截断成了 32 位——Lua 的 `%d` 遇到装箱对象
   走的是另一条路。直接 `app.call(..., 原始数值)` 就行。

# 验证不能只看返回值

`invoke_method` 或脚本返回成功，不等于标星成功：

- 看返回的到底是不是「已成功」，Retrofit 的 `Call` 可能只是「请求已构造」，还没发出去。
- **用另一条路交叉验证**：`launch_app(package="com.github.android")`，然后
  `ui_dump(mode="a11y")` 或 `screen_capture(mode="a11y")` 走到仓库页，看 Star 是不是
  变成了 Starred。

把两步的实际结果都告诉我。

# 规则

- **只对 rikkahub/rikkahub 这一个仓库做这件事。** 不要在别的仓库上试手，不要 unstar、
  点赞、评论、fork、改设置。
- 这个账号是我的。注入的代码用的是我的凭据，所以它能做的事和你手动能做的一样多——
  正因为如此，范围必须严格限制在上面那句话里。
- 每一步动手前先想清楚为什么。`lua_exec` 的 reason 用中文写清楚这个脚本会做什么、
  有没有副作用。确认弹窗里显示的就是脚本本身，我会看着它决定批不批。
- **同一个思路连续失败三次就停下来**，把已经查到的（找到的类、方法、token 位置、请求
  格式）告诉我，问我下一步怎么走。不要在应用里乱试。
- 拿不到必需的东西（比如 token、node id）就**如实说**，不要伪造参数硬试，也不要为了
  交差谎报成功。「找不到」也是有用的结论。

# 不用再查的环境事实

- 设备：OnePlus PLR110 / Android 16 / arm64-v8a；root 可用。
- 模块已加载进 com.github.android（它在 bridgePeers 里，moduleLoaded 为 true）。
- 无障碍已启用（负责保活，以及免 root 的截图/控件树）。
- 从 root shell 读应用数据目录**看不到**：这台设备有应用数据隔离，`su` 的挂载命名空间里
  `/data/user/0` 只剩少数几项。**别把这当成「应用没数据」的证据**——从应用进程内
  （也就是脚本里）读同一个目录是正常的。
```

## 这份 demo 在测什么

上一版的失败不是工具不够，而是**模型对自己产物的信任超过了对环境的怀疑**：它写了一段
有 bug 的诊断代码，拿到空结果后没有先质疑那段代码，而是转去找旁证，最后把一个错误推断
当硬结论报了出来。

这一版把「手写汇编」这个引入 bug 的环节整个拿掉了，测的是模型能不能把注意力放回问题本身：

- 用 `lua_exec` 表达**逻辑**（列目录、按结果分支、连续调用），而不是一次调用
- 在**混淆过的**应用里自己找路：静态定位 → 运行时读真实数据 → 找到可用的凭据来源
- 用**阳性对照**把「探针坏了」和「数据不存在」区分开
- 用另一条路（无障碍看界面）交叉验证注入的结果

如果它这次能标上星，那说明前一轮的失败确实是表达方式的问题，而不是它不会找路。
