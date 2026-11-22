# 稿仓 GaoCang · 项目规则

Android 原生 Kotlin + Compose，包名 `com.yejing.artvault`。
本文件只含数据契约与开发铁律两部分，供协作者与编码 Agent 遵循，不要凭猜测修改已验证的防护逻辑。

## 一、数据契约（写盘格式，必须严格遵守）

### 文件布局

```
meta/artworks/<ULID>.json     一槽一文件，版本数组内嵌（每版本带自己的 id）
meta/taxonomy.json            世界观 + 角色（version 1 格式）
meta/artists/<id>.json        画师实体：name / aliases[] / homepage
meta/tags.json                标签实体（单文件）：tags[] = { id, name, aliases[] }
meta/folders.json             归档文件夹实体（单文件）：folders[] = { id, name }——导出目录用
meta/avatars/<roleId>.webp    角色头像，固定名覆盖
meta/mirror.json              相册镜像 manifest
meta/artworks/.trash/         删除/合并的原 JSON（.trash/versions/ 是删掉的单个版本）
blobs/<sha前2位>/<sha>.<ext>  原图，内容寻址（导入临时文件在 blobs/.staging/）
thumbs/<sha>.w640.webp        缩略图，平铺
```

### 稿件字段（v2-B 轻实体化）

- `world_id` 单值 / `folder_id` / `character_ids[]` / `tag_ids[]` / `artist_id` / `note` / `versions[].id`
- **世界观 ≠ 归档**：`world_id` 是设定归属（角色可以跨世界观，联动图合法）；`folder_id` 是**人工指定**的
  导出目录。系统相册只认一级子目录，所以归档必须单值，`Mirror` 用它建目录，未分归档落「未分类」。
- **引用一律 id，名字只活在实体上**。稿件里禁止用名字当 key。
- 表单输入名字，保存时由 `ArtistBook/TagBook.ensure()` 当场建实体（内联创建）。
- `vanishingArtistNames` / `followRename` / `LinkSync` 等「名字当 key」的补偿机制已删除，禁止重新引入。

### 写盘规则

- 写入一律先 `.tmp` 再 rename（`writeJsonAtomic`，internal）。**任何失败分支都不能先删原文件**。
- `id` = ULID = 文件名。
- 时间格式 `yyyy-MM-dd[ HH:mm:ss]`，用 `Dates` 宽容解析。
- **价格单位是「元」整数**，不是分。
- 更新用 `updateArtwork()` / `appendVersions()`：只改指定字段 + `updated_at` + `rev`，保留 `versions` 与未知键。
- `received_at_guessed`（App）/ `received_inferred`（PC 老键）都读成 `Art.dateGuessed`；手改日期后必须 `o.remove("received_inferred")`。
- 删除/合并**永远不动 blob**（内容寻址，多槽共享）。
- **稿件 JSON 只从 `ArtworkCommands.commit()` 写**：它负责归一（版本 id / 主图 / 排序 / 去重）+ 补
  `updated_at`/`rev` + 原子写。编辑、批量赋值、合并、版本管理、回收站恢复、删实体清引用全走它。
  唯一例外是合并的回滚（要原样还原，不能过归一）。
- **`0` 是有效价格**（免费稿），只有 `null` 才是「没填」。判断写 `!= null`，永远不要写 `> 0`。
- **删实体必须同时清稿件引用**，键名跟 schema 走：`world_id` / `folder_id` / `artist_id` 是单值（置 `null`），
  `character_ids` / `tag_ids` 是数组（过滤掉）。只在实体层删、忘了清引用 = 悬空引用，界面上只表现为
  「少显示一个标签」，人眼看不出来（`world_ids` 复数键就踩过一次，一个都没清掉）。
