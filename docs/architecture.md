# Nodum Architecture Documentation

## Implementation status

This document describes the intended architecture alongside the implemented first module phase. The `com.nodum.modules.spi` registry now discovers the Kafka and HTTP Gateway providers. Project instances persist their provider ID/version, validated JSON configuration, and lifecycle state. The panel can create instances, start/stop them, check state, and invoke provider actions. RabbitMQ, dynamic API generation, a standalone public Gateway ingress, and process isolation are not implemented. A module instance created without a provider remains metadata only.

The SPI is an extension seam for trusted providers compiled into the host. Implement `ModulePlugin` as a Spring bean, declare a unique descriptor and capabilities, validate configuration, and implement start/stop/health/actions. The registry rejects duplicate IDs and sorts the catalog. Providers run in the Control Plane JVM and share its memory, dependencies, and failure domain; this is code modularity, not runtime isolation. Kafka currently provides a real producer and optional consumer whose uncommitted records are exposed in a bounded in-memory buffer. Gateway currently forwards requests only through an authenticated module action and an operator-configured host allowlist. Gateway validation also rejects allowlisted hosts resolving to loopback, link-local, private, or multicast addresses by default; internal upstreams require the explicit `NODUM_GATEWAY_ALLOW_PRIVATE_ADDRESSES=true` setting. Neither provider offers process isolation or production secret management.

## Overview

Nodum is organized into three architectural layers with clear separation of concerns:

### 1. Control Plane

**Responsibilities:**
- Authentication and authorization of the admin panel
- User and project management
- Configuration storage and validation
- Version management
- Module catalog management
- Dependency resolution
- Operation orchestration
- Publication management
- Administrative logs and audit
- Exposure of admin APIs consumed by `/web/`

**Key Characteristics:**
- Must remain operational when user project resources (brokers, generated APIs, project databases) fail
- Stores configurations in a declarative, validated format
- Manages the lifecycle of modules without executing them directly
- Provides the administrative interface for all operations

**Packages:**
- `identity`: User authentication, sessions, permissions
- `projects`: Project CRUD and metadata
- `apis`: API definition storage and validation
- `schemas`: Schema definition management
- `publishing`: Version control and publication orchestration
- `modules`: Module catalog and contract management
- `security`: Panel security policies
- `observability`: Platform-level logging and metrics

### 2. Execution Plane

**Responsibilities:**
- Interpret API configurations
- Expose dynamic endpoints
- Execute validations and configured operations
- Apply authentication and authorization policies
- Access associated databases
- Publish and consume messages
- Apply module execution policies
- Manage lifecycle of executable components

**Key Characteristics:**
- Executes user-created systems based on Control Plane configurations
- Independent of the web interface (can run without active admin session)
- Implements the runtime behavior defined in configurations
- Applies security policies at runtime

**Packages:**
- `runtime`: Dynamic endpoint resolution and execution
- `operations`: Business operation execution
- `security`: Runtime security enforcement (JWT validation, permission checks)

### 3. Resource Layer

**Responsibilities:**
- Database connections and management
- Message broker integration (RabbitMQ, Kafka)
- Container management (Docker module)
- External service integrations
- Resource health monitoring

**Key Characteristics:**
- Provides adapters/contracts for external resource access
- Isolates resource-specific implementations behind interfaces
- Manages connection pools and lifecycle

**Packages:**
- `persistence`: Database adapters and connection management
- `modules`: Resource-specific implementations (RabbitMQ, Docker, etc.)

## Separation and Fault Tolerance

**Dependency Rules:**
- Control Plane → Resource Layer (for internal persistence only)
- Execution Plane → Control Plane (reads configurations)
- Execution Plane → Resource Layer (executes operations)
- Control Plane does NOT depend on Execution Plane runtime
- Execution Plane does NOT depend on Control Plane being fully operational after config load

**Failure Scenarios:**
- RabbitMQ down: Control Plane remains operational, projects without RabbitMQ continue
- Project stopped: Control Plane remains operational
- Module optional: Core does not attempt to initialize it as mandatory

## Modular System

### Module Contract Interface

```java
public interface Module {
    String getId();
    String getName();
    String getVersion();
    ModuleType getType();
    
    // Lifecycle operations
    ValidationResult validateConfiguration(ModuleConfiguration config);
    void prepareResources(ModuleConfiguration config) throws ModuleException;
    void start(ModuleConfiguration config) throws ModuleException;
    void stop(ModuleConfiguration config) throws ModuleException;
    void restart(ModuleConfiguration config) throws ModuleException;
    
    // Status and health
    ModuleStatus getStatus(ModuleConfiguration config);
    HealthCheckResult healthCheck(ModuleConfiguration config);
    
    // Logs and diagnostics
    List<LogEntry> getLogs(ModuleConfiguration config, LogQuery query);
    
    // Optional capabilities
    default boolean supports(Class<?> capability) {
        return false;
    }
}
```

