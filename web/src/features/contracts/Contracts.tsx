import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Layout } from '../../components/Layout';
import { getContracts, createContract, deleteContract, publishContract } from '../../api/contracts';
import { Link } from 'react-router-dom';

export function Contracts() {
  const { projectId, moduleId } = useParams<{ projectId: string; moduleId: string }>();
  const queryClient = useQueryClient();
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newContractName, setNewContractName] = useState('');
  const [newContractDescription, setNewContractDescription] = useState('');
  const [newContractVersion, setNewContractVersion] = useState('1.0.0');
  const [newContractSchema, setNewContractSchema] = useState('');
  const [newContractExamples, setNewContractExamples] = useState('');

  const { data: contracts, isLoading } = useQuery({
    queryKey: ['contracts', projectId, moduleId],
    queryFn: () => getContracts(projectId!, moduleId!),
    enabled: !!projectId && !!moduleId,
  });

  const createMutation = useMutation({
    mutationFn: () => createContract(projectId!, moduleId!, {
      name: newContractName,
      description: newContractDescription,
      version: newContractVersion,
      schema: newContractSchema,
      examples: newContractExamples,
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['contracts', projectId, moduleId] });
      setShowCreateForm(false);
      setNewContractName('');
      setNewContractDescription('');
      setNewContractVersion('1.0.0');
      setNewContractSchema('');
      setNewContractExamples('');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (contractId: string) => deleteContract(projectId!, moduleId!, contractId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['contracts', projectId, moduleId] });
    },
  });

  const publishMutation = useMutation({
    mutationFn: (contractId: string) => publishContract(projectId!, moduleId!, contractId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['contracts', projectId, moduleId] });
    },
  });

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (newContractName.trim()) {
      createMutation.mutate();
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'PUBLISHED':
        return 'text-green-600 dark:text-green-400';
      case 'DRAFT':
        return 'text-yellow-600 dark:text-yellow-400';
      case 'DEPRECATED':
        return 'text-orange-600 dark:text-orange-400';
      case 'ARCHIVED':
        return 'text-gray-600 dark:text-gray-400';
      default:
        return 'text-gray-600 dark:text-gray-400';
    }
  };
  const statusLabel: Record<string, string> = { DRAFT: 'Borrador', PUBLISHED: 'Publicado', DEPRECATED: 'En desuso', ARCHIVED: 'Archivado' };

  return (
    <Layout>
      <div className="mb-4">
        <Link to={`/projects/${projectId}/modules`} className="text-sm text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white">
          ← Volver a módulos
        </Link>
      </div>

      <div className="flex justify-between items-center mb-6">
        <h2 className="text-2xl font-semibold text-gray-900 dark:text-white">Contratos</h2>
        <button
          onClick={() => setShowCreateForm(!showCreateForm)}
          className="bg-gray-900 dark:bg-white text-white dark:text-gray-900 px-4 py-2 rounded hover:bg-gray-800 dark:hover:bg-gray-100"
        >
          {showCreateForm ? 'Cancelar' : 'Nuevo contrato'}
        </button>
      </div>

      <div className="mb-5 rounded border border-gray-200 p-4 text-sm text-gray-600 dark:border-gray-800 dark:text-gray-400">
        <strong className="block mb-1 text-gray-900 dark:text-white">¿Qué es un contrato?</strong>
        Es una descripción versionada del formato de datos que intercambia un módulo: incluye un esquema JSON y ejemplos para que otros equipos sepan qué enviar y qué esperar.
        <p className="mt-2 mb-0">En Nodum, el contrato se guarda como documentación. Publicarlo cambia su estado, pero no despliega una API ni valida automáticamente los mensajes del módulo.</p>
      </div>

      {showCreateForm && (
        <div className="mb-6 p-6 border border-gray-200 dark:border-gray-800 rounded">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">Crear contrato</h3>
          <form onSubmit={handleCreate} className="space-y-4">
            <div>
              <label htmlFor="name" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Nombre
              </label>
              <input
                id="name"
                type="text"
                value={newContractName}
                onChange={(e) => setNewContractName(e.target.value)}
                className="w-full px-3 py-2 bg-gray-50 dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded text-gray-900 dark:text-white focus:outline-none focus:border-gray-400 dark:focus:border-gray-600"
                placeholder="p. ej., Evento de pedido"
                required
              />
            </div>
            <div>
              <label htmlFor="description" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Descripción
              </label>
              <textarea
                id="description"
                value={newContractDescription}
                onChange={(e) => setNewContractDescription(e.target.value)}
                className="w-full px-3 py-2 bg-gray-50 dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded text-gray-900 dark:text-white focus:outline-none focus:border-gray-400 dark:focus:border-gray-600"
                placeholder="Qué datos describe y cuándo se usan"
                rows={3}
              />
            </div>
            <div>
              <label htmlFor="version" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Versión
              </label>
              <input
                id="version"
                type="text"
                value={newContractVersion}
                onChange={(e) => setNewContractVersion(e.target.value)}
                className="w-full px-3 py-2 bg-gray-50 dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded text-gray-900 dark:text-white focus:outline-none focus:border-gray-400 dark:focus:border-gray-600"
                placeholder="1.0.0"
              />
            </div>
            <div>
              <label htmlFor="schema" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Esquema o definición del contrato (JSON)
              </label>
              <textarea
                id="schema"
                value={newContractSchema}
                onChange={(e) => setNewContractSchema(e.target.value)}
                className="w-full px-3 py-2 bg-gray-50 dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded text-gray-900 dark:text-white focus:outline-none focus:border-gray-400 dark:focus:border-gray-600 font-mono text-sm"
                placeholder='JSON Schema para mensajes o documento OpenAPI para una API'
                rows={6}
              />
            </div>
            <div>
              <label htmlFor="examples" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Ejemplos de solicitudes, respuestas o mensajes (JSON)
              </label>
              <textarea
                id="examples"
                value={newContractExamples}
                onChange={(e) => setNewContractExamples(e.target.value)}
                className="w-full px-3 py-2 bg-gray-50 dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded text-gray-900 dark:text-white focus:outline-none focus:border-gray-400 dark:focus:border-gray-600 font-mono text-sm"
                placeholder='{"solicitud": {}, "respuesta": {}}'
                rows={4}
              />
            </div>
            <button
              type="submit"
              disabled={createMutation.isPending}
              className="bg-gray-900 dark:bg-white text-white dark:text-gray-900 px-4 py-2 rounded hover:bg-gray-800 dark:hover:bg-gray-100 disabled:opacity-50"
            >
              {createMutation.isPending ? 'Creando…' : 'Crear contrato'}
            </button>
          </form>
        </div>
      )}

      {createMutation.isError && <p role="alert" className="mb-4 text-sm text-red-600">No se pudo crear el contrato. Comprueba que el nombre no esté repetido y que el servicio esté disponible.</p>}
      {deleteMutation.isError && <p role="alert" className="mb-4 text-sm text-red-600">No se pudo eliminar el contrato.</p>}
      {publishMutation.isError && <p role="alert" className="mb-4 text-sm text-red-600">No se pudo publicar el contrato.</p>}
      {isLoading ? (
        <p className="text-gray-500 dark:text-gray-400">Cargando contratos…</p>
      ) : contracts && contracts.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {contracts.map((contract) => (
            <div key={contract.id} className="p-6 border border-gray-200 dark:border-gray-800 rounded hover:border-gray-300 dark:hover:border-gray-700 transition-colors">
              <div className="flex justify-between items-start mb-2">
                <h3 className="text-lg font-semibold text-gray-900 dark:text-white">{contract.name}</h3>
                <div className="flex gap-2">
                  {contract.status === 'DRAFT' && (
                    <button
                      onClick={() => { if (window.confirm(`¿Marcar “${contract.name}” como publicado? Este cambio solo actualiza el estado del contrato.`)) publishMutation.mutate(contract.id); }}
                      disabled={publishMutation.isPending}
                      className="text-sm text-green-600 dark:text-green-400 hover:text-green-700 dark:hover:text-green-300 disabled:opacity-50"
                    >
                      {publishMutation.isPending ? 'Guardando…' : 'Marcar publicado'}
                    </button>
                  )}
                  <button
                    onClick={() => { if (window.confirm(`¿Eliminar el contrato “${contract.name}”?`)) deleteMutation.mutate(contract.id); }}
                    disabled={deleteMutation.isPending}
                    className="text-sm text-red-600 dark:text-red-400 hover:text-red-700 dark:hover:text-red-300 disabled:opacity-50"
                  >
                    {deleteMutation.isPending ? 'Eliminando…' : 'Eliminar'}
                  </button>
                </div>
              </div>
              <p className="text-sm text-gray-500 dark:text-gray-400 mb-2">{contract.description || 'Sin descripción'}</p>
              <div className="space-y-1 text-xs text-gray-400 dark:text-gray-500">
                <div>Versión: {contract.version}</div>
                <div className={getStatusColor(contract.status)}>Estado: {statusLabel[contract.status] || contract.status}</div>
                <div>Creado: {new Date(contract.createdAt).toLocaleDateString('es-CL')}</div>
              </div>
              {(contract.schema || contract.examples) && <details className="mt-4 border-t border-gray-200 pt-3 dark:border-gray-800">
                <summary className="cursor-pointer text-sm font-medium text-gray-700 dark:text-gray-300">Ver definición y ejemplos</summary>
                {contract.schema && <div className="mt-3"><strong className="text-xs text-gray-600 dark:text-gray-400">Definición (JSON)</strong><pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-words rounded bg-gray-50 p-3 text-xs text-gray-800 dark:bg-gray-900 dark:text-gray-200">{contract.schema}</pre></div>}
                {contract.examples && <div className="mt-3"><strong className="text-xs text-gray-600 dark:text-gray-400">Ejemplos (JSON)</strong><pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-words rounded bg-gray-50 p-3 text-xs text-gray-800 dark:bg-gray-900 dark:text-gray-200">{contract.examples}</pre></div>}
              </details>}
            </div>
          ))}
        </div>
      ) : (
        <div className="text-center py-12">
          <p className="text-gray-500 dark:text-gray-400">Todavía no hay contratos. Crea uno para documentar el formato de datos de este módulo.</p>
        </div>
      )}
    </Layout>
  );
}
