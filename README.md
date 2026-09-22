<h1 align="center">TelegramWebHookProxy</h1>

<p align="center">
  用一个轻量的自托管服务，把 HTTP Webhook 消息转发到 Telegram Bot，并扩展为可调用本地服务的 AI Agent。
</p>

---

## 概览

TelegramWebHookProxy 提供一个简单的 HTTP API，用于把第三方系统、脚本或自动化工具的消息发送到 Telegram。它内置 Web 管理界面，可以配置 Bot Token、默认会话、代理服务器和 AI 助手能力。

启用 AI 后，Telegram Bot 还能作为个人 Agent 使用：接收文本或语音消息，调用本地 HTTP API、MCP 工具、Skill 知识库和定时任务能力，用来管理家中或服务器上的服务。

## 功能特性

- **Webhook 转 Telegram**：通过 `/api/send-message` 发送普通文本、Rich Markdown、HTML 或 blocks 消息到指定或默认 Telegram 会话。
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
| 后端 | Kotlin 2.4.20、Ktor 3、Dagger、kotlinx.serialization                   |
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

可以使用 JDK 21 或 JDK 26 启动构建。编译和默认测试仍使用 JDK 21，Gradle 会按需下载对应工具链及前端所需的 Node.js/npm。Docker 构建和运行镜像使用 JDK 26。

```bash
./gradlew :backend:releaseBuild
```

该命令执行后端测试、前端构建、许可证生成和正式 Jar 验证。构建完成后运行 Shadow Jar：

```bash
java -jar backend/build/libs/TelegramWebHookProxy-1.2.0-all.jar
```

服务默认监听 `0.0.0.0:10178`，并在当前工作目录下读写 `config/`。

## 初始配置

1. 打开 `http://localhost:10178`。
2. 在 Settings 页面填写 Telegram Bot Token。
3. 如果访问 Telegram API 需要代理，配置 HTTP 或 SOCKS 代理。
4. 向 Bot 发送一条消息，让服务通过 Telegram updates 发现会话。
5. 在首页选择默认会话，之后 `/api/send-message` 可以省略 `chatId`。
6. 如需 AI Agent，在 AI Agent 页面选择 Provider 并保存 API Key，然后从“模型名称”下拉列表选择模型并独立保存，最后配置 Agent Chat ID、系统提示词并启用 AI。

> [!NOTE]
> 会话列表由后台轮询 Telegram updates 自动维护。首次启动时会跳过历史消息，从最新 update 开始处理。

## API 使用

### 查询可选 AI 模型

`GET /api/ai/models`

按已保存的 AI 提供商、API Key、Base URL 和代理查询当前模型列表。无需启用 Agent，查询不会修改已选模型或重置会话。

```json
{"provider":"OPENAI","currentModel":"model-a","availableModels":["model-a","model-b"]}
```

Gemini 会汇总分页结果，并只保留支持 `generateContent` 的模型。空列表正常返回 `200`；配置不完整返回 `400`，上游请求失败返回 `502`，超时返回 `504`，错误响应为 `{"error":"说明"}`。

`currentModel` 用于回显当前选项：优先返回已保存的 `ai.selectedModel`；未保存选择时返回同一份配置下已就绪 Agent 的实际模型。两者都不存在时返回空字符串，页面显示“请选择模型”。查询不会自动保存默认模型，已保存但不在列表中的模型仍会返回并标记为不可用。

模型选择继续使用 `PATCH /api/settings`，携带从 `GET /api/settings` 获取的 `ETag` 作为 `If-Match`，请求体为 `{"ai":{"selectedModel":"model-a"}}`。

### 发送消息

`POST /api/send-message`

支持 `application/json` 与 `application/x-www-form-urlencoded`。

| 查询参数           | 默认值      | 说明                             |
|----------------|----------|--------------------------------|
| `messagefield` | `text`   | 请求体中表示消息内容的字段名                 |
| `chatidfield`  | `chatId` | 请求体中表示目标 Telegram Chat ID 的字段名 |
| `richformat`   | 空       | 可选 `markdown`、`html`、`blocks`；省略或全空白时发送普通消息 |

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

