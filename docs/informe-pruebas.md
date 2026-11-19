# Informe de pruebas experimentales

Cada escenario debe ejecutarse y documentarse con evidencia funcional (salidas de comandos, registros, capturas de resultados). No se aceptan capturas de configuración como sustituto de una prueba funcional. Los fallos se provocan durante las pruebas y se explica el comportamiento observado, incluidos errores transitorios y tiempos de detección.

Responsable de cada escenario indicado entre paréntesis.

## P01 · Descubrimiento dinámico (Integrante 1)

- **Objetivo:** verificar que las instancias se registran en Eureka y pueden localizarse por nombre lógico.
- **Procedimiento:** (sistema levantado desde un checkout limpio con `cp .env.example .env` y `docker compose up -d --build --wait`; todos los contenedores quedaron `healthy`). Los comandos se ejecutaron desde Git Bash en Windows con Docker Desktop, usando `127.0.0.1`.
  1. Consultar el registro de Eureka en JSON y en el dashboard: `curl -H "Accept: application/json" http://127.0.0.1:8761/eureka/apps` y `http://127.0.0.1:8761`.
  2. Consultar las rutas del Gateway: `curl http://127.0.0.1:8080/actuator/gateway/routes`.
  3. Enviar solicitudes funcionales **solo por el Gateway y por nombre lógico** (sin IP ni puerto de ninguna instancia): `GET /api/productos`, `GET /api/productos/1`, `GET /api/pedidos`, y cuatro `GET /api/productos/1` seguidos mirando `X-Instance-Id`.
  4. Revisar en el log del Gateway a qué instancia fue cada solicitud: `docker compose logs api-gateway`.
- **Resultado esperado:** `API-GATEWAY`, `SERVICIO-PEDIDOS` y las dos instancias de `SERVICIO-PRODUCTOS` figuran `UP` con su `instanceId`; las rutas del Gateway apuntan a `lb://servicio-productos` y `lb://servicio-pedidos`; las solicitudes responden `200` y las de productos alternan entre `productos-1` y `productos-2`.
- **Resultado obtenido:**
  - Registro en Eureka (4 instancias, todas `UP`):

    | Aplicación | `instanceId` | `hostName` anunciado | IP | Estado |
    |---|---|---|---|---|
    | `API-GATEWAY` | `api-gateway:gateway-1` | `api-gateway` | 172.22.0.3 | UP |
    | `SERVICIO-PEDIDOS` | `servicio-pedidos:pedidos-1` | `servicio-pedidos` | 172.22.0.4 | UP |
    | `SERVICIO-PRODUCTOS` | `servicio-productos:productos-1` | `servicio-productos-1` | 172.21.0.6 | UP |
    | `SERVICIO-PRODUCTOS` | `servicio-productos:productos-2` | `servicio-productos-2` | 172.21.0.5 | UP |

    El dashboard muestra las mismas cuatro instancias.
  - `/actuator/gateway/routes` devolvió dos rutas: `servicio-productos` (`/api/productos/**` → `lb://servicio-productos`) y `servicio-pedidos` (`/api/pedidos/**` → `lb://servicio-pedidos`), con el filtro `Retry` solo para `GET` (2 reintentos) y `StripPrefix`.
  - Las tres solicitudes funcionales respondieron `200`. `GET /api/productos/1` devolvió el producto de ejemplo (Laptop 14 pulgadas) con `X-Instance-Id: productos-2`; `GET /api/productos`, `X-Instance-Id: productos-1`; `GET /api/pedidos`, `X-Instance-Id: pedidos-1` y `[]`. Cuatro `GET /api/productos/1` seguidos respondieron `productos-2`, `productos-1`, `productos-2`, `productos-1`.
  - El log del Gateway muestra el destino de cada solicitud resuelto por nombre: por ejemplo `route=servicio-productos target=servicio-productos-2:8082 instanceId=productos-2 status=200` y `route=servicio-pedidos target=servicio-pedidos:8083 instanceId=pedidos-1 status=200`.
