# TracePilot

Java预约与FIFO候补后端，以及基于真实日志、指标与版本代码的只读诊断Agent。用于展示事务并发控制、可靠事件、可观测性、Agent任务编排与可追溯评估。

## 技术与目录

| 模块 | 说明 |
|---|---|
| `business-service/` | Java21 / Spring Boot3.5.16 / MySQL / Flyway；登录、资源时段、预约、取消、候补、通知与观测 |
| `refactor/diagnosis-service/` | 当前诊断实现，Spring AI1.1.8；只读工具、预算、任务租约、检查点、取消及报告证据 |
| `web/` | Node.js22原生HTTP服务与浏览器工作台，无第三方前端运行依赖 |
| `deploy/`、`scripts/` | 源码构建容器配置、随机本地凭据生成、业务演示 |
| `docs/` | 架构、安全边界、验证结果与演示 |

业务是模块化单体；诊断独立运行，不执行SQL修改、代码变更或服务重启。诊断工作器默认暂停，没有配置任何远程模型密钥。

## 本地启动

需要Docker Desktop（Linux容器）、PowerShell7和Node.js22。源码构建需要联网下载官方镜像与Maven依赖，首次启动会迁移新的独立数据库卷。

```powershell
./scripts/configure-local.ps1
docker compose --env-file deploy/local.env -f deploy/compose.yml up -d --build
./scripts/workbench.ps1
```

打开 http://127.0.0.1:3380 。业务8382、诊断8383、MySQL3348；与原开发环境及求职交付包端口分开。演示账号`admin`、`user1`至`user200`，密码`Demo-pass-123`。这是显式demo种子，不是个人凭据，仅用于本地环境。

```powershell
./scripts/demo.ps1 -BaseUrl http://127.0.0.1:8382
# 用Ctrl+C结束前台工作台，再停止后端；保留数据
docker compose --env-file deploy/local.env -f deploy/compose.yml stop
```

重复运行demo会创建少量新数据，不清表。不要删除数据库卷后冒充恢复，也不要丢失local.env后生成不同密码连接旧卷。

该GitHub快照包含源码，不包含原固定JAR或数据库。源码构建的`source-build`标识不是原业务JAR哈希；默认不导出原固定版本代码，因此不能把新环境称为已完成原诊断效果评估。

## 测试

```powershell
cd web
node --test
```

Java源码可使用JDK21和Maven3.9.11分别构建：

```powershell
mvn -f business-service/pom.xml -DskipTests package
mvn -f refactor/diagnosis-service/pom.xml -DskipTests package
```

`skipTests`仅构建，不能算测试通过。真实MySQL集成测试必须使用专属测试schema，会清理测试表；不得连接演示或个人数据库运行。部分真实模型探测按条件跳过，不自动调用供应商。

## 设计亮点与限制

- 幂等记录、预约名额、FIFO补位与outbox在事务边界内维护一致性，数据库约束作为额外防线。
- 至少一次消费尝试结合通知event_id唯一键实现效果幂等，不宣称消息传输exactly-once。
- 诊断按任务限制只读工具、服务与窗口、结果大小、调用预算和执行时间；保存证据与结构化状态，不保存私有思维链。
- 报告引用存在不等于因果正确，COMPLETED不等于效果验收通过。历史效果失败保留在本地档案，本仓库不声称生产准确率或最终M5通过。

阅读[架构](docs/Architecture.md)、[验证摘要](docs/Validation.md)、[安全说明](SECURITY.md)、[七分钟演示](docs/Demo.md)。

## 上传内容与隐私

本仓库是经过筛选的源码快照，不是整个开发硬盘目录。没有上传`.local`、`.tools`、数据库文件、实际环境配置、API key、会话令牌、历次原始模型采证、私有评估答案或供应商排查资料。自动化测试中的`example.com`邮箱、sentinel和test-token属于非真实测试样例。新增配置应通过环境变量或GitHub Secrets提供，不提交到源码。
