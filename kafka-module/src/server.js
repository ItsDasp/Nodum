import express from 'express';
import pino from 'pino';
import { KafkaInstanceManager } from './instance-manager.js';

const app = express();
const logger = pino({ level: process.env.LOG_LEVEL || 'info' });
const manager = new KafkaInstanceManager(logger);
const contractVersion = '1';
const serviceToken = process.env.NODUM_MODULE_SERVICE_TOKEN || '';

app.use(express.json({ limit: '1mb' }));
app.use((req, res, next) => {
  if (req.path === '/actuator/health') return next();
  if (!serviceToken || req.headers.authorization !== `Bearer ${serviceToken}`) {
    return res.status(401).json({ contractVersion, success: false, observedState: 'ERROR', error: { code: 'UNAUTHORIZED', retryable: false, message: 'Unauthorized' } });
  }
  if (req.headers['x-nodum-contract-version'] && req.headers['x-nodum-contract-version'] !== contractVersion) {
    return res.status(422).json({ contractVersion, success: false, observedState: 'UNKNOWN', error: { code: 'UNSUPPORTED_CONTRACT', retryable: false, message: 'Unsupported contract version' } });
  }
  next();
});

function envelope(req, success, observedState, runtimeId, output, error, message) {
  return {
    contractVersion,
    correlationId: req.body?.correlationId || req.headers['x-nodum-correlation-id'] || null,
    instanceId: req.params.instanceId || req.body?.instanceId || null,
    success,
    observedState,
    runtimeId: runtimeId || null,
    output: output ?? null,
    error: error || null,
    message
  };
}

app.get('/actuator/health', (_req, res) => res.json({ status: 'UP' }));

app.post('/v1/modules/validate', (req, res) => {
  const configuration = req.body?.configuration || req.body;
  const errors = manager.validate(configuration);
  res.status(errors.length ? 422 : 200).json({
    contractVersion,
    correlationId: req.body?.correlationId || null,
    valid: errors.length === 0,
    errors
  });
});

app.post('/v1/modules/start', async (req, res) => {
  const instanceId = req.body?.instanceId;
  const configuration = req.body?.configuration;
  if (!/^[-0-9a-f]{36}$/i.test(instanceId || '')) {
    return res.status(422).json(envelope(req, false, 'ERROR', null, null,
      { code: 'INVALID_INSTANCE', retryable: false, message: 'Invalid instance ID', details: null }, 'Invalid instance ID'));
  }
  const errors = manager.validate(configuration);
  if (errors.length) {
    return res.status(422).json(envelope(req, false, 'ERROR', null, null,
      { code: 'INVALID_CONFIGURATION', retryable: false, message: errors.join('; '), details: null }, errors.join('; ')));
  }
  try {
    await manager.start(instanceId, configuration);
    res.json(envelope(req, true, 'RUNNING', instanceId, null, null, 'Kafka instance started'));
  } catch (error) {
    logger.error({ instanceId, error: error.message }, 'Kafka start failed');
    res.status(503).json(envelope(req, false, 'DEGRADED', instanceId, null,
      { code: 'BROKER_UNAVAILABLE', retryable: true, message: 'Kafka broker is unavailable', details: null }, 'Kafka broker is unavailable'));
  }
});

app.post('/v1/modules/stop', async (req, res) => {
  const instanceId = req.body?.instanceId;
  try {
    await manager.stop(instanceId);
    res.json(envelope(req, true, 'STOPPED', instanceId, null, null, 'Kafka instance stopped'));
  } catch (error) {
    logger.error({ instanceId, error: error.message }, 'Kafka stop failed');
    res.status(503).json(envelope(req, false, 'ERROR', instanceId, null,
      { code: 'STOP_FAILED', retryable: true, message: 'Kafka instance could not stop cleanly', details: null }, 'Kafka stop failed'));
  }
});

app.get('/v1/modules/health/:instanceId', async (req, res) => {
  const result = await manager.health(req.params.instanceId);
  res.json(envelope(req, true, result.observedState, req.params.instanceId, null, null, result.message));
});

app.post('/v1/modules/execute/:instanceId/:action', async (req, res) => {
  try {
    const output = await manager.execute(req.params.instanceId, req.params.action, req.body?.input);
    res.json(envelope(req, true, 'RUNNING', req.params.instanceId, output, null, 'Kafka action completed'));
  } catch (error) {
    const degraded = error.code === 'BROKER_UNAVAILABLE' || error.code === 'ECONNRESET' || error.code === 'ECONNREFUSED';
    const status = degraded ? 503 : 422;
    res.status(status).json(envelope(req, false, degraded ? 'DEGRADED' : 'RUNNING', req.params.instanceId, null,
      { code: degraded ? 'BROKER_UNAVAILABLE' : (error.code || 'INVALID_REQUEST'), retryable: degraded, message: degraded ? 'Kafka broker is unavailable' : error.message, details: null },
      degraded ? 'Kafka broker is unavailable' : error.message));
  }
});

const port = Number(process.env.PORT || 8082);
const server = app.listen(port, '0.0.0.0', () => logger.info({ port }, 'Kafka module listening'));

async function shutdown(signal) {
  logger.info({ signal }, 'Shutting down Kafka module');
  await manager.stopAll();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 10000).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
