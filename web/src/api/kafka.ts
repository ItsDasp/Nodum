import { del, get, post, put } from './client';

export interface KafkaBroker { id: number; host: string; port: number; rack: string | null }
export interface KafkaCluster { bootstrapServers: string; brokers: KafkaBroker[] }
export interface KafkaTopic { name: string; partitions: number; replicationFactor: number }
export interface KafkaPartition {
  partition: number;
  leader: number | null;
  replicas: number[];
  inSyncReplicas: number[];
  earliestOffset: number;
  latestOffset: number;
}
export interface KafkaGroup { groupId: string; state: string; memberCount: number; protocolType: string }
export interface KafkaMember { memberId: string; clientId: string; host: string; assignments: string[] }
export interface KafkaGroupOffset {
  topic: string; partition: number; committedOffset: number; latestOffset: number; lag: number;
}
export interface KafkaGroupDetails {
  groupId: string; state: string; protocolType: string; members: KafkaMember[];
  offsets: KafkaGroupOffset[]; totalLag: number;
}

export function getKafkaCluster() { return get<KafkaCluster>('/kafka/cluster'); }
export function getKafkaTopics() { return get<KafkaTopic[]>('/kafka/topics'); }
export function getKafkaPartitions(topic: string) {
  return get<KafkaPartition[]>(`/kafka/topics/${encodeURIComponent(topic)}/partitions`);
}
export function createKafkaTopic(data: { name: string; partitions: number; replicationFactor: number }) {
  return post<KafkaTopic>('/kafka/topics', data);
}
export function increaseKafkaPartitions(topic: string, partitions: number) {
  return put<KafkaTopic>(`/kafka/topics/${encodeURIComponent(topic)}/partitions`, { partitions });
}
export function deleteKafkaTopic(topic: string) {
  return del(`/kafka/topics/${encodeURIComponent(topic)}`);
}
export function getKafkaGroups() { return get<KafkaGroup[]>('/kafka/consumer-groups'); }
export function getKafkaGroup(groupId: string) {
  return get<KafkaGroupDetails>(`/kafka/consumer-groups/${encodeURIComponent(groupId)}`);
}
export function resetKafkaGroupOffsets(groupId: string, target: 'earliest' | 'latest') {
  return post<KafkaGroupDetails>(`/kafka/consumer-groups/${encodeURIComponent(groupId)}/offsets/reset`, { target });
}
export function deleteKafkaGroup(groupId: string) {
  return del(`/kafka/consumer-groups/${encodeURIComponent(groupId)}`);
}
