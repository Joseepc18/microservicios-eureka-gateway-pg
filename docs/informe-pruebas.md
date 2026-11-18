# Informe de pruebas experimentales

Cada escenario debe ejecutarse y documentarse con evidencia funcional (salidas de comandos, registros, capturas de resultados). No se aceptan capturas de configuración como sustituto de una prueba funcional. Los fallos se provocan durante las pruebas y se explica el comportamiento observado, incluidos errores transitorios y tiempos de detección.

Responsable de cada escenario indicado entre paréntesis.

## P01 · Descubrimiento dinámico (Integrante 1)

- **Objetivo:** verificar que las instancias se registran en Eureka y pueden localizarse por nombre lógico.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:**
- **Evidencia:**
- **Análisis:**

## P02 · Balanceo HTTP (Integrante 1)

- **Objetivo:** ejecutar al menos 100 solicitudes contra un servicio con dos instancias y registrar qué instancia responde cada una.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:** distribución observada, total de respuestas exitosas y errores.
- **Evidencia:**
- **Análisis:**

## P03 · Fallo de instancia (Integrante 1)

- **Objetivo:** detener una instancia y comprobar que el sistema continúa atendiendo solicitudes con la otra.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:** solicitudes antes, durante y después del fallo; tiempo observado de recuperación.
- **Evidencia:**
- **Análisis:**

## P04 · Comunicación entre servicios (Integrante 3)

- **Objetivo:** ejecutar una operación de negocio que requiera una llamada real de un microservicio a otro mediante descubrimiento dinámico.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:** trazas de solicitud y respuesta, resultado funcional y manejo de errores.
- **Evidencia:**
- **Análisis:**

## P05 · Enrutamiento de base de datos (Integrante 2)

- **Objetivo:** demostrar que las escrituras usan el primario y las consultas ordinarias se distribuyen entre las dos réplicas.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:** identificación del nodo utilizado, consultas SQL y registros de aplicación.
- **Evidencia:**
- **Análisis:**

## P06 · Fallo de réplica PostgreSQL (Integrante 2)

- **Objetivo:** detener una réplica y comprobar que las consultas continúan mediante la réplica restante.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:** estado de contenedores, resultados de consultas y registros del mecanismo de selección.
- **Evidencia:**
- **Análisis:**

## P07 · Consistencia de lectura (Integrante 2)

- **Objetivo:** crear un registro y consultarlo inmediatamente mediante una operación crítica dirigida al primario.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:** secuencia de solicitudes, resultado y justificación de la política aplicada.
- **Evidencia:**
- **Análisis:**

## P08 · Recuperación de réplica (Integrante 2)

- **Objetivo:** reiniciar la réplica detenida, comprobar su recuperación y verificar que vuelve a participar en las consultas.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:** estado de replicación, reincorporación y consultas atendidas.
- **Evidencia:**
- **Análisis:**

## Limitaciones observadas

_Pendiente._