#### 富消息

`richformat` 只接受小写枚举；未知值或重复指定返回 `400`。正文仍使用 `text`（或 `messagefield` 映射的字段），目标规则不变：

| 格式 | JSON 正文 | URL 编码表单正文 | Telegram 方法 |
| --- | --- | --- | --- |
| 普通消息（省略或空白） | 非空字符串 | 非空字符串 | `sendMessage` |
| `markdown` | 非空 Markdown 字符串 | 非空 Markdown 字符串 | `sendRichMessage` |
| `html` | 非空 HTML 字符串 | 非空 HTML 字符串 | `sendRichMessage` |
| `blocks` | 非空 JSON 对象数组 | 同一数组的 JSON 字符串 | `sendRichMessage` |

```bash
curl -X POST 'http://localhost:10178/api/send-message?richformat=markdown' \
  -H 'Content-Type: application/json' \
  -d '{"text":"# 构建完成\n\n**结果**：成功\n\n- 测试通过\n- 制品已生成"}'

curl -X POST 'http://localhost:10178/api/send-message?richformat=html' \
  --data-urlencode 'text=<h1>构建完成</h1><p><b>结果</b>：成功</p>'

curl -X POST 'http://localhost:10178/api/send-message?richformat=blocks&messagefield=content' \
  -H 'Content-Type: application/json' \
  -d '{"content":[{"type":"paragraph","text":"构建完成"}]}'
```

