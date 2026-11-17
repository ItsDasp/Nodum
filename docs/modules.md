# Adding a Nodum module provider

Nodum now has an initial provider SPI in `back/src/main/java/com/nodum/modules/spi`.

## What this currently supports

- Spring discovers trusted provider beans at backend startup.
- Each provider declares a stable ID, provider version, SPI contract version, display name, description, and capability IDs.
- The registry rejects duplicate provider IDs and exposes the installed catalog at `GET /api/v1/module-catalog` (JWT required).
- Providers define configuration validation, start, stop, health, and optional actions.
- Project module instances persist provider identity, version, JSON configuration, and lifecycle status. Start and stop operations restore after a backend restart.
- The built-in Kafka provider creates/verifies one topic, publishes JSON/text values, and optionally buffers consumed messages for the panel to read.
- The built-in HTTP Gateway provider routes proxy actions, or requests to `/api/v1/runtime/gateway/{projectId}/{moduleId}/...`, to a matching path prefix and only to upstream hosts in `NODUM_GATEWAY_ALLOWED_HOSTS`. Ingress requires a panel JWT unless the module configuration explicitly sets `public: true`; each instance is limited to 120 requests per minute.

The panel can create project instances from installed providers. A module record without a provider remains metadata only. Providers are compiled into the backend and execute in its process; the panel cannot upload arbitrary code. RabbitMQ, a secret manager, and process isolation are not implemented. Kafka's consumer buffer is transient and bounded, and Kafka SASL/TLS credentials are not supported. These providers are functional development adapters, not isolated production services.

## Implement a provider

1. Add a Spring `@Component` that implements `ModulePlugin`.
2. Return a unique `ModuleDescriptor` with a stable lowercase ID, semantic provider version, current contract version, and precise capabilities.
3. Parse and validate configuration without evaluating user supplied code. Return actionable errors.
4. Implement start, stop, and health checks with bounded timeouts and repeat-safe lifecycle behavior.
5. Keep secrets out of descriptors, errors, logs, and persisted module configuration. The secret reference mechanism is not implemented yet.
6. Add tests for invalid config, partial startup failures, idempotent stop, and dependency outages.

Providers run inside the Control Plane JVM. They are trusted code and can affect the host's memory, classpath, and availability. Use a separately deployed service and a versioned remote contract when a provider needs dependency conflicts or fault isolation. Gateway upstream hosts are checked against the operator's environment allowlist; redirects are not followed, and request/response bodies are capped at 1 MiB. Kafka messages and broker credentials need stronger persistence, retry, and secret handling before production use.
