# Guía de colaboración

## Flujo de trabajo

1. Tomar una issue asignada y crear una rama desde `main` con el patrón `tipo/descripcion-corta`, por ejemplo `feat/eureka-server` o `docs/informe-pruebas`.
2. No hacer commits directos a `main`.
3. Abrir un Pull Request hacia `main` que referencie la issue con `Closes #N`.
4. Se requiere la revisión de al menos otro integrante antes de integrar.

## Commits

Se usa [Conventional Commits](https://www.conventionalcommits.org/es/v1.0.0/):

```
feat(gateway): agregar ruta lb://servicio-productos
fix(persistencia): excluir réplica caída del balanceo
docs(readme): documentar recuperación de réplicas
chore(compose): parametrizar contraseñas con .env
```

Tipos habituales: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`.

## Reglas del repositorio

- Nunca versionar contraseñas, tokens ni archivos `.env`. Usar `.env.example` con valores de ejemplo.
- Los scripts `.sh` deben mantener fin de línea LF (ya configurado en `.gitattributes`).
- Cada servicio accede únicamente a su propia base de datos; no se consultan tablas de otro servicio.
- Las rutas entre servicios usan nombres lógicos de Eureka, nunca URLs fijas.
- La evidencia de cada prueba (P01–P08) se registra en `docs/informe-pruebas.md`.
