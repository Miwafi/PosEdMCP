# RikkaHub 复现：用代码注入给 rikkahub/rikkahub 标星

这是第三份提示词，测的是**纯注入**：不碰界面、不点屏幕，把代码送进 GitHub 应用，
让它**用自己已有的登录凭据**去标星。

和前两份的区别：

| demo | 手段 | 目标应用知道吗 |
|---|---|---|
| 闹钟 | 静态注入（编译好的插件调它自己的 `add_alarm`） | 知道——是它自己的接口被调了 |
| GitHub（无障碍版） | 纯界面操作 | 完全不知道——只是被看和被点 |
| **GitHub（本份）** | 运行时反射调用（`invoke_method`） | 不知道——调用发生在它进程内部 |

这一份是三份里最难的，因为**目标应用的接口没有文档，得靠你自己挖**。所以这份提示词
给的是方法论和线索，不是步骤。

## 前置检查

1. PosEdMCP 在跑，无障碍已启用（无关但保活要靠它）。
2. **`com.github.android` 在 LSPosed 作用域里**，并且**启动过一次**（让模块加载进它的进程）。
   `module_status` 的 `bridgePeers` 里要能看到它。
3. GitHub 应用**已登录**——注入的代码会用它自己的凭据，没登录就没凭据可用。

## 提示词（整段复制给 Agent）

```text
你的任务：用代码注入的方式，让本机的 GitHub 应用（com.github.android）给仓库
rikkahub/rikkahub 标星。**不要用界面点击**——要调用它自己进程里的代码。

目标应用的内部接口没有文档，你得自己挖出来。下面是方法和线索。

# 可用工具

读（不弹窗）：
- `dex_search` / `dex_classes` — 在 APK 里找类、方法、字符串
- `smali_disassemble` — 反汇编具体类看实现
- `hook_records` — 读运行时抓到的调用
- `apk_info` / `apk_list` — 看应用结构和依赖

动手（弹窗确认）：
- `hook_method` — 在目标进程里挂观察器（记录调用）**或者**改行为
- `invoke_method` — 在目标进程里反射调用任意方法（静态/实例都行）
- `plugin_load` — 只有前两者都做不到时才用

验证（不弹窗）：
- `ui_dump(mode="a11y")` / `screen_capture(mode="a11y")` — 看界面确认结果
- `launch_app` — 把 GitHub 应用切到前台

# 方法：先看它怎么做，再自己做

## 第 1 步 · 静态侦察

先找线索，不要盲猜：
- `dex_search(package="com.github.android", kind="string", pattern="user/starred")`
  —— GitHub REST 的标星端点是 `PUT /user/starred/{owner}/{repo}`
- `dex_search(package="com.github.android", kind="string", pattern="addStar")`
  —— GraphQL 的标星 mutation 是 `addStar(input:{starrableId:...})`
- `dex_search(package="com.github.android", kind="method", pattern="star")`
  —— 找它自己的标星方法
- `apk_list(package="com.github.android", filter="dex")` 看有几个 dex

## 第 2 步 · 动态侦察（这一步最有价值）

**先看应用自己标星时发出什么请求**，比猜接口可靠得多：

1. 挂一个观察器，记录它所有 HTTP 请求。GitHub 应用用 OkHttp，一个稳定的点是：
   `hook_method(package="com.github.android", class="okhttp3.OkHttpClient",
                method="newCall", params="okhttp3.Request")`
2. 然后**请我手动在 GitHub 应用里给某个仓库标星**（随便哪个不重要的仓库，之后取消）。
   我需要主动配合这一步——告诉我该做什么。
3. `hook_records(package="com.github.android", subject="newCall")`
   读回来的参数里会有 URL、method、以及 Authorization 头长什么样。
4. 看完之后 `hook_clear` 摘掉。

这一步能同时告诉你三件事：**确切的端点**、**认证头的形式**、**请求体格式**。

## 第 3 步 · 找到可调用的入口

拿到端点之后，找应用里对应的那个方法。它多半长这样：
- 一个 Retrofit 接口，方法上带 `@PUT("user/starred/{owner}/{repo}")` 之类的注解
- 或者一个 `...Service` / `...Api` 结尾的类

用 `dex_classes` 加上 `with_members: true` 找候选，用 `smali_disassemble` 反汇编确认
签名和注解。

**关键问题：怎么拿到调用者对象。** 这类接口通常不直接 new，而是由某个单例提供，例如
- 一个静态字段：`SomeModule.INSTANCE` / `ServiceLocator.api`
- 一个静态无参方法：`getInstance()` / `provideApiService()`

`invoke_method` 支持指定接收者来源：
- 静态方法：只给 `class` + `method`
- 实例方法：再加 `instance_class` + （`instance_field` 或 `instance_method`）

**如果你找不到提供者**，退一步：直接构造 HTTP 请求自己做。凭据在应用的
AccountManager 里（`AccountManager.get(context).getAuthToken(...)`），而注入的代码
跑在应用进程内，用它自己的 Context 就能拿到。这条路需要写插件
（`plugin_load`），因为要执行多行逻辑。

## 第 4 步 · 调用

```
invoke_method(package="com.github.android",
              class="<你找到的类>",
              method="<标星方法>",
              params="<参数类型，重载时必填>",
              args=["rikkahub","rikkahub"],
              instance_class="<提供者类>",
              instance_field="<静态字段>",   // 或 instance_method
              reason="...")
