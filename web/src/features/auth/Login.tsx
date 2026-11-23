import { useState } from 'react';
import { login } from '../../api/auth';

export function Login() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    try {
      const response = await login(username, password);
      localStorage.setItem('nodum-token', response.token);
      window.location.href = '/';
    } catch (err) {
      setError('No se pudo iniciar sesión. Revisa tu usuario y contraseña.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center" style={{ background: 'var(--bg)' }}>
      <div className="w-full max-w-sm p-8">
        <h1 className="text-3xl font-semibold mb-2" style={{ color: 'var(--text)' }}>Nodum</h1>
        <p className="text-sm mb-8" style={{ color: 'var(--muted-2)' }}>Entorno visual para diseñar sistemas backend.</p>
        
        <form onSubmit={handleSubmit} className="space-y-6">
          <div>
            <label htmlFor="username" className="block text-sm font-medium mb-2" style={{ color: 'var(--muted)' }}>
                Usuario
            </label>
            <input
              id="username"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="field"
              placeholder="Tu usuario"
            />
          </div>
          
          <div>
            <label htmlFor="password" className="block text-sm font-medium mb-2" style={{ color: 'var(--muted)' }}>
                Contraseña
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                setError('');
              }}
              className="field"
              placeholder="Tu contraseña"
            />
            {error && (
              <p className="mt-2 text-sm" style={{ color: 'var(--danger)' }}>{error}</p>
            )}
          </div>
          
          <button
            type="submit"
            disabled={loading}
            className="btn btn-primary w-full"
            style={{ minHeight: 40 }}
          >
            {loading ? 'Ingresando…' : 'Iniciar sesión'}
          </button>
        </form>
      </div>
    </div>
  );
}