- **删除类动作不进操作历史**：删槽（`deleteArts`）和删单个版本都只进回收站（`.trash/`、`.trash/versions/`），
  `VersionScreen` 里只有改名 / 设主图 / 调整顺序才包 `OperationJournal.run`。
  两套都记的话，撤销**不会**去清回收站里那份快照 —— 版本已经回来了、回收站计数还虚高、
  点「恢复」提示成功却什么都没变（走的是去重分支）。2026-11-21 踩过。

## 二、开发铁律（每条都踩过，违反即回退）

### 手势

- `transformable` 会吃单指拖动；详情页改用 `awaitEachGesture`，未放大时不消费任何事件。
- `pointerInput` 的 lambda 只创建一次 → 缩放进度必须用 `MutableState` 现读现写，不能靠参数传入。
- 缩放累积量**不能加阈值吸附**（`if (raw < 1.05) 1f` 会丢帧级增量，永远过不了线）；阈值只用于显示。
- 缩放锚点用 centroid：`offset' = c - (c - offset) * k + pan`。
- `blockPager` 用 `seenMulti` 一直禁到所有手指抬起，否则缩回 1x 抬手会抽搐。
- **详情页任何面板状态下都允许缩放**：`panelExpanded` 不再门禁手势（2026-11-18 撤销了旧限制）。
  当年禁它的理由是「展开时图只剩半屏，放大只会在小窗口里乱跳」，实测不成立 ——
  `clampOffset` 按当前视口算，视口小了可平移范围自然跟着小。**别再照旧理由加回来。**

### 返回键

- `BackHandler` 的 `enabled` 不能依赖「下层页面是否还活着」（写 `detailId == null` 曾在进选择器时无人接管返回 → 直接退出 App）。
- **要「最高优先级吞掉返回键」，`BackHandler` 必须随状态重新注册（放进 `if (...)`），不能常驻注册 + 改 `enabled`。**
  它内部的 `DisposableEffect` key **不含 `enabled`**，常驻的 callback 永远停在首次组合时的位置；
  而 `OnBackPressedDispatcher` 是**后注册的先调用**，页面里后注册的 handler（如多选态的
  `BackHandler(enabled = selecting)`）会把它压过去 —— 表现为「批量写入遮罩下按返回，直接退出多选」
  （2026-11-18 踩过，`Busy.kt` 里有注释）。
- **从多选态打开的二级页，必须在自己文件里再注册一个 `BackHandler { onBack() }`**，别只靠 Root 的 `when` 分支。
  同一条「后注册的先调用」：`BrowseScreen` 的 `BackHandler(enabled = selecting)` 注册得比 Root 晚，
  多选态下它会先吃掉返回键 —— 表现为「批量赋值页按一次只退了多选，页面还开着，要按两次」
  （2026-11-19 `AssignScreen` 踩过）。页面自己注册的节点更晚进入组合，才压得过它；
  `WorldScreen` / `ArtistScreen` / `TrashScreen` / `RoleScreen` / `HistoryScreen` 都是这个写法，
  只有从**非多选态**才能打开的页面（编辑、媒体选择器）可以省。

### 列表

- `LazyVerticalGrid` 会锚定可见项 key，排序后会飞走 → 传 `resetKey` 并 `scrollToItem(0)`。
- 在 Column 里必须 `weight(1f)`，`fillMaxSize()` 会把下方动作条压成 0 高。
- 网格里卸载多选态要用 `DisposableEffect { onDispose { onSelectingChange(false) } }`，否则底栏永久隐藏。
- `LazyColumn` / `LazyVerticalGrid` 的 `content` lambda 是 **LazyListScope，不是 @Composable 作用域**：
  里面只有 `item {}` / `items {}` 的 content 能调 `remember`；要算的东西提到列表外面。

### Root 的 `when` 只渲染一个分支

- 覆盖层互相替换时，**被换掉的那个页面整个离开组合，里面所有 `remember` 状态都会丢**。
- 凡是要跨越「编辑 / 版本管理」活下来的状态，一律提到 Root；**记住按 id，不要按下标**（下标会指到别的稿）。

### 数据读取

