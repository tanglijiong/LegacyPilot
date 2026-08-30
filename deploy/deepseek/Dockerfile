# The bank build pipeline must supply an internally mirrored, digest-pinned vLLM image.
ARG VLLM_IMAGE
FROM ${VLLM_IMAGE}

COPY --chown=1000:1000 model_agent.py /opt/legacy-pilot/model_agent.py
COPY --chown=1000:1000 model-agent /opt/legacy-pilot/model-agent
RUN chmod 0555 /opt/legacy-pilot/model_agent.py /opt/legacy-pilot/model-agent

USER 1000:1000
ENTRYPOINT []