- **Evidencia:** [`docs/evidencias/P01/`](evidencias/P01/): `01-compose-ps.txt` (contenedores `healthy`), `02-eureka-health.txt`, `03-eureka-apps-json.txt` (respuesta JSON completa de `/eureka/apps`), `04-eureka-instancias.txt` (extracto: una línea por instancia), `05-eureka-dashboard.txt` (líneas del dashboard), `06-gateway-routes.txt`, `07-solicitudes-gateway.txt` (cabeceras y cuerpos de las solicitudes) y `08-gateway-log.txt`. En `01-compose-ps.txt` (y en las salidas de P03 que muestran nombres de contenedor) se normalizó el prefijo del nombre del proyecto de Compose a `microservicios-eureka-gateway-pg-`: durante las pruebas el proyecto se llamaba como la carpeta de trabajo temporal. Es lo único que se cambió respecto a la salida original.
- **Análisis:** el Gateway nunca usa una dirección fija: las rutas son `lb://nombre-lógico` y es Eureka quien dice qué instancias hay detrás de cada nombre, de modo que ambas instancias de productos respondieron a la misma URL (`/api/productos`) sin que el cliente supiera cuál era cuál. El `instanceId` (`servicio-productos:productos-1/2`) y el header `X-Instance-Id` permiten distinguirlas. Las instancias de productos anuncian en Eureka su nombre de host (`servicio-productos-1/2`) y no su IP: la IP que registra Eureka (172.21.0.x) es de otra subred que la del Gateway (172.22.0.x), así que el Gateway las alcanza por nombre (decisión ya comentada en `docker-compose.yml`). Esta prueba solo cubre el registro y la localización; el reparto de carga se mide en P02.

## P02 · Balanceo HTTP (Integrante 1)

- **Objetivo:** ejecutar al menos 100 solicitudes contra un servicio con dos instancias y registrar qué instancia responde cada una.
- **Procedimiento:** con el sistema levantado, ejecutar [`scripts/p02-balanceo.sh`](../scripts/p02-balanceo.sh). El script envía `N` solicitudes `GET` al Gateway (una tras otra), lee el header `X-Instance-Id` de cada respuesta y cuenta por instancia y por código HTTP.

  ```bash
  scripts/p02-balanceo.sh                       # 100 solicitudes a http://127.0.0.1:8080/api/productos
  scripts/p02-balanceo.sh 300 http://127.0.0.1:8080 /api/productos/1   # cantidad, URL base y ruta
  DETALLE=0 scripts/p02-balanceo.sh 300         # solo el resumen (sin una línea por solicitud)
  ```

  Parámetros: cantidad, URL base y ruta (argumentos o variables `CANTIDAD`, `URL_BASE`, `RUTA`); `PAUSA` (segundos entre solicitudes) y `DETALLE`. Termina con código 0 si todas fueron `2xx`. Requiere `bash` y `curl`. Se ejecutaron dos corridas: 100 solicitudes a `/api/productos` (la prueba pedida) y 300 a `/api/productos/1` para comprobar el parámetro de cantidad.
- **Resultado esperado:** el balanceador de carga del Gateway (round-robin) reparte las solicitudes casi por igual entre `productos-1` y `productos-2`, y todas responden `200`.
- **Resultado obtenido:**

  | Corrida | Solicitudes | Exitosas (2xx) | Errores | `productos-1` | `productos-2` |
  |---|---|---|---|---|---|
  | 100 × `GET /api/productos` | 100 | 100 | 0 | 47 (47 %) | 53 (53 %) |
  | 300 × `GET /api/productos/1` | 300 | 300 | 0 | 149 (49.7 %) | 151 (50.3 %) |

  En la corrida de 100, el patrón por solicitud fue casi una alternancia estricta (`productos-1`, `productos-2`, `productos-1`, …) con seis ocasiones en las que `productos-2` respondió dos veces seguidas. El log del Gateway coincide con lo contado por el cliente (por destino y código `200`; incluye además las 6 solicitudes de P01 hechas antes (1 a `/api/productos` y 5 a `/api/productos/1`), ver `p02-gateway-log.txt`).
- **Evidencia:** [`docs/evidencias/P02/`](evidencias/P02/): `p02-salida-completa.txt` (salida íntegra de la corrida de 100, con una línea por solicitud: número, código, instancia y milisegundos), `p02-300-solicitudes-resumen.txt` (resumen de la corrida de 300) y `p02-gateway-log.txt` (recuento del log del Gateway por destino y estado).
- **Análisis:** el reparto es el esperado para un round-robin: ~50 % a cada instancia, sin errores. Los pequeños desvíos (47/53 con 100 solicitudes) vienen de las ocasiones en que una misma instancia respondió dos veces seguidas; no se investigó su causa exacta, aunque es compatible con que el LoadBalancer renueve su lista de instancias cada 5 s (`spring.cloud.loadbalancer.cache.ttl`, ver `docs/arquitectura.md`). Con más solicitudes el reparto converge (49.7 / 50.3 %). La latencia por solicitud fue de 12 a 159 ms (casi siempre 20–40 ms); el script tardó unos 0.4 s por solicitud en total porque cada iteración lanza varios procesos (`curl`, `grep`, etc.) en Git Bash sobre Windows: es costo del script, no del servicio.

