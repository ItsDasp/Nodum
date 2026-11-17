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
        Los contratos se guardan como metadatos. Marcarlos como publicados no despliega ni ejecuta una API.
      </div>

      {showCreateForm && (
        <div className="mb-6 p-6 border border-gray-200 dark:border-gray-800 rounded">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">Create Contract</h3>
          <form onSubmit={handleCreate} className="space-y-4">
            <div>
              <label htmlFor="name" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Name
              </label>
              <input
                id="name"
                type="text"
                value={newContractName}
                onChange={(e) => setNewContractName(e.target.value)}
                className="w-full px-3 py-2 bg-gray-50 dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded text-gray-900 dark:text-white focus:outline-none focus:border-gray-400 dark:focus:border-gray-600"
                placeholder="Contract name"
                required
              />
            </div>
            <div>
              <label htmlFor="description" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Description
              </label>
              <textarea
                id="description"
                value={newContractDescription}
                onChange={(e) => setNewContractDescription(e.target.value)}
                className="w-full px-3 py-2 bg-gray-50 dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded text-gray-900 dark:text-white focus:outline-none focus:border-gray-400 dark:focus:border-gray-600"
                placeholder="Contract description"
                rows={3}
              />
            </div>
            <div>
              <label htmlFor="version" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Version
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
                Schema (JSON)
              </label>
              <textarea
                id="schema"
                value={newContractSchema}
                onChange={(e) => setNewContractSchema(e.target.value)}
                className="w-full px-3 py-2 bg-gray-50 dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded text-gray-900 dark:text-white focus:outline-none focus:border-gray-400 dark:focus:border-gray-600 font-mono text-sm"
                placeholder='{"type": "object", ...}'
                rows={6}
              />
            </div>
            <div>
              <label htmlFor="examples" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                Examples (JSON)
              </label>
              <textarea
                id="examples"
                value={newContractExamples}
                onChange={(e) => setNewContractExamples(e.target.value)}
                className="w-full px-3 py-2 bg-gray-50 dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded text-gray-900 dark:text-white focus:outline-none focus:border-gray-400 dark:focus:border-gray-600 font-mono text-sm"
                placeholder='{"example": "data"}'
                rows={4}
              />
            </div>
            <button
              type="submit"
              disabled={createMutation.isPending}
              className="bg-gray-900 dark:bg-white text-white dark:text-gray-900 px-4 py-2 rounded hover:bg-gray-800 dark:hover:bg-gray-100 disabled:opacity-50"
            >
              {createMutation.isPending ? 'Creating...' : 'Create'}
            </button>
          </form>
        </div>
      )}

      {isLoading ? (
        <p className="text-gray-500 dark:text-gray-400">Loading...</p>
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
                    {deleteMutation.isPending ? 'Deleting...' : 'Delete'}
                  </button>
                </div>
              </div>
              <p className="text-sm text-gray-500 dark:text-gray-400 mb-2">{contract.description || 'No description'}</p>
              <div className="space-y-1 text-xs text-gray-400 dark:text-gray-500">
                <div>Version: {contract.version}</div>
                <div className={getStatusColor(contract.status)}>Status: {contract.status}</div>
                <div>Created: {new Date(contract.createdAt).toLocaleDateString()}</div>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="text-center py-12">
          <p className="text-gray-500 dark:text-gray-400">No contracts yet. Click "New Contract" to create your first contract.</p>
        </div>
      )}
    </Layout>
  );
}
