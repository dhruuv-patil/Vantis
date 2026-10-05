# Vantis AI Service

Isolated Python service that powers Vantis AI features via a **Google Gemini,
bring-your-own-key** interface. Only the Spring Boot backend calls it — never
the browser.

- **M0 (now):** `GET /health` (reports `ai_configured` without leaking the key).
- **M4:** `POST /ai/generate-workflow` (Gemini → workflow graph) and
  `POST /ai/agent/run` (agent execution with structured output), plus an
  allowlisted tool registry.

## Run locally

```bash
cd ai-service
python3.12 -m venv .venv        # 3.11/3.12 recommended
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8100
```

Health check: `curl http://localhost:8100/health`

## Configuration

| Variable | Purpose |
|----------|---------|
| `GEMINI_API_KEY` | Google Gemini key. Empty ⇒ AI endpoints report "not configured". |
| `GEMINI_MODEL` | Default Gemini model id (default `gemini-2.5-flash`). |
| `AI_PROVIDER` | Active LLM provider (default `gemini`). The seam for adding others. |
| `AI_SERVICE_TOKEN` | Shared secret the backend presents on each call. |
| `CORS_ORIGINS` | Allowed callers (the backend origin). |
