# TODO: Roadmap de Nodum

Backlog de funcionalidades y mejoras pendientes. Los documentos de `docs/` describen únicamente lo implementado; este archivo concentra el trabajo futuro.

## Módulos y proveedores

- [ ] Aislamiento de proceso real para proveedores embebidos en el JVM del backend.
- [ ] Proveedor `DATABASE` (gestión de conexiones y esquemas de proyecto).
- [ ] Proveedor `AUTHENTICATION` (identity providers externos).
- [ ] Proveedor `OBSERVABILITY` (métricas y tracing por proyecto).
- [ ] RabbitMQ como segundo broker de mensajería.
- [ ] Gestor de secretos de producción (vault/KMS) y mecanismo de referencias a secretos en configuración de módulos.
- [ ] Persistencia durable del buffer del consumidor Kafka (hoy es volátil y acotado en memoria).
- [ ] Credenciales SASL/TLS y OAuth para Kafka.

## Gateway

- [ ] Ingress público permanente con TLS.
- [ ] Generación dinámica de APIs REST a partir de configuraciones.
- [ ] Circuit breakers y reintentos avanzados en el proxy.

## Plataforma

- [ ] Autenticación reforzada del panel (por ejemplo Microsoft Entra ID) además del admin local.
- [ ] Métricas y dashboards de observabilidad de plataforma.
- [ ] Auditoría administrativa de operaciones.
- [ ] Multiusuario con roles (hoy existe un único administrador).

## Operación

- [ ] Despliegue productivo: TLS, secretos externos, hardening de red.
- [ ] Soporte de clúster Kafka externo (hoy el stack levanta el suyo propio en Compose).
