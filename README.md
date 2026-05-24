<h1 align="center">TelegramWebHookProxy</h1>

<p align="center">
  用一个轻量的自托管服务，把 HTTP Webhook 消息转发到 Telegram Bot，并扩展为可调用本地服务的 AI Agent。
</p>

---

## 概览

TelegramWebHookProxy 提供一个简单的 HTTP API，用于把第三方系统、脚本或自动化工具的消息发送到 Telegram。它内置 Web 管理界面，可以配置 Bot Token、默认会话、代理服务器和 AI 助手能力。

启用 AI 后，Telegram Bot 还能作为个人 Agent 使用：接收文本或语音消息，调用本地 HTTP API、MCP 工具、Skill 知识库和定时任务能力，用来管理家中或服务器上的服务。

## 功能特性

- **Webhook 转 Telegram**：通过 `/api/send-message` 发送文本消息到指定或默认 Telegram 会话。
- **字段映射**：支持通过查询参数适配不同 Webhook 来源的字段名。
- **代理支持**：Telegram API 与 AI Provider 请求可使用 HTTP 或 SOCKS 代理。
- **Web 管理界面**：配置 Token、默认聊天、代理、AI Provider、MCP 服务器和 Skill。
- **AI Agent**：支持 Google Gemini 与 OpenAI SDK，可在 Telegram 中连续对话。
- **语音消息**：可接收 Telegram 语音消息并交给 AI 处理。
- **工具调用**：AI 可调用 HTTP、MCP、Skill 和定时任务相关工具。
- **持久化配置**：配置、会话、Skill 与定时任务保存在本地 `config/` 目录。

## 技术栈

| 模块 | 技术                                                                  |
|----|---------------------------------------------------------------------|
| 后端 | Kotlin 2.2、Ktor 3、Dagger、kotlinx.serialization                      |
| AI | Google Gemini SDK、OpenAI Java SDK、Model Context Protocol Kotlin SDK |
| 前端 | React 19、Vite、Material UI、React Router、Axios                        |
| 构建 | Gradle、ShadowJar、Node Gradle Plugin、Docker                          |

## 快速开始

### 使用 Docker

```bash
docker build -t telegram-webhook-proxy .
```

```bash
docker run -d \
  --name telegram-webhook-proxy \
  -p 10178:10178 \
  -v /path/to/config:/app/config \
  telegram-webhook-proxy
```

启动后访问：

- Web 管理界面：`http://localhost:10178`
- API 基础路径：`http://localhost:10178/api`

> [!IMPORTANT]
> Docker 容器的持久化目录是 `/app/config`。请把宿主机目录挂载到这个路径。

### 使用 Jar 包

项目需要 JDK 21。Gradle 会在构建前自动下载前端所需的 Node.js 版本。

```bash
./gradlew build
```

构建完成后运行 Shadow Jar：

```bash
java -jar backend/build/libs/TelegramWebHookProxy-1.1.2-all.jar
```

服务默认监听 `0.0.0.0:10178`，并在当前工作目录下读写 `config/`。

## 初始配置

1. 打开 `http://localhost:10178`。
2. 在 Settings 页面填写 Telegram Bot Token。
3. 如果访问 Telegram API 需要代理，配置 HTTP 或 SOCKS 代理。
4. 向 Bot 发送一条消息，让服务通过 Telegram updates 发现会话。
5. 在首页选择默认会话，之后 `/api/send-message` 可以省略 `chatId`。
6. 如需 AI Agent，在 Settings 页面开启 AI，选择 Provider 并填写 API Key、Agent Chat ID 和系统提示词。

> [!NOTE]
> 会话列表由后台轮询 Telegram updates 自动维护。首次启动时会跳过历史消息，从最新 update 开始处理。

## API 使用

### 发送消息

`POST /api/send-message`

支持 `application/json` 与 `application/x-www-form-urlencoded`。

| 查询参数           | 默认值      | 说明                             |
|----------------|----------|--------------------------------|
| `messagefield` | `text`   | 请求体中表示消息内容的字段名                 |
| `chatidfield`  | `chatId` | 请求体中表示目标 Telegram Chat ID 的字段名 |

默认 JSON 请求：

```bash
curl -X POST "http://localhost:10178/api/send-message" \
  -H "Content-Type: application/json" \
  -d '{
    "chatId": "123456789",
    "text": "Hello from TelegramWebHookProxy!"
  }'
```

使用自定义字段名的表单请求：

```bash
curl -X POST "http://localhost:10178/api/send-message?messagefield=content&chatidfield=to" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "to=123456789&content=Custom field message"
```

如果请求体中没有提供 Chat ID，服务会使用 Web UI 中配置的默认会话。

### 常用接口

| 方法       | 路径                           | 说明                 |
|----------|------------------------------|--------------------|
| `GET`    | `/api/settings`              | 获取当前设置             |
| `POST`   | `/api/settings`              | 保存全局设置             |
| `POST`   | `/api/settings/chat`         | 更新默认 Telegram 会话   |
| `GET`    | `/api/chats`                 | 获取已发现的 Telegram 会话 |
| `DELETE` | `/api/chats/{id}`            | 删除本地保存的会话          |
| `GET`    | `/api/skills?page=1&size=10` | 分页获取 Skill         |
| `POST`   | `/api/skills`                | 新增或更新 Skill        |
| `DELETE` | `/api/skills/{id}`           | 删除 Skill           |

## AI Agent

AI Agent 只处理来自 `agentChatId` 的消息。开启后可在 Telegram 中使用以下命令：

| 命令              | 说明                |
|-----------------|-------------------|
| `/model`        | 查看当前模型与可用模型       |
| `/model <模型名称>` | 切换模型并重置会话         |
| `/reset`        | 重置当前会话上下文并清空待处理消息 |
| `/keep`         | 刷新自动清理上下文的计时      |

Agent 可用能力包括：

- 调用配置的 MCP 服务器工具。
- 读写 Skill 知识库，作为长期记忆或操作说明。
- 创建、列出、取消定时任务。
- 访问外部或内网 HTTP API。
- 处理 Telegram 语音消息。

## 本地开发

后端测试：

```bash
./gradlew :backend:test
```

构建完整应用：

```bash
./gradlew build
```

前端单独开发：

```bash
cd webui
npm install
npm run dev
```

前端开发服务器默认只代理前端页面；生产构建会由 Gradle 打包进后端 Shadow Jar，并由 Ktor 托管为单页应用。

## 项目结构

```text
.
├── backend/              # Kotlin/Ktor 后端、Telegram 与 AI 服务
├── webui/                # React/Vite 管理界面
├── doc/                  # 架构、API 与部署说明
├── Dockerfile            # 多阶段 Docker 构建
├── build.gradle.kts      # 根 Gradle 配置
└── settings.gradle.kts   # Gradle 模块定义
```
