# 稿仓 GaoCang

面向约稿收藏与整理场景的 Android 原生离线稿件管理工具（Kotlin + Jetpack Compose）。  
所有元数据与原图均以开放的本地文件格式保存在用户自选目录，**零联网、无账号、无云端绑定**，天然适配 Syncthing 等跨设备同步方案。

## 核心功能

- **多版本稿件槽位**：同一约稿的带背景 / 透明底 / 线稿 / 拆分图归入同一槽位，支持主图指定、版本排序与无损回收站恢复。
- **实体化分类体系**：世界观、角色（含自定义头像与附属/宠物分摊排除）、画师（别名与主页外链）、标签、导出文件夹均按 ID 关联，重命名与合并自动同步。
- **高效整理工作流**：
  - 稿库按月分组 + 长按滑动连选，支持批量赋值、合并为多版本、导出与删除；
  - 内置「待整理」缺口筛查（缺画师 / 角色 / 世界观 / 文件夹 / 价格 / 标题 / 版名 / 矫时）与「上次整理」快速定位标记；
  - 衍生制品 / 制图与原稿双向关联。
- **账本与分摊统计**：按月/年度支出统计、画师维度统计、角色页人均分摊合计（支持将附属角色/宠物排除在分母外）。
- **相册镜像导出**：按文件夹分类、按自定义命名模板（日期 / 角色 / 标题 / 版本）一键镜像导出到系统图库可浏览目录，并支持哈希校验。
- **操作日志与一致性体检**：内容编辑支持多步撤销/重做；删除操作进本地回收站；内置库一致性体检工具。

## 数据目录结构

在「我的 → 更换库目录」中可选择已有目录，或在任意可写目录下点击「新建稿仓」初始化：

```text
<VaultRoot>/
├── meta/
│   ├── artworks/<ULID>.json    # 一槽一文件，内嵌 versions[]
│   ├── taxonomy.json           # 世界观与角色定义
│   ├── artists/<id>.json       # 画师实体（name / aliases / homepage）
│   ├── tags.json               # 标签实体
│   ├── folders.json            # 导出文件夹实体
│   ├── avatars/<roleId>.webp   # 角色头像
│   ├── mirror.json             # 相册镜像 manifest
│   └── artworks/.trash/        # 回收站（整槽与单版本快照）
├── blobs/<sha前2位>/<sha>.<ext> # 内容寻址原图（含 .nomedia）
└── thumbs/<sha>.w640.webp      # 缩略图缓存（含 .nomedia）
```

## 隐私与权限说明

- **无联网权限**：`AndroidManifest.xml` 未申请 `INTERNET` 权限，所有读写均发生在设备本地。
- **所有文件访问权限（`MANAGE_EXTERNAL_STORAGE`）**：仅用于读写用户自选的本地稿仓目录及导出相册镜像，方便跨设备同步与备份。
- **无障碍服务（可选，`ScreenSenseService`）**：用于辅助提取米画师约稿文字信息（仅适配米画师「我的稿件 → 点进某张稿件 → 更多」弹出的详细信息）。仅在用户手动点击悬浮球时读取一次当前屏幕文本，不监听键盘输入、不后台采集、不上传数据。

## 构建指南

环境要求：JDK 17 + Android SDK Platform 37（`sdkmanager "platforms;android-37.2"`）；依赖最低要求 `compileSdk` 37，不能用 35/36 编译。

```bash
# 构建 Debug 包（包名 com.yejing.artvault.oss，可与正式版并存）
./gradlew :app:assembleDebug

# 运行单元测试
./gradlew :app:testDebugUnitTest

# 构建 Release 包（可选通过环境变量或 local.properties 传入签名配置）
./gradlew :app:assembleRelease
```

Release 签名支持在 `local.properties` 或环境变量中配置：`RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。未配置时默认回退 debug 签名以便本地验证。

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。
