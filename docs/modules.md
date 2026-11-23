# Proveedores de módulos en Nodum

Guía para implementar un nuevo proveedor de módulos. Para el contexto de arquitectura consulta [architecture.md](architecture.md) y para operarlos en Compose, [operation.md](operation.md).

## Qué soporta actualmente

- Spring descubre beans de proveedores confiables en el arranque del backend.
- Cada proveedor declara: ID estable, versión del proveedor, versión del contrato SPI, nombre visible, descripción y capacidades.
- El registro rechaza IDs duplicados y expone el catálogo instalado en `GET /api/v1/module-catalog` (requiere JWT).
- Los proveedores implementan: validación de configuración, `start`, `stop`, `health` y acciones opcionales.
- Las instancias de módulo persisten identidad del proveedor, versión, configuración JSON y estado de ciclo de vida. Start/stop se restauran tras reiniciar el backend.
- **Kafka** (`kafka-messaging`): crea/verifica topics, publica valores JSON/texto y, opcionalmente, bufferiza mensajes consumidos para lectura desde el panel.
- **HTTP Gateway** (`http-gateway`): enruta acciones proxy y peticiones a `/api/v1/runtime/gateway/{projectId}/{moduleId}/...` hacia prefijos de path coincidentes, solo a hosts upstream en `NODUM_GATEWAY_ALLOWED_HOSTS`. El ingress exige JWT del panel salvo `"public": true` en la configuración; cada instancia tiene un límite de 120 req/min.
- **Servicios remotos**: `gateway-module` y `kafka-module` exponen el mismo contrato v1 que los proveedores embebidos, gestionados por `infrastructure-agent` con límites de recursos.

El panel puede crear instancias desde los proveedores instalados. Un registro de módulo sin proveedor permanece como metadato. Los proveedores se compilan dentro del backend y ejecutan en su proceso: el panel no puede subir código arbitrario. Estos proveedores son adaptadores funcionales de desarrollo, no servicios de producción aislados.

> [!NOTE]
> Las mejoras pendientes (RabbitMQ, gestor de secretos, SASL/TLS para Kafka, aislamiento de proceso, buffer durable del consumidor) están en [`TODO.md`](TODO.md).

## Implementar un proveedor

1. **Crea un `@Component` de Spring** que implemente `ModulePlugin` en `back/src/main/java/com/nodum/modules/spi` (o un paquete propio).
2. **Devuelve un `ModuleDescriptor` único**: ID estable en minúsculas, versión semántica del proveedor, versión del contrato SPI actual y capacidades precisas.
3. **Parsea y valida la configuración** sin evaluar código suministrado por el usuario; devuelve errores accionables.
4. **Implementa start, stop y health** con timeouts acotados y comportamiento de ciclo de vida idempotente (seguro de reintentar).
5. **Mantén los secretos fuera** de descriptores, errores, logs y configuración persistida del módulo. Usa variables de entorno del servicio para credenciales.
6. **Agrega tests** para: configuración inválida, fallos parciales de arranque, stop idempotente y caídas de dependencias.

## Modo remoto (opcional)

Si el proveedor necesita aislamiento o tiene conflictos de dependencias, sigue el patrón de `gateway-module`/`kafka-module`:

- Servicio Spring Boot separado en su propio directorio (`gateway/`, `kafka-module/`).
- Expone el contrato v1 de módulo (misma superficie que el SPI embebido).
- Autenticación mutua con `NODUM_MODULE_SERVICE_TOKEN`.
- `infrastructure-agent` es quien crea/gestiona los contenedores; el servicio remoto nunca recibe el socket Docker.
- Actívalo desde el backend con `NODUM_<PROVIDER>_REMOTE_URL` en `.env`; si está vacío, se usa el proveedor embebido como fallback.

## Límites de los proveedores embebidos

- Ejecutan **dentro del JVM del plano de control**: comparten memoria, classpath y dominio de fallos. Son código confiable.
- Gateway: upstreams limitados al allowlist `NODUM_GATEWAY_ALLOWED_HOSTS`; sin redirects; cuerpos ≤ 1 MiB; timeouts de 8 s; anti-SSRF activo por defecto.
- Kafka: los mensajes y las credenciales del broker requieren persistencia, reintentos y gestión de secretos robustos antes de un uso productivo.

Para usar un proveedor que requiera aislamiento de fallos o dependencias incompatibles, despliega un servicio separado con un contrato remoto versionado.
