# ThoughtCoding CLI

![ThoughtCoding CLI](picture.png)

一个基于 LangChain4j 的交互式代码助手 CLI 工具，支持原生 Function Calling、流式输出和智能对话。

## 🎥 项目演示

[![观看演示视频](https://img.shields.io/badge/观看演示视频-red?style=for-the-badge&logo=bilibili)](https://www.bilibili.com/video/BV14D4uzWEhC?vd_source=5a2abdf9d1d2a4d1cb15fa9b92f6fbb2)

*点击按钮前往bilibili观看完整项目演示*

## 🚀 项目特性

- **智能对话** - 基于多种 AI 模型的智能代码助手
- **流式输出** - 支持实时流式响应，提供更好的交互体验
- **原生 Function Calling** - 基于 LangChain4j 原生 function calling 的多轮 agentic 循环
- **MCP 集成** - 内置 Model Context Protocol 支持，可连接丰富的工具生态系统
- **工具扩展** - 通过 MCP 支持文件管理、数据库操作、搜索、GitHub 等 50+ 种工具
- **动态工具发现** - 自动发现和注册 MCP 服务器的可用工具
- **即插即用** - 无需重启即可动态连接新的 MCP 服务器
- **预定义工具** - 内置常用 MCP 工具快捷方式，一键连接
- **配置管理** - 灵活的 YAML 配置文件系统，支持 MCP 服务器动态配置
- **类型安全** - 完整的 Java 类型定义和封装
- **终端 UI** - 基于 JLine + ANSI 颜色的现代化终端界面
- **会话管理** - 会话保存、加载和会话继续功能
- **上下文管理** - 智能上下文窗口管理，Token 优化，支持滑动窗口策略
- **项目感知** - 自动检测项目类型（Maven/Gradle/NPM），提供项目上下文
- **工具确认** - 工具执行前用户确认机制，只读工具静默放行，提高安全性
- **性能监控** - 内置性能监控和 Token 使用统计
- **智能搜索** - 具备文件搜索和文件内容搜索能力
- **跨平台支持** - 支持 Windows、Linux、macOS 系统

## 🏗项目结构

```
ThoughtCoding/
├── 📁 src/main/java/com/thoughtcoding/
│   ├── 📁 cli/                          # 🎯 命令行接口
│   │   ├── ThoughtCodingCommand.java    # 主命令处理器
│   │   ├── SessionCommand.java          # 会话管理命令
│   │   ├── ConfigCommand.java           # 配置管理命令
│   │   └── MCPCommand.java              # MCP 管理命令
│   ├── 📁 core/                         # 🔧 核心功能
│   │   ├── ThoughtCodingContext.java    # 应用上下文
│   │   ├── AgentLoop.java               # Agent 循环引擎（原生 function calling）
│   │   ├── StreamingOutput.java         # 流式输出处理
│   │   ├── ProjectContext.java          # 项目上下文检测
│   │   ├── ToolExecutionConfirmation.java # 工具执行确认（YES/NO 两选项）
│   │   └── DirectCommandExecutor.java   # 直接命令执行器
│   ├── 📁 service/                      # 🛠️ 服务层
│   │   ├── LangChainService.java        # AI 服务核心
│   │   ├── SessionService.java          # 会话数据管理
│   │   ├── AIService.java               # AI 服务接口
│   │   ├── ContextManager.java          # 上下文管理器（历史窗口、Token控制）
│   │   └── PerformanceMonitor.java      # 性能监控
│   ├── 📁 tools/                        # 🔨 工具集合（扁平结构）
│   │   ├── BaseTool.java                # 工具基类（name/description/schema/isReadOnly）
│   │   ├── ToolRegistry.java            # 工具注册中心
│   │   ├── ToolDispatcher.java          # 工具执行唯一收口（沙箱插桩点）
│   │   ├── ToolSpecificationFactory.java # ToolSpecification 工厂（内置+MCP统一转换）
│   │   ├── BashTool.java                # 命令执行（Windows→PowerShell, Linux/Mac→bash）
│   │   ├── ReadTool.java                # 文件读取（cat -n 风格行号输出）
│   │   ├── WriteTool.java               # 文件创建/覆写
│   │   ├── EditTool.java                # 精确字符串替换（\r\n 自动规范化）
│   │   └── GlobTool.java                # 文件搜索（walkFileTree，跳过 node_modules）
│   ├── 📁 mcp/                          # 🔌 MCP 功能模块
│   │   ├── MCPService.java              # MCP 服务管理器
│   │   ├── MCPClient.java               # MCP 客户端
│   │   ├── MCPToolManager.java          # MCP 工具管理器
│   │   └── 📁 model/                    # MCP 协议数据模型
│   │       ├── MCPRequest.java          # MCP 请求
│   │       ├── MCPResponse.java         # MCP 响应
│   │       ├── MCPError.java            # MCP 错误
│   │       ├── MCPTool.java             # MCP 工具定义
│   │       └── InputSchema.java         # 输入模式定义
│   ├── 📁 ui/                           # 🎨 用户界面
│   │   ├── ThoughtCodingUI.java         # UI 主控制器
│   │   ├── TerminalManager.java         # 终端管理
│   │   ├── AnsiColors.java              # ANSI 颜色工具
│   │   └── 📁 component/                # UI 组件
│   │       ├── ChatRenderer.java        # 聊天渲染器
│   │       ├── InputHandler.java        # 输入处理器
│   │       ├── ProgressIndicator.java   # 进度指示器
│   │       └── StatusBar.java           # 状态栏
│   ├── 📁 config/                       # ⚙️ 配置管理
│   │   ├── AppConfig.java               # 应用配置
│   │   ├── ConfigLoader.java            # 配置加载器
│   │   ├── ConfigManager.java           # 配置管理器
│   │   ├── MCPConfig.java               # MCP 配置模型
│   │   └── MCPServerConfig.java         # MCP 服务器配置
│   ├── 📁 model/                        # 📊 通用数据模型
│   │   ├── ChatMessage.java             # 聊天消息
│   │   ├── SessionData.java             # 会话数据
│   │   ├── ToolCall.java                # 工具调用
│   │   ├── ToolCallRef.java             # 工具调用引用
│   │   ├── ToolExecution.java           # 工具执行记录
│   │   ├── ToolResult.java              # 工具结果
│   │   └── ModelConfig.java             # 模型配置
│   └── 📁 util/                         # 🛠️ 工具类
│       ├── JsonUtils.java               # JSON 工具
│       ├── FileUtils.java               # 文件工具
│       ├── StreamUtils.java             # 流工具
│       └── ConsoleUtils.java            # 控制台工具
├── 📁 bin/                              # 🚀 启动脚本
│   ├── thought                         # Linux/macOS 脚本
│   └── thought.bat                     # Windows 脚本
├── 📁 sessions/                         # 💾 会话存储
├── 📜 pom.xml                          # Maven 配置
└── 📖 README.md                        # 项目说明
```

## 📁 模块说明

### `src/main/java/com/thoughtcoding/ThoughtCodingCLI.java` - CLI 入口

**功能**: 命令行界面入口，处理应用启动

**特性**:

- 初始化应用上下文和配置
- 设置命令行参数解析
- 启动主命令执行流程

### `src/main/java/com/thoughtcoding/cli/` - 命令行处理

**功能**: 管理所有 CLI 命令和参数解析

`SessionCommand.java`

- **功能**：会话管理命令类
- **特性**：支持会话列表、加载、删除等操作

`ConfigCommand.java`

- **功能**：配置管理命令类
- **特性**：支持配置查看、设置、重置等操作

`MCPCommand.java`

- **功能**：MCP 管理命令类
- **特性**：支持 MCP 服务器连接、断开、列表查看、预定义工具快捷连接

`ThoughtCodingCommand.java`

**特性**:

- 支持交互式模式 (`-i, --interactive`)
- 支持继续上次会话 (`-c, --continue`)
- 支持指定会话 (`-S, --session`)
- 支持单次提问 (`-p, --prompt`)
- 支持模型选择 (`-m, --model`)
- 会话管理功能 (列表、删除会话)

### `src/main/java/com/thoughtcoding/config/` - 配置管理

**功能**: 管理应用配置

`AppConfig.java`

- **功能**：应用配置类

`ConfigLoader.java`

- **功能**：配置加载器
- **特性**：自动读取 `config.yaml` 文件

`ConfigManager.java`

- **功能**：配置管理器（单例模式）
- **特性**：全局唯一配置实例，支持动态加载和热更新

`MCPConfig.java`

- **功能**：MCP 配置模型
- **特性**：定义 MCP 功能模块的配置结构

`MCPServerConfig.java`

- **功能**：MCP 服务器配置
- **特性**：定义单个 MCP 服务器的配置项（名称、命令、参数等）

### `src/main/java/com/thoughtcoding/model/` - 通用数据模型

**功能**: 集中管理通用的数据模型和类型定义（独立包，与 `mcp/model/` 不同）

**注意**: 此 `model/` 包是独立的通用数据模型包，与 `mcp/model/`（MCP 协议专用数据模型）是并列关系。

**主要类**:

- `ChatMessage.java` - 聊天消息模型
- `ModelConfig.java` - 模型配置
- `SessionData.java` - 会话数据
- `ToolCall.java` - 工具调用
- `ToolCallRef.java` - 工具调用引用
- `ToolExecution.java` - 工具执行记录
- `ToolResult.java` - 工具结果

### `src/main/java/com/thoughtcoding/core/` - 核心功能

**功能**: 提供核心业务逻辑

`ThoughtCodingContext.java`

- **功能**：应用上下文容器（依赖注入）
- **特性**：统一管理所有服务组件，提供全局访问入口

`AgentLoop.java`

- **功能**：Agent 循环引擎
- **特性**：基于 LangChain4j 原生 function calling 实现多轮 agentic 循环，工具结果自动回喂模型，支持工具执行确认

`StreamingOutput.java`

- **功能**：流式输出处理类
- **特性**：Token-by-Token 实时输出，优化用户体验

`ProjectContext.java`

- **功能**：项目上下文检测
- **特性**：自动识别项目类型（Maven/Gradle/NPM等），提供项目相关信息

`ToolExecutionConfirmation.java`

- **功能**：工具执行确认组件
- **特性**：写/执行类工具执行前展示工具名和参数，YES/NO 两选项确认；只读工具（read、glob）静默放行

`DirectCommandExecutor.java`

- **功能**：直接命令执行器
- **特性**：支持直接执行系统命令，无需通过工具调用

### `src/main/java/com/thoughtcoding/service/` - 服务层

**功能**: 业务逻辑和服务实现

**主要服务**:

- `LangChainService.java` - AI 服务核心实现
  - **特性**：集成 LangChain4j，支持流式响应和工具调用
- `SessionService.java` - 会话数据管理
  - **特性**：会话持久化、加载、自动保存
- `AIService.java` - AI 服务接口
  - **特性**：定义统一的 AI 服务接口，支持多模型切换
- `ContextManager.java` - 上下文管理器
  - **特性**：管理对话历史窗口，控制 Token 使用，实现滑动窗口策略
- `PerformanceMonitor.java` - 性能监控
  - **特性**：Token 使用统计、执行时间监控、性能指标收集

### `src/main/java/com/thoughtcoding/tools/` - 工具集合

**功能**: 内置工具的实现，统一继承 `BaseTool` 基类。

`BaseTool.java`

- **功能**：工具抽象基类
- **特性**：定义 `name`、`description`、`inputSchema()`、`isReadOnly()` 等标准接口；`execute(String)` 接收 JSON 参数

`ToolRegistry.java`

- **功能**：工具注册中心
- **特性**：统一管理内置工具与 MCP 工具，提供按名查找和 ToolSpecification 列表生成

`ToolDispatcher.java`

- **功能**：工具执行唯一收口
- **特性**：所有工具调用经此分发，作为沙箱插桩点（未来 workspace 边界检查在此一处即可覆盖 100% 写/执行操作）

`ToolSpecificationFactory.java`

- **功能**：将 BaseTool 转换为 LangChain4j 原生 ToolSpecification
- **特性**：内置工具用 `inputSchema()` 自带 schema，MCP 工具从原始 JSON schema 转换，失败时兜底通用 schema

**内置工具**:

- **BashTool** - 命令执行，Windows 自动切换 PowerShell，Linux/Mac 使用 bash
- **ReadTool** - 文件读取，`cat -n` 风格带行号输出，只读工具无需确认
- **WriteTool** - 文件创建/覆写，自动创建父目录
- **EditTool** - 精确字符串替换，自动规范化 `\r\n` → `\n`（兼容 Windows 文件）
- **GlobTool** - 文件搜索，基于 `walkFileTree` 跨平台实现，跳过 `node_modules`，只读工具无需确认

**扩展性**: 继承 `BaseTool` 并注册到 `ToolRegistry` 即可添加新工具。

### `src/main/java/com/thoughtcoding/mcp/` - MCP 功能

**功能**: 实现 Model Context Protocol 客户端功能，连接和管理外部 MCP 服务器

`MCPService.java` - MCP 服务管理器

- **功能**: MCP 服务的核心管理器
- **特性**: 管理多个 MCP 服务器连接，统一工具注册

`MCPClient.java` - MCP 客户端

- **功能**: 单个 MCP 服务器的客户端实现
- **特性**: JSON-RPC 通信，进程管理，错误处理

`MCPToolManager.java` - MCP 工具管理器

- **功能**: 管理所有 MCP 工具的统一入口
- **特性**: 工具发现、注册、调用路由

**`mcp/model/`** - MCP 协议数据模型

**功能**: 定义 MCP 协议的数据结构和类型（位于 `mcp` 包下的子包）

- `MCPRequest.java` - MCP 请求模型
- `MCPResponse.java` - MCP 响应模型
- `MCPError.java` - MCP 错误模型
- `MCPTool.java` - MCP 工具定义
- `InputSchema.java` - 输入模式定义

**注意**: `mcp/model/` 是 MCP 协议专用的数据模型，与独立的 `model/` 包（通用数据模型）不同。

### `src/main/java/com/thoughtcoding/ui/` - 用户界面

**功能**: 终端用户界面管理

**主要组件**:

`ThoughtCodingUI.java`

- **功能**：UI 主类

`TerminalManager.java`

- **功能**：终端管理器

`AnsiColors.java`

- **功能**：ANSI 颜色工具类

**`component/`**

- **`ChatRenderer.java`**：聊天渲染器
  - **特性**：实时渲染 AI 响应，支持代码高亮
- **`InputHandler.java`**：输入处理器
  - **特性**：处理用户输入，支持命令补全和历史记录
- **`ProgressIndicator.java`**：进度指示器
  - **特性**：显示任务执行进度，提供视觉反馈
- **`StatusBar.java`**：状态栏类
  - **特性**：显示当前状态信息（模型、会话、Token 使用等）

**`themes/`**

- **`ColorScheme.java`**：颜色方案类
  - **特性**：定义终端颜色主题，支持自定义配色

## ⚙ 配置说明

### 配置文件 (`src/main/resources/config.yaml`)

```yaml
# ThoughtCoding 配置模板
# 用法：复制为 config.yaml（已被 .gitignore 忽略），填入你的 API Key。
# 注意：本项目使用 langchain4j 原生 function calling，必须使用支持 function calling 的模型。

models:
  deepseek-v4-pro:
    name: "deepseek-v4-pro"
    baseURL: "https://api.deepseek.com"
    apiKey: "your-api-key-here"
    streaming: true
    maxTokens: 4096
    temperature: 0

# 默认模型
defaultModel: "deepseek-v4-pro"

# AI 行为配置
ai:
  autoProcessToolResults: true  # true=工具结果自动回喂模型，形成 agentic 多轮循环
  maxToolIterations: 10         # 单次用户输入内的最大工具轮次上限

# 工具配置
tools:
  bash:
    enabled: true
  read:
    enabled: true
  write:
    enabled: true
  edit:
    enabled: true
  glob:
    enabled: true

# MCP 配置（可选）
mcp:
  enabled: true
  autoDiscover: true
  connectionTimeout: 30
  servers:
    - name: "filesystem"
      command: "npx"
      enabled: true
      args:
        - "@modelcontextprotocol/server-filesystem"
        - "."

    - name: "github"
      command: "npx"
      enabled: false
      args:
        - "@modelcontextprotocol/server-github"
        - "--token"
        - "your_github_token_here"
```

### 配置项说明

- `models` : 支持的 AI 模型配置
  - `name`: 模型名称
  - `baseURL`: API 基础 URL
  - `apiKey`: API 密钥
  - `streaming`: 是否启用流式输出
  - `maxTokens`: 单次请求最大 Token 数
  - `temperature`: 生成温度（推荐 0 以获得确定性工具调用）

- `defaultModel`: 默认使用的模型

- `ai` : AI 行为配置
  - `autoProcessToolResults`: 工具结果是否自动回喂模型继续对话
  - `maxToolIterations`: 单次用户输入内最大工具调用轮次

- `tools` : 内置工具配置
  - `bash`: 命令执行工具（Windows→PowerShell, Linux/Mac→bash）
  - `read`: 文件读取工具（只读，无需确认）
  - `write`: 文件创建/覆写工具
  - `edit`: 精确字符串替换工具（自动规范化 `\r\n`）
  - `glob`: 文件搜索工具（只读，无需确认）

- `mcp` : MCP 功能配置
  - `enabled`: 是否启用 MCP 功能模块
  - `autoDiscover`: 是否自动发现和注册 MCP 服务器的工具
  - `connectionTimeout`: MCP 服务器连接和初始化的超时时间
  - `servers`: MCP 服务器列表配置
    - `name`: 服务器名称
    - `command`: 启动 MCP 服务器的命令
    - `enabled`: 是否启用该服务器
    - `args`: 传递给 MCP 服务器的命令行参数

## 🛠️ 快速开始

### 安装要求

- Java 17 或更高版本
- Maven 3.6+
- 至少 2GB 可用内存
- Node.js 环境 16.0+
- 配置 npm 镜像（如淘宝镜像）
- 下载 MCP 服务器包

### **克隆仓库**

```bash
git clone https://github.com/zengxinyueooo/ThoughtCoding.git
```

### 配置 API

将 `src/main/resources/config.yaml` 复制到项目根目录为 `config.yaml`，编辑并填入你的 API Key。

#### **Linux/macOS**

```
cp src/main/resources/config.yaml config.yaml
# 编辑 config.yaml，填入你的 API 密钥
```

#### **Windows**

```
copy src\main\resources\config.yaml config.yaml
# 编辑 config.yaml，填入你的 API 密钥
```

### 构建项目

```
mvn clean package
```

### 运行应用

```
cd ThoughtCoding
```

#### **Linux/macOS**

```
# 交互模式
./bin/thought

# 继续上次对话
./bin/thought -c

# 指定会话
./bin/thought -S <session-id>

# 单次对话
./bin/thought -p "帮我写一个Java类"

# 指定模型
./bin/thought -m deepseek-chat

# 查看帮助
./bin/thought help
```

#### **Windows**

```
# 交互模式
.\bin\thought.bat

# 继续上次对话
.\bin\thought.bat -c

# 指定会话
.\bin\thought.bat -S <session-id>

# 单次对话
.\bin\thought.bat -p "帮我写一个Java类"

# 指定模型
.\bin\thought.bat -m deepseek-chat

# 查看帮助
.\bin\thought.bat help
```

### MCP 使用指南

支持两种方式使用 MCP 工具

#### 📁 配置文件方式 : 持久化配置，适合常用工具

编辑 `config.yaml` 文件中的 `mcp` 部分，重启应用，验证工具加载，`/mcp list` 

#### ⌨️ 终端命令方式 : 动态连接，适合临时工具

启动应用后，命令连接需要的工具，

```
/mcp connect github npx @modelcontextprotocol/server-github
/mcp connect postgres npx @modelcontextprotocol/server-postgres
```

```
/mcp predefined          # 显示可用的预定义工具
/mcp tools redis,docker  # 快捷连接预定义工具
```

## 🔧 开发指南

### 在 `src/main/java/com/thoughtcoding/tools/` 目录下创建新工具

继承 `BaseTool` 基类并实现核心方法：

```java
package com.thoughtcoding.tools;

import com.thoughtcoding.model.ToolResult;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class MyTool extends BaseTool {
    
    public MyTool() {
        super("my_tool", "工具描述");
    }
    
    @Override
    public ToolResult execute(String input) {
        // input 为 JSON 字符串，从 ToolDispatcher 传入
        // 工具实现逻辑
        return success("工具执行结果");
    }
    
    @Override
    public JsonObjectSchema inputSchema() {
        // 定义工具参数 schema（供模型了解参数结构）
        return JsonObjectSchema.builder()
            .addStringProperty("param1", "参数1描述")
            .build();
    }
    
    @Override
    public boolean isReadOnly() {
        // 只读工具返回 true，静默放行无需用户确认
        return false;
    }
}
```

在 `ThoughtCodingContext.java` 中注册新工具：

```java
toolRegistry.register(new MyTool());
```

### 使用类型定义

```
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.SessionData;

// 使用类型安全的模型
ChatMessage message = new ChatMessage("user", "Hello");
SessionData session = new SessionData("session-id", "标题", "model");
```

### 编码规范

- **类型安全**：充分利用Java的类型系统
- **异常处理**：使用明确的异常处理机制
- **日志记录**: 使用SLF4J进行日志记录
- **代码文档**：使用JavaDoc注释重要的方法和类
- **单元测试**：为核心功能编写单元测试

### 项目结构最佳实践

- 按功能分包，保持包结构清晰
- 使用接口定义边界服务
- 依赖注入管理组件依赖
- 配置与代码分离
- 使用构建工具管理依赖和构建过程

### **MCP 相关技术说明**

#### **MCP 功能特性：**

- ✅ **多服务器支持** - 可同时连接多个 MCP 服务器
- ✅ **预定义工具** - 内置常用 MCP 工具快捷方式，支持 `/mcp tools` 一键连接
- ✅ **动态连接** - 运行时按需连接/断开 MCP 服务器，无需重启
- ✅ **协议兼容** - 支持标准 MCP 协议规范（JSON-RPC over stdio）
- ✅ **工具适配** - 自动将 MCP 工具适配为统一的 BaseTool 接口
- ✅ **自动发现** - 启动时自动发现并注册已配置的 MCP 工具

#### **支持的 MCP 工具：**

- 🔧 **文件系统工具** - 本地文件操作（filesystem）
- 🗃️ **数据库工具** - SQLite、PostgreSQL、MySQL 查询
- 🌐 **网络工具** - GitHub API、Web 搜索、API 调用
- 📊 **计算工具** - 数学计算、数据处理
- 🌤️ **天气工具** - 天气查询服务
- 🔍 **搜索工具** - 文件搜索、内容检索

#### **集成方式：**

- ⚙️ **配置文件预连接** - 启动时自动连接常用工具
- ⌨️ **命令行动态连接** - 交互模式下按需连接工具
- 🔄 **混合模式** - 配置+命令灵活组合使用

## 📊 脚本说明

- `./bin/thought` - Linux/macOS 启动脚本
- `./bin/thought.bat` - Windows 启动脚本
- `mvn clean package` - 构建项目并打包
- `mvn test` - 运行测试套件

## 💡 使用示例

### 基本对话

```
./bin/thought
```

启动交互式对话模式

### 代码生成

```
./bin/thought -p "帮我写一个Java类，实现用户管理功能"
```

### 文件操作

```
./bin/thought -p "创建一个Spring Boot配置文件"
```

### 继续对话

```
./bin/thought -c
```

### 会话管理

```
# 列出所有会话
./bin/thought --list-sessions

# 删除指定会话
./bin/thought --delete-session <session-id>

# 加载指定会话
./bin/thought -S <session-id>
```

### 基础 MCP 工具使用

```
./bin/thought -p "读取config.yaml文件内容并分析配置结构"

./bin/thought -p "连接SQLite数据库并查询所有用户表"

./bin/thought -p "通过GitHub工具获取我的开源项目列表"
```

### MCP 服务器管理命令

```
# 查看已连接的 MCP 工具
/mcp list

# 显示可用的预定义工具
/mcp predefined

# 快捷连接预定义工具（支持多个，逗号分隔）
/mcp tools filesystem,sqlite,github

# 断开 MCP 服务器连接
/mcp disconnect filesystem

# 连接文件系统服务器（动态连接）
/mcp connect filesystem npx @modelcontextprotocol/server-filesystem

# 连接 GitHub 服务器
/mcp connect github npx @modelcontextprotocol/server-github --token your_token
```

## 🤝 协作指南

### 代码规范

- **类型安全**: 所有代码必须使用 Java 强类型
- **包结构**: 遵循约定的包组织结构
- **错误处理**: 完善的异常处理机制
- **文档注释**: 重要函数和类需要 Javadoc 注释

### 提交规范

- 使用清晰的提交信息
- 每个功能一个分支
- 提交前运行测试

### 开发流程

1. Fork 项目
2. 创建功能分支
3. 实现功能
4. 添加测试
5. 提交 Pull Request

## 🛠️ 技术栈

- **语言**: Java 17+
- **构建工具**: Maven
- **AI 框架**: LangChain4j（原生 Function Calling）
- **MCP 支持**: Model Context Protocol 客户端（JSON-RPC over stdio）
- **UI 框架**: JLine + 自定义 ANSI 终端 UI
- **配置管理**: YAML + Jackson
- **命令行**: Picocli
- **JSON 处理**: Jackson Databind

------

## 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 贡献

欢迎贡献代码！请查看 [贡献指南](CONTRIBUTING.md) 了解详情。

## 联系方式

如有问题或建议，请通过以下方式联系：

- 提交 Issue
- 发送邮件
- 参与讨论

------

**ThoughtCoding** **CLI** - 让 AI 编程助手更智能、更易用！ 🚀

