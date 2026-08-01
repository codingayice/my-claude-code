# Veyra

Veyra 是一个面向桌面端智能助手的 Agent Harness：负责模型接入、会话循环、工具执行、权限控制、上下文压缩、长期记忆、子 Agent 协作、任务恢复与本地工作区交互。

该仓库以 Harness 为核心，不包含金融或其他垂直业务模块。

## 核心模块

- Agent Runtime：模型调用、工具循环、流式事件与停止控制
- Tool Harness：工具注册、参数校验、执行结果与错误边界
- Context Engineering：轮次预算、微压缩、自动压缩与压缩后恢复
- Memory：长期记忆提取、索引、召回与上下文注入
- Permission：危险操作分级、授权策略与执行审计
- Multi-Agent：子 Agent 生命周期、任务委派与结果回传
- Session：会话持久化、恢复与运行状态管理
- Desktop：React + Tauri 桌面壳层和本地工作区交互

## 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 20+
- Rust stable（构建 Tauri 客户端时需要）

模型密钥通过环境变量提供：

```powershell
$env:DEEPSEEK_API_KEY = "你的密钥"
```

## 后端

```powershell
mvn -s .codex-maven-settings.xml test
mvn -s .codex-maven-settings.xml package
java -jar target/veyra-1.0-SNAPSHOT.jar --http --port 17361
```

桌面客户端启动 Agent 前，还需要生成依赖类路径：

```powershell
mvn -s .codex-maven-settings.xml dependency:build-classpath "-Dmdep.outputFile=target/classpath.txt"
```

## 桌面端

```powershell
cd veyra-desktop
npm ci
npm test
npm run typecheck
npm run tauri dev
```

本仓库是从 Veyra 当前实现提取出的全新本地基线，Git 历史从单一初始化提交开始。