## P03 · Fallo de instancia (Integrante 1)

- **Objetivo:** detener una instancia y comprobar que el sistema continúa atendiendo solicitudes con la otra.
- **Procedimiento:** con el sistema levantado, ejecutar [`scripts/p03-fallo-instancia.sh`](../scripts/p03-fallo-instancia.sh), que hace lo siguiente en un solo comando:
  1. Comprueba que el Gateway responde y que `servicio-productos:productos-2` está `UP` en Eureka.
  2. Genera carga continua de `GET /api/productos` por el Gateway (una solicitud cada 200 ms, lanzadas sin esperar a que termine la anterior) durante 90 s.
  3. En el segundo 20 ejecuta `docker compose kill servicio-productos-2` (terminación abrupta: el proceso no avisa a Eureka). Una vez cada segundo consulta el **registro** de Eureka para saber cuándo la expulsa.
  4. Al terminar la carga resume las solicitudes por fase (antes del fallo, **durante** = los 40 s siguientes al fallo —cubre los ~35 s del peor caso documentado—, y después), los tiempos y los destinos del log del Gateway.
  5. Ejecuta `docker compose start servicio-productos-2` y mide cuánto tarda en volver a `UP` en Eureka y en volver a responder por el Gateway; después envía 30 solicitudes más para ver el reparto.

  ```bash
  scripts/p03-fallo-instancia.sh                          # terminación abrupta (kill), 90 s de carga
  MODO=stop DURACION=60 FALLO_EN=15 VENTANA_DURANTE=30 scripts/p03-fallo-instancia.sh   # parada ordenada
  SALIDA=docs/evidencias/P03/corrida SERVICIO=servicio-productos-1 INSTANCIA=productos-1 scripts/p03-fallo-instancia.sh
  ```

  Variables: `MODO` (`kill`/`stop`), `DURACION`, `FALLO_EN`, `INTERVALO_MS`, `VENTANA_DURANTE`, `SERVICIO`, `INSTANCIA`, `URL_BASE`, `EUREKA_URL`, `SALIDA` (carpeta con el detalle por solicitud). Requiere `bash`, `curl`, `awk`, `date` de GNU y `docker compose`; deja la instancia otra vez levantada. Se ejecutó **tres veces con `kill`** (para ver la variación entre corridas) y **una con `stop`**, que es la parada ordenada y se comporta distinto.
