# Travel Agent 基线记录

- GitHub 基准提交：`cf9487bbffdeda643483f6060253f49b9f2e7f99`
- 当前实现提交：`efa7542465113e3f40f8784db93eb3a366578345`
- 当前工作区改动数：5229（包含本地候选实现、测试、文档和环境文件）
- Java：21
- Spring Boot：3.4.4
- 数据库：MySQL 8 / H2 测试
- Redis：任务租约和分布式派发依赖 Redis

已执行验证：

- `mvn -q -DskipTests compile`：通过
- `mvn -q -DskipTests package`：通过
- `TaskRecoveryIntegrationTest`：5/5 通过
- `TaskLifecycleGovernanceServiceTest`：3/3 通过
- `RedisTaskLockServiceTest`：5/5 通过
- `RagServiceImplTest`：17/17 通过

本记录只证明当前候选工作区的构建和测试结果，不把候选实现追溯为 GitHub 基准已验收能力。
