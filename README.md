# TelegramWebHookProxy

TelegramWebHookProxy可以用于转发消息到Telegram机器人，只需要一个POST请求。

核心功能：

- [x] 使用更简单的API发送消息。
- [x] 使用单独设置的代理服务器转发Telegram API请求。
- [ ] 通过模板发送复杂消息。
- [ ] 接收消息后执行特定操作。

## 快速开始

### Docker部署

#### 1. 构建镜像

你可以使用预先打包好的镜像，也可以自己构建。在项目根目录下运行以下命令构建 Docker 镜像：

```bash
docker build -t telegram-webhook-proxy .
```

#### 2. 启动容器

启动容器时，需要映射端口（默认 `10178`）并挂载配置目录以持久化数据：

```bash
docker run -d \
  --name telegram-webhook-proxy \
  -p 10178:10178 \
  -v <dir>:/config \
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

*   **Web 管理界面:** [http://localhost:10178](http://localhost:10178)
    *   你需要设置Telegram Bot Token才能开始使用。
    *   设置Token后所有Telegram API请求都将发送到这个Bot。你可以使用Web UI首页的聊天列表来快速选择你需要的聊天ID，并测试消息是否能成功发送。
*   **API 基础路径:** `http://localhost:10178/api`

## 接口文档

### 发送消息 (`/api/send-message`)

这是核心接口，用于通过配置好的Bot发送消息到指定的Chat ID。

*   **URL:** `/api/send-message`
*   **Method:** `POST`
*   **Content-Type:** `application/json`

#### 请求参数 (Request Body)

| 字段名 | 类型 | 必填 | 描述 |
| :--- | :--- | :--- | :--- |
| `chatId` | String | 否 | 目标Telegram会话ID(Chat ID)。如果为空，则会给WebUI中选择的默认聊天发送消息。 |
| `text` | String | 是 | 要发送的消息内容 |

#### 请求示例

```bash
curl -X POST http://localhost:10178/api/send-message \
  -H "Content-Type: application/json" \
  -d '{
    "chatId": "123456789",
    "text": "Hello from TelegramWebHookProxy!"
  }'
```

#### 响应 (Response)

*   **成功 (200 OK):** 返回 Telegram API 的原始响应 JSON。
*   **错误 (500 Internal Server Error):** 返回错误描述信息。
