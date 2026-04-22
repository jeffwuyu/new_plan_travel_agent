# 项目速查：Travel Agent

## 1. 项目一句话说明

这是一个前后端分离的旅行规划系统：用户提交出行意图后，后端创建任务并驱动一个带状态机的 agent 规划流程，结合 LLM、地图工具、天气与交通信息逐步生成旅行计划，前端通过任务页和流式进度页展示执行过程与最终结果。

## 2. 技术栈与运行依赖

### 后端

- Spring Boot 3.4.4
- Java 21
- MyBatis + XML Mapper
- MySQL
- Redis
- Caffeine
- JWT
- Spring AI Alibaba / Dashscope
- Alibaba OSS
- DashVector
- PDFBox

关键配置集中在：

- `pom.xml`
- `src/main/resources/application.yml`
- 项目根目录 `key.yml`（本地私密配置，来自 `key.example.yml`）

本地运行依赖概览：

- MySQL：默认库名 `travel_agent`
- Redis：默认 `127.0.0.1:6379`
- Dashscope：LLM 与 embedding
- Amap：地理编码、天气、路线/耗时
- OSS：RAG 文档存储
- DashVector：RAG 向量检索

### 前端

- Vue 3
- Vite
- Vue Router
- Pinia
- Axios
- Element Plus

前端入口配置：

- `frontend/package.json`
- `frontend/src/main.js`
- `frontend/src/router/index.js`

## 3. 仓库结构导航

高频目录：

- `src/main/java/com/travelagent`：后端主代码
- `src/main/resources`：Spring 配置、SQL、MyBatis XML
- `src/test/java/com/travelagent`：单测与集成测试
- `frontend/src`：Vue 页面、组件、路由、API 封装、store
- `ops`：MySQL / Redis 备份恢复脚本
- `logs`：运行日志输出目录

后端按职责大致分层：

- `controller`：HTTP 接口入口
- `service`：业务逻辑
- `agent`：任务规划、状态机、工具调用、checkpoint
- `mapper`：MyBatis Mapper 接口
- `model`：实体、DTO、枚举
- `config`：Spring 配置与启动期守卫
- `aop`：限流、配额、幂等切面
- `client`：对接 Dashscope、Amap、DashVector、OSS

前端按职责大致分层：

- `views`：页面
- `components`：通用组件
- `api`：接口调用封装
- `stores`：Pinia 状态
- `composables`：可复用逻辑，如任务流式订阅

## 4. 核心业务主链路

### 任务创建到计划生成

1. 前端在任务页创建规划任务，请求 `POST /api/tasks`。
2. `TaskServiceImpl.createTask(...)` 创建 `task` 记录，并写入初始 `checkpointJson`。
3. 初始 checkpoint 会记录：
   - 用户意图 `userIntent`
   - 目的地 `region`
   - 当前地点查询 `currentLocationQuery`
   - 规划配置 `PlanningConfig`
   - 起点候选 `locationCandidates`
4. 如果起点还没确认，任务进入 `awaiting_user_input`，前端在任务详情页展示候选起点。
5. 用户调用 `POST /api/tasks/{taskUuid}/origin-selection` 确认起点后，任务转入 `resuming`，随后继续规划。
6. `AgentServiceImpl.executeTask(...)` 进入主循环：
   - 通过 `MarkovPlanner.planNextAttraction(...)` 规划下一个景点
   - 调用 `GeocodeTool`
   - 调用 `WeatherTool`
   - 从上一个点或用户选定起点调用 `TrafficTimeTool`
   - 将结果写回 checkpoint，并记录任务进度事件
7. 全部 step 完成后，`persistPlan(...)` 将结果写入 `plans` 和 `plan_steps`。
8. 前端在任务完成后通过 `/api/plans/by-task/{taskUuid}` 获取最终 plan，再进入计划详情页查看完整结果。

### 状态流转关键词

高频任务状态包括：

- `pending`
- `planning`
- `tool_calling`
- `awaiting_user_input`
- `paused`
- `resuming`
- `completed`
- `failed`
- `cancelled`

状态机核心位于：

- `src/main/java/com/travelagent/agent/statemachine/AgentStateMachine.java`
- `src/main/java/com/travelagent/model/enums/TaskStatus.java`

### 流式进度与前端展示

- 后端通过 `SseNotificationService` 推送状态变更、工具结果、step 完成、错误、暂停、完成等事件。
- 前端任务详情页通过 `useTaskStream` 连接 SSE，同时保留轮询兜底。
- 任务页与详情页都会根据任务状态决定是否展示取消、恢复、查看结果等操作。

直接看主链路时，优先读这几个文件：

- `src/main/java/com/travelagent/service/task/impl/TaskServiceImpl.java`
- `src/main/java/com/travelagent/service/agent/impl/AgentServiceImpl.java`
- `frontend/src/views/TaskDetailView.vue`

## 5. 后端模块速查

### 认证与用户

