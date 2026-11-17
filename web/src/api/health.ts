import { get } from './client';

export interface HealthResponse {
  status: string;
  timestamp: string;
  service: string;
}

export async function getHealth(): Promise<HealthResponse> {
  return get<HealthResponse>('/health');
}
