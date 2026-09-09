# DeepSeek 本地模型

## 开发机直接运行（当前正式技术基线）

开发与技术验收不需要银行 OIDC、内部 CA、测试身份、审批摘要、CUDA 或 GPU 服务器。Apple Silicon 可直接使用 Docker Model Runner 的 Metal/llama.cpp 引擎；普通 x86 机器也可以使用 CPU，只是推理会更慢。

本项目选用 DeepSeek-Coder-V2-Lite-Instruct 的 `Q4_K_M` GGUF 量化版本。下载并启动模型：

```bash
docker model pull \
  hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m
docker model configure --context-size=8192 \
  hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m
docker model run \
  hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m \
  '只回复 OK'
```

LegacyPilot Server/CLI 连接本机 OpenAI-compatible API 时设置：

```bash
export LEGACY_PILOT_MODEL_BASE_URL=http://127.0.0.1:12434/engines/v1
export LEGACY_PILOT_MODEL=hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m
```

程序只接受显式 loopback HTTP 地址，不接受公网主机、HTTPS 代理、URL 凭据或重定向。无需 API key。
`8192` 上下文配置用于容纳固定 revision 的源码上下文、结构化 Schema 和纠错反馈；使用 Docker Model Runner 默认的小上下文可能得到 HTTP 400。

真实 5 题 smoke 可使用 `eval-model-run --model-adapter local-http`，完整命令见 [`docs/EVALS.md`](../../docs/EVALS.md)。

## 可选：严格隔离部署

This image is the first bank-internal implementation of the LegacyPilot JSONL model-agent contract. A persistent vLLM process serves DeepSeek over a Unix-domain socket, so the model loads once for the complete experiment. Short-lived task Agent containers share only that socket and an isolated workspace; they have Docker networking disabled. The Agent supplies bounded Java production source context, accepts a structured complete-file response, and atomically writes only under `src/main/java` or `src/main/resources`.

以下镜像 digest、权重摘要、vLLM/CUDA 和 Unix socket 要求只适用于机构希望建设严格离线、可审计部署的场景，不是本地开发或本版本技术交付门槛。

The public build does not download model weights or select an upstream container tag. The bank build pipeline must:

1. mirror an approved vLLM base image and reference it by immutable digest;
2. obtain and scan `deepseek-ai/DeepSeek-Coder-V2-Lite-Instruct` weights under the applicable model license;
3. record the approved model artifact SHA-256 and place the weights in a read-only host directory;
4. build this image without network access after all inputs are mirrored;
5. push it to the internal registry and record its resulting image digest.

Example build using an already mirrored base:

```bash
docker build --network none \
  --build-arg VLLM_IMAGE=registry.bank.local/ai/vllm@sha256:<digest> \
  -t registry.bank.local/legacy-pilot/deepseek-agent:review \
  deploy/deepseek
```

Before starting the service, write the approved artifact digest into `<weights>/MODEL_ARTIFACT_SHA256`. Then start the persistent service; the script validates the marker, pinned image and resource limits before invoking Docker:

```bash
python3 deploy/deepseek/start_model_service.py \
  --image registry.bank.local/legacy-pilot/deepseek-agent@sha256:<digest> \
  --weights /srv/legacy-pilot/models/deepseek-coder-v2-lite \
  --model-artifact-sha256 <weights-digest> \
  --socket-directory /run/legacy-pilot/deepseek \
  --served-model-name deepseek-coder-v2-lite \
  --memory 24g --cpus 8 --gpus all \
  --tensor-parallel-size 1 --max-model-length 32768
```

The startup command waits for the socket health check, then atomically writes `service-manifest.json` beside the socket. The Eval Runner refuses to start if this manifest does not exactly match its pinned image, model artifact digest and resource configuration. Keep the startup flags and `eval-model-run` flags identical.

The service and task Agent containers both use `--network none` and `--pull never`. Model weights are read-only and visible only to the persistent service. The Agent sees the socket directory through a read-only mount and its own workspace; its HTTP client never resolves an IP endpoint or system proxy. When the experiment finishes, stop the named service with `docker stop legacypilot-deepseek`, then remove the stale socket and service manifest before the next approved start.