- **Resultado esperado:** al caer `productos-2` el sistema sigue respondiendo con `productos-1`. Mientras Eureka y el Gateway no se enteran (~24 s hasta que Eureka expulsa la instancia y ~35 s en el peor caso hasta que el Gateway deja de elegirla, según `docs/arquitectura.md`) pueden aparecer errores transitorios; el `GET` se reintenta hasta 2 veces, así que muchos se recuperan. Después del fallo todo debe responder `200` desde `productos-1`, y al volver a levantar la instancia debe registrarse y recibir tráfico.
- **Resultado obtenido:**

  *Terminación abrupta (`docker compose kill`), tres corridas.* El fallo se lanzó en t≈20.2 s; el comando tardó 1.8–3.0 s en volver. Las solicitudes por fase (las tres corridas, en este orden):

  | Fase | Solicitudes | Exitosas | Errores | Instancia que respondió |
  |---|---|---|---|---|
  | Antes del fallo (20 s) | 100 / 69 / 71 | 100 / 69 / 71 | 0 / 0 / 0 | 50 + 50 / 35 + 34 / 35 + 36 (`productos-1` + `productos-2`) |
  | Durante (40 s desde el fallo) | 138 / 134 / 130 | 129 / 126 / 119 | **9 / 8 / 11** | `productos-1`: 127 / 124 / 118; `productos-2`: 2 / 2 / 1 (justo al inicio, antes de que el proceso muriera); sin header (error del Gateway): 9 / 8 / 11 |
  | Después (hasta el segundo 90) | 115 / 107 / 102 | 115 / 107 / 102 | 0 / 0 / 0 | `productos-1`: 100 % |

  Todos los errores fueron `500` del Gateway (sin `X-Instance-Id`), con `target=servicio-productos-2:8082` en su log y una duración de 6.17–6.30 s. Los tiempos medidos (segundos desde el comando de fallo):

  | Medida | Corrida 1 | Corrida 2 | Corrida 3 |
  |---|---|---|---|
  | Primer error | +2.1 s | +8.5 s | +0.9 s |
  | **Último error** (desde ahí, cero errores) | **+26.7 s** | **+23.2 s** | **+22.4 s** |
  | Última solicitud "rescatada" por reintento (aún se elegía la instancia caída) | +30.7 s | +30.3 s | +30.4 s |
  | **Eureka la retira del registro** (muestreo de 1 s) | **+24.5 s** | **+27.4 s** | **+25.8 s** |
  | Solicitudes exitosas lentas (≥ 1.5 s: el 1.er intento cayó en la instancia caída y el reintento respondió) | 44 | 46 | 40 |
  | Latencia máxima de una exitosa / de un error | 4.22 s / 6.30 s | 4.20 s / 6.18 s | 4.21 s / 6.20 s |

  En el registro de Eureka la instancia pasó directamente de `UP` a ausente (nunca figuró `DOWN`). En el log del servidor Eureka aparece `expired lease for SERVICIO-PRODUCTOS/servicio-productos:productos-2` seguido de `Cancelled instance`. Los errores no se reparten parejo: se concentran en los primeros ~25 s (por ejemplo, corrida 3: 1, 4, 1, 3 y 2 errores en las ventanas de 5 s hasta +25 s, y ninguno después).

  *Recuperación (`docker compose start`), tres corridas con `kill` y una con `stop`:* la instancia volvió a figurar `UP` en Eureka a los 15.0 / 18.6 / 20.9 s (kill) y 19.0 s (stop) del comando; volvió a responder por el Gateway a los 22.1 / 19.4 / 27.6 s (kill) y 25.6 s (stop); y en las 30 solicitudes posteriores, todas `200`, el reparto fue 16/14, 15/15, 15/15 y 15/15 entre `productos-1` y `productos-2`.

  *Parada ordenada (`docker compose stop`, 60 s de carga, fallo en t≈15.2 s):* 51 solicitudes antes, 101 durante y 48 después, **todas `200`, cero errores**. Eureka la marcó `DOWN` a los +1.7 s; hubo 6 solicitudes lentas (rescatadas por reintento) entre +2.3 y +5.2 s, y desde entonces todo fue a `productos-1` (salvo una respuesta de `productos-2` en los primeros 0.5 s). Sin embargo, Eureka la retiró del registro recién a los +25.4 s (el servidor lo registra como `expired lease`, no como baja inmediata).
