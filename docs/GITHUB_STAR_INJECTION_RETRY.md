# RikkaHub 复现（第二轮）：修正上一轮注入标星的错误

这是给 **RikkaHub 里的 Agent** 的续测提示词。上一轮它卡在一个**错误结论**上——
"GitHub 应用没登录，没有凭据可用"。那个结论是错的，成因是它自己手写 smali 的
分支极性 bug，不是环境限制。

这一轮测的是**自我纠正**：给它正确的旁证和 bug 类型，看它能不能把路重新走通。

正文在下面的代码块里，整段复制给 Agent（可以接在上一轮会话后面，也可以开新会话）。

## 提示词（整段复制给 Agent）

```text
你的任务不变：用代码注入的方式，让本机的 GitHub 应用（com.github.android）给仓库
rikkahub/rikkahub 标星。不要用界面点击——要调用它自己进程里的代码。约束也不变。

先说最重要的一件事：**你上一轮"应用未登录、没有凭据"的结论是错的。**
应用一直登录着，账号是 yunqinglt。下面的证据是我从设备上独立核对的，不是你的探针产出的：

- 系统 `dumpsys account` 里有 `Account {name=yunqinglt, type=com.github.android}`
- 应用自己的 `shared_prefs/user_preferences.xml` 里写着 `key_accounts=["yunqinglt"]`、
  `key_account_name=yunqinglt`
- `shared_prefs/yunqinglt_preferences.xml` 里有该账号的头像 URL
  （avatars.githubusercontent.com/u/68486916）和 `approved_oauth_scope="user repo ..."`
- `user_preferences.xml` 的修改时间停在 2025-12-30，整个会话期间没变过——
  不存在"中途被登出"这回事

所以"没有凭据"这个阻塞点根本不存在。你是被自己的探针误导了。

# 你上一轮犯的那个 bug —— 这次必须避免

你手写的 GhStar.smali 里，空值守卫的**分支极性写反了**：

    if-nez p0, :done        # 语义是"p0 不为 0 就跳"
    const/4 v0, 0x0         # 于是数组【非空】时直接跳到 :done 返回，什么都不列
    :loop
    array-length v1, p0
    if-lt v0, v1, :done

只要目录里真的有条目，这段代码就立刻返回、输出为空。同样的写反**至少出现在 7 处**：
listInto、listAccounts、dumpAccounts、dumpFiles 的 shared_prefs 守卫、findToken、mask、doStar。
结果探针返回了一条干净的空串、**一次异常都没抛**——而你把这个空串当成了"设备事实"。

记住判据：
- 目标标签是**失败/退出**路径（`:done`、`:none`、`:isnull`、`:notoken`）→ 用 `if-eqz`（为 null 才跳）
- 目标标签是**非空**路径（`:nn`）→ 用 `if-nez`
- 反推：如果那个数组真的是 null，`array-length` 会抛 NullPointerException，而 `doInfo`
  没有 try/catch。你的探针没抛异常 ⇒ 数组非空 ⇒ 数据在，只是被你跳过了。

# 硬性要求：每个探针都要带阳性对照

从现在起，任何"没找到"的结论，必须先排除"探针本身是坏的"。诊断型插件除了报告目标，
还要报告一个**你知道必然存在**的东西，例如：
- `new File("/system/bin/sh").exists()`
- `getFilesDir()` 的列表
- `android.os.Process.myUid()`（应等于应用的 uid）

如果连阳性对照都是空的，正确结论是"探针坏了"，不是"目标不存在"。
另外：账号列表为空**可能**还叠加了 Android 的账号可见性问题，用阳性对照去区分，
不要把两种可能混成一个结论。

# 上一轮已经查清、这次可以直接用的结论

- 应用 **R8 全量混淆**：`okhttp3.OkHttpClient`、`okio` 的类名都被抹掉，
  `RepositoryDetailViewModel` 之类只剩字符串残留。按名字 hook 网络层这条路不通。
- 应用自己的标星走 **GraphQL**（Apollo）：
  `mutation AddStar($id: ID!) { addStar(input: { starrableId: $id }) { ... } }`
  需要仓库的 node id，认证头是 `Authorization: Bearer ...`。
- 应用的 Authenticator（`com.github.service.auth.AuthenticatorService` → 混淆后 `Lpn/b;`）
  是**返回空 Bundle 的桩**。所以 `AccountManager.peekAuthToken(...)` 大概率永远取不到 token，
  **不要把方案建立在它上面**。token 在应用自己的存储里（加密 prefs / DataStore / files /
  自有刷新流程），需要你实探。
- 从**应用进程内**（插件里、用应用的 Context）读写应用自己的数据目录是**可行的**：
  `getDataDir()` 返回 `/data/user/0/com.github.android`，列目录正常。
- 从 **root shell** 读同一目录**不行**：这台设备有应用数据隔离，`su` 的挂载命名空间里
  `/data/user/0` 只剩 `com.google.android.gms` 和 `dev.posedmcp` 两项。
  真要用 root 读，得 `nsenter -t 1 -m -- <命令>` 跳出该命名空间。
  （别再把这条当成"应用没数据"的证据。）
- `com.posedmcp.star.GhStar` 已经加载在 GitHub 进程里，可以直接 `plugin_invoke`，
  改完源码也只需重新 assemble + load 一次。

# 建议的做法

1. 先修好探针（极性 + 阳性对照），用它把**真实的账号、文件树、token 位置**读出来。
2. 拿到 token 后发 REST：`PUT https://api.github.com/user/starred/rikkahub/rikkahub`，
   头 `Authorization: Bearer <token>`，`User-Agent` 必填。若拿不到 token，改走应用自己的
   GraphQL 调用（要先解析出该仓库的 node id）。
3. 验证不能只看返回值：`launch_app` 把应用切到前台，再用
   `screen_capture(mode="a11y")` / `ui_dump(mode="a11y")` 走到仓库页，看 Star 是否变成 Starred。

# 规则（不变）

- 只对 rikkahub/rikkahub 做这一件事。不要 unstar，不要动别的仓库、点赞、评论、改设置。
- 每次动手前想清楚为什么；`invoke_method` / `plugin_*` 的 reason 用中文写清这次调用会发生什么。
- 同一个思路连续失败三次就停下来，把已查到的告诉我，问我下一步怎么走。
- 拿不到必需参数（比如 node id）就如实说，不要伪造参数硬试。
- 不要用界面点击代替注入——这题要的就是在应用进程内调用代码。

# 不用再花调用去查的事实

- 设备：OnePlus PLR110 / Android 16 / arm64-v8a；root 可用。
- 模块已加载进 com.github.android（它在 bridgePeers 里，`moduleLoaded:true`）。
- 无障碍已启用（负责保活，以及免 root 的截图/控件树）。
```

## 这一轮在测什么

上一轮的失败不是"工具不够"，而是**模型对自己产物的信任超过了对环境的怀疑**：它写了一段
有 bug 的诊断代码，拿到空结果后，没有先去质疑那段代码，而是转去用截图找旁证，最后把一个
错误的推断当成硬结论报了出来。

所以这一轮给它的不是更多工具，而是三条纪律：

1. **修正错误结论**——应用是登录的，别再往"没凭据"上走。
2. **认识这个 bug 类型**——手写 smali 的分支极性，`if-nez` / `if-eqz` 用反会静默地什么都不做。
3. **阴性结果要有阳性对照**——"没找到"必须先排除"探针坏了"，否则不构成证据。

如果它能修好探针、读出真实的账号/文件/token，并真的标上星，那说明它具备自我纠正能力；
如果它又绕过这几条、换一种方式自证，那也是有价值的观察。
