# Nodum

A modular platform for building, configuring, executing, testing, and managing backend systems visually.

## Project Structure

```
nodum/
├── web/                       # Frontend TypeScript (React + Vite)
│   ├── src/
│   │   ├── app/              # Application setup
│   │   ├── routes/           # Route definitions
│   │   ├── components/       # UI components
│   │   ├── features/         # Feature modules
│   │   ├── api/              # API client
│   │   ├── hooks/            # Custom hooks
│   │   └── types/            # TypeScript types
│   ├── package.json
│   └── vite.config.ts
├── back/                      # Backend Java (Spring Boot)
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/nodum/
│   │   │   │   ├── identity/      # User authentication
│   │   │   │   ├── projects/      # Project management
│   │   │   │   ├── apis/          # API definitions
│   │   │   │   ├── schemas/       # Schema management
│   │   │   │   ├── runtime/       # Dynamic execution
│   │   │   │   ├── persistence/   # Database adapters
│   │   │   │   ├── modules/       # Module implementations
│   │   │   │   ├── security/      # Security policies
│   │   │   │   ├── publishing/    # Version control
│   │   │   │   ├── operations/    # Business operations
│   │   │   │   ├── observability/ # Logging and metrics
│   │   │   │   └── shared/        # Shared utilities
│   │   │   └── resources/
│   │   └── test/
│   └── pom.xml
├── docs/                      # Documentation
├── docker-compose.yml          # Local PostgreSQL
└── README.md
```

## Development

### Prerequisites

- Node.js 18+
- Java 21
- Maven 3.8+
- Docker (for full stack or PostgreSQL only)

### Option 1: Full Stack with Docker Compose

```bash
# Prepare local credentials once. Keep .env private and generate every required secret.
cp .env.example .env

# Build and run all services (postgres, back, web)
docker compose up -d --build

# View logs
docker compose logs -f

# Stop all services
docker compose down

# Stop services without removing data
docker compose down

# Only if you explicitly intend to delete local data (not needed for normal tests)
# docker compose down -v
```

Access:
- Frontend: http://localhost:5173
- Backend: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

Kafka is optional. Start its local broker with `docker compose --profile messaging up -d kafka`. Configure a module with `kafka:9092` when the backend runs in Compose, or `localhost:9094` when the backend runs directly on the host.

Gateway routes are disabled until an operator allowlists target hosts. Set `NODUM_GATEWAY_ALLOWED_HOSTS` in the environment used by Compose (for example, the root `.env` file) to the exact service hostnames the Gateway may reach. Each route request is capped at 1 MiB and 8 seconds, redirects are not followed, and the module has a 120 requests per minute in-memory limit. The runtime route requires a panel JWT unless that module configuration explicitly sets `public: true`.
By default, upstream hostnames that resolve to loopback, link-local, private, or multicast addresses are rejected to reduce SSRF risk. For a deliberately internal upstream on a trusted network, set `NODUM_GATEWAY_ALLOW_PRIVATE_ADDRESSES=true` as an explicit operator decision in addition to the host allowlist.

### Option 2: Local Development

#### Frontend (/web)

```bash
cd web
npm install
npm run dev
```

The frontend runs on http://localhost:5173 by default.

#### Backend (/back)

```bash
# Start PostgreSQL only (from the repository root)
docker compose --env-file .env up -d postgres

cd back
mvn spring-boot:run
```

The backend runs on http://localhost:8080 by default.

## Authentication Configuration

### Admin User Initialization

Nodum is an administrative platform. Admin credentials must be configured via environment variables before starting the backend.

**Required Environment Variables:**

```bash
NODUM_ADMIN_USERNAME=admin          # Admin username
NODUM_ADMIN_PASSWORD=your_password   # Admin password (required)
NODUM_ADMIN_EMAIL=admin@nodum.local  # Admin email
POSTGRES_PASSWORD=...               # Password for the platform PostgreSQL database
JWT_SECRET=...                       # Random secret of at least 64 bytes for HS512
```

Compose maps `POSTGRES_PASSWORD` to the backend's `SPRING_DATASOURCE_PASSWORD`; do not maintain a second datasource password in `.env`.

