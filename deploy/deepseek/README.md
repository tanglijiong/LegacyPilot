# DeepSeek offline model Agent image

This image is the first bank-internal implementation of the LegacyPilot JSONL model-agent contract. A persistent vLLM process serves DeepSeek over a Unix-domain socket, so the model loads once for the complete experiment. Short-lived task Agent containers share only that socket and an isolated workspace; they have Docker networking disabled. The Agent supplies bounded Java production source context, accepts a structured complete-file response, and atomically writes only under `src/main/java` or `src/main/resources`.

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
