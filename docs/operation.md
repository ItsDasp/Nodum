# Guía de operación de Nodum

Esta guía cubre la operación diaria del stack: arranque, autenticación, módulos remotos, agente de infraestructura, Kafka, Shell Linux y recuperación de acceso. Para conceptos de arquitectura consulta [architecture.md](architecture.md) y para extender proveedores, [modules.md](modules.md).

## Índice

- [Arranque del stack](#arranque-del-stack)
- [Desarrollo local (sin Docker completo)](#desarrollo-local-sin-docker-completo)
- [Autenticación y usuario administrador](#autenticación-y-usuario-administrador)
- [Migración remota del Gateway](#migración-remota-del-gateway)
- [Registros y consola remota](#registros-y-consola-remota)
- [Agente de infraestructura](#agente-de-infraestructura)
- [Módulo remoto Kafka](#módulo-remoto-kafka)
- [Restablecimiento de contraseña](#restablecimiento-de-contraseña)
- [Autenticación del frontend](#autenticación-del-frontend)
- [Solución de problemas](#solución-de-problemas)

---

## Arranque del stack

### Stack completo con Docker Compose

```bash
# 1. Preparar credenciales locales (una sola vez)
cp .env.example .env
# Editar .env y completar todos los valores vacíos

# 2. Compilar y levantar todos los servicios
docker compose up -d --build

# 3. Seguir los logs durante el primer arranque
docker compose logs -f
```

Servicios levantados por Compose:

| Servicio | Contenedor | Puerto | Descripción |
|---|---|---|---|
| `postgres` | `nodum-postgres` | 5432 | PostgreSQL 16 (persistencia) |
| `back` | `nodum-back` | 8080 | API REST (Spring Boot) |
| `web` | `nodum-web` | 5173 | Panel React (Vite) |
| `gateway-module` | `nodum-gateway-module` | — (interno 8081) | Proxy HTTP remoto |
| `kafka-module` | `nodum-kafka-module` | — (interno 8082) | Kafka remoto |
| `infrastructure-agent` | `nodum-infrastructure-agent` | — (interno 8090) | Gestor de contenedores Docker |
| `kafka-1/2/3` | `nodum-kafka-{1,2,3}-1` | 9094–9096 | Clúster KRaft (broker+controller) |
| `kafka-ui` | `nodum-kafka-ui` | 8085 | Interfaz web de Kafka |
| `shell-image` | — | — | Imagen base Alpine (one-shot) |

### Datos de demostración

En el primer arranque, el backend crea automáticamente el proyecto **"Tienda de demostración"** para el administrador configurado, que incluye:

- Un productor y consumidor Kafka operativo sobre el topic `nodum.order-events` (3 particiones, 1 réplica).
- Un Gateway HTTP protegido hacia JSONPlaceholder.
- Contratos JSON Schema y OpenAPI publicados con ejemplos.

### Kafka desde el host vs. dentro de Compose

| Escenario | Valor de brokers |
|---|---|
| Backend dentro de Compose | `kafka-1:9092,kafka-2:9092,kafka-3:9092` |
| Backend en el host (desarrollo) | `localhost:9094` |

Configura `NODUM_KAFKA_BROKERS` con la lista separada por comas del clúster al que te conectas.

### Detener y limpiar

```bash
docker compose down          # Detiene todo, conserva volúmenes y datos
docker compose down -v       # DETIENE Y BORRA todos los datos (postgres + kafka)
docker compose restart <svc> # Reinicia un servicio concreto
```

> [!WARNING]
> `down -v` elimina los volúmenes `postgres_data`, `kafka_data_1/2/3`. Úsalo solo si quieres empezar de cero.

---

## Desarrollo local (sin Docker completo)

Útil para iterar sobre el código sin rebuild de imágenes.

### Requisitos

- Node.js 18+, Java 21, Maven 3.8+
- Docker solo para PostgreSQL (y opcionalmente Kafka)

### 1. Levantar solo PostgreSQL

```bash
docker compose --env-file .env up -d postgres
```

### 2. Backend

Las variables de `.env` deben estar presentes en el shell. En Linux/macOS:

```bash
set -a && . ./.env && set +a
cd back
./mvnw spring-boot:run        # o: mvn spring-boot:run
```

El backend queda en http://localhost:8080.

### 3. Frontend

```bash
cd web
npm install
npm run dev
```

El frontend queda en http://localhost:5173 y consume la API en `http://localhost:8080/api/v1` (configurable con `VITE_API_BASE_URL`).

---

## Autenticación y usuario administrador

Nodum es una plataforma administrativa: **todo el panel requiere autenticación**. No existe registro público ni modo sin auth.

### Variables requeridas

```bash
NODUM_ADMIN_USERNAME=admin            # Usuario administrador
NODUM_ADMIN_PASSWORD=<contraseña>     # Se hashea con BCrypt al crear el usuario
NODUM_ADMIN_EMAIL=admin@nodum.local   # Correo del administrador
POSTGRES_PASSWORD=<password>          # Contraseña de PostgreSQL
JWT_SECRET=<secreto-aleatorio>        # HS512, mínimo 64 bytes
```

### Reglas importantes

- **El administrador se crea solo en el primer inicio.** Cambiar las variables después no rota la contraseña persistida.
- La contraseña se guarda como hash **BCrypt** en `users.password_hash`.
- `JWT_SECRET` debe ser aleatorio, nunca un valor de ejemplo, y tener al menos 64 bytes (usa `openssl rand -hex 32`).
- `POSTGRES_PASSWORD` debe coincidir con la contraseña con la que se inicializó el volumen de PostgreSQL existente. Nodum no la migra automáticamente.
- Compose lee `.env` de la raíz; `mvn spring-boot:run` necesita las variables exportadas en el shell.

### Verificar credenciales de PostgreSQL

Para comprobar que `POSTGRES_PASSWORD` coincide con el volumen existente, carga las variables en un shell sin imprimirlas y ejecuta una consulta inocua (por ejemplo, `SELECT 1` con `psql`). Si el backend arranca y las migraciones Flyway aplican correctamente, la contraseña es correcta. **No** uses `docker compose down -v` para esta prueba: borraría los datos.

---

## Migración remota del Gateway

El Gateway HTTP puede ejecutarse embebido en `back` (legacy) o como servicio remoto `gateway-module`.

### Activar el modo remoto

En `.env`:

```dotenv
NODUM_GATEWAY_REMOTE_URL=http://gateway-module:8081
NODUM_MODULE_SERVICE_TOKEN=<token-privado-compartido>
```

- El token es compartido entre `back` y `gateway-module`. **No** debe aparecer en `configuration_json` de módulos, logs ni PostgreSQL.
- Si `NODUM_GATEWAY_REMOTE_URL` está vacío o ausente, el backend usa el `GatewayModule` legado embebido como fallback.

### Políticas del Gateway

- Cada request está limitado a **1 MiB** y **8 segundos**; no se siguen redirects.
- Límite interno de **120 requests/minuto** por instancia de módulo.
- La ruta de runtime requiere un JWT del panel salvo que la configuración del módulo defina `"public": true`.
- Por defecto se rechazan upstreams que resuelvan a loopback, link-local, privadas o multicast (anti-SSRF). Para permitirlos deliberadamente en una red confiable: `NODUM_GATEWAY_ALLOW_PRIVATE_ADDRESSES=true` **además** del allowlist.
- Los hosts destino deben estar en `NODUM_GATEWAY_ALLOWED_HOSTS` (nombres exactos, separados por comas). Sin allowlist, las rutas Gateway están deshabilitadas.

### Reversión al modo legado

```bash
# Vaciar NODUM_GATEWAY_REMOTE_URL en .env y recrear back
docker compose --env-file .env up -d --force-recreate back
```

Los registros existentes no se eliminan.

---

## Registros y consola remota

Las vistas globales **Logs** y **Shell remota** están en la barra de navegación del panel.

### Shell Linux remota

Es un módulo que se inicia/detiene por proyecto dentro de un contenedor Alpine propio:

- Terminal interactiva persistente sobre WebSocket con PTY.
- Ejecuta como usuario sin privilegios `nodum`.
- Capacidades Linux eliminadas y `no-new-privileges` activo.
- **Sin** acceso al socket Docker ni montajes arbitrarios del host.
- Cada proyecto obtiene una red Docker privada; la shell resuelve y contacta solo a contenedores hermanos del mismo proyecto.
- Límites por contenedor de CPU, memoria y procesos.

Estas operaciones pasan por el agente de infraestructura autenticado; los endpoints del backend exigen acceso al proyecto.

> [!NOTE]
> No imprimas credenciales del entorno en la consola ni compartas salidas sin revisarlas.

---

## Agente de infraestructura

Nodum gestiona contenedores **exclusivamente** a través de `infrastructure-agent`.

- Es el **único** servicio Compose que monta `/var/run/docker.sock`.
- Ni `back` ni los módulos reciben el socket directamente.
- Acepta solo `create`, `start`, `stop`, `restart` e `inspect` para los providers `http-gateway`, `kafka-messaging` y `linux-shell`, con UUID de instancia válido.
- **No** acepta imágenes, comandos, montajes, redes, volúmenes ni variables arbitrarias del usuario.
- Los contenedores se identifican por etiquetas `nodum.provider-id` y `nodum.instance-id`.

### Tokens

```dotenv
NODUM_INFRASTRUCTURE_AGENT_TOKEN=<token-privado-del-agente>
NODUM_MODULE_SERVICE_TOKEN=<token-privado-del-servicio>
NODUM_INFRASTRUCTURE_AGENT_URL=http://infrastructure-agent:8090
```

Genera tokens privados independientes y guárdalos solo en `.env`.

### Límites de los contenedores Gateway

| Recurso | Límite |
|---|---|
| Memoria | 512 MiB |
| CPU | 0.75 |
| Procesos/hilos | 128 |
| Privilegios | No |
| Montajes | Ninguno arbitrario |

### Prueba local del agente

```bash
docker compose --env-file .env up -d --build postgres infrastructure-agent gateway-module back web

curl -i http://localhost:8080/api/v1/health

# Inspeccionar límites de un gateway levantado por el agente
docker inspect nodum-module-http-gateway-<instance-id> \
  --format '{{.HostConfig.Memory}} {{.HostConfig.CpuQuota}} {{.HostConfig.PidsLimit}}'

docker restart nodum-module-http-gateway-<instance-id>
curl -i http://localhost:8080/api/v1/projects
```

La integración Docker solo puede considerarse operativa cuando `/var/run/docker.sock` es accesible desde el entorno donde corre el agente.

---

## Módulo remoto Kafka

`kafka-module` expone el mismo contrato v1 que Gateway para el provider `kafka-messaging` (versión `1.0.0`), con acciones `publish` y `read`.

### Configuración

```dotenv
NODUM_KAFKA_REMOTE_URL=http://kafka-module:8082
NODUM_KAFKA_REMOTE_TOKEN=<token-privado-del-servicio-kafka>
NODUM_KAFKA_BROKERS=kafka-1:9092,kafka-2:9092,kafka-3:9092
```

> [!WARNING]
> Nunca coloques contraseñas o tokens dentro de `configuration_json` del módulo.

### Topología Kafka

El stack levanta un clúster **KRaft de 3 nodos** (`apache/kafka:3.8.1`), cada uno con roles `broker,controller` y quórum `1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093`.

| Broker | Puerto interno | Puerto host |
|---|---|---|
| `kafka-1` | 9092 | 9094 |
| `kafka-2` | 9092 | 9095 |
| `kafka-3` | 9092 | 9096 |

Configuraciones relevantes: `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=3`, `KAFKA_DEFAULT_REPLICATION_FACTOR=3`, `KAFKA_MIN_INSYNC_REPLICAS=2`, `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`.

### Administración desde el panel

La vista de administración Kafka permite:

- Crear o eliminar topics (con particiones y factor de replicación).
- Incrementar particiones de topics existentes.
- Consultar grupos de consumidores, miembros, offsets y lag por partición.
- Restablecer offsets de grupos inactivos y eliminar grupos `EMPTY`.

Kafka rebalancea automáticamente cuando miembros entran o salen del grupo.

### Reglas y límites

- El factor de replicación **no puede superar** la cantidad de brokers (con 1 broker, solo factor 1).
- Incrementar particiones **no redistribuye** los mensajes existentes.
- Los contenedores Kafka usan 256 MiB de memoria, 0.25 CPU y 64 procesos.
- La configuración del módulo admite: brokers, creación de topics, particiones, replicación, partición predeterminada y consumer group.

### Reversión al modo legado

Elimina `NODUM_KAFKA_REMOTE_URL` del `.env` y reinicia `back`. Los registros de módulos existentes no se eliminan.

---

## Restablecimiento de contraseña

### Con sesión activa

```bash
curl -X POST http://localhost:8080/api/v1/auth/password \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{"currentPassword":"<actual>","newPassword":"<nueva>"}'
```

La contraseña actual es obligatoria; la nueva se guarda como BCrypt.

### Recuperación local sin contraseña anterior

Si perdiste el acceso, usa la herramienta administrativa explícita `AdminPasswordResetTool` (desde `back/`):

```bash
set -a && . ../.env && set +a

export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/nodum
export SPRING_DATASOURCE_USERNAME=nodum
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export NODUM_ADMIN_USERNAME=admin

./mvnw -q dependency:build-classpath -Dmdep.outputFile=/tmp/nodum-back.classpath

java -cp "target/classes:$(cat /tmp/nodum-back.classpath)" \
  com.nodum.identity.AdminPasswordResetTool
```

La herramienta:

- Pide la contraseña dos veces sin mostrarla.
- Aplica BCrypt y actualiza **solo** `password_hash` y `updated_at` del administrador seleccionado.
- Confirma únicamente si exactamente una fila fue actualizada.
- **No** toca `POSTGRES_PASSWORD`, otros usuarios, proyectos, tablas ni volúmenes.

Tras recuperarla, inicia sesión en `/login` o vía `POST /api/v1/auth/login`.

---

## Autenticación del frontend

- Login con las credenciales del administrador (`NODUM_ADMIN_*`).
- El JWT se almacena en `localStorage` (`nodum-token`) y se envía como Bearer a las rutas protegidas.
- No hay registro público, ni control por país, ni modo anónimo del panel.

---

## Solución de problemas

| Síntoma | Causa probable | Acción |
|---|---|---|
| `depends_on` falla al hacer `up` | Healthcheck expiró (p. ej. Postgres en recovery tras apagado brusco) | Esperar a que el servicio esté `healthy` y ejecutar `docker compose up -d` de nuevo; revisar `docker compose logs <svc>` |
| Brokers Kafka en restart loop | Quórum Raft sin líder (I/O saturado, arranque simultáneo) | Verificar conectividad `nc -z kafka-2 9093` desde un broker; esperar a que Postgres termine recovery y dejar que Compose reinicie |
| `nodum-postgres` unhealthy por minutos | Recovery/fsync tras apagado no limpio | Normal: el log muestra `syncing data directory` y luego `ready to accept connections` |
| Módulo demo en estado `ERROR` | Servicio remoto aún no estaba listo al inicializar | Reiniciar `back` o reintentar el ciclo de vida del módulo desde el panel |
| Gateway rechaza upstream | Host no está en `NODUM_GATEWAY_ALLOWED_HOSTS` o resuelve a IP privada | Agregar el host exacto al allowlist; `NODUM_GATEWAY_ALLOW_PRIVATE_ADDRESSES=true` solo en redes confiables |
| `docker` no encontrado en terminal | Docker Desktop no está en el PATH de esa shell | Usar `"C:\Program Files\Docker\Docker\resources\bin\docker.exe"` o abrir una terminal nueva |

## Referencias

- [README principal](../README.md)
- [Arquitectura](architecture.md)
- [Módulos](modules.md)