Generate local values without putting them in shell history or logs, then write them only to the ignored `.env` file:

```bash
openssl rand -hex 32  # JWT_SECRET: use the output as a 64-byte hexadecimal secret
openssl rand -hex 24  # NODUM_ADMIN_PASSWORD: use the output as the local admin password
```

**Important:**
- The admin user is created on first startup only; changing these environment variables later does not rotate the saved password
- Password is stored as a BCrypt hash in the database
- `JWT_SECRET` must be random, non-placeholder, and at least 64 bytes for HS512; startup fails otherwise
- `POSTGRES_PASSWORD` must match the password that initialized the existing PostgreSQL volume; Nodum never changes it automatically
- Docker Compose reads the root `.env` file; local `mvn spring-boot:run` requires the variables in the current shell

To verify the existing PostgreSQL credentials, use the same values in a local shell without printing them and run a harmless connection check. A successful backend startup and Flyway migration confirm the configured password; a failure must be investigated before changing the volume. Never use `docker compose down -v` for this check.

### Remote Gateway migration

The Gateway can run in-process or in the `gateway-module` container. Remote mode is enabled only when `NODUM_GATEWAY_REMOTE_URL` is set in the root `.env` and `NODUM_MODULE_SERVICE_TOKEN` contains a private service token shared by the backend and Gateway service.

```dotenv
NODUM_GATEWAY_REMOTE_URL=http://gateway-module:8081
NODUM_MODULE_SERVICE_TOKEN=<generate-a-private-service-token>
```

The token must not be stored in module configuration, logs or PostgreSQL. The legacy `GatewayModule` remains active only as fallback when no remote URL is configured.

To test locally with Docker access:

```bash
docker compose --env-file .env up -d --build postgres back gateway-module
curl -i http://localhost:8080/api/v1/health
docker compose restart gateway-module
curl -i http://localhost:8080/api/v1/projects
```

These container commands are intentionally not executed in this environment while Docker socket access is denied.

To roll back without deleting data:

```bash
# Remove NODUM_GATEWAY_REMOTE_URL from .env or set it to an empty value
# Restart the backend
docker compose --env-file .env up -d --force-recreate back
```

The legacy in-process provider will be selected again, and existing module records remain unchanged.

### Infrastructure Agent

Nodum communicates with containers only through `infrastructure-agent`. The agent is the sole Compose service that mounts `/var/run/docker.sock`; `back` and module containers do not receive the socket.

The agent accepts only `create`, `start`, `stop`, `restart`, and `inspect` for the existing `http-gateway` provider and an instance UUID. It does not accept arbitrary images, commands, mounts, networks, volumes or environment variables.

Generate separate private service tokens and keep them in the ignored root `.env`:

```dotenv
NODUM_INFRASTRUCTURE_AGENT_TOKEN=<private-agent-token>
NODUM_MODULE_SERVICE_TOKEN=<private-module-token>
NODUM_INFRASTRUCTURE_AGENT_URL=http://infrastructure-agent:8090
NODUM_GATEWAY_REMOTE_URL=http://gateway-module:8081
```

Gateway containers are created with fixed limits: 512 MiB memory, 0.75 CPU, 128 PIDs, no privileged mode and no binds. Container IDs and operations are restricted by `nodum.provider-id` and `nodum.instance-id` labels.

After Docker access is authorized, execute:

```bash
docker compose --env-file .env up -d --build postgres infrastructure-agent gateway-module back web
curl -i http://localhost:8080/api/v1/health
docker inspect nodum-module-http-gateway-<instance-id> \
  --format '{{.HostConfig.Memory}} {{.HostConfig.CpuQuota}} {{.HostConfig.PidsLimit}}'
docker restart nodum-module-http-gateway-<instance-id>
curl -i http://localhost:8080/api/v1/projects
```

Do not remove volumes. Docker integration remains pending until `/var/run/docker.sock` is accessible to the terminal.

### Remote Kafka module

