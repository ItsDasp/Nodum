import { get, post, put, del } from './client';

export interface Contract {
  id: string;
  name: string;
  description: string;
  version: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED' | 'ARCHIVED';
  schema: string;
  examples: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateContractRequest {
  name: string;
  description: string;
  version: string;
  schema: string;
  examples: string;
}

export interface UpdateContractRequest {
  name?: string;
  description?: string;
  schema?: string;
  examples?: string;
}

export interface NewVersionRequest {
  version: string;
  description?: string;
  schema?: string;
  examples?: string;
}

export async function getContracts(projectId: string, moduleId: string): Promise<Contract[]> {
  return get<Contract[]>(`/projects/${projectId}/modules/${moduleId}/contracts`);
}

export async function getLatestContract(projectId: string, moduleId: string): Promise<Contract> {
  return get<Contract>(`/projects/${projectId}/modules/${moduleId}/contracts/latest`);
}

export async function createContract(projectId: string, moduleId: string, data: CreateContractRequest): Promise<Contract> {
  return post<Contract>(`/projects/${projectId}/modules/${moduleId}/contracts`, data);
}

export async function updateContract(projectId: string, moduleId: string, contractId: string, data: UpdateContractRequest): Promise<Contract> {
  return put<Contract>(`/projects/${projectId}/modules/${moduleId}/contracts/${contractId}`, data);
}

export async function publishContract(projectId: string, moduleId: string, contractId: string): Promise<Contract> {
  return post<Contract>(`/projects/${projectId}/modules/${moduleId}/contracts/${contractId}/publish`, {});
}

export async function createNewVersion(projectId: string, moduleId: string, contractId: string, data: NewVersionRequest): Promise<Contract> {
  return post<Contract>(`/projects/${projectId}/modules/${moduleId}/contracts/${contractId}/new-version`, data);
}

export async function deleteContract(projectId: string, moduleId: string, contractId: string): Promise<void> {
  return del(`/projects/${projectId}/modules/${moduleId}/contracts/${contractId}`);
}
