import { post } from './client';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface AuthResponse {
  token: string;
}

export async function login(username: string, password: string): Promise<AuthResponse> {
  return post<AuthResponse>('/auth/login', { username, password });
}