Kafka runs in `kafka-module` and exposes the same v1 contract as Gateway. Its provider ID remains `kafka-messaging` and version `1.0.0`; existing `publish` and `read` actions remain compatible.

Configure private service and broker values in the ignored root `.env`:

```dotenv
NODUM_KAFKA_REMOTE_URL=http://kafka-module:8082
NODUM_KAFKA_REMOTE_TOKEN=<private-kafka-service-token>
NODUM_KAFKA_BROKERS=kafka:9092
```

Do not place passwords or tokens in module `configuration_json`. Kafka containers use 256 MiB, 0.25 CPU and 64 PIDs. The Infrastructure Agent applies a fixed image and environment; HTTP requests cannot provide Docker images, commands, mounts or volumes.

After Docker access and broker configuration are available:

```bash
docker compose --env-file .env up -d --build postgres infrastructure-agent gateway-module kafka-module back web
curl -i http://localhost:8080/api/v1/health
curl -i http://localhost:8080/api/v1/module-catalog
```

Rollback keeps existing Java Kafka fallback behavior: remove `NODUM_KAFKA_REMOTE_URL` from `.env` and restart `back`. Existing module records are not deleted.

### Password reset

Nodum does not overwrite an existing administrator password during startup. An authenticated administrator can change it with:

```bash
curl -X POST http://localhost:8080/api/v1/auth/password \
	-H "Authorization: Bearer <current-jwt>" \
	-H "Content-Type: application/json" \
	-d '{"currentPassword":"<current-password>","newPassword":"<new-password>"}'
```

The current password is required and the new password is stored as BCrypt. If access is lost, generate a BCrypt hash through an offline administrative procedure and update only that user's `password_hash` row inside a transaction; do not delete the database or volume. Verify the PostgreSQL credentials separately with the operator who created the volume before changing `.env`.

For local recovery when the previous password is unavailable, use the explicit administrative tool. It is not exposed over HTTP and is not started by Spring Boot:

```bash
set -a
. ../.env
set +a
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/nodum
export SPRING_DATASOURCE_USERNAME=nodum
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export NODUM_ADMIN_USERNAME=admin

./mvnw -q dependency:build-classpath -Dmdep.outputFile=/tmp/nodum-back.classpath
java -cp "target/classes:$(cat /tmp/nodum-back.classpath)" \
	com.nodum.identity.AdminPasswordResetTool
```

Run it from `back` in an interactive terminal. It prompts twice without echoing the password, uses BCrypt, updates only the selected administrator's `password_hash` and `updated_at`, and commits only when exactly one row is updated. It does not alter `POSTGRES_PASSWORD`, users, projects, tables, or volumes. Afterward, obtain a JWT through `/api/v1/auth/login` and use it to call `/api/v1/projects`.

### Frontend Authentication

The panel always requires authentication. Sign in with the administrator credentials configured for the backend. The JWT is held by the browser and sent to protected API routes. There is no public registration, country-based access control, or unauthenticated panel mode.

### API Documentation

Once the backend is running, access OpenAPI documentation at:
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/api-docs

## Architecture

Nodum is organized into three layers:

1. **Control Plane**: Administrative core (authentication, configuration, versioning)
2. **Execution Plane**: Runtime execution of user-defined systems
3. **Resource Layer**: External resource management (databases, brokers, containers)

See [docs/architecture.md](docs/architecture.md) for detailed architecture documentation.

## Current Implementation

The repository is an early Control Plane prototype. It includes administrator login, project CRUD, a trusted in-process module SPI, project module instances with persisted configuration/lifecycle, Kafka producer/consumer actions, an allowlisted HTTP Gateway proxy action, JSON contract metadata, backend health, and a browser HTTP request tool. Providers run inside the backend process; there is no runtime isolation, RabbitMQ, public Gateway ingress, dynamic API generation, or production secret manager yet. See [the module provider guide](docs/modules.md). Architectural documents describe target direction as well as the current module phase; they are not claims of isolation guarantees.

The backend requires explicit administrator and JWT secrets and protects project, module, and contract endpoints with JWT authentication. The local Compose stack uses plaintext development services and is intended for a trusted machine, not direct internet exposure.
