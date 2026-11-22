package com.nodum.projects;

import com.nodum.contracts.Contract;
import com.nodum.contracts.ContractRepository;
import com.nodum.contracts.ContractStatus;
import com.nodum.identity.User;
import com.nodum.identity.UserRepository;
import com.nodum.modules.Module;
import com.nodum.modules.ModuleLifecycleService;
import com.nodum.modules.ModuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** Creates an editable, executable sample workspace for the configured administrator. */
@Component
public class DemoWorkspaceInitializer {
    private static final Logger log = LoggerFactory.getLogger(DemoWorkspaceInitializer.class);
    private static final String PROJECT_NAME = "Tienda de demostración";
    private static final String KAFKA_MODULE_NAME = "Eventos de pedidos (Kafka)";
    private static final String AUDIT_KAFKA_MODULE_NAME = "Eventos de auditoría (Kafka)";
    private static final String API_MODULE_NAME = "API de publicaciones";

    private static final String KAFKA_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "Evento de pedido",
              "type": "object",
              "additionalProperties": false,
              "required": ["eventId", "eventType", "occurredAt", "orderId", "customerId", "total", "currency", "items"],
              "properties": {
                "eventId": { "type": "string", "format": "uuid" },
                "eventType": { "type": "string", "enum": ["order.created", "order.cancelled"] },
                "occurredAt": { "type": "string", "format": "date-time" },
                "orderId": { "type": "string", "minLength": 1 },
                "customerId": { "type": "string", "minLength": 1 },
                "total": { "type": "number", "minimum": 0 },
                "currency": { "type": "string", "const": "CLP" },
                "items": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["productId", "quantity", "unitPrice"],
                    "properties": {
                      "productId": { "type": "string" },
                      "quantity": { "type": "integer", "minimum": 1 },
                      "unitPrice": { "type": "number", "minimum": 0 }
                    }
                  }
                }
              }
            }
            """;

    private static final String KAFKA_EXAMPLES = """
            [
              {
                "eventId": "7a43f129-7687-4ab6-a9bb-12f302b99a01",
                "eventType": "order.created",
                "occurredAt": "2026-11-20T18:30:00-03:00",
                "orderId": "PED-1042",
                "customerId": "CLI-208",
                "total": 24990,
                "currency": "CLP",
                "items": [{ "productId": "LIB-01", "quantity": 2, "unitPrice": 12495 }]
              },
              {
                "eventId": "1f837ae4-3cc1-4b6c-9fc5-579ca865b809",
                "eventType": "order.cancelled",
                "occurredAt": "2026-11-20T18:42:00-03:00",
                "orderId": "PED-1042",
                "customerId": "CLI-208",
                "total": 24990,
                "currency": "CLP",
                "items": [{ "productId": "LIB-01", "quantity": 2, "unitPrice": 12495 }]
              }
            ]
            """;

    private static final String API_SCHEMA = """
            {
              "openapi": "3.1.0",
              "info": {
                "title": "API de publicaciones de la tienda de demostración",
                "version": "1.0.0",
                "description": "API REST de ejemplo publicada a través de la puerta de enlace HTTP de Nodum."
              },
              "servers": [{
                "url": "/api/v1/runtime/gateway/{projectId}/{moduleId}",
                "description": "Ruta protegida de Nodum; requiere token Bearer",
                "variables": { "projectId": { "default": "reemplazar-por-uuid" }, "moduleId": { "default": "reemplazar-por-uuid" } }
              }],
              "paths": {
                "/posts": {
                  "get": {
                    "summary": "Listar publicaciones",
                    "parameters": [{ "name": "_limit", "in": "query", "schema": { "type": "integer", "minimum": 1, "maximum": 100 } }],
                    "responses": { "200": { "description": "Lista de publicaciones", "content": { "application/json": { "schema": { "type": "array", "items": { "$ref": "#/components/schemas/Publicacion" } } } } } }
                  },
                  "post": {
                    "summary": "Crear una publicación de prueba",
                    "requestBody": { "required": true, "content": { "application/json": { "schema": { "$ref": "#/components/schemas/NuevaPublicacion" }, "example": { "title": "Nuevo lanzamiento", "body": "Disponible en la tienda", "userId": 1 } } } },
                    "responses": { "201": { "description": "Publicación creada", "content": { "application/json": { "schema": { "$ref": "#/components/schemas/Publicacion" } } } } }
                  }
                },
                "/posts/{id}": {
                  "get": {
                    "summary": "Consultar una publicación",
                    "parameters": [{ "name": "id", "in": "path", "required": true, "schema": { "type": "integer", "minimum": 1 } }],
                    "responses": { "200": { "description": "Publicación encontrada", "content": { "application/json": { "schema": { "$ref": "#/components/schemas/Publicacion" } } } }, "404": { "description": "Publicación no encontrada" } }
                  }
                }
              },
              "components": {
                "securitySchemes": { "BearerAuth": { "type": "http", "scheme": "bearer" } },
                "schemas": {
                  "NuevaPublicacion": {
                    "type": "object", "additionalProperties": false,
                    "required": ["title", "body", "userId"],
                    "properties": { "title": { "type": "string", "maxLength": 200 }, "body": { "type": "string", "maxLength": 5000 }, "userId": { "type": "integer", "minimum": 1 } }
                  },
                  "Publicacion": {
                    "type": "object", "additionalProperties": false,
                    "required": ["id", "title", "body", "userId"],
                    "properties": { "id": { "type": "integer" }, "title": { "type": "string" }, "body": { "type": "string" }, "userId": { "type": "integer" } }
                  }
                }
              },
              "security": [{ "BearerAuth": [] }]
            }
            """;

    private static final String API_EXAMPLES = """
            {
              "consultar": {
                "solicitud": { "method": "GET", "path": "/posts/1" },
                "respuesta": { "status": 200, "body": { "userId": 1, "id": 1, "title": "delectus aut autem", "body": "neque porro quisquam est qui dolorem ipsum quia dolor sit amet" } }
              },
              "crear": {
                "solicitud": { "method": "POST", "path": "/posts", "headers": { "Content-Type": "application/json" }, "body": { "title": "Nuevo lanzamiento", "body": "Disponible en la tienda", "userId": 1 } },
                "respuesta": { "status": 201, "body": { "id": 101, "title": "Nuevo lanzamiento", "body": "Disponible en la tienda", "userId": 1 } }
              }
            }
            """;

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ModuleRepository moduleRepository;
    private final ContractRepository contractRepository;
    private final ModuleLifecycleService lifecycleService;
    private final String adminUsername;

    public DemoWorkspaceInitializer(UserRepository userRepository, ProjectRepository projectRepository,
                                    ModuleRepository moduleRepository, ContractRepository contractRepository,
                                    ModuleLifecycleService lifecycleService,
                                    @Value("${nodum.admin.username}") String adminUsername) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.moduleRepository = moduleRepository;
        this.contractRepository = contractRepository;
        this.lifecycleService = lifecycleService;
        this.adminUsername = adminUsername;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void createDemoWorkspace() {
        User owner = userRepository.findByUsername(adminUsername).orElse(null);
        if (owner == null) {
            log.warn("No se pudo crear la muestra de Nodum porque no existe el usuario administrador configurado.");
            return;
        }

        Project project = projectRepository.findByNameAndOwnerId(PROJECT_NAME, owner.getId()).orElseGet(() -> {
            Project sample = new Project();
            sample.setName(PROJECT_NAME);
            sample.setDescription("Proyecto editable para explorar una API REST, eventos Kafka y sus contratos publicados.");
            sample.setOwner(owner);
            return projectRepository.save(sample);
        });

        Module kafka = findOrCreateKafka(project, owner);
        Module auditKafka = findOrCreateAuditKafka(project, owner);
        Module api = findOrCreateApi(project, owner);
        ensureContract(kafka, owner, "Evento de pedido", "Esquema JSON Schema del evento publicado en el tema nodum.order-events. Incluye pedidos creados y cancelados.", "1.0.0", KAFKA_SCHEMA, KAFKA_EXAMPLES);
        ensureContract(api, owner, "API REST de publicaciones", "Definición OpenAPI 3.1 de los endpoints GET /posts, GET /posts/{id} y POST /posts, con seguridad Bearer.", "1.0.0", API_SCHEMA, API_EXAMPLES);

        startSampleModule(kafka);
        startSampleModule(auditKafka);
        startSampleModule(api);
    }

    private Module findOrCreateKafka(Project project, User owner) {
        return moduleRepository.findByProjectIdAndOwnerId(project.getId(), owner.getId()).stream()
                .filter(module -> KAFKA_MODULE_NAME.equals(module.getName()))
                .findFirst()
                .map(module -> updateDemoKafkaConfiguration(module,
                        "Tema nodum.order-events con tres particiones y tres réplicas.",
                        """
                        {"bootstrapServers":"kafka-1:9092,kafka-2:9092,kafka-3:9092","topic":"nodum.order-events","partitions":3,"replicationFactor":3,"groupId":"nodum-demo-orders","consume":true}
                        """))
                .orElseGet(() -> saveModule(project, owner, KAFKA_MODULE_NAME,
                        "Tema nodum.order-events con tres particiones y tres réplicas.",
                        "kafka-messaging", """
                                {"bootstrapServers":"kafka-1:9092,kafka-2:9092,kafka-3:9092","topic":"nodum.order-events","partitions":3,"replicationFactor":3,"groupId":"nodum-demo-orders","consume":true}
                                """));
    }

    private Module findOrCreateAuditKafka(Project project, User owner) {
        return moduleRepository.findByProjectIdAndOwnerId(project.getId(), owner.getId()).stream()
                .filter(module -> AUDIT_KAFKA_MODULE_NAME.equals(module.getName()))
                .findFirst()
                .map(module -> updateDemoKafkaConfiguration(module,
                        "Tema nodum.audit-events con tres particiones y tres réplicas.",
                        """
                        {"bootstrapServers":"kafka-1:9092,kafka-2:9092,kafka-3:9092","topic":"nodum.audit-events","partitions":3,"replicationFactor":3,"groupId":"nodum-demo-audit","consume":true}
                        """))
                .orElseGet(() -> saveModule(project, owner, AUDIT_KAFKA_MODULE_NAME,
                        "Tema nodum.audit-events con tres particiones y tres réplicas.",
                        "kafka-messaging", """
                                {"bootstrapServers":"kafka-1:9092,kafka-2:9092,kafka-3:9092","topic":"nodum.audit-events","partitions":3,"replicationFactor":3,"groupId":"nodum-demo-audit","consume":true}
                                """));
    }

    private Module findOrCreateApi(Project project, User owner) {
        return moduleRepository.findByProjectIdAndOwnerId(project.getId(), owner.getId()).stream()
                .filter(module -> API_MODULE_NAME.equals(module.getName()))
                .findFirst().orElseGet(() -> saveModule(project, owner, API_MODULE_NAME,
                        "API REST de ejemplo, protegida por token y enrutada por la puerta de enlace de Nodum.",
                        "http-gateway", """
                                {"public":false,"routes":[{"pathPrefix":"/posts","upstreamUrl":"https://jsonplaceholder.typicode.com"}]}
                                """));
    }

    private Module updateDemoKafkaConfiguration(Module module, String description, String configuration) {
        String normalizedConfiguration = configuration.trim();
        boolean changed = !normalizedConfiguration.equals(module.getConfigurationJson());
        module.setDescription(description);
        module.setConfigurationJson(normalizedConfiguration);
        module = moduleRepository.save(module);
        if (changed && "RUNNING".equals(module.getLifecycleState())) {
            lifecycleService.stop(module);
        }
        return module;
    }

    private Module saveModule(Project project, User owner, String name, String description,
                              String providerId, String configuration) {
        Module module = new Module();
        module.setName(name);
        module.setDescription(description);
        module.setProject(project);
        module.setOwner(owner);
        module.setBasePath(providerId.equals("http-gateway")
                ? "/api/v1/runtime/gateway/" + project.getId() + "/{moduleId}"
                : "/");
        module.setVersion("1.0.0");
        module.setProviderId(providerId);
        module.setProviderVersion("1.0.0");
        module.setConfigurationJson(configuration.trim());
        return moduleRepository.save(module);
    }

    private void ensureContract(Module module, User owner, String name, String description,
                                String version, String schema, String examples) {
        if (contractRepository.existsByNameAndModuleId(name, module.getId())) return;
        Contract contract = new Contract();
        contract.setName(name);
        contract.setDescription(description);
        contract.setVersion(version);
        contract.setStatus(ContractStatus.PUBLISHED);
        contract.setSchema(schema);
        contract.setExamples(examples);
        contract.setModule(module);
        contract.setOwner(owner);
        contractRepository.save(contract);
    }

    private void startSampleModule(Module module) {
        if ("RUNNING".equals(module.getLifecycleState())) return;
        try {
            Module started = lifecycleService.start(module);
            if (!"RUNNING".equals(started.getLifecycleState())) {
                log.warn("La muestra {} quedó en estado {}: {}", module.getName(), started.getLifecycleState(), started.getRuntimeError());
            } else {
                log.info("Muestra de Nodum iniciada: {}", module.getName());
            }
        } catch (RuntimeException failure) {
            log.warn("No se pudo iniciar la muestra {}. Se puede volver a iniciar desde el panel.", module.getName(), failure);
        }
    }
}