表单发送 blocks 时可使用 `--data-urlencode 'text=[{"type":"paragraph","text":"构建完成"}]'`。
服务只检查正文类型、非空要求及 JSON 结构，保留 blocks 对象内容，完整语法和内容限制交由 Telegram 验证。
富消息请求只设置 `rich_message` 中选定的一个字段，详见 [InputRichMessage](https://core.telegram.org/bots/api#inputrichmessage)。

一次 API 请求仅投递一条消息，不自动拆分或降级，Telegram 状态码和响应正文继续透传。普通消息保留 4,096 个 UTF-16 单元和 64 KiB 请求体限制；三种富消息格式的 HTTP 请求体上限均为 1 MiB（1,048,576 字节），按实际传输的字节计量，包含 JSON 或表单编码开销。超过上限返回 `413`，未声明 `Content-Length` 的流式请求同样受限；仍保留 JSON 深度及节点数保护。
正文可引用媒体 URL，blocks 可使用 Telegram 文件引用；接口不提供独立 `media` 参数或文件上传。富消息语法和平台限制见 [Telegram 官方文档](https://core.telegram.org/bots/api#rich-message-formatting-options)。

Web UI 的 Webhook 页面可选择四种格式并载入示例，首页快捷发送继续使用普通消息。

### 常用接口

| 方法       | 路径                           | 说明                 |
|----------|------------------------------|--------------------|
| `GET`    | `/api/settings`              | 获取当前设置             |
| `PUT`    | `/api/settings`              | 使用完整严格 JSON 替换全局设置 |
| `PATCH`  | `/api/settings`              | 使用严格 JSON 局部更新全局设置 |
| `POST`   | `/api/settings`              | 兼容的完整设置替换，语义同 `PUT` |
| `POST`   | `/api/settings/chat`         | 兼容的默认 Telegram 会话更新 |
| `GET`    | `/api/chats`                 | 获取已发现的 Telegram 会话 |
| `DELETE` | `/api/chats/{id}`            | 删除本地保存的会话          |
| `GET`    | `/api/skills?page=1&size=10` | 分页获取 Skill         |
| `POST`   | `/api/skills`                | 新增或编辑待审批 Skill 草稿 |
| `POST`   | `/api/skills/{id}/approve`   | 以版本号批准 Skill 并启用 |
| `POST`   | `/api/skills/{id}/revoke`    | 以版本号撤销已批准 Skill |
| `DELETE` | `/api/skills/{id}`           | 删除 Skill           |

设置写入必须携带 `GET /api/settings` 返回的单个强 `ETag` 作为 `If-Match`。`PUT` 要求
提供所有顶层与非空嵌套字段；`PATCH` 仅修改出现的字段，`proxy` 与 `ai` 可用 `null` 删除，
`proxy.username` 与 `proxy.password` 可用 `null` 清除。嵌套对象递归合并，列表与 MCP
`headers` 映射整体替换；除 `headers` 的动态键外，未知字段均会被拒绝。

## AI Agent

AI Agent 仅处理授权用户的私聊消息：消息必须来自私聊，且发送者 ID 与聊天 ID 都要等于 `agentChatId`。开启后可在 Telegram 中使用以下命令：

| 命令              | 说明                |
|-----------------|-------------------|
| `/model`        | 查看当前模型与可用模型       |
| `/model <模型名称>` | 切换模型并重置会话         |
| `/reset`        | 重置当前会话上下文并清空待处理消息 |
| `/keep`         | 刷新自动清理上下文的计时      |

Agent 可用能力包括：

- 调用配置的 MCP 服务器工具。
- 读取已批准的 Skill，并只能创建等待管理端批准的 Skill 草稿。
- 创建、列出、取消定时任务。
- 访问外部或内网 HTTP API。
- 处理 Telegram 语音消息。

文字、语音交互后的 AI 成功回复，以及定时任务结果，默认以 Rich Markdown 发送。OpenAI 与 Gemini 使用相同格式指引，并保留全局上下文。命令响应、系统错误及固定失败提示继续使用普通消息。

长回复按 Markdown 结构分片：代码续片保留围栏及语言，表格续片重复表头，各片补齐需要的脚注和引用链接定义。公式、折叠块、媒体组合保持完整；无法安全容纳的部分保留原文并转为普通消息。采用 commonmark-java 0.30.0 及表格、脚注、任务列表、删除线扩展；无法准确解析的 Telegram 扩展按保守预算处理，最终由上游判定。

聊天回复在 AI 回合完成时保存完整投递计划。重启后恢复片段和普通降级进度，不重新调用 AI；历史记录继续按原有普通消息游标发送。富片段被明确拒绝时，首片先去除回复引用重试，仍被拒绝才降级当前部分，之后继续后续富片段。网络异常、限流和服务端临时错误保留原格式重试。投递仍是至少一次语义，网络结果不确定时可能重复发送。

定时任务复用相同分片和局部降级规则，保留结果前缀并在每次发送前检查 token；网络结果不确定或普通消息发送失败时停止，不新增持久化重试，不因发送失败重新执行 AI。本次不提供流式草稿或 AI 格式开关，也不扩展接收端富消息解析。

自动化测试覆盖请求契约、内容分片及恢复状态；实际 Telegram 的三种富格式、长回复续接、局部降级、聊天及定时任务显示效果仍需在客户端验收。

> [!WARNING]
> Skill 管理 API 可以批准、撤销或删除 Agent 的长期指令。模型工具只能创建待审批草稿，不能自行批准或覆盖既有
> Skill。

## 本地开发

后端测试：

```bash
./gradlew :backend:test
```

日常后端构建（包含后端测试）：

```bash
./gradlew build
```

`build`、`:backend:assemble`、`:backend:jar`、`:backend:check`、`:backend:test` 和
`:backend:run` 均不触发前端构建或许可证生成。普通 Jar 仅包含后端代码及后端资源。

构建完整应用并验证正式制品（Docker 使用同一入口）：

```bash
./gradlew :backend:releaseBuild
```

仅生成包含前端和许可证的正式 Jar：

```bash
./gradlew :backend:shadowJar
```

`:backend:runShadow`、`:backend:shadowDistZip`、`:backend:shadowDistTar` 和
`:backend:installShadowDist` 使用同一完整 Shadow Jar，也会触发前端和许可证任务。
正式 Jar 验证包括泛型序列化、首页、静态资源、前端路由及 `/license`。

验证日常任务与正式打包任务的依赖边界：

```bash
bash scripts/verify-build-boundaries.sh
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
