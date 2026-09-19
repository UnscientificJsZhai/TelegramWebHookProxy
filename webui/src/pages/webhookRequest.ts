import {isTelegramMessageTextWithinLimit} from '../messageText';
import {utf8Length} from '../settings';

export interface WebhookDraft {
    format: 'json' | 'form';
    chatId: string;
    text: string;
    messageField: string;
    chatIdField: string;
}

export const validateWebhookDraft = (draft: WebhookDraft): string | null => {
    if (!draft.text.trim()) return '消息正文不能全为空白。';
    if (!isTelegramMessageTextWithinLimit(draft.text)) return '消息不能超过 4,096 个 UTF-16 代码单元。';
    if (utf8Length(draft.chatId) > 64) return 'Chat ID 不能超过 64 个 UTF-8 字节。';
    const messageField = draft.messageField || 'text';
    const chatIdField = draft.chatIdField || 'chatId';
    if (!messageField.trim() || !chatIdField.trim()) return '映射键名不能全为空白。';
    if (utf8Length(messageField) > 64 || utf8Length(chatIdField) > 64) return '映射键名不能超过 64 个 UTF-8 字节。';
    if (messageField === chatIdField) return '正文和目标会话不能使用相同的映射键名。';
    return null;
};

export const buildWebhookRequest = (draft: WebhookDraft) => {
    const error = validateWebhookDraft(draft);
    if (error) throw new Error(error);
    const query = new URLSearchParams();
    if (draft.messageField) query.set('messagefield', draft.messageField);
    if (draft.chatIdField) query.set('chatidfield', draft.chatIdField);
    const body: Record<string, string> = Object.fromEntries([
        [draft.messageField || 'text', draft.text],
        ...(draft.chatId.trim() ? [[draft.chatIdField || 'chatId', draft.chatId]] : []),
    ]);
    const suffix = query.toString();
    return {
        path: `/send-message${suffix ? `?${suffix}` : ''}`,
        body: draft.format === 'json' ? JSON.stringify(body) : new URLSearchParams(body).toString(),
        contentType: draft.format === 'json' ? 'application/json' : 'application/x-www-form-urlencoded',
    };
};

export const WEBHOOK_EXAMPLES = {
    cURL: `curl -X POST 'https://your-domain.example/api/send-message' \\\n  -H 'Content-Type: application/json' \\\n  -d '{"text":"构建流水线已执行完毕","chatId":"-1001234567890"}'`,
    Python: `import requests\n\nresponse = requests.post(\n    "https://your-domain.example/api/send-message",\n    json={"text": "构建流水线已执行完毕", "chatId": "-1001234567890"},\n    timeout=30,\n)\nresponse.raise_for_status()\nprint(response.text)`,
    'Node.js': `const response = await fetch(\n  'https://your-domain.example/api/send-message',\n  {\n    method: 'POST',\n    headers: {'Content-Type': 'application/json'},\n    body: JSON.stringify({\n      text: '构建流水线已执行完毕',\n      chatId: '-1001234567890',\n    }),\n  },\n);\nconsole.log(response.status, await response.text());`,
    Go: `package main\n\nimport (\n    "bytes"\n    "encoding/json"\n    "fmt"\n    "io"\n    "net/http"\n    "time"\n)\n\nfunc main() {\n    payload, err := json.Marshal(map[string]string{\n        "text": "构建流水线已执行完毕",\n        "chatId": "-1001234567890",\n    })\n    if err != nil { panic(err) }\n    client := &http.Client{Timeout: 30 * time.Second}\n    resp, err := client.Post(\n        "https://your-domain.example/api/send-message",\n        "application/json", bytes.NewReader(payload),\n    )\n    if err != nil { panic(err) }\n    defer resp.Body.Close()\n    body, err := io.ReadAll(resp.Body)\n    if err != nil { panic(err) }\n    fmt.Println(resp.Status, string(body))\n}`,
};
