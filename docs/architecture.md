# Arquitectura de Nodum

> [!NOTE]
> Este documento describe la arquitectura objetivo junto al estado real de implementación. Las funcionalidades futuras y pendientes están centralizadas en [`TODO.md`](TODO.md).

## Estado de implementación

La arquitectura de tres planos ya está parcialmente materializada:

- **Plano de control**: autenticación JWT, CRUD de proyectos, catálogo de módulos, instancias con configuración JSON validada y ciclo de vida persistido (`lifecycle_state`, `observed_state`, `lifecycle_error`).
- **SPI de módulos** (`com.nodum.modules.spi`): descubrimiento de proveedores confiables en el arranque del backend, contrato `ModulePlugin`, catálogo en `GET /api/v1/module-catalog` (JWT requerido) y acciones de instancia.
- **Proveedores implementados**: `kafka-messaging` (productor/consumidor real, buffer limitado en memoria) y `http-gateway` (proxy autenticado con allowlist), ambos disponibles también como servicios remotos (`kafka-module`, `gateway-module`) gestionados por `infrastructure-agent`.
- **Shell Linux remota**: terminal PTY sobre WebSocket en contenedores Alpine aislados por proyecto.

Una instancia de módulo creada sin proveedor permanece como metadato.

El SPI es una costura de extensión para proveedores **confiables compilados en el host**. Los proveedores se registran como beans de Spring, comparten el classpath, la memoria y el dominio de fallos del plano de control: es modularidad de código, **no** aislamiento de runtime. Para proveedores que requieran aislamiento, la dirección es un servicio desplegado por separado con un contrato remoto versionado (patrón ya aplicado a `gateway-module` y `kafka-module`).

---

## 1. Plano de control

**Responsabilidades**

- Autenticación y autorización del panel administrativo.
- Gestión de usuarios y proyectos.
- Almacenamiento y validación de configuraciones.
- Gestión del catálogo de módulos y resolución de dependencias.
- Orquestación de operaciones y publicación de contratos.
- Logs administrativos y auditoría.
- Exposición de las APIs administrativas consumidas por `web/`.

**Características clave**

- Debe permanecer operativo aunque fallen los recursos de los proyectos de usuario (brokers, APIs generadas, bases de datos de proyecto).
- Almacena configuraciones en formato declarativo y validado.
- Gestiona el ciclo de vida de los módulos sin ejecutarlos directamente en el plano de ejecución.
- Provee la interfaz administrativa de todas las operaciones.

**Paquetes** (`com.nodum.*`)

| Paquete | Contenido |
|---|---|
| `identity` | Autenticación de usuarios, JWT, contraseña admin |
| `projects` | CRUD de proyectos y metadatos |
| `apis` | Definición y validación de APIs/contratos |
| `schemas` | Gestión de esquemas JSON |
| `publishing` | Versionado y publicación de contratos |
| `modules` | Catálogo, SPI y contrato de proveedores |
| `runtime` | Resolución y ejecución dinámica |
| `persistence` | Adaptadores JPA/PostgreSQL |
| `security` | Políticas de seguridad del panel |
| `operations` | Operaciones de negocio |
| `observability` | Logs y métricas de plataforma |
| `shared` | Utilidades compartidas |

## 2. Plano de ejecución

**Responsabilidades**

- Interpretar las configuraciones de API.
- Exponer endpoints dinámicos.
- Ejecutar validaciones y operaciones configuradas.
- Aplicar políticas de autenticación y autorización.
- Acceder a las bases de datos asociadas.
- Publicar y consumir mensajes.
- Gestionar el ciclo de vida de los componentes ejecutables.

**Características clave**

- Ejecuta sistemas creados por usuarios a partir de configuraciones del plano de control.
- Independiente de la interfaz web (puede correr sin sesión de admin activa).
- Aplica las políticas de seguridad en runtime.

**Paquetes**

- `runtime`: resolución de endpoints y ejecución dinámica (incluye el ingress del Gateway).
- `operations`: ejecución de operaciones de negocio.
- `security`: enforcement en runtime (validación JWT, permisos).

## 3. Capa de recursos

**Responsabilidades**

