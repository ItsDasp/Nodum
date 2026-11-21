import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Layout } from '../../components/Layout';
import { createProject, deleteProject, getProjects } from '../../api/projects';

export function Projects() {
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const projects = useQuery({ queryKey: ['projects'], queryFn: getProjects });
  const create = useMutation({ mutationFn: () => createProject({ name: name.trim(), description: description.trim() }), onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['projects'] }); setShowForm(false); setName(''); setDescription(''); } });
  const remove = useMutation({ mutationFn: deleteProject, onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['projects'] }) });

  return <Layout>
    <div className="page-heading"><div><h1>Proyectos</h1><p>Espacios independientes para diseñar y probar sistemas.</p></div><button className="btn btn-primary" onClick={() => setShowForm((open) => !open)}>{showForm ? 'Cancelar' : '+ Nuevo proyecto'}</button></div>
    {create.isError && <p role="alert" style={{ color: '#a24842', fontSize: 13 }}>No se pudo crear el proyecto. Revisa la conexión e inténtalo de nuevo.</p>}
    {remove.isError && <p role="alert" style={{ color: '#a24842', fontSize: 13 }}>{remove.error.message || 'No se pudo eliminar el proyecto.'}</p>}
    {showForm && <form className="surface surface-pad" style={{ display: 'grid', gap: 14, marginBottom: 18 }} onSubmit={(event) => { event.preventDefault(); if (name.trim()) create.mutate(); }}>
      <h2 className="section-title">Crear proyecto</h2>
      <div><label className="field-label" htmlFor="project-name">Nombre</label><input id="project-name" className="field" value={name} onChange={(event) => setName(event.target.value)} maxLength={120} required autoFocus placeholder="p. ej. Laboratorio universitario" /></div>
      <div><label className="field-label" htmlFor="project-description">Descripción <span style={{ fontWeight: 400 }}>(opcional)</span></label><textarea id="project-description" className="field" value={description} onChange={(event) => setDescription(event.target.value)} rows={2} maxLength={1000} placeholder="¿Qué quieres construir?" /></div>
      <div><button className="btn btn-primary" type="submit" disabled={create.isPending || !name.trim()}>{create.isPending ? 'Creando…' : 'Crear proyecto'}</button></div>
    </form>}

    {projects.isLoading ? <div className="surface empty-state">Cargando proyectos…</div> : projects.isError ? <div className="surface empty-state"><strong>No se pudieron cargar los proyectos</strong>Comprueba que el backend esté disponible.</div> : projects.data?.length ? <div className="surface">
      {projects.data.map((project) => <div className="list-row" key={project.id} style={{ padding: '16px 20px' }}>
        <Link to={`/projects/${project.id}/modules`} style={{ flex: 1, minWidth: 0 }}><strong style={{ display: 'block', fontSize: 14, fontWeight: 600 }}>{project.name}</strong><span style={{ display: 'block', marginTop: 4, color: '#818a83', fontSize: 12 }}>{project.description || 'Sin descripción'}</span><span className="mono" style={{ display: 'block', marginTop: 7, color: '#9aa29c', fontSize: 10 }}>Creado {new Date(project.createdAt).toLocaleDateString('es-CL')}</span></Link>
        <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}><Link to={`/projects/${project.id}/modules`} className="btn">Abrir</Link><button className="btn btn-danger" disabled={remove.isPending} onClick={() => { if (window.confirm(`¿Eliminar “${project.name}” y sus módulos? Esta acción no se puede deshacer.`)) remove.mutate(project.id); }}>Eliminar</button></div>
      </div>)}
    </div> : <div className="surface empty-state"><strong>Tu espacio parte aquí</strong>Crea un proyecto para mantener sus módulos y contratos organizados.<div style={{ marginTop: 14 }}><button className="btn btn-primary" onClick={() => setShowForm(true)}>Crear primer proyecto</button></div></div>}
  </Layout>;
}
