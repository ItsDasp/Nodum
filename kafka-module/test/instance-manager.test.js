import test from 'node:test';
import assert from 'node:assert/strict';
import { KafkaInstanceManager, nextOffsetsToCommit } from '../src/instance-manager.js';

const silentLogger = { info() {}, error() {} };

test('validates existing Kafka configuration shape', () => {
  const manager = new KafkaInstanceManager(silentLogger);
  assert.deepEqual(manager.validate({
    bootstrapServers: 'kafka:9092',
    topic: 'events',
    groupId: 'nodum-events',
    consume: false
  }), []);
});

test('rejects unsupported properties and credentials', () => {
  const manager = new KafkaInstanceManager(silentLogger);
  const errors = manager.validate({
    bootstrapServers: 'kafka:9092',
    topic: 'events',
    groupId: 'nodum-events',
    consume: false,
    password: 'secret'
  });
  assert.ok(errors.some(error => error.includes('Unsupported Kafka property')));
  assert.ok(errors.some(error => error.includes('secrets')));
});

test('stopping an unknown instance is idempotent', async () => {
  const manager = new KafkaInstanceManager(silentLogger);
  assert.equal(await manager.stop('11111111-1111-1111-1111-111111111111'), false);
  await manager.stopAll();
});

test('computes the next offsets to commit from consumed records', () => {
  assert.deepEqual(nextOffsetsToCommit([
    { topic: 'events', partition: 0, offset: '1' },
    { topic: 'events', partition: 0, offset: '3' },
    { topic: 'events', partition: 1, offset: '7' }
  ]), [
    { topic: 'events', partition: 0, offset: '4' },
    { topic: 'events', partition: 1, offset: '8' }
  ]);
});

test('validates partitions and a configured default partition', () => {
  const manager = new KafkaInstanceManager(silentLogger);
  assert.deepEqual(manager.validate({
    bootstrapServers: 'kafka:9092',
    topic: 'events',
    groupId: 'nodum-events',
    consume: true,
    partitions: 3,
    replicationFactor: 1,
    partition: 2
  }), []);

  const errors = manager.validate({
    bootstrapServers: 'kafka:9092',
    topic: 'events',
    groupId: 'nodum-events',
    consume: true,
    partitions: 2,
    partition: 2
  });
  assert.ok(errors.some(error => error.includes('partition must be lower')));
});
