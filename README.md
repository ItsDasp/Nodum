# Nodum

Para una guía completa de arquitectura, arranque, seguridad, módulos, Kafka, Gateway, Docker y Shell Linux, consulta [docs/PROJECT_OPERATION.md](docs/PROJECT_OPERATION.md).

Una plataforma modular para crear, configurar, ejecutar, probar y administrar sistemas backend de forma visual.

## Estructura del proyecto

```text
nodum/
├── web/                       # Frontend TypeScript (React + Vite)
│   ├── src/
│   │   ├── app/              # Configuración de la aplicación
│   │   ├── routes/           # Definición de rutas
│   │   ├── components/       # Componentes compartidos
│   │   ├── features/         # Funcionalidades
│   │   ├── api/              # Cliente HTTP
│   │   ├── hooks/            # Hooks personalizados
│   │   └── types/            # Tipos TypeScript
│   ├── package.json
│   └── vite.config.ts
├── back/                      # Backend Java (Spring Boot)
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/nodum/
│   │   │   │   ├── identity/      # Autenticación de usuarios
│   │   │   │   ├── projects/      # Gestión de proyectos
│   │   │   │   ├── apis/          # Definición de API
│   │   │   │   ├── schemas/       # Gestión de esquemas
│   │   │   │   ├── runtime/       # Ejecución dinámica
│   │   │   │   ├── persistence/   # Adaptadores de base de datos
│   │   │   │   ├── modules/       # Implementación de módulos
│   │   │   │   ├── security/      # Políticas de seguridad
│   │   │   │   ├── publishing/    # Control de versiones
│   │   │   │   ├── operations/    # Operaciones de negocio
│   │   │   │   ├── observability/ # Logs y métricas
│   │   │   │   └── shared/        # Utilidades compartidas
│   │   │   └── resources/
│   │   └── test/
│   └── pom.xml
├── docs/                      # Documentación
├── docker-compose.yml          # PostgreSQL y servicios locales
└── README.md
```

## Desarrollo

### Requisitos previos

- Node.js 18 o superior.
- Java 21.
- Maven 3.8 o superior.
- Docker, para levantar el stack completo o solamente PostgreSQL.

### Opción 1: stack completo con Docker Compose

```bash
# Preparar una vez las credenciales locales y mantener el archivo .env privado.
cp .env.example .env

# Compilar y ejecutar todos los servicios.
docker compose up -d --build

# Ver los logs.
docker compose logs -f

# Detener todos los servicios.
docker compose down

# Detener servicios sin eliminar datos.
docker compose down

# Eliminar también los volúmenes solo si se desea borrar los datos locales.
# No es necesario para pruebas normales.
docker compose down -v
```

Accesos:

- Frontend: http://localhost:5173
- Backend: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

El broker Kafka local inicia automáticamente con Compose y espera hasta estar listo. Configura un módulo con `kafka:9092` cuando el backend se ejecuta dentro de Compose, o con `localhost:9094` cuando el backend se ejecuta directamente en el host.

Configura `NODUM_KAFKA_BROKERS` con una lista separada por comas para conectarte a un clúster con varios brokers.

Al iniciar, Nodum crea el proyecto editable `Tienda de demostración` para el administrador configurado si todavía no existe. Incluye:

- Un productor y consumidor Kafka operativo.
- El topic `nodum.order-events`, con tres particiones y una réplica.
- Un Gateway HTTP protegido para JSONPlaceholder.
- Contratos JSON Schema y OpenAPI publicados con ejemplos.

El ejemplo API permite `jsonplaceholder.typicode.com` mediante `NODUM_GATEWAY_ALLOWED_HOSTS`. Cualquier otro destino debe estar explícitamente permitido.

La API de demostración requiere el mismo Bearer que el panel de Nodum. Kafka inicia automáticamente con Compose.

Las rutas Gateway están deshabilitadas hasta que el operador agregue explícitamente los hosts permitidos.

Configura `NODUM_GATEWAY_ALLOWED_HOSTS` en el entorno usado por Compose, por ejemplo mediante el `.env` raíz, con los nombres de host exactos a los que el Gateway puede acceder.

Cada solicitud está limitada a 1 MiB y 8 segundos. No se siguen redirecciones y el módulo tiene un límite interno de 120 solicitudes por minuto. La ruta de runtime requiere un JWT del panel, salvo que la configuración del módulo establezca explícitamente `"public": true`.

