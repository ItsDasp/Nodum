package com.nodum.kafka;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/kafka")
public class KafkaAdministrationController {
    private final KafkaAdministrationService service;

    public KafkaAdministrationController(KafkaAdministrationService service) {
        this.service = service;
    }

    @GetMapping("/cluster")
    public KafkaAdministrationService.ClusterSummary cluster() { return service.cluster(); }

    @GetMapping("/topics")
    public List<KafkaAdministrationService.TopicSummary> topics() { return service.topics(); }

    @GetMapping("/topics/{topic}/partitions")
    public List<KafkaAdministrationService.PartitionSummary> partitions(@PathVariable String topic) {
        return service.partitions(topic);
    }

    @PostMapping("/topics")
    public KafkaAdministrationService.TopicSummary createTopic(@Valid @RequestBody CreateTopicRequest request) {
        return service.createTopic(request.name(), request.partitions(), request.replicationFactor());
    }

    @PutMapping("/topics/{topic}/partitions")
    public KafkaAdministrationService.TopicSummary increasePartitions(@PathVariable String topic,
                                                                        @RequestBody ExpandTopicRequest request) {
        return service.increasePartitions(topic, request.partitions());
    }

    @DeleteMapping("/topics/{topic}")
    public ResponseEntity<Void> deleteTopic(@PathVariable String topic) {
        service.deleteTopic(topic);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/consumer-groups")
    public List<KafkaAdministrationService.GroupSummary> groups() { return service.groups(); }

    @GetMapping("/consumer-groups/{groupId}")
    public KafkaAdministrationService.GroupDetails group(@PathVariable String groupId) {
        return service.group(groupId);
    }

    @PostMapping("/consumer-groups/{groupId}/offsets/reset")
    public KafkaAdministrationService.GroupDetails resetOffsets(@PathVariable String groupId,
                                                                 @RequestBody ResetOffsetsRequest request) {
        return service.resetOffsets(groupId, request.target());
    }

    @DeleteMapping("/consumer-groups/{groupId}")
    public ResponseEntity<Void> deleteGroup(@PathVariable String groupId) {
        service.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    public record CreateTopicRequest(@NotBlank @Size(max = 249) String name,
                                     @Min(1) @Max(1000) Integer partitions,
                                     @Min(1) @Max(1000) Short replicationFactor) {}
    public record ResetOffsetsRequest(@NotBlank String target) {}
    public record ExpandTopicRequest(@Min(1) @Max(1000) int partitions) {}
}
