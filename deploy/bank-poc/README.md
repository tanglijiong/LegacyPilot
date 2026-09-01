# Bank-internal single-node reference deployment

This directory is a deployment hand-off, not a claim that LegacyPilot is installed in a real bank environment. It describes a conservative single-node PoC in which the bank supplies approved images, model weights, certificates, identity provider and secrets.

## Topology

```text
Bank user -> TLS reverse proxy / OIDC -> LegacyPilot Server (dedicated rootless-Docker host)
                                      -> PostgreSQL (localhost or protected DB segment)
                                      -> task worktrees and audit artifacts (encrypted volume)
                                      -> rootless Docker Maven sandboxes (network none)
                                      -> vLLM/DeepSeek over Unix socket (network none)
```

The Server runs directly under a dedicated operating-system account. Do not mount a privileged Docker socket into the Server container. Docker access is effectively host administration; use rootless Docker on a host dedicated to LegacyPilot and do not colocate unrelated workloads.

## Required bank-supplied inputs

- JDK 21 runtime and the packaged Server JAR;
- approved PostgreSQL image pinned by digest;
- approved Maven sandbox image pinned by digest;
- approved vLLM/DeepSeek image and read-only weights with recorded SHA-256;
- internal CA certificate, TLS termination and OIDC issuer/JWK configuration;
- secrets delivered through the bank secret manager or protected systemd credential mechanism;
- encrypted data, worktree, Maven cache, model socket and backup volumes.

No example file in this directory contains a working credential.

## Installation sequence

1. Mirror and scan every image and Maven artifact in a connected staging zone.
2. Generate `bom.json`, artifact checksums and third-party notices in the release pipeline.
3. Transfer the signed bundle through the bank's approved media process.
4. Run `preflight.sh` on the dedicated host.
5. Start PostgreSQL using `postgres.compose.yml`, bound to loopback by default.
6. Install `application-bank.yml`, `policy-bank.yml` and the systemd unit using bank-owned paths.
7. Start the approved vLLM service through `../deepseek/start_model_service.py`.
8. Set `LEGACY_PILOT_MODEL_SOCKET` to the approved socket and start the Server.
9. Run the five-task smoke acceptance before importing any internal repository.

## Fail-closed defaults

- REST access is denied until `LEGACY_PILOT_SECURITY_MODE=oidc` and JWT validation are configured.
- OpenAPI UI is disabled unless explicitly enabled for an administrator-only test network.
- Task builds and the model have no network.
- Writes require Policy approval; approvers cannot approve runs they initiated.
- The Server does not push, merge or deploy changed application code.

## Files

- `application-bank.yml`: externalized PostgreSQL, TLS/OIDC and storage configuration.
- `policy-bank.yml`: conservative approval policy.
- `postgres.compose.yml`: loopback-only single PostgreSQL reference.
- `systemd/legacypilot-server.service`: hardened host service example.
- `environment.example`: non-secret environment variable names.
- `preflight.sh`: read-only host and artifact checks.
- `build-evidence.sh`: release checksum, dependency and CycloneDX SBOM commands; requires approved Maven and PostgreSQL test images pinned by full digest and disables the auxiliary Ryuk image for offline verification.
- `../../docs/BANK_POC_API.md`: administrator OpenAPI access, unified API flow and stable error contract.

The bank remains responsible for host hardening, firewall enforcement, vulnerability acceptance, backup media, HSM/secret-manager integration and change-management approval.
