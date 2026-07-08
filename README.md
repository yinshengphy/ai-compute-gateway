# ai-compute-gateway

Spring Boot gateway for private AI compute. It exposes OpenAI-compatible internal APIs and proxies to the private Ollama runtime.

## APIs

- `POST /v1/chat/completions`
- `POST /v1/embeddings`
- `GET /v1/models`
- `GET /internal/queue/status`

All public AI workloads should call this gateway instead of talking to Ollama directly.

The gateway sends `keep_alive` to Ollama so the chat model stays warm between requests. Defaults:

- Chat model: `huihui-qwen3:4b-instruct-2507-abliterated-q4_K_M`
- Fallback chat model: `qwen2.5:3b`
- Chat keep-alive: `10m`
- Embedding keep-alive: `5m`

## Local Run

```bash
export AI_COMPUTE_API_TOKEN=dev-token
export OLLAMA_BASE_URL=http://127.0.0.1:11434
mvn spring-boot:run
```
