package com.nodum.modules.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nodum.modules.spi.ModuleDescriptor;
import com.nodum.modules.spi.ModuleHealth;
import com.nodum.modules.spi.ModuleInstanceContext;
import com.nodum.modules.spi.ModulePlugin;
import com.nodum.modules.spi.ModuleValidation;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.common.errors.TopicExistsException;

@Component
public class KafkaMessagingModule implements ModulePlugin {
    private static final int MAX_BUFFERED_MESSAGES = 500;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<UUID, Instance> instances = new ConcurrentHashMap<>();

    public KafkaMessagingModule(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ModuleDescriptor descriptor() {
        return new ModuleDescriptor("kafka-messaging", "1.0.0", 1, "Kafka",
                "Publica eventos y, opcionalmente, consume mensajes desde un topic.",
                Set.of("messaging.produce", "messaging.consume", "messaging.health"));
    }

    @Override
    public ModuleValidation validateConfiguration(String json) {
        try {
            JsonNode config = objectMapper.readTree(json);
            if (config == null || !config.isObject()) return ModuleValidation.invalid("La configuración debe ser un objeto JSON.");
            var fields = config.fieldNames();
            while (fields.hasNext()) {
                String key = fields.next();
                if (!Set.of("bootstrapServers", "topic", "groupId", "consume").contains(key)) {
                    return ModuleValidation.invalid("Propiedad Kafka no admitida: " + key + ". No almacenes credenciales en el módulo.");
                }
            }
            if (blank(config, "bootstrapServers")) return ModuleValidation.invalid("bootstrapServers es obligatorio.");
            if (blank(config, "topic")) return ModuleValidation.invalid("topic es obligatorio.");
            if (blank(config, "groupId")) return ModuleValidation.invalid("groupId es obligatorio.");
            if (config.has("consume") && !config.get("consume").isBoolean()) return ModuleValidation.invalid("consume debe ser true o false.");
            String topic = config.path("topic").asText();
            if (!topic.matches("[A-Za-z0-9._-]{1,249}") || ".".equals(topic) || "..".equals(topic)) {
                return ModuleValidation.invalid("topic tiene caracteres o longitud no permitidos.");
            }
            String servers = config.path("bootstrapServers").asText();
            if (servers.contains(" ") || servers.contains("\n")) return ModuleValidation.invalid("bootstrapServers debe ser una lista separada por comas.");
            return ModuleValidation.ok();
        } catch (Exception exception) {
            return ModuleValidation.invalid("La configuración no contiene JSON válido.");
        }
    }

    @Override
    public void start(ModuleInstanceContext context, String configurationJson) {
        stop(context);
        JsonNode config = readConfig(configurationJson);
        String bootstrapServers = config.path("bootstrapServers").asText();
        String topic = config.path("topic").asText();
        String groupId = config.path("groupId").asText();

        ensureTopic(bootstrapServers, topic);

        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProperties.put(ProducerConfig.CLIENT_ID_CONFIG, "nodum-" + context.instanceId());
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties);

        Instance instance = new Instance(producer, bootstrapServers, topic);
        instances.put(context.instanceId(), instance);
        try {
            if (config.path("consume").asBoolean(false)) {
                Properties consumerProperties = new Properties();
                consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
                consumerProperties.put(ConsumerConfig.CLIENT_ID_CONFIG, "nodum-" + context.instanceId() + "-consumer");
                consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
                consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties);
                instance.consumer = consumer;
                instance.consumerThread = Thread.ofPlatform().name("nodum-kafka-" + context.instanceId()).daemon(true)
                        .start(() -> consume(instance, consumer, topic));
            }
        } catch (RuntimeException failure) {
            stop(context);
            throw failure;
        }
    }

    private void ensureTopic(String bootstrapServers, String topic) {
        Properties adminProperties = new Properties();
        adminProperties.put("bootstrap.servers", bootstrapServers);
        adminProperties.put("client.id", "nodum-topic-setup");
        try (AdminClient admin = AdminClient.create(adminProperties)) {
            try {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException exception) {
                if (!(exception.getCause() instanceof TopicExistsException)) throw exception;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Kafka is unavailable or the topic could not be prepared.");
        }
    }

    private void consume(Instance instance, KafkaConsumer<String, String> consumer, String topic) {
        try (consumer) {
            consumer.subscribe(List.of(topic));
            while (!Thread.currentThread().isInterrupted()) {
                var records = consumer.poll(Duration.ofMillis(250));
                for (var record : records) {
                    synchronized (instance.messages) {
                        while (instance.messages.size() >= MAX_BUFFERED_MESSAGES) instance.messages.removeFirst();
                        instance.messages.addLast(objectMapper.createObjectNode()
                                .put("key", record.key())
                                .put("value", record.value())
                                .put("partition", record.partition())
                                .put("offset", record.offset())
                                .put("timestamp", record.timestamp()));
                    }
                }
            }
        } catch (WakeupException expectedOnStop) {
            // Wakeup is how the owning instance shuts down the poll loop.
        } catch (RuntimeException failure) {
            instance.consumerState.set(ModuleHealth.State.ERROR);
        } finally {
            instance.consumerState.compareAndSet(ModuleHealth.State.RUNNING, ModuleHealth.State.STOPPED);
        }
    }

    @Override
    public void stop(ModuleInstanceContext context) {
        Instance instance = instances.remove(context.instanceId());
        if (instance == null) return;
        if (instance.consumer != null) instance.consumer.wakeup();
        if (instance.consumerThread != null) {
            try { instance.consumerThread.join(TimeUnit.SECONDS.toMillis(3)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        instance.producer.close(Duration.ofSeconds(3));
    }

    @Override
    public ModuleHealth health(ModuleInstanceContext context) {
        Instance instance = instances.get(context.instanceId());
        if (instance == null) return new ModuleHealth(ModuleHealth.State.STOPPED, "Kafka está detenido.");
        if (instance.consumerState.get() == ModuleHealth.State.ERROR) {
            return new ModuleHealth(ModuleHealth.State.DEGRADED, "El consumidor Kafka terminó con un error.");
        }
        try (AdminClient admin = AdminClient.create(readBootstrapConfig(instance))) {
            int brokers = admin.describeCluster().nodes().get(4, TimeUnit.SECONDS).size();
            if (brokers == 0) return new ModuleHealth(ModuleHealth.State.DEGRADED, "No hay brokers Kafka disponibles.");
            return new ModuleHealth(ModuleHealth.State.RUNNING, "Conectado a " + brokers + " broker(s).");
        } catch (Exception failure) {
            return new ModuleHealth(ModuleHealth.State.DEGRADED, "No se pudo comprobar la conexión con Kafka.");
        }
    }

    @Override
    public JsonNode execute(ModuleInstanceContext context, String action, JsonNode input) {
        Instance instance = instances.get(context.instanceId());
        if (instance == null) throw new IllegalStateException("Kafka module is not running");
        return switch (action) {
            case "publish" -> publish(instance, input);
            case "read" -> read(instance);
            default -> throw new UnsupportedOperationException("Unknown Kafka action");
        };
    }

    private JsonNode publish(Instance instance, JsonNode input) {
        if (input == null || !input.hasNonNull("value")) throw new IllegalArgumentException("La acción publish requiere value.");
        try {
            String value = input.get("value").isTextual() ? input.get("value").asText() : input.get("value").toString();
            if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
                throw new IllegalArgumentException("El mensaje supera 1 MiB.");
            }
            var metadata = instance.producer.send(new ProducerRecord<>(instance.topic,
                    input.hasNonNull("key") ? input.get("key").asText() : null,
                    value))
                    .get(5, TimeUnit.SECONDS);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("topic", metadata.topic()).put("partition", metadata.partition()).put("offset", metadata.offset());
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka publish was interrupted");
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Kafka could not acknowledge the message.");
        }
    }

    private JsonNode read(Instance instance) {
        if (instance.consumer == null) throw new IllegalStateException("Enable consume in the module configuration first.");
        ArrayNode result = objectMapper.createArrayNode();
        synchronized (instance.messages) {
            while (!instance.messages.isEmpty()) result.add(instance.messages.removeFirst());
        }
        return result;
    }

    private Properties readBootstrapConfig(Instance instance) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", instance.bootstrapServers);
        properties.put("client.id", "nodum-health-check");
        return properties;
    }

    private JsonNode readConfig(String json) {
        try { return objectMapper.readTree(json); }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid Kafka configuration"); }
    }

    private static boolean blank(JsonNode node, String field) {
        return !node.hasNonNull(field) || node.path(field).asText().isBlank();
    }

    private static final class Instance {
        private final KafkaProducer<String, String> producer;
        private final String bootstrapServers;
        private final String topic;
        private final ArrayDeque<JsonNode> messages = new ArrayDeque<>();
        private final AtomicReference<ModuleHealth.State> consumerState = new AtomicReference<>(ModuleHealth.State.RUNNING);
        private volatile KafkaConsumer<String, String> consumer;
        private volatile Thread consumerThread;

        private Instance(KafkaProducer<String, String> producer, String bootstrapServers, String topic) {
            this.producer = producer;
            this.bootstrapServers = bootstrapServers;
            this.topic = topic;
        }
    }
}