- `AuthController`：注册、登录、登出、注销账号
- `UserController`：用户相关接口
- `JwtAuthInterceptor`：解析 token、注入用户身份
- `UserServiceImpl`：用户注册登录与账号状态逻辑

关键文件：

- `src/main/java/com/travelagent/controller/AuthController.java`
- `src/main/java/com/travelagent/filter/JwtAuthInterceptor.java`
- `src/main/java/com/travelagent/service/user/impl/UserServiceImpl.java`

### 任务与 Agent

- `TaskController`：创建、查询、取消、恢复、确认起点、查询进度
- `TaskServiceImpl`：任务生命周期与 checkpoint 初始化
- `AgentServiceImpl`：规划主循环、工具调度、完成后落库
- `TaskProgressServiceImpl`：任务事件记录

关键文件：

- `src/main/java/com/travelagent/controller/TaskController.java`
- `src/main/java/com/travelagent/service/task/impl/TaskServiceImpl.java`
- `src/main/java/com/travelagent/service/agent/impl/AgentServiceImpl.java`
- `src/main/java/com/travelagent/service/task/impl/TaskProgressServiceImpl.java`

### Planner / Tool / Checkpoint

- `MarkovPlanner`：决定下一个景点与最终总结生成
- `TaskCheckpoint`：任务运行时快照
- `PendingToolCall` / `CompletedStep` / `RetryState`：checkpoint 子结构
- `ToolRegistry`：管理可调用工具
- `GeocodeTool` / `WeatherTool` / `TrafficTimeTool`：外部能力封装

关键文件：

- `src/main/java/com/travelagent/agent/planner/MarkovPlanner.java`
- `src/main/java/com/travelagent/agent/context/TaskCheckpoint.java`
- `src/main/java/com/travelagent/agent/tools/ToolRegistry.java`

### 计划结果

- `PlanController`：查询计划列表、计划详情、步骤详情、按任务查计划
- `PlanMapper`：`plans` 与 `plan_steps` 的数据库访问

关键文件：

- `src/main/java/com/travelagent/controller/PlanController.java`
- `src/main/java/com/travelagent/mapper/PlanMapper.java`
- `src/main/resources/mapper/PlanMapper.xml`

### RAG

- `RagController`：上传文档、注册文档、触发 ingest、查状态、手动 query
- `RagServiceImpl`：文档入库、分块、embedding、写入 DashVector
- `DocumentTextExtractor` 及其实现：PDF / 文本提取
- `OssClient`：文档上传下载

关键文件：

- `src/main/java/com/travelagent/controller/RagController.java`
- `src/main/java/com/travelagent/service/rag/impl/RagServiceImpl.java`
- `src/main/java/com/travelagent/client/oss/OssClient.java`

### 附近 POI 推荐

- `PoiRecommendationController`：`POST /api/recommendations/nearby`
- `NearbyPoiRecommendationServiceImpl`：推荐主服务
- `CandidateRecallServiceImpl`：候选召回
- `CandidateRankingServiceImpl`：候选排序
- `RecommendationExplanationServiceImpl`：推荐解释

关键文件：

- `src/main/java/com/travelagent/controller/PoiRecommendationController.java`
- `src/main/java/com/travelagent/service/recommendation/impl/NearbyPoiRecommendationServiceImpl.java`

### 管理后台、配额与限流

- `AdminController`：用户管理、配额配置、任务列表、运行指标
- `QuotaServiceImpl`：配额校验与扣减
- `RateLimitAspect`：匿名限流
- `QuotaInterceptor`：配额守卫
- `TaskMetricsService`：运行指标快照

关键文件：

- `src/main/java/com/travelagent/controller/AdminController.java`
- `src/main/java/com/travelagent/service/user/impl/QuotaServiceImpl.java`
- `src/main/java/com/travelagent/aop/RateLimitAspect.java`
- `src/main/java/com/travelagent/monitoring/TaskMetricsService.java`

## 6. 前端页面与接口对应

### 路由总览

路由定义在：

- `frontend/src/router/index.js`

主要页面：

- `/login`：登录页 `frontend/src/views/LoginView.vue`
- `/register`：注册页 `frontend/src/views/RegisterView.vue`
- `/tasks`：任务列表页 `frontend/src/views/TasksView.vue`
- `/tasks/:uuid`：任务详情页 `frontend/src/views/TaskDetailView.vue`
- `/plans`：计划列表页 `frontend/src/views/PlansView.vue`
- `/plans/:id`：计划详情页 `frontend/src/views/PlanDetailView.vue`
- `/admin/*`：管理后台

### 页面与后端接口对应

认证相关：

- 前端：`frontend/src/api/auth.js`
- 后端：`/api/auth/*`

任务相关：

- 前端：`frontend/src/api/tasks.js`
- 后端：
  - `POST /api/tasks`
  - `GET /api/tasks`
  - `GET /api/tasks/{taskUuid}`
  - `DELETE /api/tasks/{taskUuid}`
  - `POST /api/tasks/{taskUuid}/resume`
  - `POST /api/tasks/{taskUuid}/origin-selection`
  - `GET /api/tasks/{taskUuid}/progress`

