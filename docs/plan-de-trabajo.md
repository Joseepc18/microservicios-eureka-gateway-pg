# Plan de trabajo

Tres integrantes, tres frentes. Cada frente tiene sus issues en GitHub, etiquetadas por integrante y por área.

| Frente | Integrante | Alcance | Escenarios |
|---|---|---|---|
| 1. Descubrimiento, Gateway e integración | Integrante 1 (Joseepc18) | Estructura Maven, Eureka, Gateway, Docker Compose integrado, README y diagrama | P01, P02, P03 |
| 2. Persistencia replicada | Integrante 2 | Endurecer el laboratorio PostgreSQL, enrutamiento lecturas/escrituras, resiliencia de réplicas | P05, P06, P07, P08 |
| 3. Microservicios de negocio | Integrante 3 | `servicio-productos`, `servicio-pedidos`, comunicación por Eureka, colección de solicitudes, informe consolidado | P04 |
| Transversal | Todos | Video demostrativo y defensa técnica | — |

## Orden recomendado

1. **Base:** estructura multi-módulo Maven (Integrante 1). Desbloquea a los demás.
2. **En paralelo:** Eureka y Gateway (1), módulo de persistencia (2), servicios de negocio (3). El laboratorio PostgreSQL ya existe en `docker-compose.yml`.
3. **Integración:** Docker Compose completo con todos los servicios.
4. **Pruebas P01–P08** con evidencia en `docs/informe-pruebas.md`.
5. **Documentación final y video.**

## Rúbrica (100 puntos)

| Criterio | Puntaje |
|---|---|
| Arquitectura y separación de responsabilidades | 15 |
| Eureka y descubrimiento dinámico | 15 |
| Gateway y balanceo HTTP | 15 |
| Enrutamiento de lecturas y escrituras | 20 |
| Manejo de fallos y recuperación | 20 |
| Evidencias, documentación y defensa | 15 |
