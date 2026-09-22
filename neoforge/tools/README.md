# 工具入口

工具用法、输入格式、限制和验证范围统一见 [测试与审计](../../docs/development/testing.md#tools)。
故障排查见 [调试指南](../../docs/development/debugging.md)。

## 分层规划预览

`PlanningRedesignPreview <profile.json> <plan-directory> <output-directory>` 读取冻结生产计划，输出温湿度图、道路俯视图，
并调用生产 RoadPlanner 为合成陡崖生成栈道 JSON 与侧视图。它不生成 Minecraft 截图，也不证明客户端可视外观。
通过[审计运行器](run-audit-tool.sh)编译运行，产物位于指定输出目录。
