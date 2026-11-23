import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createKafkaTopic, deleteKafkaGroup, deleteKafkaTopic, getKafkaCluster, getKafkaGroup, increaseKafkaPartitions,
  getKafkaGroups, getKafkaPartitions, getKafkaTopics, resetKafkaGroupOffsets,
} from '../../api/kafka';

export function KafkaAdministration() {
  const client = useQueryClient();
  const [topicName, setTopicName] = useState('');
  const [partitions, setPartitions] = useState('1');
  const [replicationFactor, setReplicationFactor] = useState('1');
  const [expandedPartitionCount, setExpandedPartitionCount] = useState('');
  const [selectedTopic, setSelectedTopic] = useState('');
  const [selectedGroup, setSelectedGroup] = useState('');
  const cluster = useQuery({ queryKey: ['kafka-cluster'], queryFn: getKafkaCluster, refetchInterval: 15000 });
  const topics = useQuery({ queryKey: ['kafka-topics'], queryFn: getKafkaTopics, refetchInterval: 15000 });
  const groups = useQuery({ queryKey: ['kafka-groups'], queryFn: getKafkaGroups, refetchInterval: 15000 });
  const partitionDetails = useQuery({ queryKey: ['kafka-partitions', selectedTopic], queryFn: () => getKafkaPartitions(selectedTopic), enabled: !!selectedTopic });
  const groupDetails = useQuery({ queryKey: ['kafka-group', selectedGroup], queryFn: () => getKafkaGroup(selectedGroup), enabled: !!selectedGroup, refetchInterval: 10000 });
  const refresh = () => {
    void client.invalidateQueries({ queryKey: ['kafka-topics'] });
    void client.invalidateQueries({ queryKey: ['kafka-groups'] });
    void client.invalidateQueries({ queryKey: ['kafka-cluster'] });
  };
  const create = useMutation({ mutationFn: () => createKafkaTopic({
    name: topicName.trim(), partitions: Number(partitions), replicationFactor: Number(replicationFactor),
  }), onSuccess: () => { setTopicName(''); refresh(); } });
  const removeTopic = useMutation({ mutationFn: deleteKafkaTopic, onSuccess: () => { setSelectedTopic(''); refresh(); } });
  const expandTopic = useMutation({ mutationFn: ({ topic, partitions }: { topic: string; partitions: number }) => increaseKafkaPartitions(topic, partitions), onSuccess: () => refresh() });
  const resetOffsets = useMutation({ mutationFn: ({ groupId, target }: { groupId: string; target: 'earliest' | 'latest' }) => resetKafkaGroupOffsets(groupId, target), onSuccess: () => { void client.invalidateQueries({ queryKey: ['kafka-group', selectedGroup] }); refresh(); } });
  const removeGroup = useMutation({ mutationFn: deleteKafkaGroup, onSuccess: () => { setSelectedGroup(''); refresh(); } });

  const brokerCount = cluster.data?.brokers.length ?? 1;
  const groupStateLabels: Record<string, string> = {
    EMPTY: 'Sin consumidores activos', STABLE: 'Estable', DEAD: 'Inactivo', UNKNOWN: 'Desconocido',
    PREPARING_REBALANCE: 'Preparando rebalanceo', COMPLETING_REBALANCE: 'Completando rebalanceo',
  };
  return <section className="surface surface-pad" style={{ marginTop: 20 }}>
    <div className="section-head"><div><h2 className="section-title">Administración de Kafka</h2><p style={{ margin: '5px 0 0', color: '#818a83', fontSize: 12 }}>Gestiona temas y consulta el estado de sus consumidores.</p></div><button className="btn" onClick={refresh}>Actualizar</button></div>
    {cluster.isLoading ? <p className="empty-state">Conectando con el clúster…</p> : cluster.isError ? <p role="alert" style={{ color: '#a24842', fontSize: 13 }}>No se pudo consultar Kafka. Comprueba que el broker esté iniciado y accesible.</p> : <div style={{ marginBottom: 18, padding: 12, borderRadius: 8, background: '#131714', fontSize: 12 }}>
      <strong>{brokerCount} {brokerCount === 1 ? 'broker disponible' : 'brokers disponibles'}</strong><span className="mono" style={{ marginLeft: 10, color: '#68716b' }}>{cluster.data?.bootstrapServers}</span>
      <p style={{ margin: '6px 0 0', color: '#68716b' }}>Un tema (topic) contiene mensajes distribuidos en particiones. Cada partición mantiene su propio desplazamiento (offset). Las réplicas son copias de una partición en distintos brokers. Con un solo broker, el factor máximo es 1. Kafka reasigna automáticamente las particiones cuando consumidores se unen o salen del grupo.</p>
    </div>}

    <div className="kafka-admin-grid">
      <div>
        <h3 style={{ fontSize: 14, margin: '0 0 10px' }}>Temas (topics)</h3>
        <form className="kafka-topic-form" onSubmit={(event) => { event.preventDefault(); create.mutate(); }}>
          <div><label className="field-label" htmlFor="kafka-topic-name">Nombre del tema</label><input id="kafka-topic-name" className="field" value={topicName} onChange={(event) => setTopicName(event.target.value)} placeholder="pedidos" pattern="[A-Za-z0-9._-]{1,249}" required /></div>
          <div><label className="field-label" htmlFor="kafka-partitions">Particiones</label><input id="kafka-partitions" className="field" type="number" min="1" max="1000" value={partitions} onChange={(event) => setPartitions(event.target.value)} required title="Los productores pueden distribuir mensajes entre estas particiones." /></div>
          <div><label className="field-label" htmlFor="kafka-replicas">Réplicas</label><input id="kafka-replicas" className="field" type="number" min="1" max={brokerCount} value={replicationFactor} onChange={(event) => setReplicationFactor(event.target.value)} required title="Cada réplica debe residir en un broker distinto." /></div>
          <button className="btn btn-primary" disabled={create.isPending || !topicName.trim() || !cluster.data || Number(replicationFactor) > brokerCount}>{create.isPending ? 'Creando…' : 'Crear'}</button>
        </form>
        <small style={{ color: '#818a83' }}>El desplazamiento (offset) avanza por separado dentro de cada partición. Un tema existente no cambia al crear un módulo que lo use.</small>
        {create.isError && <p role="alert" style={{ color: '#a24842', fontSize: 12 }}>{create.error.message}</p>}
        {removeTopic.isError && <p role="alert" style={{ color: '#a24842', fontSize: 12 }}>{removeTopic.error.message}</p>}
        {topics.isLoading ? <p className="empty-state">Cargando topics…</p> : topics.isError ? <p style={{ color: '#a24842', fontSize: 12 }}>No se pudieron cargar los topics.</p> : <div style={{ display: 'grid', gap: 5 }}>
          {topics.data?.length ? topics.data.map((topic) => <div key={topic.name} className="list-row" style={{ gap: 8, padding: '9px 0' }}>
            <button className="btn" style={{ flex: 1, textAlign: 'left' }} onClick={() => setSelectedTopic(selectedTopic === topic.name ? '' : topic.name)}><strong>{topic.name}</strong><span style={{ marginLeft: 8, color: '#818a83', fontSize: 11 }}>{topic.partitions} particiones · {topic.replicationFactor} réplica(s)</span></button>
            <button className="btn btn-danger" onClick={() => { if (window.confirm(`¿Eliminar el topic “${topic.name}” y todos sus mensajes?`)) removeTopic.mutate(topic.name); }}>Eliminar</button>
          </div>) : <p className="empty-state">Todavía no hay topics.</p>}
        </div>}
        {selectedTopic && <div style={{ marginTop: 10, overflowX: 'auto' }}><strong style={{ fontSize: 12 }}>Particiones de {selectedTopic}</strong>{partitionDetails.isLoading ? <p>Cargando…</p> : partitionDetails.isError ? <p role="alert">No se pudieron cargar las particiones.</p> : <table style={{ width: '100%', fontSize: 11, textAlign: 'left', marginTop: 6 }}><thead><tr><th>Partición</th><th>Líder</th><th>Réplicas / sincronizadas</th><th>Desplazamientos (offsets)</th></tr></thead><tbody>{partitionDetails.data?.map((item) => <tr key={item.partition}><td>{item.partition}</td><td>{item.leader ?? '—'}</td><td>{item.replicas.join(', ')} / {item.inSyncReplicas.join(', ')}</td><td>{item.earliestOffset}–{item.latestOffset}</td></tr>)}</tbody></table>}
          <form onSubmit={(event) => { event.preventDefault(); expandTopic.mutate({ topic: selectedTopic, partitions: Number(expandedPartitionCount) }); }} style={{ display: 'flex', gap: 8, alignItems: 'end', marginTop: 10 }}><div><label className="field-label" htmlFor="kafka-expand-partitions">Aumentar a esta cantidad total</label><input id="kafka-expand-partitions" className="field" type="number" min={(topics.data?.find((topic) => topic.name === selectedTopic)?.partitions ?? 1) + 1} max="1000" value={expandedPartitionCount} onChange={(event) => setExpandedPartitionCount(event.target.value)} required /></div><button className="btn" disabled={expandTopic.isPending}>{expandTopic.isPending ? 'Actualizando…' : 'Aumentar particiones'}</button></form>
          <small style={{ color: '#818a83' }}>Al agregar particiones, los mensajes existentes no se redistribuyen y el orden solo se garantiza dentro de cada partición.</small>{expandTopic.isError && <p role="alert" style={{ color: '#a24842', fontSize: 12 }}>{expandTopic.error.message}</p>}
        </div>}
      </div>

      <div>
        <h3 style={{ fontSize: 14, margin: '0 0 10px' }}>Grupos de consumidores</h3>
        {groups.isLoading ? <p className="empty-state">Cargando grupos…</p> : groups.isError ? <p style={{ color: '#a24842', fontSize: 12 }}>No se pudieron cargar los grupos.</p> : groups.data?.length ? <div style={{ display: 'grid', gap: 5 }}>
          {groups.data.map((group) => <button key={group.groupId} className="btn" style={{ textAlign: 'left' }} onClick={() => setSelectedGroup(group.groupId)}><strong>{group.groupId}</strong><span style={{ marginLeft: 8, color: '#818a83', fontSize: 11 }}>{groupStateLabels[group.state] || group.state} · {group.memberCount} miembro(s)</span></button>)}
        </div> : <p className="empty-state">Aún no hay grupos con offsets.</p>}
        {groupDetails.data && <div style={{ marginTop: 14, padding: 12, background: '#131714', borderRadius: 8 }}>
          <div className="section-head"><div><strong>{groupDetails.data.groupId}</strong><div style={{ color: '#68716b', fontSize: 11, marginTop: 3 }}>Estado: {groupStateLabels[groupDetails.data.state] || groupDetails.data.state} · {groupDetails.data.members.length} miembro(s) · retraso total (lag): {groupDetails.data.totalLag}</div></div><button className="btn" onClick={() => setSelectedGroup('')}>Cerrar</button></div>
          <p style={{ fontSize: 11, color: '#68716b' }}>El retraso (lag) es la diferencia entre el último desplazamiento del tema y el último desplazamiento confirmado por el grupo.</p>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 9 }}>
            <button className="btn" disabled={resetOffsets.isPending || groupDetails.data.state !== 'EMPTY'} onClick={() => { if (window.confirm('¿Restablecer los offsets del grupo al inicio de cada partición?')) resetOffsets.mutate({ groupId: groupDetails.data!.groupId, target: 'earliest' }); }}>Volver al inicio</button>
            <button className="btn" disabled={resetOffsets.isPending || groupDetails.data.state !== 'EMPTY'} onClick={() => { if (window.confirm('¿Mover los offsets del grupo al final de cada partición? Los mensajes pendientes se omitirán.')) resetOffsets.mutate({ groupId: groupDetails.data!.groupId, target: 'latest' }); }}>Ir al final</button>
            <button className="btn btn-danger" disabled={removeGroup.isPending || groupDetails.data.state !== 'EMPTY'} onClick={() => { if (window.confirm(`¿Eliminar el grupo ${groupDetails.data!.groupId} y sus offsets confirmados?`)) removeGroup.mutate(groupDetails.data!.groupId); }}>Eliminar grupo</button>
          </div>
          {(resetOffsets.isError || removeGroup.isError) && <p role="alert" style={{ color: '#a24842', fontSize: 12 }}>{resetOffsets.error?.message || removeGroup.error?.message}</p>}
          {groupDetails.data.members.map((member) => <div key={member.memberId} style={{ fontSize: 11, marginBottom: 5 }}><strong>{member.clientId}</strong> · {member.host} · {member.assignments.join(', ') || 'sin particiones asignadas'}</div>)}
          <div style={{ overflowX: 'auto' }}><table style={{ width: '100%', fontSize: 11, textAlign: 'left', marginTop: 8 }}><thead><tr><th>Tema</th><th>Partición</th><th>Desplazamiento confirmado</th><th>Desplazamiento final</th><th>Retraso</th></tr></thead><tbody>{groupDetails.data.offsets.map((offset) => <tr key={`${offset.topic}-${offset.partition}`}><td>{offset.topic}</td><td>{offset.partition}</td><td>{offset.committedOffset}</td><td>{offset.latestOffset}</td><td>{offset.lag}</td></tr>)}</tbody></table></div>
          {groupDetails.data.state !== 'EMPTY' && <small style={{ display: 'block', marginTop: 8, color: '#818a83' }}>Detén los módulos consumidores de este grupo para restablecer offsets o eliminarlo.</small>}
        </div>}
        {groupDetails.isError && <p role="alert" style={{ color: '#a24842', fontSize: 12 }}>{groupDetails.error.message}</p>}
      </div>
    </div>
  </section>;
}
