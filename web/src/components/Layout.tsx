import { Link, useLocation } from 'react-router-dom';
import { toggleTheme } from '../lib/theme';

interface LayoutProps { children: React.ReactNode }

const navItems = [
  { path: '/', label: 'Inicio', glyph: '⌂' },
  { path: '/projects', label: 'Proyectos', glyph: '▧' },
  { path: '/logs', label: 'Logs', glyph: '≋' },
  { path: '/shell', label: 'Shell remota', glyph: '>_' },
  { path: '/testing', label: 'Pruebas de API', glyph: '⇄' },
];

export function Layout({ children }: LayoutProps) {
  const location = useLocation();
  const pageLabel = location.pathname.includes('/contracts') ? 'Contratos'
    : location.pathname.includes('/modules') ? 'Módulos'
      : navItems.find((item) => item.path === location.pathname)?.label ?? 'Proyectos';
  const handleLogout = () => {
    localStorage.removeItem('nodum-token');
    window.location.href = '/login';
  };

  return (
    <div className="shell">
      <aside className="sidebar">
        <Link to="/" className="brand"><span className="brand-mark"><img src="/Icon.png" alt="" /></span>Nodum</Link>
        <div className="project-switcher">ESPACIO DE TRABAJO<strong>Mis proyectos</strong></div>
        <span className="nav-label">Plataforma</span>
        <nav className="nav-list" aria-label="Navegación principal">
          {navItems.map((item) => (
            <Link key={item.path} to={item.path} className={`nav-link ${location.pathname === item.path || (item.path !== '/' && location.pathname.startsWith(item.path + '/')) ? 'active' : ''}`}>
              <span className="nav-glyph" aria-hidden="true">{item.glyph}</span>{item.label}
            </Link>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <button onClick={toggleTheme} className="nav-link" type="button"><span className="nav-glyph" aria-hidden="true">◐</span>Cambiar tema</button>
          <button onClick={handleLogout} className="nav-link" type="button"><span className="nav-glyph" aria-hidden="true">↪</span>Cerrar sesión</button>
        </div>
      </aside>
      <div className="main-area">
        <header className="topbar">
          <span className="breadcrumb">Plataforma <span aria-hidden="true">/</span> {pageLabel}</span>
        </header>
        <main className="content">{children}</main>
      </div>
    </div>
  );
}
