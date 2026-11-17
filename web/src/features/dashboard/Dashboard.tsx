import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Layout } from '../../components/Layout';
import { getHealth } from '../../api/health';
import { getProjects } from '../../api/projects';

export function Dashboard() {
  const health = useQuery({ queryKey: ['health'], queryFn: getHealth, refetchInterval: 30000 });
  const projects = useQuery({ queryKey: ['projects'], queryFn: getProjects });
  const online = health.data?.status === 'UP';

  return (
    <Layout>
      <div className="page-heading">
        <div><h1>Inicio</h1><p>Estado de tu espacio de trabajo.</p></div>
        <Link to="/projects" className="btn btn-primary">Ver proyectos</Link>
      </div>

      <section className="metric-grid" aria-label="Resumen">
        <div className="surface metric"><div className="metric-label">Proyectos</div><div className="metric-value">{projects.isLoading ? '—' : projects.data?.length ?? '—'}</div></div>
        <div className="surface metric"><div className="metric-label">APIs publicadas</div><div className="metric-value">—</div><div className="metric-label">Disponible en una fase posterior</div></div>
        <div className="surface metric"><div className="metric-label">Control Plane</div><div className="metric-value" style={{ fontSize: 16, marginTop: 13 }}><span className={`status-dot ${health.isLoading ? '' : online ? 'ok' : 'error'}`}>{health.isLoading ? 'Comprobando' : online ? 'Operativo' : 'Sin conexión'}</span></div></div>
      </section>

      <section className="surface surface-pad">
        <div className="section-head"><h2 className="section-title">Proyectos recientes</h2><Link to="/projects" className="btn">Todos los proyectos</Link></div>
        {projects.isLoading ? <div className="empty-state">Cargando proyectos…</div> : projects.isError ? <div className="empty-state"><strong>No se pudieron cargar los proyectos</strong>Comprueba la conexión con el backend.</div> : projects.data?.length ? projects.data.slice(0, 5).map((project) => (
          <Link to={`/projects/${project.id}/modules`} key={project.id} className="list-row">
            <div><strong style={{ display: 'block', fontSize: 13, fontWeight: 600 }}>{project.name}</strong><span style={{ color: '#818a83', fontSize: 12 }}>{project.description || 'Sin descripción'}</span></div>
            <span aria-hidden="true" style={{ color: '#879089' }}>→</span>
          </Link>
        )) : <div className="empty-state"><strong>Aún no hay proyectos</strong>Crea un proyecto para empezar a organizar módulos y contratos.<div style={{ marginTop: 14 }}><Link to="/projects" className="btn btn-primary">Crear proyecto</Link></div></div>}
      </section>
    </Layout>
  );
}
