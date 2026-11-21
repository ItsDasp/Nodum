package com.nodum.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Node;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class KafkaAdministrationService {
    private static final Pattern TOPIC_NAME = Pattern.compile("[A-Za-z0-9._-]{1,249}");
    private final String bootstrapServers;

    public KafkaAdministrationService(@Value("${nodum.kafka.brokers:kafka:9092}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public ClusterSummary cluster() {
        return withAdmin(admin -> {
            List<Broker> brokers = admin.describeCluster().nodes().get(6, TimeUnit.SECONDS).stream()
                    .sorted(Comparator.comparingInt(Node::id))
                    .map(node -> new Broker(node.id(), node.host(), node.port(), node.rack()))
                    .toList();
            return new ClusterSummary(bootstrapServers, brokers);
        });
    }

    public List<TopicSummary> topics() {
        return withAdmin(admin -> {
            Set<String> names = admin.listTopics().names().get(6, TimeUnit.SECONDS).stream()
                    .filter(name -> !name.startsWith("_"))
                    .collect(java.util.stream.Collectors.toSet());
            if (names.isEmpty()) return List.of();
            return admin.describeTopics(names).all().get(6, TimeUnit.SECONDS).values().stream()
                    .sorted(Comparator.comparing(description -> description.name()))
                    .map(description -> new TopicSummary(description.name(), description.partitions().size(),
                            description.partitions().isEmpty() ? 0 : description.partitions().getFirst().replicas().size()))
                    .toList();
        });
    }

    public List<PartitionSummary> partitions(String topic) {
        return withAdmin(admin -> {
            var description = admin.describeTopics(List.of(topic)).all().get(6, TimeUnit.SECONDS).get(topic);
            Map<TopicPartition, OffsetSpec> earliestRequests = new HashMap<>();
            Map<TopicPartition, OffsetSpec> latestRequests = new HashMap<>();
            description.partitions().forEach(partition -> {
                TopicPartition key = new TopicPartition(topic, partition.partition());
                earliestRequests.put(key, OffsetSpec.earliest());
                latestRequests.put(key, OffsetSpec.latest());
            });
            var earliest = admin.listOffsets(earliestRequests).all().get(6, TimeUnit.SECONDS);
            var latest = admin.listOffsets(latestRequests).all().get(6, TimeUnit.SECONDS);
            return description.partitions().stream().map(partition -> {
                TopicPartition key = new TopicPartition(topic, partition.partition());
                return new PartitionSummary(partition.partition(),
                        partition.leader() == null ? null : partition.leader().id(),
                        partition.replicas().stream().map(Node::id).toList(),
                        partition.isr().stream().map(Node::id).toList(),
                        earliest.get(key).offset(), latest.get(key).offset());
            }).toList();
        });
    }

    public TopicSummary createTopic(String name, Integer requestedPartitions, Short requestedReplicationFactor) {
        validateTopicName(name);
        int partitions = requestedPartitions == null ? 1 : requestedPartitions;
        short replicationFactor = requestedReplicationFactor == null ? 1 : requestedReplicationFactor;
        if (partitions < 1 || partitions > 1000) throw badRequest("La cantidad de particiones debe estar entre 1 y 1000.");
        if (replicationFactor < 1) throw badRequest("El factor de réplicas debe ser al menos 1.");
        return withAdmin(admin -> {
            int brokerCount = admin.describeCluster().nodes().get(6, TimeUnit.SECONDS).size();
            if (replicationFactor > brokerCount) {
                throw badRequest("El factor de réplicas supera los " + brokerCount + " broker(s) disponibles.");
            }
            admin.createTopics(List.of(new NewTopic(name, partitions, replicationFactor)))
                    .all().get(8, TimeUnit.SECONDS);
            return topics().stream().filter(topic -> topic.name().equals(name)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Kafka no devolvió el topic creado."));
        });
    }

    public void deleteTopic(String name) {
        validateTopicName(name);
        withAdmin(admin -> {
            admin.deleteTopics(List.of(name)).all().get(8, TimeUnit.SECONDS);
            return null;
        });
    }

    public TopicSummary increasePartitions(String name, int newCount) {
        validateTopicName(name);
        if (newCount < 1 || newCount > 1000) throw badRequest("La cantidad total de particiones debe estar entre 1 y 1000.");
        return withAdmin(admin -> {
            int current = admin.describeTopics(List.of(name)).all().get(6, TimeUnit.SECONDS).get(name).partitions().size();
            if (newCount <= current) throw badRequest("La nueva cantidad debe ser mayor que las " + current + " particiones actuales.");
            admin.createPartitions(Map.of(name, NewPartitions.increaseTo(newCount))).all().get(8, TimeUnit.SECONDS);
            return new TopicSummary(name, newCount, topics().stream().filter(topic -> topic.name().equals(name))
                    .map(TopicSummary::replicationFactor).findFirst().orElse(1));
        });
    }

    public List<GroupSummary> groups() {
        return withAdmin(admin -> {
            List<ConsumerGroupListing> listings = admin.listConsumerGroups().all().get(6, TimeUnit.SECONDS).stream()
                    .sorted(Comparator.comparing(ConsumerGroupListing::groupId)).toList();
            if (listings.isEmpty()) return List.of();
            Map<String, ConsumerGroupDescription> descriptions = admin.describeConsumerGroups(
                    listings.stream().map(ConsumerGroupListing::groupId).toList()).all().get(6, TimeUnit.SECONDS);
            return listings.stream().map(listing -> {
                ConsumerGroupDescription description = descriptions.get(listing.groupId());
                return new GroupSummary(listing.groupId(), groupState(description),
                        description == null ? 0 : description.members().size(),
                        description == null ? "" : groupProtocol(description));
            }).toList();
        });
    }

    public GroupDetails group(String groupId) {
        return withAdmin(admin -> {
            ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(groupId))
                    .describedGroups().get(groupId).get(6, TimeUnit.SECONDS);
            List<Member> members = description.members().stream().map(member -> new Member(member.consumerId(),
                    member.clientId(), member.host(), member.assignment().topicPartitions().stream()
                    .map(partition -> partition.topic() + " / " + partition.partition()).sorted().toList())).toList();

            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata().get(6, TimeUnit.SECONDS);
            if (committed.isEmpty()) return new GroupDetails(groupId, groupState(description),
                    groupProtocol(description), members, List.of(), 0);
            Map<TopicPartition, OffsetSpec> latestRequests = new HashMap<>();
            committed.keySet().forEach(partition -> latestRequests.put(partition, OffsetSpec.latest()));
            var latest = admin.listOffsets(latestRequests).all().get(6, TimeUnit.SECONDS);
            List<GroupOffset> offsets = committed.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition)))
                    .map(entry -> {
                        long end = latest.get(entry.getKey()).offset();
                        long committedOffset = entry.getValue().offset();
                        return new GroupOffset(entry.getKey().topic(), entry.getKey().partition(),
                                committedOffset, end, Math.max(0, end - committedOffset));
                    }).toList();
            return new GroupDetails(groupId, groupState(description), groupProtocol(description), members,
                    offsets, offsets.stream().mapToLong(GroupOffset::lag).sum());
        });
    }

    public GroupDetails resetOffsets(String groupId, String target) {
        String normalized = target == null ? "" : target.toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("earliest", "latest").contains(normalized)) {
            throw badRequest("El destino debe ser earliest (inicio) o latest (final).");
        }
        return withAdmin(admin -> {
            ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(groupId))
                    .describedGroups().get(groupId).get(6, TimeUnit.SECONDS);
            if (description.state() != org.apache.kafka.common.ConsumerGroupState.EMPTY) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Detén los consumidores del grupo antes de restablecer sus offsets.");
            }
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata().get(6, TimeUnit.SECONDS);
            if (committed.isEmpty()) throw badRequest("El grupo todavía no tiene offsets confirmados para restablecer.");
            Map<TopicPartition, OffsetSpec> requests = new HashMap<>();
            committed.keySet().forEach(partition -> requests.put(partition,
                    normalized.equals("earliest") ? OffsetSpec.earliest() : OffsetSpec.latest()));
            var offsets = admin.listOffsets(requests).all().get(6, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> reset = new HashMap<>();
            offsets.forEach((partition, info) -> reset.put(partition, new OffsetAndMetadata(info.offset())));
            admin.alterConsumerGroupOffsets(groupId, reset).all().get(8, TimeUnit.SECONDS);
            return group(groupId);
        });
    }

    public void deleteGroup(String groupId) {
        withAdmin(admin -> {
            ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(groupId))
                    .describedGroups().get(groupId).get(6, TimeUnit.SECONDS);
            if (description.state() != org.apache.kafka.common.ConsumerGroupState.EMPTY) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Detén los consumidores antes de eliminar el grupo.");
            }
            admin.deleteConsumerGroups(List.of(groupId)).all().get(8, TimeUnit.SECONDS);
            return null;
        });
    }

    private String groupState(ConsumerGroupDescription description) {
        return description == null || description.state() == null ? "UNKNOWN" : description.state().toString();
    }

    private String groupProtocol(ConsumerGroupDescription description) {
        return description.isSimpleConsumerGroup() ? "simple" : "consumer";
    }

    private void validateTopicName(String name) {
        if (name == null || !TOPIC_NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw badRequest("El nombre del topic solo puede incluir letras, números, punto, guion y guion bajo.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private <T> T withAdmin(AdminOperation<T> operation) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "6000");
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "8000");
        try (AdminClient admin = AdminClient.create(properties)) {
            return operation.apply(admin);
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo completar la operación con Kafka. Comprueba los brokers y vuelve a intentarlo.");
        }
    }

    @FunctionalInterface
    private interface AdminOperation<T> { T apply(AdminClient admin) throws Exception; }

    public record Broker(int id, String host, int port, String rack) {}
    public record ClusterSummary(String bootstrapServers, List<Broker> brokers) {}
    public record TopicSummary(String name, int partitions, int replicationFactor) {}
    public record PartitionSummary(int partition, Integer leader, List<Integer> replicas,
                                  List<Integer> inSyncReplicas, long earliestOffset, long latestOffset) {}
    public record GroupSummary(String groupId, String state, int memberCount, String protocolType) {}
    public record Member(String memberId, String clientId, String host, List<String> assignments) {}
    public record GroupOffset(String topic, int partition, long committedOffset, long latestOffset, long lag) {}
    public record GroupDetails(String groupId, String state, String protocolType, List<Member> members,
                               List<GroupOffset> offsets, long totalLag) {}
    public record ExpandTopicRequest(int partitions) {}
}
