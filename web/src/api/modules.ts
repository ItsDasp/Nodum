import { get, post, put, del } from './client';

export interface Module {
  id: string;
  name: string;
  description: string;
  basePath: string;
  version: string;
  createdAt: string;
  updatedAt: string;
  providerId: string | null;
  providerVersion: string | null;
  configurationJson: string;
  lifecycleState: 'CONFIGURED' | 'STARTING' | 'RUNNING' | 'STOPPED' | 'ERROR';
  lifecycleError: string | null;
}

export interface ModuleProvider {
  id: string;
  version: string;
  contractVersion: number;
  name: string;
  description: string;
  capabilities: string[];
}

export interface CreateModuleRequest {
  name: string;
  description: string;
  basePath: string;
  version: string;
  providerId?: string;
  configurationJson?: string;
}

export interface UpdateModuleRequest {
  name?: string;
  description?: string;
  basePath?: string;
  version?: string;
}

export async function getModules(projectId: string): Promise<Module[]> {
  return get<Module[]>(`/projects/${projectId}/modules`);
}

export async function getModuleCatalog(): Promise<ModuleProvider[]> {
  return get<ModuleProvider[]>('/module-catalog');
}

export async function createModule(projectId: string, data: CreateModuleRequest): Promise<Module> {
  return post<Module>(`/projects/${projectId}/modules`, data);
}

export async function updateModule(projectId: string, moduleId: string, data: UpdateModuleRequest): Promise<Module> {
  return put<Module>(`/projects/${projectId}/modules/${moduleId}`, data);
}

export async function deleteModule(projectId: string, moduleId: string): Promise<void> {
  return del(`/projects/${projectId}/modules/${moduleId}`);
}

export async function startModule(projectId: string, moduleId: string): Promise<Module> {
  return post<Module>(`/projects/${projectId}/modules/${moduleId}/start`, {});
}

export async function stopModule(projectId: string, moduleId: string): Promise<Module> {
  return post<Module>(`/projects/${projectId}/modules/${moduleId}/stop`, {});
}

export async function executeModuleAction(projectId: string, moduleId: string, action: string, input: unknown): Promise<unknown> {
  return post<unknown>(`/projects/${projectId}/modules/${moduleId}/actions/${encodeURIComponent(action)}`, input);
}

export async function validateModule(projectId: string, moduleId: string): Promise<{ valid: boolean; errors: string[] }> {
  return get(`/projects/${projectId}/modules/${moduleId}/validation`);
}

export async function getModuleHealth(projectId: string, moduleId: string): Promise<{ state: string; message: string }> {
  return get(`/projects/${projectId}/modules/${moduleId}/health`);
}