```

reason 用中文写清楚：这次调用会让 GitHub 应用用它自己的登录凭据给
rikkahub/rikkahub 标星，不改动其他数据。

## 第 5 步 · 验证（不能省）

**调用返回成功不等于标星成功。** 至少要两重确认：

1. 看 `invoke_method` 返回的 `returned` / `returnedClass`——是不是一个表示成功的结果。
   如果是 Retrofit 的 `Call`，它可能只是"请求已构造"，还没发出去。
2. **用界面确认**：`launch_app(package="com.github.android")`，然后
   `ui_dump(mode="a11y")` 走到仓库页，看 Star 按钮是不是变成了 Starred。
   或者简单点：`screen_capture(mode="a11y")` 看截图。

把这两步的实际结果告诉我。

# 规则

- **只对 rikkahub/rikkahub 这一个仓库做这件事。** 不要在别的仓库上试手，
  不要 unstar 任何东西，不要点赞、评论、fork、改设置。
- 这个账号是我的。注入的代码用的是我的凭据，所以它能做的事和你手动能做的一样多——
  正因为如此，范围必须严格限制在上面那句话里。
- 每一步动手前先想清楚为什么。`invoke_method` 的 reason 要能让一个不了解上下文的人
  看懂这次调用会发生什么。
- **同一个思路连续失败三次就停下来**，把你已经查到的（找到的类、方法、请求格式）
  告诉我，问我下一步怎么走。不要在应用里乱试。
- 如果发现接口需要仓库的 GraphQL node id 之类你拿不到的东西，**如实说**，
  不要伪造一个参数硬试。

# 卡住时怎么说

告诉我：查到哪一步、看到的证据是什么、卡在哪个具体问题上。
"找不到"也是一个有用的结论——它说明这条路需要写插件而不是直接调用，那是另一种做法。
不要为了交差而谎报成功。
```

## 这份 demo 在测什么

前两份 demo 用的都是**已经存在的能力**：闹钟那次我事先编译好了插件；无障碍那次只是
看和点。这一份测的是**运行时自己找路**：

- `dex_search` / `smali_disassemble` 做静态定位
- `hook_method` 做动态侦察——**观察目标应用自己的行为，而不是猜它的接口**
- `invoke_method` 做注入——在目标进程里调用任意方法，**不需要编译任何东西**
- `ui_dump` 做交叉验证——注入的结果用另一条路（无障碍）确认

`invoke_method` 是这次新加的，它补上了之前的一个真实缺口：在此之前，要在目标应用里
执行代码就必须有一个插件 DEX，而设备上从零写插件 smali 很难。有了它，"调用目标应用的
某个方法"变成了一次工具调用，而不是一次构建。

## 和"编译器"那个问题有什么关系

之前你问过要不要在桥里加动态编译器。这次正好说明了那个问题的边界：

- **不需要编译**：调用既有方法（`invoke_method`）、改一个方法的行为（`hook_method`）、
  改参数和字段——这些都是数据，不是代码
- **需要编译**：要写**新逻辑**（拼字符串、循环、条件分支、调好几个 API）时，才真的
  需要一门语言

第 3 步里"退一步自己构造 HTTP 请求"就是后者的典型：那不是一次调用，是一段逻辑。
到那一步再考虑写插件（今天要手写 smali），或者——如果这种需求反复出现——才是打包
ECJ+d8 的时候。
