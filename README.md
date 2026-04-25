# TelegramWebHookProxy

TelegramWebHookProxy可以用于转发消息到Telegram机器人，只需要一个POST请求。也可以作为控制家中各种服务的AI Agent，通过各种方式调用家中部署的服务。

## 核心功能

### 消息转发

- [x] 使用更简单的API发送消息。
- [x] 使用单独设置的代理服务器转发Telegram API请求。

### AI 功能

- [x] 接入 Google Gemini API 。
- [ ] 接入 OpenAI SDK。

- [x] **语音交互**：支持接收并理解 Telegram 语音消息。
- [x] **小型 Skill 系统**：允许 AI 读写知识库，实现长期记忆与技能扩展。
- [x] **定时任务**：支持 AI 代理创建并管理自动化定时任务。
- [x] **HTTP 访问**：AI 代理可主动调用外部或本地 HTTP API。
- [x] 支持 **Model Context Protocol**，可接入 MCP 工具。

## 快速开始

### Docker部署

#### 1. 构建镜像

你可以使用预先打包好的镜像，也可以自己构建镜像。在项目根目录下运行以下命令构建 Docker 镜像：

```bash
docker build -t telegram-webhook-proxy .
```

#### 2. 启动容器

启动容器时，需要映射端口（默认 `10178`）并挂载配置目录以持久化数据：

```bash
docker run -d \
  --name telegram-webhook-proxy \
  -p 10178:10178 \
  -v <dir>:/app/config \
  telegram-webhook-proxy
```

*   `-p 10178:10178`: 将容器的 10178 端口映射到主机的 10178 端口。
*   `-v <dir>:/config`: 选择一个目录，用于存储配置文件。将这个目录挂载到容器的 `/config` 目录。

### 直接使用Jar包

#### 1. 打Jar包

你可以使用Gradle自己构建项目：

```bash
./gradlew build
```

构建完成后，在 `backend/build/libs/` 目录下会生成 `TelegramWebHookProxy-[版本]-all.jar` 文件。

#### 2. 运行

使用Java命令运行构建好的 Jar 包：

```bash
java -jar <path-to>/TelegramWebHookProxy-<version>-all.jar
```

*注意：请将路径替换为实际的 Jar 包路径。*

启动成功后，服务默认监听 **10178** 端口，并在`./config`目录下保存配置文件。

*   **Web 管理界面:** `http://localhost:10178`
    *   你需要设置Telegram Bot Token才能开始使用。
    *   设置Token后，所有Telegram API请求都将发送到这个Bot。
    *   向机器人发送消息，然后你和机器人的对话信息就会出现在首页。通过Web UI确认选择要监听的对话ID，并测试消息是否能成功发送。
    *   在这里启用AI功能，填写API Key后才能启用AI功能
*   **API 基础路径:** `http://localhost:10178/api`

## 接口文档

### 发送消息 (`/api/send-message`)

这是核心接口，用于通过配置好的Bot发送消息。该接口支持通过查询参数自定义 Body 中的字段名，方便对接不同的 Webhook 来源。

*   **URL:** `/api/send-message`
*   **Method:** `POST`
*   **Content-Type:** `application/json`, `application/x-www-form-urlencoded`

#### 查询参数 (Query Parameters)

| 参数名 | 默认值      | 描述 |
| :--- |:---------| :--- |
| `messagefield` | `text`   | 指定 Body 中表示消息内容的字段名。 |
| `chatidfield` | `chatId` | 指定 Body 中表示目标 Chat ID 的字段名。 |

#### 请求参数 (Request Body)

Body 中的字段名由上述查询参数决定。

| 默认字段名    | 类型 | 必填 | 描述 |
|:---------|:-------|:---|:--------------------------------------------------- |
| `chatId` | String | 否 | 目标 Telegram 会话 ID。如果为空，则发送给 Web 管理界面中选择的默认聊天。 |
| `text`   | String | 是 | 要发送的消息内容。 |

#### 请求示例

**1. 使用默认字段名 (JSON):**

```bash
curl -X POST "http://localhost:10178/api/send-message" \
  -H "Content-Type: application/json" \
  -d '{
    "chatid": "123456789",
    "text": "Hello from TelegramWebHookProxy!"
  }'
```

**2. 自定义字段名 (表单提交):**

例如，对接某个第三方系统，其发送的消息字段名为 `content`，目标 ID 为 `to`:

```bash
curl -X POST "http://localhost:10178/api/send-message?messagefield=content&chatidfield=to" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "to=123456789&content=Custom field message"
```

#### 响应 (Response)

*   **成功 (200 OK):** 返回 Telegram API 的原始响应 JSON。
*   **错误 (400 Bad Request):** 消息内容缺失或 Chat ID 未配置。
*   **错误 (415 Unsupported Media Type):** 不支持的 Content-Type。
*   **错误 (500 Internal Server Error):** 其他内部错误。
