# Panel web de Nodum

Frontend del panel de administración de Nodum, construido con **React + TypeScript + Vite**.

## Stack

- React + TypeScript
- Vite (dev server y build)
- Tailwind CSS
- React Router
- TanStack Query
- xterm.js (terminal remota por WebSocket)

## Desarrollo

```bash
npm install
npm run dev
```

La app queda disponible en http://localhost:5173 y consume la API en `http://localhost:8080/api/v1` (sobreescribible con `VITE_API_BASE_URL`).

## Build

```bash
npm run build   # genera dist/
npm run lint    # oxlint
```

## Estructura

```text
src/
├── api/         # Cliente HTTP y endpoints tipados
├── app/         # Configuración de la aplicación
├── components/  # Componentes compartidos (Layout, etc.)
├── features/    # auth, dashboard, projects, modules,
│                # contracts, testing, operations
├── hooks/       # Hooks personalizados
├── lib/         # Utilidades (tema, etc.)
├── routes/      # Definición de rutas
└── types/       # Tipos TypeScript
```

## Autenticación

El panel requiere login con las credenciales del administrador. El JWT se guarda en `localStorage` (`nodum-token`) y se envía como `Authorization: Bearer` a las rutas protegidas del API.

## Temas

Soporta tema claro/oscuro con persistencia en `localStorage` (`nodum-theme`); el oscuro es el predeterminado.
