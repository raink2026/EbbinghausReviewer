# 🧠 Ebbinghaus Review (艾宾浩斯复习助手)

> 一个基于艾宾浩斯遗忘曲线的现代化 Android 记忆辅助工具。
> "遗忘是记忆的天敌，而重复是记忆的朋友。"

本项目采用 2025 年主流 Android 技术栈（Kotlin + Compose + Room + MVVM）构建，旨在提供一个科学、高效、体验优雅的复习管理方案。

---

## 📦 模块功能详解 (Features)

### 1. 核心复习系统 (Core Review System)
这是 App 的“大脑”，负责接管你的记忆规划。
*   **科学算法**：内置标准艾宾浩斯 8 阶段复习曲线 (1天, 2天, 4天, 7天, 15天, 30天, 60天, 120天)。
*   **智能防刷机制**：
    *   系统会自动识别“今日已完成”的任务。
    *   如果当前知识点的下次复习时间未到（即今天已打卡），复习页面的按钮会自动变灰（不可点击），防止因重复点击导致算法进度错乱。
*   **复习决策**：
    *   **完成 (Done)**：确认为“记得”，系统自动将阶段 +1，并计算下一次复习时间。
    *   **放弃 (Skip)**：暂不复习，保持当前进度不变，直接返回列表。

### 2. 每日计划与防拖延 (Daily Plan & Anti-Procrastination)
不仅仅是复习，更是你的每日学习管家。
*   **待办清单 (To-Do)**：支持快速添加每日学习计划，勾选完成会有删除线反馈。
*   **旧账清理机制**：
    *   每天打开 App 时，系统会自动检查昨日未完成的任务。
    *   **强制决策**：必须选择“顺延到今天”或“彻底放弃”，防止任务无限堆积产生焦虑。
*   **进度反馈**：顶部实时显示今日完成度进度条，激励你完成所有计划。

### 3. 数据统计与热力图 (Statistics & Heatmap)
可视化你的努力，让坚持看得见。
*   **月度热力图**：类似 GitHub 的贡献图，颜色越深代表当天完成的任务越多。
*   **成就反馈**：统计本月“完美天数”（高产出日），给予正向反馈。
*   **历史回溯**：支持切换月份查看过往的学习记录。

### 4. 知识管理与录入 (Knowledge Management)
*   **富媒体支持**：
    *   **图文混排**：支持标题、详情内容、备注描述。
    *   **多图上传**：支持从相册批量选择多张图片，在详情页可横向滑动浏览。
*   **全屏图片查看器**：
    *   在复习页点击图片可进入全屏沉浸模式。
    *   支持**双指缩放 (Pinch-to-Zoom)** 和拖动查看高清细节。
*   **历史回溯**：
    *   在列表页**长按卡片**，弹出复习历史日志。
    *   清晰展示该知识点的每一次复习时间、操作结果（记得/忘了）及阶段变化。

### 5. 多用户与个性化 (User Profile & Personalization)
支持多用户切换，满足不同场景或不同用户的需求。
*   **多账户体系**：支持创建多个用户档案，数据相互隔离（基于 Room 数据库设计）。
*   **个性化设置**：支持切换“深邃星空”等不同风格的壁纸背景。
*   **数据管理**：
    *   **回收站**：软删除机制，支持一键恢复或彻底删除，15天自动清理。
    *   **导入导出**：支持 JSON 格式全量备份与恢复。

### 6. 首页看板 (Dashboard)
*   **分栏展示**：
    *   **待复习**：实时展示截止当前需要复习的任务。
    *   **今日已学**：展示今天新创建的任务 + 今天已完成复习的任务，给予正向反馈。
*   **交互优化**：
    *   **左滑删除**：支持侧滑删除手势，配有二次确认弹窗，防止误触。

### 7. 智能规划日历 (Smart Calendar)
*   **未来推演**：不同于普通日历只看历史，本系统的日历支持**复习计划推演**。
*   **逻辑**：点击日历上的未来某一天（例如 7 天后），系统会基于当前所有任务的进度，**实时推算**出那一天将会掉落哪些复习任务，帮助用户提前规划时间。
*   **热力标记**：日历上会自动标记有复习计划的日期。