- Conexiones y gestión de bases de datos.
- Integración con brokers de mensajes (Kafka).
- Gestión de contenedores vía `infrastructure-agent`.
- Integraciones con servicios externos.
- Monitoreo de salud de recursos.

**Características clave**

- Adaptadores/contratos para acceso a recursos externos.
- Aísla las implementaciones específicas detrás de interfaces.
- Gestiona pools de conexiones y ciclos de vida.

**Puntos de integración actuales**

- `persistence`: JPA/Hibernate sobre PostgreSQL 16 con Flyway.
- `kafka-module`: servicio remoto Spring Boot que habla con el clúster KRaft por AdminClient/producer/consumer.
- `gateway-module`: servicio remoto Spring Boot que actúa como proxy HTTP.
- `infrastructure-agent`: gestor Docker (único con `/var/run/docker.sock`).

---

## Separación de capas y tolerancia a fallos

### Reglas de dependencia

```text
Plano de control  ──►  Capa de recursos   (solo persistencia interna)
Plano de ejecución ──►  Plano de control  (lee configuraciones)
Plano de ejecución ──►  Capa de recursos   (ejecuta operaciones)
```

- El plano de control **no** depende del runtime del plano de ejecución.
- El plano de ejecución **no** depende de que el plano de control siga operativo tras cargar la configuración.

### Escenarios de fallo

| Fallo | Resultado esperado |
|---|---|
| Broker Kafka caído | El plano de control sigue operativo; los proyectos sin Kafka continúan |
| Módulo remoto caído | El módulo queda en estado `DEGRADED`/`ERROR` sin tumbar el panel |
| Proyecto detenido | El plano de control permanece operativo |
| Recurso opcional | El núcleo nunca lo inicializa como dependencia obligatoria |

---

## Sistema modular

### SPI de proveedores (`ModulePlugin`)

Contrato implementado por cada proveedor confiable:

```java
public interface ModulePlugin {
    ModuleDescriptor descriptor();                    // id, versión, capacidades
    ValidationResult validateConfiguration(JsonNode config);
    void start(ModuleInstance instance) throws ModuleException;
    void stop(ModuleInstance instance) throws ModuleException;
    HealthResult health(ModuleInstance instance);
    default ActionResult execute(ModuleInstance instance, String action, JsonNode input) { ... }
}
```

Reglas del registro:

- Spring descubre beans `ModulePlugin` en el arranque.
- El registro rechaza IDs duplicados y ordena el catálogo.
- Cada proveedor declara ID estable, versión semántica, versión del contrato SPI y capacidades.
- Los proveedores **no** pueden subir código arbitrario desde el panel.

### Estados del módulo

`CONFIGURED` → `VALIDATING` → `PREPARING` → `STARTING` → `RUNNING` → (`DEGRADED` | `STOPPING` → `STOPPED` | `ERROR`)

- `RUNNING`: operativo.
- `DEGRADED`: parcialmente operativo.
- `ERROR`: estado de fallo con `lifecycle_error`/`runtime_error` visibles en el panel.
- `STOPPED`: no ejecutándose.

### Tipos de módulo implementados

| Tipo | Proveedor |
|---|---|
| `MESSAGE_BROKER` | `kafka-messaging` (productor/consumidor + buffer en memoria) |
| `API_RUNTIME` / Gateway HTTP | `http-gateway` (proxy autenticado + ingress JWT) |
| `CONTAINER` | `linux-shell` y módulos Runtime vía `infrastructure-agent` |

---

## Almacenamiento versionado de configuraciones

### Estrategia

Las configuraciones se guardan con versionado explícito:

1. **Borrador** (`DRAFT`): trabajo en progreso.
2. **Validada** (`VALIDATED`): superó validación.
3. **Publicada** (`PUBLISHED`): configuración activa.

Las versiones publicadas anteriores permanecen disponibles para rollback: se reactiva un `version_id` previo. Los datos en recursos externos (bases de datos, brokers) **no** se revierten automáticamente; los cambios destructivos de esquema requieren confirmación explícita.

Esquema conceptual:

