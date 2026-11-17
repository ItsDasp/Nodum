import { get, post, put, del } from './client';

export interface Project {
  id: string;
  name: string;
  description: string;
  ownerId: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateProjectRequest {
  name: string;
  description?: string;
}

export interface UpdateProjectRequest {
  name?: string;
  description?: string;
}

export async function getProjects(): Promise<Project[]> {
  return get<Project[]>('/projects');
}

export async function getProject(id: string): Promise<Project> {
  return get<Project>(`/projects/${id}`);
}

export async function createProject(data: CreateProjectRequest): Promise<Project> {
  return post<Project>('/projects', data);
}

export async function updateProject(id: string, data: UpdateProjectRequest): Promise<Project> {
  return put<Project>(`/projects/${id}`, data);
}

export async function deleteProject(id: string): Promise<void> {
  return del<void>(`/projects/${id}`);
}
