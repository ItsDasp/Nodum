import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Layout } from '../../components/Layout';
import {
  createModule, deleteModule, executeModuleAction, getModuleCatalog, getModules,
  startModule, stopModule, type Module as NodumModule,
} from '../../api/modules';
import { KafkaAdministration } from './KafkaAdministration';

const configurationExamples: Record<string, string> = {
  'http-gateway': JSON.stringify({ public: false, routes: [{ pathPrefix: '/posts', upstreamUrl: 'https://jsonplaceholder.typicode.com' }] }, null, 2),
};

const lifecycleLabels: Record<string, string> = {
  CONFIGURED: 'Configurado', STARTING: 'Iniciando', RUNNING: 'En ejecución', STOPPED: 'Detenido', ERROR: 'Error',
};

function readableRuntimeError(error: string | null) {
  if (!error) return null;
  if (error === 'Remote module request timed out') return 'El proveedor tardó demasiado en responder. Comprueba que Kafka esté disponible y vuelve a iniciar el módulo.';
  if (error === 'Remote module is unreachable') return 'No se pudo conectar con el proveedor remoto.';
  return error;
}

export function Modules() {
  const { projectId } = useParams<{ projectId: string }>();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [basePath, setBasePath] = useState('/api/v1');
  const [version, setVersion] = useState('1.0.0');
  const [providerId, setProviderId] = useState('');
  const [configurationJson, setConfigurationJson] = useState('{}');
  const [kafkaConfig, setKafkaConfig] = useState({ bootstrapServers: 'kafka:9092', topic: 'events', partitions: '1', replicationFactor: '1', partition: '', groupId: 'nodum-events', consume: true });
  const [formError, setFormError] = useState('');
  const [actionModule, setActionModule] = useState<NodumModule | null>(null);
  const [actionInput, setActionInput] = useState('{}');
  const [actionName, setActionName] = useState('publish');
  const [actionOutput, setActionOutput] = useState('');
  const modules = useQuery({ queryKey: ['modules', projectId], queryFn: () => getModules(projectId!), enabled: !!projectId });
  const catalog = useQuery({ queryKey: ['module-catalog'], queryFn: getModuleCatalog });
  const create = useMutation({
    mutationFn: () => createModule(projectId!, {
      name: name.trim(), description: description.trim(), basePath: providerId ? '/' : basePath.trim(), version: providerId ? '1.0.0' : version.trim(),
      ...(providerId ? { providerId, configurationJson: providerId === 'kafka-messaging'
        ? JSON.stringify({ bootstrapServers: kafkaConfig.bootstrapServers.trim(), topic: kafkaConfig.topic.trim(), partitions: Number(kafkaConfig.partitions), replicationFactor: Number(kafkaConfig.replicationFactor), ...(kafkaConfig.partition === '' ? {} : { partition: Number(kafkaConfig.partition) }), groupId: kafkaConfig.groupId.trim(), consume: kafkaConfig.consume })
        : configurationJson } : {}),
    }),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['modules', projectId] }); setOpen(false); setName(''); setDescription(''); setProviderId(''); setConfigurationJson('{}'); setFormError(''); },
  });
  const remove = useMutation({ mutationFn: (id: string) => deleteModule(projectId!, id), onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['modules', projectId] }) });
  const lifecycle = useMutation({
    mutationFn: ({ module, action }: { module: NodumModule; action: 'start' | 'stop' }) => action === 'start' ? startModule(projectId!, module.id) : stopModule(projectId!, module.id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['modules', projectId] }),
  });
  const execute = useMutation({
    mutationFn: ({ module, action, input }: { module: NodumModule; action: string; input: unknown }) => executeModuleAction(projectId!, module.id, action, input),
    onSuccess: (result) => setActionOutput(JSON.stringify(result, null, 2)),
  });

  const submitAction = () => {
    if (!actionModule) return;
    try {
      const input = JSON.parse(actionInput) as unknown;
      execute.mutate({ module: actionModule, action: actionName, input });
    } catch {
      setActionOutput('La solicitud debe ser JSON válido.');
    }
  };

  return <Layout>
    <div style={{ marginBottom: 18 }}><Link to="/projects" className="breadcrumb">← Proyectos</Link></div>
    <div className="page-heading"><div><h1>Módulos</h1><p>Instancias y capacidades conectadas a este proyecto.</p></div><button className="btn btn-primary" onClick={() => setOpen((value) => !value)}>{open ? 'Cancelar' : '+ Añadir módulo'}</button></div>
    <div className="surface" style={{ marginBottom: 18, padding: '13px 16px', color: '#68716b', fontSize: 12 }}>
      Los proveedores instalados en el backend aparecen aquí. La puerta de enlace solo accede a los hosts autorizados en <code>NODUM_GATEWAY_ALLOWED_HOSTS</code>. No guardes contraseñas ni tokens dentro de la configuración del módulo.
    </div>
    {create.isError && <p role="alert" style={{ color: '#a24842', fontSize: 13 }}>{create.error.message || 'No se pudo crear el módulo.'}</p>}
    {remove.isError && <p role="alert" style={{ color: '#a24842', fontSize: 13 }}>No se pudo eliminar el módulo.</p>}
    {lifecycle.isError && <p role="alert" style={{ color: '#a24842', fontSize: 13 }}>{lifecycle.error.message || 'No se pudo cambiar el estado del módulo.'}</p>}
    {execute.isError && <p role="alert" style={{ color: '#a24842', fontSize: 13 }}>{execute.error.message || 'Falló la acción del módulo.'}</p>}

    {open && <form className="surface surface-pad" style={{ display: 'grid', gap: 14, marginBottom: 18 }} onSubmit={(event) => {
      event.preventDefault();
      if (!name.trim()) return;
      if (providerId === 'kafka-messaging') {
        if (!kafkaConfig.bootstrapServers.trim() || !kafkaConfig.groupId.trim() || !/^[A-Za-z0-9._-]{1,249}$/.test(kafkaConfig.topic)) { setFormError('Completa brokers, topic y grupo con valores válidos.'); return; }
        if (!Number.isInteger(Number(kafkaConfig.partitions)) || Number(kafkaConfig.partitions) < 1 || !Number.isInteger(Number(kafkaConfig.replicationFactor)) || Number(kafkaConfig.replicationFactor) < 1) { setFormError('Las particiones y el factor de réplicas deben ser enteros positivos.'); return; }
        if (kafkaConfig.partition !== '' && (!Number.isInteger(Number(kafkaConfig.partition)) || Number(kafkaConfig.partition) < 0 || Number(kafkaConfig.partition) >= Number(kafkaConfig.partitions))) { setFormError('La partición fija debe estar entre cero y el total de particiones menos uno.'); return; }
      } else if (providerId) { try { JSON.parse(configurationJson); } catch { setFormError('La configuración debe ser JSON válido.'); return; } }
      create.mutate();
    }}>
      <h2 className="section-title">Añadir módulo</h2>
      <div><label className="field-label" htmlFor="provider">Proveedor instalado</label><select id="provider" className="field" value={providerId} onChange={(event) => { const id = event.target.value; setProviderId(id); setConfigurationJson(configurationExamples[id] ?? '{}'); }}><option value="">Solo metadatos (sin ejecución)</option>{catalog.data?.map((provider) => <option value={provider.id} key={provider.id}>{provider.name} · v{provider.version}</option>)}</select>
        {catalog.isError && <small role="alert">No se pudo cargar el catálogo de proveedores.</small>}
        {providerId && <p style={{ margin: '6px 0 0', color: '#818a83', fontSize: 11 }}>{catalog.data?.find((provider) => provider.id === providerId)?.description} Capacidades: {catalog.data?.find((provider) => provider.id === providerId)?.capabilities.join(', ')}.</p>}
      </div>
      <div><label className="field-label" htmlFor="module-name">Nombre</label><input id="module-name" className="field" value={name} onChange={(event) => setName(event.target.value)} required maxLength={120} placeholder="p. ej. API de medicamentos" /></div>
      <div><label className="field-label" htmlFor="module-description">Descripción</label><textarea id="module-description" className="field" value={description} onChange={(event) => setDescription(event.target.value)} rows={2} maxLength={1000} /></div>
      {!providerId && <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 12 }}><div><label className="field-label" htmlFor="module-path">Prefijo de ruta</label><input id="module-path" className="field mono" value={basePath} onChange={(event) => setBasePath(event.target.value)} required pattern="/[A-Za-z0-9/_{}-]*" /></div><div><label className="field-label" htmlFor="module-version">Versión</label><input id="module-version" className="field" value={version} onChange={(event) => setVersion(event.target.value)} /></div></div>}
      {providerId === 'kafka-messaging' && <div style={{ display: 'grid', gap: 12 }}>
        <p style={{ margin: 0, color: '#68716b', fontSize: 12 }}>Configura el clúster, el topic y el consumidor. Si el topic no existe, Kafka lo crea con las particiones y réplicas indicadas. Los brokers se separan con comas.</p>
        <div><label className="field-label" htmlFor="kafka-brokers">Brokers</label><input id="kafka-brokers" className="field mono" value={kafkaConfig.bootstrapServers} onChange={(event) => setKafkaConfig({ ...kafkaConfig, bootstrapServers: event.target.value })} required placeholder="kafka:9092, kafka-2:9092" /><small>Usa nombres y puertos accesibles desde Nodum; dentro de Compose, el broker local es kafka:9092.</small></div>
        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr', gap: 10 }}>
          <div><label className="field-label" htmlFor="kafka-topic">Topic</label><input id="kafka-topic" className="field" value={kafkaConfig.topic} onChange={(event) => setKafkaConfig({ ...kafkaConfig, topic: event.target.value })} required pattern="[A-Za-z0-9._-]{1,249}" /></div>
          <div><label className="field-label" htmlFor="kafka-topic-partitions">Particiones</label><input id="kafka-topic-partitions" className="field" type="number" min="1" max="1000" value={kafkaConfig.partitions} onChange={(event) => setKafkaConfig({ ...kafkaConfig, partitions: event.target.value })} required /></div>
          <div><label className="field-label" htmlFor="kafka-topic-replicas">Factor de réplicas</label><input id="kafka-topic-replicas" className="field" type="number" min="1" value={kafkaConfig.replicationFactor} onChange={(event) => setKafkaConfig({ ...kafkaConfig, replicationFactor: event.target.value })} required /></div>
        </div>
        <small style={{ color: '#818a83' }}>Cada réplica se aloja en un broker distinto. Con un único broker, usa factor 1. Si el topic ya existe, su número de particiones y réplicas no se modifica desde esta configuración.</small>
        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 10 }}>
          <div><label className="field-label" htmlFor="kafka-group">Grupo de consumidores</label><input id="kafka-group" className="field" value={kafkaConfig.groupId} onChange={(event) => setKafkaConfig({ ...kafkaConfig, groupId: event.target.value })} required /></div>
          <div><label className="field-label" htmlFor="kafka-default-partition">Partición fija (opcional)</label><input id="kafka-default-partition" className="field" type="number" min="0" value={kafkaConfig.partition} onChange={(event) => setKafkaConfig({ ...kafkaConfig, partition: event.target.value })} placeholder="Automática" /></div>
        </div>
        <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 12 }}><input type="checkbox" checked={kafkaConfig.consume} onChange={(event) => setKafkaConfig({ ...kafkaConfig, consume: event.target.checked })} /> Consumir mensajes con este grupo</label>
        <p style={{ margin: 0, color: '#68716b', fontSize: 11 }}>Una partición conserva su propio orden y offsets. Sin partición fija, Kafka distribuye los mensajes; también puedes indicar una partición en cada acción de publicación. Los offsets confirmados pertenecen al grupo.</p>
      </div>}
      {providerId && providerId !== 'kafka-messaging' && <div><label className="field-label" htmlFor="module-config">Configuración JSON</label><textarea id="module-config" className="field mono" value={configurationJson} onChange={(event) => { setConfigurationJson(event.target.value); setFormError(''); }} rows={8} spellCheck={false} required /><p style={{ color: '#818a83', fontSize: 11 }}>La configuración se valida antes de iniciar el proveedor.</p></div>}
      {formError && <p role="alert" style={{ margin: 0, color: '#a24842', fontSize: 12 }}>{formError}</p>}
      <div><button className="btn btn-primary" disabled={create.isPending || !name.trim()}>{create.isPending ? 'Guardando…' : 'Añadir módulo'}</button></div>
    </form>}

    {modules.isLoading || catalog.isLoading ? <div className="surface empty-state">Cargando módulos…</div> : modules.isError ? <div className="surface empty-state"><strong>No se pudieron cargar los módulos</strong>Comprueba la conexión con el backend.</div> : modules.data?.length ? <div className="surface">{modules.data.map((module) => {
      const provider = catalog.data?.find((item) => item.id === module.providerId);
      const executable = Boolean(module.providerId && provider);
      return <div key={module.id} className="list-row" style={{ padding: '16px 20px', alignItems: 'flex-start' }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <strong style={{ display: 'block', fontSize: 14, fontWeight: 600 }}>{module.name}</strong>
          <span style={{ display: 'block', marginTop: 4, color: '#818a83', fontSize: 12 }}>{module.description || 'Sin descripción'}{provider ? ` · ${provider.name}` : module.providerId ? ` · Proveedor ${module.providerId} no instalado` : ' · Solo metadatos'}</span>
          <span className="mono" style={{ display: 'block', marginTop: 7, color: '#78827b', fontSize: 11 }}>{provider ? `Proveedor v${module.providerVersion}` : `${module.basePath} · v${module.version || '1.0.0'}`}</span>
          <span className={`status-dot ${module.lifecycleState === 'RUNNING' ? 'ok' : module.lifecycleState === 'ERROR' ? 'error' : ''}`} style={{ marginTop: 9 }}>{lifecycleLabels[module.lifecycleState] || module.lifecycleState}</span>
          {module.providerId === 'http-gateway' && module.lifecycleState === 'RUNNING' && <span className="mono" style={{ display: 'block', marginTop: 7, color: '#78827b', fontSize: 10 }}>Ruta activa: /api/v1/runtime/gateway/{projectId}/{module.id}/&lt;pathPrefix&gt;</span>}
          {module.lifecycleError && <p style={{ color: '#a24842', fontSize: 11 }}>{module.lifecycleError}</p>}
          {module.runtimeError && <p style={{ color: '#a24842', fontSize: 11 }}>{readableRuntimeError(module.runtimeError)}</p>}
        </div>
        <div style={{ display: 'flex', gap: 8, flexShrink: 0, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
          {executable && <button className="btn" disabled={lifecycle.isPending || module.lifecycleState === 'RUNNING'} onClick={() => lifecycle.mutate({ module, action: 'start' })}>Iniciar</button>}
          {executable && <button className="btn" disabled={lifecycle.isPending || !['RUNNING', 'ERROR'].includes(module.lifecycleState)} onClick={() => lifecycle.mutate({ module, action: 'stop' })}>Detener</button>}
          {module.lifecycleState === 'RUNNING' && (module.providerId === 'kafka-messaging' || module.providerId === 'http-gateway') && <button className="btn" onClick={() => { setActionModule(module); setActionName(module.providerId === 'http-gateway' ? 'proxy' : 'publish'); setActionInput(module.providerId === 'http-gateway' ? '{\n  "method": "GET",\n  "path": "/posts/1"\n}' : '{\n  "key": "PED-1042",\n  "value": {\n    "eventId": "7a43f129-7687-4ab6-a9bb-12f302b99a01",\n    "eventType": "order.created",\n    "occurredAt": "2026-11-20T18:30:00-03:00",\n    "orderId": "PED-1042",\n    "customerId": "CLI-208",\n    "total": 24990,\n    "currency": "CLP",\n    "items": [{ "productId": "LIB-01", "quantity": 2, "unitPrice": 12495 }]\n  },\n  "partition": 0\n}'); setActionOutput(''); }}>Probar</button>}
          <Link className="btn" to={`/projects/${projectId}/modules/${module.id}/contracts`}>Contratos</Link>
          <button className="btn btn-danger" disabled={remove.isPending || ['RUNNING', 'STARTING', 'ERROR'].includes(module.lifecycleState)} onClick={() => { if (window.confirm(`¿Eliminar el módulo “${module.name}” y sus contratos?`)) remove.mutate(module.id); }}>Eliminar</button>
        </div>
      </div>;
    })}</div> : <div className="surface empty-state"><strong>Este proyecto aún no tiene módulos</strong>Instala un proveedor disponible o crea un registro de metadatos.<div style={{ marginTop: 14 }}><button className="btn btn-primary" onClick={() => setOpen(true)}>Añadir primer módulo</button></div></div>}

    {actionModule && <div role="dialog" aria-modal="true" aria-labelledby="action-title" className="surface surface-pad" style={{ position: 'fixed', inset: '50% auto auto 50%', zIndex: 20, width: 'min(620px, calc(100vw - 32px))', maxHeight: '85vh', overflow: 'auto', transform: 'translate(-50%, -50%)', boxShadow: '0 16px 48px #0002' }}>
      <div className="section-head"><h2 id="action-title" className="section-title">Probar {actionModule.name}</h2><button className="btn" onClick={() => setActionModule(null)}>Cerrar</button></div>
      {actionModule.providerId === 'kafka-messaging' && <div style={{ marginBottom: 10 }}><label className="field-label" htmlFor="module-action">Acción</label><select className="field" id="module-action" value={actionName} onChange={(event) => setActionName(event.target.value)}><option value="publish">Publicar evento</option><option value="read">Leer mensajes consumidos</option></select></div>}
      <label className="field-label" htmlFor="module-action-input">{actionModule.providerId === 'kafka-messaging' ? 'Datos del mensaje (JSON)' : 'Solicitud (JSON)'}</label><textarea id="module-action-input" className="field mono" rows={9} value={actionInput} onChange={(event) => setActionInput(event.target.value)} spellCheck={false} />
      {actionModule.providerId === 'kafka-messaging' && actionName === 'publish' && <small style={{ display: 'block', marginTop: 6, color: '#818a83' }}>La partición es opcional. Si la indicas, debe existir en el topic; el offset se asigna automáticamente dentro de esa partición.</small>}
      {actionOutput && <pre className="mono" style={{ maxHeight: 220, overflow: 'auto', whiteSpace: 'pre-wrap', fontSize: 11 }}>{actionOutput}</pre>}
      <button className="btn btn-primary" style={{ marginTop: 10 }} disabled={execute.isPending} onClick={submitAction}>{execute.isPending ? 'Ejecutando…' : 'Ejecutar'}</button>
    </div>}
    <KafkaAdministration />
  </Layout>;
}