Por defecto se rechazan nombres upstream que resuelvan a direcciones loopback, link-local, privadas o multicast para reducir riesgo SSRF. Para permitir deliberadamente una dirección interna en una red confiable, configura `NODUM_GATEWAY_ALLOW_PRIVATE_ADDRESSES=true` además del allowlist.

### Opción 2: desarrollo local

#### Frontend (`web`)

```bash
cd web
npm install
npm run dev
```

El frontend se ejecuta por defecto en http://localhost:5173.

#### Backend (`back`)

Para iniciar solamente PostgreSQL desde la raíz del repositorio:

```bash
docker compose --env-file .env up -d postgres
```

Luego inicia el backend:

```bash
cd back
mvn spring-boot:run
```

El backend se ejecuta por defecto en http://localhost:8080.

## Configuración de autenticación

### Inicialización del usuario administrador

Nodum es una plataforma administrativa. Las credenciales del administrador deben configurarse mediante variables de entorno antes de iniciar el backend.

**Variables requeridas:**

```bash
NODUM_ADMIN_USERNAME=admin          # Nombre de usuario administrador
NODUM_ADMIN_PASSWORD=tu_contraseña  # Contraseña requerida
NODUM_ADMIN_EMAIL=admin@nodum.local # Correo del administrador
POSTGRES_PASSWORD=...               # Contraseña de PostgreSQL
JWT_SECRET=...                       # Secreto aleatorio de al menos 64 bytes para HS512
```

Compose asigna `POSTGRES_PASSWORD` a `SPRING_DATASOURCE_PASSWORD` del backend. No mantengas una segunda contraseña de datasource en `.env`.

Genera valores locales sin incluirlos en el historial del shell ni en los logs y escríbelos únicamente en el archivo `.env`, que está ignorado por Git:

```bash
openssl rand -hex 32  # JWT_SECRET: secreto hexadecimal de 64 caracteres
openssl rand -hex 24  # NODUM_ADMIN_PASSWORD: contraseña local
```

**Importante:**

- El usuario administrador se crea solamente en el primer inicio; cambiar las variables posteriormente no rota la contraseña guardada.
- La contraseña se almacena como hash BCrypt en la base de datos.
- `JWT_SECRET` debe ser aleatorio, no contener valores de ejemplo y tener al menos 64 bytes para HS512.
- `POSTGRES_PASSWORD` debe coincidir con la contraseña que inicializó el volumen existente de PostgreSQL. Nodum no la cambia automáticamente.
- Docker Compose lee el `.env` de la raíz.
- `mvn spring-boot:run` ejecutado localmente requiere que las variables estén presentes en el shell.

Para verificar las credenciales existentes de PostgreSQL, usa los mismos valores en un shell local sin mostrarlos y realiza una comprobación inocua. Un inicio correcto del backend y las migraciones Flyway confirman que la contraseña coincide. Si falla, investiga el volumen antes de modificarlo.

No uses `docker compose down -v` para esta comprobación, ya que eliminaría los datos.

### Migración remota del Gateway

El Gateway puede ejecutarse dentro del backend o dentro del contenedor `gateway-module`.

El modo remoto se habilita cuando se configura `NODUM_GATEWAY_REMOTE_URL` en el `.env` raíz y `NODUM_MODULE_SERVICE_TOKEN` contiene un token privado compartido por el backend y el servicio Gateway:

```dotenv
NODUM_GATEWAY_REMOTE_URL=http://gateway-module:8081
NODUM_MODULE_SERVICE_TOKEN=<genera-un-token-privado-de-servicio>
```

El token no debe almacenarse en la configuración de módulos, logs ni PostgreSQL.

El `GatewayModule` legado sigue activo solamente como fallback cuando no hay URL remota configurada.

## Registros y consola remota de módulos

Las vistas globales **Logs** y **Shell remota** están disponibles en la barra de navegación.

La Shell Linux es un módulo que se inicia y detiene desde el proyecto, dentro de un contenedor Alpine propio:

- Usa una terminal interactiva persistente sobre WebSocket con PTY.
- Ejecuta bajo el usuario sin privilegios `nodum`.
- Elimina capacidades Linux.
- Activa `no-new-privileges`.
- No recibe el socket Docker.
- No recibe montajes arbitrarios del host.
- Cada proyecto obtiene una red privada.
- La shell puede resolver y contactar contenedores hermanos del mismo proyecto.
- No comparte esa red con otros proyectos.
- CPU, memoria y procesos están limitados por contenedor.