### 8. 系统级集成 (System Integration)
*   **桌面小部件 (Widget)**：
    *   无需打开 App，在手机桌面即可查看“待复习”数量。
    *   数据实时同步更新。
*   **后台提醒**：基于 WorkManager 的周期性任务，每 15 分钟检查一次到期任务并发送通知。

---

## 🏗 实现思路与技术细节 (Implementation)

### 1. 架构设计 (Architecture)
采用标准的 **MVVM (Model-View-ViewModel)** 架构：
*   **UI Layer**: 完全基于 **Jetpack Compose** (Material Design 3)。无 XML 布局。
*   **ViewModel**: 承载业务逻辑（如算法计算、状态流转），使用 `StateFlow` 驱动 UI 响应式更新。
*   **Data Layer**: 使用 **Room** 作为本地 SQLite 数据库封装，提供 DAO 接口。

### 2. 数据库模型 (Schema)
*   **ReviewItem 表**：核心复习数据。
*   **PlanItem 表**：每日计划数据，包含状态（TODO/DONE/GIVEN_UP）和日期。
*   **User 表**：用户档案数据。
*   **ReviewLog 表**：复习行为日志。

### 3. 关键算法逻辑 (Algorithms)
*   **日历推演 (Calendar Projection)**：内存计算模拟未来复习节点。
*   **防刷逻辑**：UI 层判断 `nextReviewTime` 防止重复打卡。
*   **热力图计算**：聚合查询每日完成任务数，映射到颜色等级。

### 4. 交互实现 (UI Interactions)
*   **手势冲突处理**：`SwipeToDismissBox` 与 Dialog 的状态协同。
*   **纯 Compose 图片缩放**：`detectTransformGestures` + `graphicsLayer`。

---

## 🛠 技术栈 (Tech Stack)

*   **Language**: Kotlin
*   **UI**: Jetpack Compose (Material3)
*   **Architecture**: MVVM + ViewModel
*   **Database**: Room (SQLite)
*   **Concurrency**: Kotlin Coroutines + Flow
*   **Background**: WorkManager
*   **Image Loading**: Coil
*   **Serialization**: Gson (JSON Export/Import)

---

## 📝 更新日志 (Changelog)

### v1.2.1 (Optimization Update)
- **Refactoring & Optimization**:
  - **UI Modularization**: 拆分了臃肿的 `Screens.kt`，将 UI 组件重构为独立文件 (`HomeScreen`, `ReviewScreen` 等)，提升代码可维护性。
  - **Internationalization**: 提取了所有硬编码字符串到 `strings.xml`，规范化资源管理。
  - **Performance**: 迁移底层时间计算逻辑至 `java.time` (Android API 26+)，移除过时的 `Calendar` API，提升性能与代码可读性。

### v1.1.0 (Feature Update)
- **New Features**:
  - **每日计划 (Daily Plan)**: 新增待办清单功能，支持每日任务管理与进度追踪。
  - **防拖延机制**: 新增“昨日旧账”清理弹窗，强制决策未完成任务。
  - **数据统计 (Statistics)**: 新增月度热力图与完美天数统计。
  - **多用户支持 (Multi-User)**: 支持创建和切换不同用户档案。
  - **个性化**: 支持更换应用背景壁纸。

### v1.0.0 (First Release)
- **Core Features**:
  - 实现了完整的艾宾浩斯遗忘曲线算法 (8个阶段)。
  - 首页看板：支持“待复习”与“今日已学”任务的实时展示。
  - 知识录入：支持图文混排、多图上传。
  - 知识详情与复习：支持全屏图片浏览、双指缩放。
  - 历史记录：长按列表项可查看详细复习日志。
- **Advanced Features**:
  - 智能日历：支持基于当前任务推演未来的复习计划，带有热力图标记。
  - 回收站：支持软删除、一键恢复及自动清理过期项目。
  - 数据备份：支持 JSON 格式的全量导入与导出。
- **System Integration**:
  - 桌面 Widget：实时显示待复习数量。
  - 后台提醒：每 15 分钟检查并发送复习通知。

---
*Designed with ❤️ for lifelong learners.*
