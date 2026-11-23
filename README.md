<div align="center">

<a href="https://instagram.com/xidasp">
  <img src="https://i.imgur.com/DqvcxcH.png" alt="Banner" style="width: 200%; max-width: 3048px;">
</a>

**Plataforma modular para diseñar, configurar, ejecutar y administrar sistemas backend de forma visual.**

[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](back/pom.xml)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)](back/pom.xml)
[![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)](web/package.json)
[![TypeScript](https://img.shields.io/badge/TypeScript-6-3178C6?logo=typescript&logoColor=white)](web/package.json)
[![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite&logoColor=white)](web/package.json)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](docker-compose.yml)
[![License](https://img.shields.io/badge/License-Proprietary-lightgrey)](#)

</div>

---

## ¿Qué es Nodum?

Nodum es una plataforma de **plano de control** que permite crear proyectos backend completos desde una interfaz visual: definir módulos (Kafka, Gateway HTTP, Shell Linux), configurar su ciclo de vida, publicar contratos de API, probar endpoints y operar logs y terminales remotas, todo sin escribir infraestructura a mano.

> [!NOTE]
> Este es un prototipo funcional en desarrollo activo. La arquitectura está documentada en [`docs/architecture.md`](docs/architecture.md); las funcionalidades futuras y pendientes están centralizadas en [`docs/TODO.md`](docs/TODO.md).

## Características

- **Panel visual** con autenticación JWT para gestionar proyectos, módulos y contratos.
- **SPI de módulos**: proveedores confiables compilados en el backend (`ModulePlugin`) con catálogo, validación de configuración y ciclo de vida persistido.
- **Proveedor Kafka** (`kafka-messaging`): publicación/consumo de mensajes, administración de topics, grupos, offsets y lag desde el panel.
- **Proveedor Gateway HTTP** (`http-gateway`): proxy a upstreams con allowlist de hosts, límites de tamaño/tiempo y protección SSRF.
- **Shell Linux remota**: terminal interactiva por WebSocket con PTY, ejecutada en contenedores Alpine aislados por proyecto.
- **Clúster Kafka KRaft** de 3 nodos (broker + controller) levantado automáticamente con Compose.
- **Pruebas de API** desde el navegador y visor de logs en tiempo real.
- **Infraestructura como servicio**: los módulos se ejecutan como contenedores gestionados por un agente dedicado con el socket de Docker.

## Inicio rápido

### Requisitos previos

| Requisito | Versión |
|---|---|
| Docker + Docker Compose | reciente |
| Node.js | 18+ (solo desarrollo del frontend) |
| Java | 21 (solo desarrollo del backend) |
| Maven | 3.8+ (solo desarrollo del backend) |

### 1. Configurar el entorno

```bash
cp .env.example .env
```

Edita `.env` y completa todos los valores vacíos. Genera secretos seguros:

```bash
openssl rand -hex 32  # JWT_SECRET
openssl rand -hex 24  # NODUM_ADMIN_PASSWORD
```

> [!IMPORTANT]
> `.env` está ignorado por Git. **Nunca** subas credenciales reales al repositorio.

### 2. Levantar el stack completo

```bash
docker compose up -d --build
docker compose logs -f   # seguir el arranque
```

### 3. Acceder

| Servicio | URL |
|---|---|
| Panel web | http://localhost:5173 |
| API REST | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Kafka UI | http://localhost:8085 |

Al primer inicio, Nodum crea automáticamente el proyecto de demostración **"Tienda de demostración"** con un módulo Kafka operativo, un Gateway HTTP y contratos de ejemplo.

### Comandos útiles

```bash
docker compose ps              # estado de los servicios
docker compose logs -f back    # logs del backend
docker compose down            # detener (conserva datos)
docker compose down -v         # detener y BORRAR todos los datos locales
```

## Estructura del repositorio

```text
nodum/
├── back/                      # Backend: Java 21 + Spring Boot 3.3
│   ├── src/main/java/com/nodum/
│   │   ├── identity/          # Autenticación JWT y usuarios
│   │   ├── projects/          # CRUD de proyectos
│   │   ├── modules/           # SPI de proveedores y ciclo de vida
│   │   ├── runtime/           # Runtime dinámico y gateway
│   │   ├── persistence/       # Adaptadores JPA/PostgreSQL
│   │   ├── observability/     # Logs y métricas
│   │   └── shared/            # Utilidades compartidas
│   ├── Dockerfile
│   └── pom.xml
├── web/                       # Frontend: React + TypeScript + Vite
│   ├── src/features/          # auth, dashboard, projects, modules,
│   │                          # contracts, testing, operations
│   ├── Dockerfile
│   └── package.json
├── gateway/                   # Servicio Gateway remoto (Spring Boot)
├── kafka-module/              # Servicio Kafka remoto (Spring Boot)
├── infrastructure-agent/      # Agente Docker (único con acceso al socket)
├── shell-module/              # Imagen Alpine para shells remotas
├── docs/                      # Documentación técnica
├── docker-compose.yml         # Stack completo (11 servicios)
└── .env.example               # Plantilla de configuración
```

## Documentación

| Documento | Contenido |
|---|---|
| [docs/operation.md](docs/operation.md) | **Guía operativa completa**: arranque, autenticación, Gateway, agente de infraestructura, Kafka remoto, Shell Linux, recuperación de contraseña |
| [docs/architecture.md](docs/architecture.md) | Arquitectura de tres planos, SPI de módulos, seguridad, riesgos y estado de implementación |
| [docs/modules.md](docs/modules.md) | Cómo implementar un nuevo proveedor de módulos |
| [web/README.md](web/README.md) | Guía de desarrollo del frontend |
| [docs/TODO.md](docs/TODO.md) | Backlog de funcionalidades futuras y pendientes |

## Configuración principal

Las variables se definen en `.env` (ver [.env.example](.env.example)):

| Variable | Propósito |
|---|---|
| `POSTGRES_PASSWORD` | Contraseña de PostgreSQL |
| `NODUM_ADMIN_*` | Credenciales del administrador inicial |
| `JWT_SECRET` | Secreto HS512 para firmar JWT (≥ 64 bytes) |
| `NODUM_MODULE_SERVICE_TOKEN` | Token compartido backend ↔ módulos remotos |
| `NODUM_INFRASTRUCTURE_AGENT_TOKEN` | Token del agente Docker |
| `NODUM_GATEWAY_ALLOWED_HOSTS` | Allowlist de hosts del Gateway (anti-SSRF) |
| `NODUM_KAFKA_BROKERS` | Lista de brokers Kafka separados por comas |

## Seguridad

- El panel requiere autenticación JWT; no existe registro público ni modo sin auth.
- Solo `infrastructure-agent` monta `/var/run/docker.sock`; ni `back` ni los módulos tienen acceso directo.
- El Gateway valida hosts contra un allowlist explícito y rechaza por defecto destinos loopback/privados (anti-SSRF).
- Las shells remotas corren sin privilegios, sin capabilities y con límites de CPU/memoria/procesos.
- El stack local usa protocolos plaintext y está pensado para una máquina confiable, **no** para exposición directa a internet.

---

<div align="center">

Hecho con ❤️ por la comunidad

</div>