### Module States

- `CONFIGURED`: Definition exists, not validated
- `VALIDATING`: Configuration validation in progress
- `PREPARING`: Resources being allocated
- `STARTING`: Initialization in progress
- `RUNNING`: Operational
- `DEGRADED`: Partially operational
- `STOPPING`: Shutdown in progress
- `STOPPED`: Not running
- `ERROR`: Failed state

### Module Types

- `API_RUNTIME`: Dynamic API execution
- `DATABASE`: Database connection and management
- `MESSAGE_BROKER`: RabbitMQ, Kafka
- `AUTHENTICATION`: Identity providers
- `CONTAINER`: Docker management
- `OBSERVABILITY`: Metrics and tracing

## Versioned Configuration Storage

### Storage Strategy

Configurations are stored with explicit versioning:

1. **Draft Version**: Work-in-progress configuration
2. **Validated Version**: Configuration passed validation
3. **Published Version**: Active configuration in use

### Database Schema

```sql
-- Configuration versions table
CREATE TABLE configuration_versions (
    id UUID PRIMARY KEY,
    entity_id UUID NOT NULL,  -- References API, module, etc.
    entity_type VARCHAR(50) NOT NULL,
    version_number INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,  -- DRAFT, VALIDATED, PUBLISHED, RETIRED
    configuration JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    UNIQUE(entity_id, entity_type, version_number)
);

-- Active version tracking
CREATE TABLE active_versions (
    entity_id UUID PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    active_version_id UUID REFERENCES configuration_versions(id)
);
```

### Rollback Strategy

- Previous published versions remain available
- Rollback reactivates a previous version_id
- Data in external resources (databases, brokers) is NOT automatically reverted
- Destructive schema changes require explicit confirmation

## Security Architecture

### Panel Authentication (Control Plane)
- JWT-based or session-based authentication
- Local authentication initially
- Extensible to Microsoft Entra ID via adapters
- Role-based access control for admin operations

### API Authentication (Execution Plane)
- User-defined authentication endpoints
- JWT validation with signature, issuer, audience checks
- Scope/permission-based authorization per endpoint
- Secrets stored securely (not in plain text)
- Deny-by-default policy

### Separation of Concerns
- Panel credentials ≠ API credentials
- Panel tokens do not grant access to user projects
- API tokens do not grant admin access to Nodum

## Technology Stack Decisions

### Frontend
- **React + TypeScript**: Type safety, component reusability
- **Vite**: Fast development server and build
- **Tailwind CSS**: Utility-first styling
- **React Router**: Client-side routing
- **TanStack Query**: Server state management
- **React Hook Form + Zod**: Form validation
- **Monaco Editor**: Code editing

### Backend
- **Java 21 LTS**: Modern Java with long-term support
- **Spring Boot 3.3**: Opinionated framework with auto-configuration
- **Spring Data JPA**: Database abstraction
- **PostgreSQL**: Reliable relational database for internal persistence
- **Flyway**: Database migration management
- **SpringDoc OpenAPI**: API documentation
- **Spring Security**: Authentication and authorization

## Risks and Mitigations

### Risk 1: Tight Coupling Between Layers
**Mitigation**: Define explicit interfaces, use dependency injection, avoid direct access to internal implementations

### Risk 2: Module Failure Affecting Core
**Mitigation**: Isolate module execution, implement circuit breakers, ensure Control Plane independence

### Risk 3: Security Vulnerabilities in Dynamic Execution
**Mitigation**: Validate all configurations, sandbox where possible, deny-by-default policies, audit all operations

### Risk 4: Configuration Drift
**Mitigation**: Version all configurations, track active versions, implement validation before publication

### Risk 5: Database Schema Conflicts
**Mitigation**: Separate internal Nodum schema from user project schemas, use explicit schema names, controlled migrations

### Risk 6: Secret Exposure
**Mitigation**: Store secrets separately from configurations, never return secrets to frontend, use environment variables or secret managers

## Next Steps

1. Implement basic HTTP integration between frontend and backend
2. Add authentication to the Control Plane
3. Implement project CRUD operations
4. Design and implement the module registry
5. Build the API Builder configuration interface
6. Implement dynamic endpoint execution
