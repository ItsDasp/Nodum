import { useState } from 'react';
import { Layout } from '../../components/Layout';

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
type HeaderRow = { id: number; name: string; value: string };
type Result = { status: number; statusText: string; headers: Record<string, string>; body: string; duration: number };

export function ApiTesting() {
  const [method, setMethod] = useState<Method>('GET');
  const [url, setUrl] = useState('');
  const [headers, setHeaders] = useState<HeaderRow[]>([]);
  const [token, setToken] = useState('');
  const [body, setBody] = useState('');
  const [result, setResult] = useState<Result | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [nextHeaderId, setNextHeaderId] = useState(0);

  const send = async (event: React.FormEvent) => {
    event.preventDefault(); setError(''); setResult(null);
    let target: URL;
    try { target = new URL(url); } catch { setError('Introduce una URL completa, por ejemplo http://localhost:8080/api/v1/items.'); return; }
    if (target.protocol !== 'http:' && target.protocol !== 'https:') { setError('Solo se admiten URLs HTTP y HTTPS.'); return; }
    const outgoing: Record<string, string> = {};
    for (const header of headers) { if (header.name.trim()) outgoing[header.name.trim()] = header.value; }
    if (token.trim()) outgoing.Authorization = /^Bearer\s/i.test(token.trim()) ? token.trim() : `Bearer ${token.trim()}`;
    if (body && !Object.keys(outgoing).some((key) => key.toLowerCase() === 'content-type')) outgoing['Content-Type'] = 'application/json';
    if (body && !['GET', 'DELETE'].includes(method)) {
      try { JSON.parse(body); } catch { setError('El cuerpo no es JSON válido.'); return; }
    }
    setLoading(true); const started = performance.now();
    try {
      const response = await fetch(target, { method, headers: outgoing, body: !['GET', 'DELETE'].includes(method) && body ? body : undefined });
      const text = await response.text(); const responseHeaders: Record<string, string> = {};
      response.headers.forEach((value, key) => { responseHeaders[key] = value; });
      setResult({ status: response.status, statusText: response.statusText, headers: responseHeaders, body: text, duration: Math.round(performance.now() - started) });
    } catch (cause) {
      setError(cause instanceof TypeError ? 'No se pudo conectar. Comprueba la URL, el servidor y sus reglas CORS.' : 'La solicitud no pudo completarse.');
    } finally { setLoading(false); }
  };

  return <Layout>
    <div className="page-heading"><div><h1>Pruebas de API</h1><p>Envía una solicitud HTTP y revisa la respuesta real del servidor.</p></div></div>
    <div className="request-grid">
      <form className="surface surface-pad" onSubmit={send} style={{ display: 'grid', gap: 16, alignContent: 'start' }}>
        <h2 className="section-title">Solicitud</h2>
        <div style={{ display: 'grid', gridTemplateColumns: '105px 1fr', gap: 8 }}><select className="field mono" aria-label="Método HTTP" value={method} onChange={(event) => setMethod(event.target.value as Method)}>{(['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as Method[]).map((value) => <option key={value}>{value}</option>)}</select><input className="field mono" aria-label="URL" type="url" value={url} onChange={(event) => setUrl(event.target.value)} placeholder="http://localhost:8080/api/v1/items" required /></div>
        <div><div className="section-head"><label className="field-label" style={{ margin: 0 }}>Cabeceras</label><button type="button" className="btn" onClick={() => { setHeaders((items) => [...items, { id: nextHeaderId, name: '', value: '' }]); setNextHeaderId((id) => id + 1); }}>+ Añadir</button></div>
          {headers.map((header) => <div key={header.id} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 32px', gap: 7, marginTop: 7 }}><input className="field mono" aria-label="Nombre de cabecera" placeholder="Nombre" value={header.name} onChange={(event) => setHeaders((items) => items.map((item) => item.id === header.id ? { ...item, name: event.target.value } : item))} /><input className="field mono" aria-label="Valor de cabecera" placeholder="Valor" value={header.value} onChange={(event) => setHeaders((items) => items.map((item) => item.id === header.id ? { ...item, value: event.target.value } : item))} /><button className="icon-button" type="button" aria-label="Quitar cabecera" onClick={() => setHeaders((items) => items.filter((item) => item.id !== header.id))}>×</button></div>)}</div>
        <div><label className="field-label" htmlFor="request-token">Token Bearer</label><input id="request-token" className="field mono" type="password" autoComplete="off" value={token} onChange={(event) => setToken(event.target.value)} placeholder="Opcional" /></div>
        {!['GET', 'DELETE'].includes(method) && <div><label className="field-label" htmlFor="request-body">Cuerpo JSON</label><textarea id="request-body" className="field mono" rows={8} value={body} onChange={(event) => setBody(event.target.value)} placeholder={'{\n  "name": "Ejemplo"\n}'} /></div>}
        {error && <p role="alert" style={{ margin: 0, color: '#a24842', fontSize: 12 }}>{error}</p>}
        <div><button className="btn btn-primary" type="submit" disabled={loading || !url.trim()}>{loading ? 'Enviando…' : 'Enviar solicitud'}</button></div>
      </form>
      <section className="surface surface-pad" aria-live="polite" style={{ minWidth: 0 }}><div className="section-head"><h2 className="section-title">Respuesta</h2>{result && <span className="mono" style={{ color: '#768078', fontSize: 11 }}>{result.duration} ms</span>}</div>
        {result ? <><div className="list-row"><span className={`status-dot ${result.status >= 200 && result.status < 400 ? 'ok' : 'error'}`}>{result.status} {result.statusText}</span></div>
          <details style={{ margin: '12px 0' }}><summary style={{ cursor: 'pointer', color: '#68716b', fontSize: 12 }}>Cabeceras de respuesta ({Object.keys(result.headers).length})</summary><pre className="mono" style={{ overflow: 'auto', maxHeight: 180, fontSize: 11, whiteSpace: 'pre-wrap' }}>{Object.entries(result.headers).map(([key, value]) => `${key}: ${value}`).join('\n') || 'Sin cabeceras'}</pre></details>
          <pre className="mono" style={{ overflow: 'auto', maxHeight: '55vh', border: '1px solid #e7eae6', borderRadius: 6, background: '#f7f8f6', padding: 12, fontSize: 11, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{result.body || 'La respuesta no incluye cuerpo.'}</pre>
        </> : <div className="empty-state"><strong>Sin respuesta todavía</strong>Completa la solicitud para consultar el resultado del servidor.</div>}
      </section>
    </div>
  </Layout>;
}