计划相关：

- 前端：`frontend/src/api/plans.js`
- 后端：
  - `GET /api/plans`
  - `GET /api/plans/{planId}`
  - `GET /api/plans/{planId}/steps`
  - `GET /api/plans/by-task/{taskUuid}`

管理后台：

- 前端：`frontend/src/api/admin.js`
- 页面：
  - `frontend/src/views/admin/AdminUsersView.vue`
  - `frontend/src/views/admin/AdminQuotaView.vue`
  - `frontend/src/views/admin/AdminRagView.vue`
  - `frontend/src/views/admin/AdminMetricsView.vue`
- 后端：`/api/admin/*` 与 `/api/rag/*`

### 前端主观感受上最值得先读的页面

如果后续聊天涉及“用户到底看到了什么”，优先读：

- `frontend/src/views/TasksView.vue`
- `frontend/src/views/TaskDetailView.vue`
- `frontend/src/views/PlanDetailView.vue`
- `frontend/src/views/admin/AdminLayout.vue`

其中 `TaskDetailView.vue` 是最关键的前端页面，因为它把：

- SSE 事件展示
- 起点候选选择
- 状态变化展示
- 暂停/恢复/取消
- 任务完成后跳转结果

都串在同一个页面里。

## 7. 数据与外部依赖

### 主要数据表

数据库初始化脚本位于：

- `src/main/resources/sql/schema-runtime.sql`
- `src/main/resources/sql/schema.sql`

按代码阅读，高频表包括：

- `users`
- `tasks`
- `task_execution_events`
- `plans`
- `plan_steps`
- `rag_documents`
- `rag_chunks`
- `user_quota_config`
- `user_quota_usage`
- `llm_call_log`
- `attraction`
- `attraction_recommendation`

### 主要外部服务

- Dashscope：LLM 规划与 embedding
- Amap：地理编码、天气、步行/驾车路线、附近检索
- OSS：RAG 文档原始文件存储
- DashVector：向量检索
- Redis：缓存、黑名单、运行期辅助能力

相关 client 实现：

- `src/main/java/com/travelagent/client/dashscope/DashscopeLlmClient.java`
- `src/main/java/com/travelagent/client/amap/AmapClient.java`
- `src/main/java/com/travelagent/client/dashvector/DashVectorClient.java`
- `src/main/java/com/travelagent/client/oss/OssClient.java`

## 8. 测试与排查入口

### 测试分布

测试主要在：

- `src/test/java/com/travelagent/controller`
- `src/test/java/com/travelagent/service`
- `src/test/java/com/travelagent/integration`
- `src/test/java/com/travelagent/agent`
- `src/test/java/com/travelagent/client`
- `src/test/java/com/travelagent/advisor`
- `src/test/java/com/travelagent/aop`

可以优先看的测试：

- `TaskControllerTest`
- `TaskIntegrationTest`
- `TaskRecoveryIntegrationTest`
- `PlanControllerTest`
- `RagControllerTest`
- `AgentServiceImplTest`
- `MarkovPlannerTest`

### 本地排查高频入口

配置与启动排查：

- `README.md`
- `src/main/resources/application.yml`
- `key.example.yml`
- `docker-compose.yml`

日志与运行排查：

- `src/main/resources/logback.xml`
- `logs/travel-agent.log`
- 仓库根目录若干历史编译/测试日志，如 `test-output.log`、`compile-output.log`

数据库与兼容性排查：

- `src/main/resources/sql/patches/2026-04-21-users-compatibility.sql`
- `src/main/java/com/travelagent/config/LegacySchemaPatchRunner.java`
- `src/main/java/com/travelagent/config/DatabaseSchemaGuard.java`

## 9. 后续聊天建议入口

后续如果要继续聊这个项目，可以直接基于这份文档问我类似问题：

- “任务从创建到 completed 的完整链路在哪几层实现？”
- “`awaiting_user_input` 是在哪里进入、又在哪里恢复的？”
- “前端任务详情页是如何接 SSE 和轮询兜底的？”
- “RAG 文档上传和 ingest 入口分别在哪？”
- “附近 POI 推荐走了哪几个 service？”
- “计划结果是在哪一步落库到 `plans` / `plan_steps` 的？”
- “配额限制和匿名限流分别在哪个模块做？”
- “如果我想改任务状态展示，前后端分别改哪些文件？”

如果时间紧，后续优先把这 3 个文件当成主入口：

- `src/main/java/com/travelagent/service/task/impl/TaskServiceImpl.java`
- `src/main/java/com/travelagent/service/agent/impl/AgentServiceImpl.java`
- `frontend/src/views/TaskDetailView.vue`

这份文档是当前仓库快照。后续如果任务状态、页面路由、RAG 流程或推荐链路发生变化，应该同步更新这里，避免后续聊天建立在过时认知上。
