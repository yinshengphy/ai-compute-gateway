# ai-compute-gateway

私有 AI 计算的 Spring Boot 网关。它对集群内部暴露 OpenAI 兼容 API，并代理到私有 Ollama 运行时。

## 接口

- `POST /v1/chat/completions`
- `POST /v1/embeddings`
- `GET /v1/models`
- `GET /internal/queue/status`

聊天接口兼容文本、图片内容数组、`tools`、`tool_calls` 和流式输出。所有公开入口产生的 AI 计算请求都应该调用这个网关，不要直接访问 Ollama。

网关会向 Ollama 发送 `keep_alive`，让聊天模型在请求间保持热加载。默认值：

- 聊天模型：`huihui_ai/qwen3-vl-abliterated:4b-instruct`
- 备用聊天模型：`qwen2.5:3b`
- 聊天模型保活时间：`10m`
- Embedding 模型保活时间：`5m`

## 本地运行

```bash
export AI_COMPUTE_API_TOKEN=dev-token
export OLLAMA_BASE_URL=http://127.0.0.1:11434
mvn spring-boot:run
```