Estas operaciones pasan por el agente de infraestructura autenticado y los endpoints del backend exigen acceso al proyecto.

No imprimas credenciales del entorno en la consola ni compartas su salida sin revisarla.

### Prueba local con Docker

```bash
docker compose --env-file .env up -d --build postgres back gateway-module

curl -i http://localhost:8080/api/v1/health

docker compose restart gateway-module

curl -i http://localhost:8080/api/v1/projects
```

Estos comandos están pensados para ejecutarse desde un entorno con acceso autorizado al Docker socket.

### Reversión sin borrar datos

Elimina `NODUM_GATEWAY_REMOTE_URL` del `.env` o asigna un valor vacío:

```bash
docker compose --env-file .env up -d --force-recreate back
```

Se volverá a seleccionar el proveedor legado dentro del backend y los registros existentes permanecerán intactos.

## Agente de infraestructura

Nodum se comunica con contenedores solamente mediante `infrastructure-agent`.

El agente es el único servicio Compose que monta `/var/run/docker.sock`. Ni `back` ni los módulos reciben directamente el socket.

El agente acepta solamente `create`, `start`, `stop`, `restart` e `inspect` para los providers existentes `http-gateway`, `kafka-messaging` y `linux-shell`, junto con un UUID de instancia válido.

No acepta imágenes, comandos, montajes, redes, volúmenes o variables arbitrarias proporcionados por el usuario.

Genera tokens privados separados y guárdalos en el `.env` raíz ignorado por Git:

```dotenv
NODUM_INFRASTRUCTURE_AGENT_TOKEN=<token-privado-del-agente>
NODUM_MODULE_SERVICE_TOKEN=<token-privado-del-servicio>
NODUM_INFRASTRUCTURE_AGENT_URL=http://infrastructure-agent:8090
NODUM_GATEWAY_REMOTE_URL=http://gateway-module:8081
```

Los contenedores Gateway se crean con límites fijos:

- 512 MiB de memoria.
- 0.75 CPU.
- 128 procesos o hilos.
- Sin modo privilegiado.
- Sin montajes arbitrarios.

Los identificadores de contenedor y las operaciones están restringidos mediante las etiquetas `nodum.provider-id` y `nodum.instance-id`.

Después de autorizar el acceso Docker:

```bash
docker compose --env-file .env up -d --build postgres infrastructure-agent gateway-module back web

curl -i http://localhost:8080/api/v1/health

docker inspect nodum-module-http-gateway-<instance-id> \
  --format '{{.HostConfig.Memory}} {{.HostConfig.CpuQuota}} {{.HostConfig.PidsLimit}}'

docker restart nodum-module-http-gateway-<instance-id>

curl -i http://localhost:8080/api/v1/projects
```

No elimines volúmenes. La integración Docker puede considerarse lista solamente cuando `/var/run/docker.sock` sea accesible desde el entorno.

## Módulo remoto Kafka

Kafka se ejecuta en `kafka-module` y expone el mismo contrato v1 que Gateway.

- ID del proveedor: `kafka-messaging`.
- Versión del proveedor: `1.0.0`.
- Acciones compatibles: `publish` y `read`.

Configura los valores privados y el broker en el `.env` raíz ignorado:

```dotenv
NODUM_KAFKA_REMOTE_URL=http://kafka-module:8082
NODUM_KAFKA_REMOTE_TOKEN=<token-privado-del-servicio-kafka>
NODUM_KAFKA_BROKERS=kafka:9092
```

No coloques contraseñas o tokens dentro de `configuration_json` del módulo.

Los contenedores Kafka usan:

- 256 MiB de memoria.
- 0.25 CPU.
- 64 procesos o hilos.

El agente de infraestructura aplica una imagen y un entorno fijos. Las solicitudes HTTP no pueden proporcionar imágenes Docker, comandos, montajes o volúmenes.

La configuración del módulo Kafka admite:

- Lista de brokers.
- Creación de topics.
- Cantidad de particiones.
- Factor de replicación.
- Partición predeterminada opcional.
- Configuración de consumer group.

El panel de administración Kafka muestra brokers, topics y particiones. Permite:

