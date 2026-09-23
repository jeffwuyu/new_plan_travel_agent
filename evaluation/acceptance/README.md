# E01 验收报告目录

每次验收运行使用唯一 `runId`，结果保存到：

```text
reports/acceptance/<runId>/<module>/<caseId>/
```

每个用例必须包含 `result.json`、`command.txt` 和与断言有关的脱敏日志或快照。没有执行的用例使用 `BLOCKED`，不能填写 `PASS`。

`result.json` 最少包含 `caseId`、`status`、`commit`、`environment`、`startedAt`、`finishedAt`、`expected`、`actual` 和 `artifacts`。