- **可空字符串必须走 `JSONObject.strOrNull(key)`**（Library.kt）：Android org.json 的 `optString` 对 JSON `null`
  返回的是**字符串 `"null"`**（不是 `""`）。用 `optString(...).takeIf { it.isNotBlank() }` 会把「没填画师」
  读成 `artist_id="null"` —— 一个指向不存在实体的假引用：详情页显示「档案缺失」、统计里多出一行假画师，
  而人眼完全看不出问题出在哪（2026-11-17 体检抓到，56 件稿全被误判）。

### Compose

- `ChipPill` 的 `onClick` 必须在最后一个参数位（否则尾随 lambda 绑到 `leading`）。
- **别让局部状态和参数同名**：表单里 `var tags` 会遮蔽 `tags: TagBook` 参数；局部变量改叫 `tagStr` / `selTags`。
- **`background` 不拦截指针事件**：全屏覆盖层的空白处会穿透到底下的 tab 页。Root 在覆盖层 `when` 前垫一层 `matchParentSize + pointerInput { detectTapGestures {} }`。
- **Kotlin 块注释可以嵌套**：KDoc 里写 `artworks/*.json` 会开一个嵌套注释 → `Unclosed comment`。注释里别写 `/*`。
- `CheckerBoard` 是 `internal`，跨文件复用。

### 详情面板拖拽（修好过的，别用别的写法）

1. 导航栏留白必须是 `Modifier.padding(bottom = navPad)`，不能是内容里的 Spacer。
2. 测量收敛：`if (it.height > contentH) contentH = it.height`（只增不减取 max），第一遍不给高度约束。
3. 用 `detectVerticalDragGestures`（带 `onDragCancel`），松手吸附阈值 `maxOffset * 0.4f`。

### 改动习惯

- 大段删除 / 替换后**回读函数边界**：少一个 `}` 不会报「括号不匹配」，而是让后面所有符号集体变成
  `Unresolved reference`，报错位置和真正的问题完全无关（删对话框时吃过一次）。

### 与 ponytail 等「精简优先」skill 的边界

- 本文件所有条目都是**踩过坑才留下的**，属于「不可省」清单，不是可优化样板。ponytail 的
  `Deletion beats addition` / `最短 diff 获胜` 只适用于**本次新增代码**，不适用于这些既有写法。
- 尤其别为了行数去动：`.tmp`+rename 与失败分支不动原文件、删实体同时清引用、`strOrNull` 读可空字符串、
  `0` 价格用 `!= null`、详情面板拖拽三步法、缩放 centroid 锚点与不吸附、`seenMulti` 禁 Pager、
  跨编辑状态提到 Root 且按 id 记、`BackHandler` 不依赖下层存活、全屏覆盖层的指针拦截垫层。
- 判断口诀：**这段代码有没有「踩过」的历史？** 有就别省；真要省，先说清它保护的那条规则为什么失效。
- ponytail 的正确用法：简化**新写**的实现，而不是重写已验证的防护；后者的最小改动通常是「一行都不动」。

### 刻意不做的事

- **不唤起第三方 App**（2026-11-21 开了**唯一一个**口子，见下）：
  - 画师主页链接只发 `ACTION_VIEW` 交给系统默认浏览器；别再加包可见性或非浏览器 handler 优先。
  - **例外：已导出到相册的版本 →「在原生图库中打开」。** 同样是裸 `ACTION_VIEW`
    （`content://` + `FLAG_GRANT_READ_URI_PERMISSION`），**不指定包名、不加 `<queries>`、不探测"首选 App"**。
    只对 `meta/mirror.json` 里确实导出过的版本给这个入口；没导出过的不显示（`blobs/` 原图没有媒体库条目，
    也不该把库内路径暴露给外部）。除这一种，别的"跳出去"一律不加。
- 详情页截图里露出下一张图不是 bug（Pager 中间帧），别加 `height(pageHeight)` 之类「修复」。
- `adb screencap` 抓不到动画中间帧，别用它判断动画好坏。