- Crear o eliminar topics.
- Incrementar la cantidad de particiones.
- Consultar grupos y miembros.
- Consultar offsets y lag por partición.
- Restablecer offsets de grupos inactivos.
- Eliminar grupos inactivos.

Kafka realiza rebalanceos automáticamente cuando miembros entran o salen.

El factor de replicación no puede superar la cantidad de brokers disponibles. Con un solo broker, el único factor posible es 1.

Incrementar la cantidad de particiones no redistribuye automáticamente los registros existentes.

Después de disponer de Docker y configurar el broker:

```bash
docker compose --env-file .env up -d --build postgres kafka infrastructure-agent gateway-module kafka-module back web

curl -i http://localhost:8080/api/v1/health

curl -i http://localhost:8080/api/v1/module-catalog
```

Para revertir al comportamiento Kafka Java legacy, elimina `NODUM_KAFKA_REMOTE_URL` del `.env` y reinicia `back`.

Los registros de módulos existentes no se eliminan.

## Restablecimiento de contraseña

Nodum no sobrescribe la contraseña existente del administrador durante el inicio.

Un administrador autenticado puede cambiarla mediante:

```bash
curl -X POST http://localhost:8080/api/v1/auth/password \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{"currentPassword":"<contraseña-actual>","newPassword":"<contraseña-nueva>"}'
```

La contraseña actual es obligatoria y la nueva se almacena como BCrypt.

Si pierdes el acceso, genera un hash BCrypt mediante un procedimiento administrativo offline y actualiza solamente la columna `password_hash` del usuario dentro de una transacción.

No elimines la base de datos ni el volumen.

Verifica por separado las credenciales de PostgreSQL con la persona que creó el volumen antes de cambiar `.env`.

Para recuperación local cuando no se conoce la contraseña anterior, usa la herramienta administrativa explícita:

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

Ejecuta el programa desde `back`, en una terminal interactiva.

Pide la contraseña dos veces sin mostrarla, usa BCrypt y actualiza solamente `password_hash` y `updated_at` del administrador seleccionado.

Confirma el cambio únicamente si exactamente una fila fue actualizada.

No modifica `POSTGRES_PASSWORD`, usuarios adicionales, proyectos, tablas ni volúmenes.

Después de recuperarlo, obtén un JWT mediante `/api/v1/auth/login` y úsalo para llamar a `/api/v1/projects`.

## Autenticación del frontend

El panel siempre requiere autenticación.

- Inicia sesión con las credenciales del administrador configuradas en el backend.
- El JWT queda en el navegador.
- El JWT se envía a las rutas protegidas del API.
- No existe registro público.
- No existe control de acceso por país.
- No existe modo de panel sin autenticación.

## Documentación de API

Cuando el backend esté ejecutándose:

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/api-docs

## Arquitectura

Nodum está organizado en tres capas:

1. **Plano de control**: núcleo administrativo de autenticación, configuración y versionado.
2. **Plano de ejecución**: runtime de sistemas definidos por los usuarios.
3. **Capa de recursos**: gestión de recursos externos como bases de datos, brokers y contenedores.

Consulta [docs/architecture.md](docs/architecture.md) para conocer la arquitectura detallada.

## Implementación actual

El repositorio es un prototipo temprano de plano de control.

Incluye:

- Inicio de sesión del administrador.
- CRUD de proyectos.
- SPI de módulos confiables dentro del backend.
- Instancias de módulos con configuración y ciclo de vida persistidos.
- Acciones de productor y consumidor Kafka.
- Acción de proxy HTTP Gateway con allowlist.
- Contratos JSON.
- Health del backend.
- Herramienta HTTP desde el navegador.

Los proveedores se ejecutan dentro del proceso backend. Actualmente no hay:

- Aislamiento de runtime.
- RabbitMQ.
- Ingress público permanente del Gateway.
- Generación dinámica de APIs.
- Gestor de secretos de producción.

Consulta la [guía de proveedores de módulos](docs/modules.md).

Los documentos arquitectónicos describen la dirección objetivo y la fase actual de módulos. No deben interpretarse como garantías de aislamiento de producción.

El backend requiere explícitamente los secretos del administrador y JWT. Protege proyectos, módulos y contratos mediante autenticación JWT.

El stack local Compose usa servicios plaintext de desarrollo y está pensado para una máquina confiable, no para exposición directa a internet.