- **Evidencia:** [`docs/evidencias/P03/`](evidencias/P03/). Cada corrida (`corrida1-kill/`, `corrida2-kill/`, `corrida3-kill/`, `stop/`) contiene `resumen.txt` (salida completa del script), `solicitudes.txt` (una línea por solicitud: instante en ms desde el inicio, código, `X-Instance-Id`, latencia en ms), `eureka.txt` (estado de la instancia en el registro cada ~1 s), `gateway.txt` (líneas del log del Gateway durante la prueba), `posterior.txt` y `docker-fallo.txt`. Además: `eureka-server-log-productos-2.txt` (log del servidor Eureka y de la instancia al apagarse) y `analisis-rescatadas-corridas-1-2.txt` / `corrida1-kill/analisis-latencias.txt` (las corridas 1 y 2 se hicieron con una versión anterior del script que aún no calculaba las solicitudes rescatadas ni las latencias por fase; esas cifras se obtuvieron después con `awk` sobre sus `solicitudes.txt`, y la corrida 3 y `stop` ya las calculó el script).
- **Análisis:**
  - **Por qué hay errores transitorios.** Al matar el proceso, Eureka no se entera hasta que vence su lease (10 s configurados; en la práctica Eureka la retiró del registro entre +24.5 y +27.4 s) y el Gateway conserva la instancia en su lista un rato más. Durante ese tiempo el LoadBalancer sigue eligiendo `productos-2` en ~la mitad de las solicitudes y la conexión no responde: cada intento espera el timeout de conexión de 2 s. Con 2 reintentos y esperas de 50 y 100 ms, una solicitud cuyos tres intentos caen en la instancia caída tarda 2 + 0.05 + 2 + 0.1 + 2 ≈ 6.15 s y termina en `500`: es lo que se midió (6.17–6.30 s). Si el reintento cae en `productos-1`, la solicitud se recupera pero tarda ~2 s más (una instancia caída en el primer intento) o ~4.2 s (dos): las 40–46 solicitudes "lentas" exitosas de cada corrida. Es decir, el reintento rescató entre el 78 % y el 85 % (44 de 53, 46 de 54 y 40 de 51) de las solicitudes que primero cayeron en la instancia caída, pero no todas. Que un reintento vuelva a caer en la instancia caída es esperable con carga concurrente: no se verificó la causa, pero es compatible con que las solicitudes en vuelo compartan el mismo contador del round-robin y su reintento no avance de forma estricta hacia la otra instancia.
  - **Los tiempos coinciden con lo documentado.** Eureka expulsó la instancia a los +24.5, +27.4 y +25.8 s (documentado: ~24 s; el rango observado es algo mayor, de 24.5 a 27.4 s, con el muestreo de 1 s incluido). El Gateway siguió eligiendo la instancia caída hasta +30.3–30.7 s (última solicitud rescatada), dentro de los ~35 s de peor caso. Los errores terminaron antes (+22.4 a +26.7 s) porque para fallar hacen falta tres intentos seguidos en la instancia caída; los rescates por reintento duraron hasta el final de la ventana. En resumen: el último error ocurrió entre +22.4 y +26.7 s y el Gateway dejó de elegir la instancia caída, como máximo, a los +30.7 s. El primer error apareció entre +0.9 y +8.5 s: depende del azar de qué solicitudes caen en la instancia caída, y el comando `kill` mismo tarda 2–3 s en volver.
  - **No se exige cero errores en la interrupción** y no los hubo: 8–11 solicitudes de ~130 en la ventana (≈ 6–9 %), todas durante los primeros ~27 s, y ninguna antes ni después. Ninguna escritura entró en la prueba; las escrituras (`POST`/`PUT`/`PATCH`) no se reintentan y fallarían más a menudo en esa ventana (ver `docs/arquitectura.md`).
  - **`kill` frente a `stop`.** Con `stop` el servicio avisó a Eureka (`Unregistering ... with status DOWN`), el Gateway dejó de enviarle tráfico en ~5 s y no hubo errores; con `kill`, en cambio, hubo 8–11 errores y el Gateway tardó ~30 s. Pero **en el registro de Eureka la instancia detenida con `stop` siguió figurando `DOWN` hasta los +25.4 s** (la retiró la expiración del lease, no una baja inmediata). `docs/arquitectura.md` y el README dicen que la parada ordenada se da de baja de inmediato / en ~10 s; lo observado es que se **marca `DOWN` de inmediato** (suficiente para que el Gateway la deje de usar) pero **sale del registro a los ~25 s**. No se investigó la causa.
  - **Recuperación.** Al volver a arrancar, la instancia se registró sola: `UP` en Eureka a los 15–21 s del `start` (arranque de Spring Boot y primer latido) y recibiendo tráfico por el Gateway a los 19–28 s (compatible con hasta 5 s de consulta del registro más 5 s de la caché del LoadBalancer); después el reparto volvió a ser ~50/50.
  - **Limitaciones de la prueba.** El generador de carga es un script `bash` que lanza un proceso `curl` por solicitud; en Git Bash sobre Windows eso lo hace más lento que 5 solicitudes/s (se lograron entre ~3.5 y ~5 por segundo: 100 solicitudes en los primeros 20 s en la corrida 1, 69–71 en las otras). Eureka se sondea una vez por segundo, así que sus tiempos tienen ±1 s de resolución. Son tres corridas con `kill` en un solo equipo, sin repetir en otro entorno. El ritmo real de la carga (el script lo imprime al final) y el tiempo que tarda `docker compose kill` en volver dependen de la máquina: aquí tardó entre 1.8 y 3.0 s, pero en una corrida de verificación hecha durante la revisión del PR, en otra ejecución, tardó 11.4 s y Eureka retiró la instancia a +30.8 s desde el lanzamiento del comando. Por eso los tiempos de este informe son una referencia y no un valor fijo.

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
