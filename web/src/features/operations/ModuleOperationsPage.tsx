import { useEffect, useMemo, useRef, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import { Layout } from '../../components/Layout';
import { createShellSessionTicket, getModuleLogs, getModules, shellWebSocketUrl, type Module } from '../../api/modules';
import { getProjects } from '../../api/projects';

type Mode = 'logs' | 'shell';
type LogLevel = 'all' | 'error' | 'warn' | 'info' | 'debug';

const logProviders = new Set(['http-gateway', 'kafka-messaging', 'linux-shell']);
const shellProviders = new Set(['linux-shell']);

function logLevel(line: string): Exclude<LogLevel, 'all'> {
  const text = line.toLowerCase();
  const numericLevel = text.match(/"level"\s*:\s*(\d+)/);
  if (numericLevel) {
    const value = Number(numericLevel[1]);
    if (value >= 50) return 'error';
    if (value >= 40) return 'warn';
    if (value <= 20) return 'debug';
    return 'info';
  }
  if (/\b(error|fatal|exception|failed|failure)\b/.test(text)) return 'error';
  if (/\b(warn|warning)\b/.test(text)) return 'warn';
  if (/\b(debug|trace)\b/.test(text)) return 'debug';
  return 'info';
}

function ShellTerminal({ projectId, module }: { projectId: string; module: Module }) {
  const mountRef = useRef<HTMLDivElement>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const [status, setStatus] = useState<'connecting' | 'connected' | 'disconnected' | 'error'>('connecting');
  const [error, setError] = useState('');

  useEffect(() => {
    if (!mountRef.current || module.lifecycleState !== 'RUNNING') { setStatus('disconnected'); return; }
    let disposed = false;
    let resizeObserver: ResizeObserver | undefined;
    const fit = new FitAddon();
    const terminal = new Terminal({
      cursorBlink: true, scrollback: 5000, convertEol: true,
      fontFamily: '"Cascadia Code", "SFMono-Regular", Consolas, monospace', fontSize: 13,
      theme: { background: '#0d0a16', foreground: '#e9e4f8', cursor: '#a78bfa', selectionBackground: '#3b2a63', black: '#0f0b18', green: '#69d79c', brightGreen: '#92efb7', red: '#ff7777', yellow: '#ecc777', blue: '#83adff', magenta: '#cf9cff', cyan: '#74d9d1', white: '#e9e4f8' },
    });
    terminal.loadAddon(fit); terminal.open(mountRef.current); terminalRef.current = terminal;
    terminal.onData((data) => { if (socketRef.current?.readyState === WebSocket.OPEN) socketRef.current.send(data); });
    terminal.writeln('\x1b[35mNodum Linux · /workspace\x1b[0m');
    terminal.writeln('\x1b[90mAbriendo una sesión interactiva segura…\x1b[0m\r\n');
    const connect = async () => {
      try {
        const ticket = await createShellSessionTicket(projectId, module.id);
        if (disposed) return;
        const socket = new WebSocket(shellWebSocketUrl(ticket)); socket.binaryType = 'arraybuffer'; socketRef.current = socket;
        socket.onopen = () => { if (disposed) return; setStatus('connected'); setError(''); terminal.write('\r\n\x1b[32m● Terminal conectado\x1b[0m\r\n'); fit.fit(); terminal.focus(); };
        socket.onmessage = (event: MessageEvent<ArrayBuffer | string>) => terminal.write(typeof event.data === 'string' ? event.data : new Uint8Array(event.data));
        socket.onerror = () => { setStatus('error'); setError('No se pudo conectar con el contenedor Shell Linux.'); };
        socket.onclose = () => { if (!disposed) { setStatus('disconnected'); terminal.write('\r\n\x1b[90mSesión cerrada. Usa Reconectar para abrir otra.\x1b[0m\r\n'); } };
        terminal.attachCustomKeyEventHandler((event) => { if (event.ctrlKey && event.key.toLowerCase() === 'l' && event.type === 'keydown') { terminal.clear(); return false; } return true; });
      } catch (failure) { setStatus('error'); setError(failure instanceof Error ? failure.message : 'No se pudo abrir la sesión.'); }
    };
    resizeObserver = new ResizeObserver(() => { try { fit.fit(); } catch { /* Terminal still mounting. */ } });
    resizeObserver.observe(mountRef.current);
    void connect();
    return () => { disposed = true; resizeObserver?.disconnect(); socketRef.current?.close(); terminal.dispose(); terminalRef.current = null; };
  }, [projectId, module.id, module.lifecycleState]);

  const reconnect = async () => {
    setStatus('connecting');
    socketRef.current?.close();
    try {
      const ticket = await createShellSessionTicket(projectId, module.id);
      const socket = new WebSocket(shellWebSocketUrl(ticket)); socket.binaryType = 'arraybuffer'; socketRef.current = socket;
      socket.onopen = () => { setStatus('connected'); setError(''); terminalRef.current?.write('\r\n\x1b[32m● Terminal conectado\x1b[0m\r\n'); terminalRef.current?.focus(); };
      socket.onmessage = (event: MessageEvent<ArrayBuffer | string>) => terminalRef.current?.write(typeof event.data === 'string' ? event.data : new Uint8Array(event.data));
      socket.onerror = () => { setStatus('error'); setError('No se pudo conectar con el contenedor Shell Linux.'); };
      socket.onclose = () => setStatus('disconnected');
      terminalRef.current?.focus();
    } catch (failure) { setStatus('error'); setError(failure instanceof Error ? failure.message : 'No se pudo abrir la sesión.'); }
  };

  const statusLabel = { connecting: 'Conectando', connected: 'Conectada', disconnected: 'Desconectada', error: 'Error de conexión' }[status];
  return <section className="terminal-card" aria-label="Terminal Linux interactivo">
    <header className="terminal-toolbar">
      <div className="terminal-title"><span className={`terminal-status-dot is-${status}`} /><div><strong>{module.name}</strong><small>{module.lifecycleState === 'RUNNING' ? 'Alpine Linux · usuario nodum · /workspace' : 'Módulo detenido'}</small></div></div>
      <div className="terminal-actions"><span className={`terminal-status is-${status}`}>{statusLabel}</span><button className="btn terminal-tool-btn" onClick={() => terminalRef.current?.clear()} disabled={status !== 'connected'}>Limpiar</button><button className="btn terminal-tool-btn" onClick={() => void reconnect()} disabled={module.lifecycleState !== 'RUNNING' || status === 'connecting'}>{status === 'connected' ? 'Reconectar' : 'Conectar'}</button></div>
    </header>
    <div ref={mountRef} className="terminal-screen" onClick={() => terminalRef.current?.focus()} />
    <footer className="terminal-footer"><span>Enter ejecuta · Ctrl+C interrumpe · Ctrl+L limpia · ↑/↓ historial</span><span>Red aislada del proyecto</span></footer>
    {error && <div className="terminal-error" role="alert">{error}</div>}
    {module.lifecycleState !== 'RUNNING' && <div className="terminal-overlay"><strong>Shell Linux detenida</strong><span>Inicia el módulo desde su proyecto para abrir la terminal.</span></div>}
  </section>;
}

export function ModuleOperationsPage({ mode }: { mode: Mode }) {
  const [projectId, setProjectId] = useState('');
  const [moduleId, setModuleId] = useState('');
  const [tail, setTail] = useState(500);
  const [search, setSearch] = useState('');
  const [level, setLevel] = useState<LogLevel>('all');
  const [since, setSince] = useState<'all' | 'hour' | 'day'>('all');
  const projects = useQuery({ queryKey: ['projects'], queryFn: getProjects });
  const projectModuleQueries = useQueries({ queries: (projects.data ?? []).map((project) => ({
    queryKey: ['modules', project.id], queryFn: () => getModules(project.id),
  })) });
  const allowedProviders = mode === 'shell' ? shellProviders : logProviders;
  const allModules = useMemo(() => (projects.data ?? []).flatMap((project, index) =>
    (projectModuleQueries[index]?.data ?? []).filter((module) => allowedProviders.has(module.providerId ?? '')).map((module) => ({ project, module }))), [projects.data, projectModuleQueries, mode]);
  const requestedProjectExists = projects.data?.some((project) => project.id === projectId) ?? false;
  const chosenProject = requestedProjectExists ? projectId : allModules[0]?.project.id || projects.data?.[0]?.id || '';
  const availableModules = allModules.filter((option) => option.project.id === chosenProject);
  const selected = availableModules.find((option) => option.module.id === moduleId) ?? availableModules[0];
  const logs = useQuery({
    queryKey: ['module-logs', chosenProject, selected?.module.id, tail],
    queryFn: () => getModuleLogs(chosenProject, selected!.module.id, tail),
    enabled: mode === 'logs' && !!selected,
    refetchInterval: mode === 'logs' && !!selected ? 5000 : false,
  });

  const lines = (logs.data?.output ?? '').split(/\r?\n/).filter(Boolean);
  const filteredLines = lines.filter((line) => {
    if (level !== 'all' && logLevel(line) !== level) return false;
    if (search && !line.toLowerCase().includes(search.toLowerCase())) return false;
    if (since !== 'all') {
      const stampMatch = line.match(/\b(\d{10,13})\b/) ?? line.match(/\b(20\d\d-\d\d-\d\d[T ][^\s,]+)\b/);
      if (stampMatch) {
        const timestamp = /^\d+$/.test(stampMatch[1]) ? Number(stampMatch[1].length === 10 ? `${stampMatch[1]}000` : stampMatch[1]) : Date.parse(stampMatch[1]);
        const threshold = Date.now() - (since === 'hour' ? 3_600_000 : 86_400_000);
        if (Number.isFinite(timestamp) && timestamp < threshold) return false;
      }
    }
    return true;
  });

  const title = mode === 'logs' ? 'Logs de módulos' : 'Shell remota';
  const selectModule = (id: string) => setModuleId(id);
  return <Layout>
    <div className="page-heading"><div><h1>{title}</h1><p>{mode === 'logs' ? 'Consulta y filtra registros de los módulos de todos tus proyectos.' : 'Abre una consola en el contenedor de un módulo administrado.'}</p></div></div>
    {projects.isLoading || projectModuleQueries.some((query) => query.isLoading) ? <div className="surface empty-state">Cargando proyectos y módulos…</div>
      : projects.isError || projectModuleQueries.some((query) => query.isError) ? <div className="surface empty-state"><strong>No se pudieron cargar los módulos</strong>Comprueba la conexión con el backend.</div>
      : !allModules.length ? <div className="surface empty-state"><strong>{mode === 'shell' ? 'No hay una shell Linux' : 'No hay módulos administrados'}</strong>{mode === 'shell' ? 'Crea un módulo Shell Linux desde un proyecto y luego inícialo para abrir su terminal.' : 'Crea un módulo ejecutable desde uno de tus proyectos para ver aquí sus registros.'}</div>
      : <>
        <div className="surface surface-pad" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12, marginBottom: 16 }}>
          <div><label className="field-label" htmlFor="ops-project">Proyecto</label><select id="ops-project" className="field" value={chosenProject} onChange={(event) => { setProjectId(event.target.value); setModuleId(''); }}>
            {projects.data?.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
          </select></div>
          <div><label className="field-label" htmlFor="ops-module">Módulo</label><select id="ops-module" className="field" value={selected?.module.id ?? ''} onChange={(event) => selectModule(event.target.value)}>
            {availableModules.map(({ module }) => <option key={module.id} value={module.id}>{module.name} · {module.providerId}</option>)}
          </select></div>
          {mode === 'logs' && <>
            <div><label className="field-label" htmlFor="ops-level">Nivel</label><select id="ops-level" className="field" value={level} onChange={(event) => setLevel(event.target.value as LogLevel)}><option value="all">Todos</option><option value="error">Errores</option><option value="warn">Advertencias</option><option value="info">Información</option><option value="debug">Depuración</option></select></div>
            <div><label className="field-label" htmlFor="ops-since">Fecha</label><select id="ops-since" className="field" value={since} onChange={(event) => setSince(event.target.value as typeof since)}><option value="all">Cualquier fecha</option><option value="hour">Última hora</option><option value="day">Últimas 24 horas</option></select></div>
            <div><label className="field-label" htmlFor="ops-tail">Líneas consultadas</label><select id="ops-tail" className="field" value={tail} onChange={(event) => setTail(Number(event.target.value))}><option value={100}>100</option><option value={200}>200</option><option value={500}>500</option></select></div>
            <div><label className="field-label" htmlFor="ops-search">Buscar en registros</label><input id="ops-search" className="field" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Texto, error, identificador…" /></div>
          </>}
        </div>
        {mode === 'logs' ? <>
          <div className="section-head"><span style={{ color: 'var(--muted)', fontSize: 12 }}>{filteredLines.length} de {lines.length} líneas · se actualiza cada 5 s</span><button className="btn" disabled={!selected || logs.isFetching} onClick={() => void logs.refetch()}>{logs.isFetching ? 'Actualizando…' : 'Actualizar'}</button></div>
          {logs.isError ? <div role="alert" className="surface empty-state">{logs.error.message || 'No se pudieron obtener los registros.'}</div>
            : <pre className="mono" style={{ minHeight: 320, maxHeight: '62vh', overflow: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', padding: 16, borderRadius: 8, background: 'var(--panel)', color: '#ddd6f5', fontSize: 12 }}>{logs.isLoading ? 'Cargando registros…' : filteredLines.join('\n') || 'No hay líneas que coincidan con estos filtros.'}</pre>}
          {logs.data?.truncated && <small>La salida consultada alcanzó el límite de 32 KiB del backend.</small>}
          <small style={{ display: 'block', marginTop: 8, color: 'var(--muted-2)' }}>Los filtros de nivel, fecha y texto se aplican en el navegador a las líneas recibidas.</small>
        </> : selected ? <ShellTerminal key={selected.module.id} projectId={selected.project.id} module={selected.module} /> : <div className="surface empty-state">Este proyecto no tiene módulos compatibles.</div>}
      </>}
  </Layout>;
}