```sql
CREATE TABLE configuration_versions (
    id UUID PRIMARY KEY,
    entity_id UUID NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    version_number INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,          -- DRAFT, VALIDATED, PUBLISHED, RETIRED
    configuration JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    UNIQUE(entity_id, entity_type, version_number)
);

CREATE TABLE active_versions (
    entity_id UUID PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    active_version_id UUID REFERENCES configuration_versions(id)
);
```

---

## Arquitectura de seguridad

### Autenticación del panel (plano de control)

- JWT HS512 firmado con `JWT_SECRET` (mínimo 64 bytes).
- Credenciales del administrador sembradas en el primer arranque; contraseña en BCrypt.
- Sin registro público ni modo anónimo.
- Cambio de contraseña autenticado vía `POST /api/v1/auth/password`; recuperación offline con `AdminPasswordResetTool`.

### Autenticación de APIs (plano de ejecución)

- Endpoints definidos por el usuario con políticas propias.
- Validación JWT con firma, issuer y audience.
- Autorización por scopes/permisos por endpoint.
- Política deny-by-default.

### Separación de credenciales

- Credenciales del panel ≠ credenciales de APIs de usuario.
- Tokens del panel no otorgan acceso a proyectos de usuario.
- Tokens de API no otorgan acceso administrativo a Nodum.
- Tokens de servicio (`NODUM_MODULE_SERVICE_TOKEN`, `NODUM_INFRASTRUCTURE_AGENT_TOKEN`) nunca se persisten en módulos ni aparecen en logs.

### Aislamiento de infraestructura

- Solo `infrastructure-agent` monta `/var/run/docker.sock`.
- Contenedores de módulos con límites fijos de CPU/memoria/procesos, sin privilegios ni montajes arbitrarios.
- Shells remotas en red Docker privada por proyecto, usuario sin privilegios, `no-new-privileges`.
- Gateway con allowlist de hosts y rechazo de destinos privados/loopback por defecto (anti-SSRF).

---

## Stack tecnológico

### Frontend (`web/`)

| Tecnología | Uso |
|---|---|
| React + TypeScript | UI del panel |
| Vite | Dev server y build |
| Tailwind CSS | Estilos utility-first |
| React Router | Ruteo cliente |
| TanStack Query | Estado de servidor |
| xterm.js | Terminal remota |

### Backend (`back/`, `gateway/`, `kafka-module/`)

| Tecnología | Uso |
|---|---|
| Java 21 LTS | Runtime |
| Spring Boot 3.3 | Framework |
| Spring Data JPA + Hibernate | Persistencia |
| PostgreSQL 16 | Base de datos interna |
| Flyway | Migraciones |
| SpringDoc OpenAPI | Documentación API (Swagger UI) |
| Spring Security | AuthN/AuthZ |

### Infraestructura

| Componente | Rol |
|---|---|
| Docker Compose | Orquestación local del stack |
| Apache Kafka 3.8.1 (KRaft) | Clúster 3 nodos broker+controller |
| provectuslabs/kafka-ui | Administración visual de Kafka |
| Alpine Linux | Imagen base de shells remotas |

---

## Riesgos y mitigaciones

| # | Riesgo | Mitigación |
|---|---|---|
| 1 | Acoplamiento entre capas | Interfaces explícitas, inyección de dependencias, sin acceso a implementaciones internas |
| 2 | Falla de módulo afecta al core | Proveedores remotos para lo crítico, estados de ciclo de vida, circuit breakers |
| 3 | Vulnerabilidades en ejecución dinámica | Validación de configuraciones, deny-by-default, auditoría, allowlists |
| 4 | Deriva de configuración | Versionado, versión activa explícita, validación previa a publicación |
| 5 | Conflictos de esquema de BD | Esquema interno Nodum separado del de proyectos, migraciones controladas |
| 6 | Exposición de secretos | Secretos fuera de configuraciones/logs/BD, variables de entorno, tokens de servicio separados |
| 7 | Aislamiento insuficiente de proveedores | Migración progresiva a servicios remotos (gateway/kafka ya lo están) |

---

## Trabajo pendiente

El backlog de funcionalidades futuras (RabbitMQ, APIs dinámicas, TLS, gestor de secretos, etc.) está centralizado en [`TODO.md`](TODO.md).
