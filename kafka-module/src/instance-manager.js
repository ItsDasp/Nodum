import { Kafka } from 'kafkajs';

export class KafkaInstanceManager {
  constructor(logger) {
    this.logger = logger;
    this.instances = new Map();
  }

  validate(configuration) {
    if (!configuration || typeof configuration !== 'object' || Array.isArray(configuration)) {
      return ['Configuration must be an object.'];
    }
    const allowed = new Set(['bootstrapServers', 'topic', 'groupId', 'consume']);
    const unknown = Object.keys(configuration).filter(key => !allowed.has(key));
    if (unknown.length) return [`Unsupported Kafka property: ${unknown.join(', ')}.`];

    const errors = [];
    if (typeof configuration.bootstrapServers !== 'string' || !configuration.bootstrapServers.trim()) {
      errors.push('bootstrapServers is required.');
    }
    if (typeof configuration.topic !== 'string' || !/^[A-Za-z0-9._-]{1,249}$/.test(configuration.topic) || configuration.topic === '.' || configuration.topic === '..') {
      errors.push('topic is invalid.');
    }
    if (typeof configuration.groupId !== 'string' || !configuration.groupId.trim()) {
      errors.push('groupId is required.');
    }
    if ('consume' in configuration && typeof configuration.consume !== 'boolean') {
      errors.push('consume must be true or false.');
    }
    if (Object.keys(configuration).some(key => /password|secret|token|credential/i.test(key))) {
      errors.push('Kafka secrets must not be stored in module configuration.');
    }
    return errors;
  }

  async start(instanceId, configuration) {
    if (this.instances.has(instanceId)) await this.stop(instanceId);

    const brokers = configuration.bootstrapServers.split(',').map(value => value.trim()).filter(Boolean);
    const kafka = new Kafka({
      clientId: `nodum-kafka-${instanceId}`,
      brokers,
      connectionTimeout: 3000,
      authenticationTimeout: 3000,
      reauthenticationThreshold: 10,
      retry: { retries: 3 }
    });

    const admin = kafka.admin();
    await admin.connect();
    try {
      await admin.createTopics({ topics: [{ topic: configuration.topic, numPartitions: 1 }] });
    } catch (error) {
      if (!String(error?.message || '').includes('TopicAlreadyExists')) throw error;
    } finally {
      await admin.disconnect();
    }

    const producer = kafka.producer({ idempotent: true, maxInFlightRequests: 5 });
    await producer.connect();

    const recordBuffer = [];
    let consumer = null;

    if (configuration.consume === true) {
      consumer = kafka.consumer({
        groupId: configuration.groupId,
        allowAutoTopicCreation: false
      });
      await consumer.connect();
      await consumer.subscribe({ topic: configuration.topic, fromBeginning: false });
      await consumer.run({
        eachMessage: async ({ message, partition, topic }) => {
          recordBuffer.push({
            key: message.key?.toString() || null,
            value: message.value?.toString() || null,
            partition,
            topic,
            offset: message.offset,
            timestamp: message.timestamp
          });
          while (recordBuffer.length > 500) recordBuffer.shift();
        }
      });
    }

    const instance = { configuration, kafka, producer, consumer, recordBuffer, observedState: 'RUNNING' };
    this.instances.set(instanceId, instance);
    this.logger.info({ instanceId }, 'Kafka instance started');
    return instance;
  }

  async stop(instanceId) {
    const instance = this.instances.get(instanceId);
    if (!instance) return false;
    if (instance.consumer) await instance.consumer.disconnect();
    await instance.producer.disconnect();
    this.instances.delete(instanceId);
    this.logger.info({ instanceId }, 'Kafka instance stopped');
    return true;
  }

  async health(instanceId) {
    const instance = this.instances.get(instanceId);
    if (!instance) return { observedState: 'STOPPED', message: 'Kafka instance is stopped.' };
    try {
      const admin = instance.kafka.admin();
      await admin.connect();
      const metadata = await admin.fetchTopicMetadata({ topics: [instance.configuration.topic] });
      await admin.disconnect();
      if (!metadata?.topics?.length) throw new Error('Topic metadata unavailable');
      return { observedState: 'RUNNING', message: 'Kafka broker and topic are available.' };
    } catch (error) {
      return { observedState: 'DEGRADED', message: 'Kafka broker or topic is unavailable.' };
    }
  }

  async execute(instanceId, action, input) {
    const instance = this.instances.get(instanceId);
    if (!instance) throw Object.assign(new Error('Kafka instance is not running.'), { code: 'INSTANCE_NOT_RUNNING' });

    if (action === 'publish') {
      if (!input || input.value === undefined) throw Object.assign(new Error('publish requires value.'), { code: 'INVALID_REQUEST' });
      const value = typeof input.value === 'string' ? input.value : JSON.stringify(input.value);
      if (Buffer.byteLength(value, 'utf8') > 1_048_576) throw Object.assign(new Error('Kafka message exceeds 1 MiB.'), { code: 'INVALID_REQUEST' });
      const result = await instance.producer.send({
        topic: instance.configuration.topic,
        messages: [{ key: input.key === undefined ? undefined : String(input.key), value }]
      });
      const record = result[0];
      return { topic: record.topicName, partition: record.partition, offset: record.baseOffset };
    }

    if (action === 'read') {
      if (!instance.consumer) throw Object.assign(new Error('Consume is not enabled for this instance.'), { code: 'INVALID_REQUEST' });
      const records = instance.recordBuffer.splice(0, instance.recordBuffer.length);
      return records;
    }

    throw Object.assign(new Error('Unknown Kafka action.'), { code: 'UNKNOWN_ACTION' });
  }

  async stopAll() {
    await Promise.all([...this.instances.keys()].map(instanceId => this.stop(instanceId)));
  }
}
