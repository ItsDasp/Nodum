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
# Prepare local credentials once. Keep .env private and change the example values.
cp .env.example .env

# Build and run all services (postgres, back, web)
docker compose up -d --build

# View logs
docker compose logs -f

# Stop all services
docker compose down

# Stop and remove volumes
docker compose down -v
```

Access:
- Frontend: http://localhost:5173
- Backend: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

Kafka is optional. Start its local broker with `docker compose --profile messaging up -d kafka`. Configure a module with `kafka:9092` when the backend runs in Compose, or `localhost:9094` when the backend runs directly on the host.

Gateway routes are disabled until an operator allowlists target hosts. Set `NODUM_GATEWAY_ALLOWED_HOSTS` in the environment used by Compose (for example, the root `.env` file) to the exact service hostnames the Gateway may reach. Each route request is capped at 1 MiB and 8 seconds, redirects are not followed, and the module has a 120 requests per minute in-memory limit. The runtime route requires a panel JWT unless that module configuration explicitly sets `public: true`.

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
SPRING_DATASOURCE_PASSWORD=...       # Password for the platform PostgreSQL database
JWT_SECRET=...                       # Random secret of at least 64 bytes for HS512
```

**Important:**
- The admin user is created on first startup only; changing these environment variables later does not rotate the saved password
- Password is stored as a BCrypt hash in the database
- `JWT_SECRET` must be a random value of at least 64 bytes for HS512
- The committed `.env.example` contains local development values; replace them before sharing the environment
- Docker Compose reads the root `.env` file; local `mvn spring-boot:run` requires the variables in the current shell

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
