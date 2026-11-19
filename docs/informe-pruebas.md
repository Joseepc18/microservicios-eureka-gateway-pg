# Informe de pruebas experimentales

Cada escenario debe ejecutarse y documentarse con evidencia funcional (salidas de comandos, registros, capturas de resultados). No se aceptan capturas de configuración como sustituto de una prueba funcional. Los fallos se provocan durante las pruebas y se explica el comportamiento observado, incluidos errores transitorios y tiempos de detección.

Responsable de cada escenario indicado entre paréntesis.

## Resumen

| Escenario | Responsable | Resultado | Evidencia |
|---|---|---|---|
| [P01 · Descubrimiento dinámico](#p01--descubrimiento-dinámico-integrante-1) | Integrante 1 | 4 instancias `UP` en Eureka, rutas `lb://` y solicitudes `200` por nombre lógico | [P01](evidencias/P01/) |
| [P02 · Balanceo HTTP](#p02--balanceo-http-integrante-1) | Integrante 1 | 100 de 100 solicitudes exitosas, repartidas 47 / 53 (300 solicitudes: 149 / 151) | [P02](evidencias/P02/) |
| [P03 · Fallo de instancia](#p03--fallo-de-instancia-integrante-1) | Integrante 1 | Con `kill`: 8 a 11 errores `500` de ~130 solicitudes en los ~27 s siguientes y ninguno después; con `stop`: 0 errores. La instancia vuelve en 15 a 21 s | [P03](evidencias/P03/) |
| [P04 · Comunicación entre servicios](#p04--comunicación-entre-servicios-integrante-3) | Integrante 3 | 16 de 16 comprobaciones: pedido `201`, errores `404`, `409` y `503`, y recuperación | [P04](evidencias/P04/) |
| [P05 · Enrutamiento de base de datos](#p05--enrutamiento-de-base-de-datos-integrante-2) | Integrante 2 | 4 de 4 escrituras al primario; 10 lecturas repartidas 5 y 5 entre las réplicas | [P05](evidencias/P05/) |
| [P06 · Fallo de réplica](#p06--fallo-de-réplica-postgresql-integrante-2) | Integrante 2 | 30 de 30 lecturas `200` con `replica1` detenida; excluida a los 6 s | [P06](evidencias/P06/) |
| [P07 · Consistencia de lectura](#p07--consistencia-de-lectura-integrante-2) | Integrante 2 | 20 de 20 lecturas críticas desde el primario; con la réplica pausada, la ordinaria no ve el dato y la crítica sí | [P07](evidencias/P07/) |
| [P08 · Recuperación de réplica](#p08--recuperación-de-réplica-integrante-2) | Integrante 2 | 5 de 5 pedidos recuperados en `replica1`; reincorporada a la rotación a los 12 s | [P08](evidencias/P08/) |

Todos los escenarios se ejecutaron sobre el sistema real en Docker Compose. Las limitaciones observadas y las mejoras posibles están al final del informe.

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
  - **`kill` frente a `stop`.** Con `stop` el servicio avisó a Eureka (`Unregistering ... with status DOWN`), el Gateway dejó de enviarle tráfico en ~5 s y no hubo errores; con `kill`, en cambio, hubo 8–11 errores y el Gateway tardó ~30 s. Pero **en el registro de Eureka la instancia detenida con `stop` siguió figurando `DOWN` hasta los +25.4 s** (la retiró la expiración del lease, no una baja inmediata). `docs/arquitectura.md` y el README decían que la parada ordenada se daba de baja de inmediato / en ~10 s; esta prueba lo corrigió: la instancia se **marca `DOWN` de inmediato** (suficiente para que el Gateway la deje de usar) pero **sale del registro a los ~25 s**. No se investigó la causa.
  - **Recuperación.** Al volver a arrancar, la instancia se registró sola: `UP` en Eureka a los 15–21 s del `start` (arranque de Spring Boot y primer latido) y recibiendo tráfico por el Gateway a los 19–28 s (compatible con hasta 5 s de consulta del registro más 5 s de la caché del LoadBalancer); después el reparto volvió a ser ~50/50.
  - **Limitaciones de la prueba.** El generador de carga es un script `bash` que lanza un proceso `curl` por solicitud; en Git Bash sobre Windows eso lo hace más lento que 5 solicitudes/s (se lograron entre ~3.5 y ~5 por segundo: 100 solicitudes en los primeros 20 s en la corrida 1, 69–71 en las otras). Eureka se sondea una vez por segundo, así que sus tiempos tienen ±1 s de resolución. Son tres corridas con `kill` en un solo equipo, sin repetir en otro entorno. El ritmo real de la carga (el script lo imprime al final) y el tiempo que tarda `docker compose kill` en volver dependen de la máquina: aquí tardó entre 1.8 y 3.0 s, pero en una corrida de verificación hecha durante la revisión del PR, en otra ejecución, tardó 11.4 s y Eureka retiró la instancia a +30.8 s desde el lanzamiento del comando. Por eso los tiempos de este informe son una referencia y no un valor fijo.

## P04 · Comunicación entre servicios (Integrante 3)

- **Objetivo:** ejecutar una operación de negocio que requiera una llamada real de un microservicio a otro mediante descubrimiento dinámico.
- **Procedimiento:** con el sistema completo levantado, ejecutar `scripts/p04-comunicacion.sh`. La operación es crear un pedido: `servicio-pedidos` consulta el producto en `servicio-productos` (`GET /productos/{id}`) y descuenta su stock (`PATCH /productos/{id}/stock`) llamándolo por su nombre lógico de Eureka (`http://servicio-productos`), sin direcciones fijas. El script:
  1. comprueba que los servicios están `healthy` y que Eureka tiene registradas las dos instancias de `servicio-productos`;
  2. **caso correcto:** crea un producto (precio 20.00, stock 10) y un pedido de 3 unidades por el Gateway con `X-Request-Id: p04-ok-1`; comprueba el total y el stock restante, y sigue ese identificador en los logs de ambos servicios;
  3. **producto inexistente:** pide el producto 999999;
  4. **stock insuficiente:** pide 5 unidades de un producto con stock 2;
  5. **`servicio-productos` detenido:** ejecuta `docker compose stop servicio-productos-1 servicio-productos-2` y crea un pedido inmediatamente y otro 15 s después, con Eureka ya sin instancias; luego consulta `GET /api/productos` por el Gateway;
  6. vuelve a iniciar las instancias, espera su registro en Eureka y crea otro pedido.

  En cada caso de error comprueba además que no se guardó ningún pedido (`SELECT count(*) FROM pedido`).
- **Resultado esperado:** el caso correcto responde `201`, descuenta el stock en `servicio-productos` y deja el mismo `X-Request-Id` en los logs de ambos servicios. Los errores responden `404`, `409` y `503` con un mensaje claro y sin crear pedidos. Tras restaurar las instancias, el sistema vuelve a crear pedidos.
- **Resultado obtenido:** ejecución del 2026-11-19 a las 03:36 UTC; las 16 comprobaciones del script resultaron correctas (`P04 OK`).
  - **Descubrimiento:** Eureka listó `api-gateway:gateway-1`, `servicio-pedidos:pedidos-1`, `servicio-productos:productos-1` y `servicio-productos:productos-2`.
  - **Caso correcto:** `POST /api/pedidos` respondió `201` con total `60.00` (3 × 20.00) y el stock del producto pasó de 10 a 7. El `GET` del pedido respondió `200`. En los logs, `p04-ok-1` aparece en `servicio-pedidos` y en `servicio-productos`: el `GET /productos/6` lo atendió la instancia `productos-2` y el `PATCH /productos/6/stock` la instancia `productos-1`, es decir, el LoadBalancer eligió una instancia distinta para cada llamada.
  - **Producto inexistente:** `404` con `No existe el producto con id 999999`; el número de pedidos no cambió.
  - **Stock insuficiente:** `409` con `Stock insuficiente para el producto 7 (solicitado: 5)`; no se creó el pedido y el stock siguió en 2.
  - **`servicio-productos` detenido:** ambas solicitudes respondieron `503`. La inmediata tardó 2,06 s y dijo `sin respuesta (timeout o error de conexión)`; la de 15 s después respondió en 0,03 s con `no hay instancias registradas en Eureka` (Eureka ya tenía 0 instancias). `GET /api/productos` por el Gateway también respondió `503`. `servicio-pedidos` siguió `healthy`.
  - **Recuperación:** 63 s después del `start` las dos instancias estaban `healthy` y registradas en Eureka, y el siguiente pedido respondió `201` al primer intento.

  ```
  p04-ok-1 servicio-productos-2  REQ method=GET   path=/productos/6        status=200
  p04-ok-1 servicio-productos-1  REQ method=PATCH path=/productos/6/stock  status=200
  p04-ok-1 servicio-pedidos-1    Pedido 1 creado: productoId=6 cantidad=3 total=60.00
  p04-ok-1 servicio-pedidos-1    REQ method=POST path=/pedidos status=201
  p04-503  servicio-pedidos-1    No servers available for service: servicio-productos
  ```
- **Evidencia:** [salida completa](evidencias/P04/salida.txt) y [trazas de ambos servicios por X-Request-Id](evidencias/P04/trazas.txt).
- **Análisis:** `servicio-pedidos` no conoce ninguna dirección de `servicio-productos`: su cliente HTTP usa el nombre lógico y Spring Cloud LoadBalancer elige una instancia del registro de Eureka en cada llamada (round-robin, con caché de 5 s). Por eso las dos llamadas de un mismo pedido pueden ir a instancias distintas. El `X-Request-Id` que llega por el Gateway se conserva en `servicio-pedidos`, se reenvía a `servicio-productos` y aparece en cada línea de log de ambos, lo que permite reconstruir la operación completa. Los errores se traducen según su origen: `404` y `409` los decide `servicio-productos` y `servicio-pedidos` los reenvía con el mismo código; `503` lo decide `servicio-pedidos` cuando no consigue hablar con `servicio-productos`. La diferencia de tiempos del `503` muestra la ventana de detección de la caída: inmediatamente después de detener las instancias, el LoadBalancer aún puede conservarlas en su caché (5 s) y la llamada falla tras unos 2 s, coherente con el timeout de conexión configurado (2 s); cuando el registro ya no las tiene, responde al instante. Un `PATCH` de stock no se reintenta, para no descontar dos veces, de modo que el cliente debe reintentar el pedido. El script espera 2 s tras crear un producto porque `GET /productos/{id}` se atiende desde una réplica y una réplica con retraso podría no tenerlo todavía; la descripción de esta limitación está en `docs/api.md`.

## P05 · Enrutamiento de base de datos (Integrante 2)

- **Objetivo:** demostrar que las escrituras usan el primario y las consultas ordinarias se distribuyen entre las dos réplicas.
- **Procedimiento:** con el sistema levantado, ejecutar `scripts/p05-enrutamiento.sh`. El script:
  1. muestra el rol de cada nodo (`pg_is_in_recovery()`) y el estado de `pg_stat_replication`;
  2. crea un producto y 4 pedidos por el Gateway (`POST /api/pedidos`), que son escrituras;
  3. hace 10 lecturas ordinarias `GET /api/pedidos` (`@Transactional(readOnly = true)`);
  4. identifica el nodo de cada solicitud por su `X-Request-Id` en las líneas `DB route` del log de `servicio-pedidos`;
  5. consulta en cada nodo los pedidos creados y las conexiones abiertas del rol `pedidos_app`.
- **Resultado esperado:** las 4 escrituras en `PRIMARY node=primary tx=read-write`; las 10 lecturas en `REPLICA`, alternando `replica1` y `replica2` (5 y 5); los pedidos presentes en los tres nodos.
- **Resultado obtenido:** ejecución del 2026-11-18 a las 23:39:49 UTC.
  - Las 4 escrituras respondieron `201` y las atendió el primario (`DB route=PRIMARY node=primary tx=read-write`).
  - Las 10 lecturas respondieron `200` en 11-14 ms. Se alternaron en orden: `replica1`, `replica2`, `replica1`... (5 y 5).
  - Consultas SQL: los 4 pedidos están en `primary`, `replica1` y `replica2`. `pg_is_in_recovery()` es `f` en el primario y `t` en las réplicas. Cada nodo tiene abiertas 5 conexiones de `pedidos_app`: el módulo crea un pool de HikariCP por nodo, con `maximum-pool-size` = 5, y HikariCP las mantiene abiertas porque `minimum-idle` toma ese mismo valor.

  ```
  p05-escritura-1 -> DB route=PRIMARY node=primary tx=read-write
  p05-lectura-1 -> DB route=REPLICA node=replica1 tx=read-only
  p05-lectura-2 -> DB route=REPLICA node=replica2 tx=read-only
  p05-lectura-3 -> DB route=REPLICA node=replica1 tx=read-only
  ...
  Escrituras atendidas por el primario: 4 de 4
  Lecturas ordinarias: replica1=5 replica2=5 (total 10)
  ```
- **Evidencia:** [salida completa](evidencias/P05/salida.txt) y [log de enrutamiento de servicio-pedidos](evidencias/P05/logs-servicio-pedidos.txt).
- **Análisis:** el servicio no elige el nodo; solo marca sus métodos como `@Transactional` o `@Transactional(readOnly = true)`. La decisión la toma `persistence-routing`: `ReadWriteRoutingDataSource` (un `AbstractRoutingDataSource`) envía las escrituras al primario y las lecturas a `ReplicaPoolDataSource`, que aplica round-robin entre las réplicas disponibles. Cada transacción usa un solo nodo de principio a fin. El log también muestra líneas `DB route=PRIMARY ... tx=none`: son el health check de Actuator, que comprueba solo el primario.

## P06 · Fallo de réplica PostgreSQL (Integrante 2)

- **Objetivo:** detener una réplica y comprobar que las consultas continúan mediante la réplica restante.
- **Procedimiento:** ejecutar `scripts/p06-fallo-replica.sh`. El script:
  1. comprueba que las dos réplicas están en la rotación (4 lecturas previas);
  2. ejecuta `docker compose stop replica1` y muestra `docker compose ps -a`;
  3. hace 30 lecturas `GET /api/pedidos`, una por segundo, y registra el código, el tiempo y el nodo de cada una;
  4. extrae los registros `DB health` y consulta el primario y `replica2` (`pg_stat_replication`, `pg_replication_slots`).

  Deja `replica1` detenida para P08.
- **Resultado esperado:** ninguna lectura falla; tras la caída, todas las lecturas van a `replica2`; el módulo registra la exclusión de `replica1`.
- **Resultado obtenido:** ejecución del 2026-11-18 a las 23:40:11 UTC.
  - `replica1` quedó `Exited (0)` a las 23:40:14.
  - Las **30 lecturas respondieron `200`, sin errores**, y todas las atendió `replica2`.
  - La primera lectura a la que le tocaba `replica1` (`p06-durante-2`) tardó 3,0 s, el `connection-timeout` del pool. En ese momento el módulo excluyó la réplica y atendió la lectura en `replica2`. Las demás tardaron 10-39 ms.
  - El primario y `replica2` siguieron respondiendo consultas SQL. `pg_stat_replication` solo mostró `replica2` y el slot `replica1` quedó `active = f`.

  ```
  23:40:13 docker compose stop replica1
  23:40:18 DB health node=replica1 verificación fallida 1/2: sin conexión: PSQLException: This connection has been closed.
  23:40:19 DB health node=replica1 estado=DOWN motivo="fallo al obtener conexión: UnknownHostException: replica1" -> excluida de la rotación; rotación=[replica2]
  Lecturas con replica1 detenida: 30 correctas, 0 con error; atendidas por replica1: 0
  ```
- **Evidencia:** [salida completa](evidencias/P06/salida.txt) y [log de enrutamiento](evidencias/P06/logs-servicio-pedidos.txt).
- **Análisis:** la caída se detecta por dos vías:
  - **Verificación periódica:** `ReplicaHealthMonitor` comprueba cada 5 s que cada réplica responde, sigue en recuperación y tiene un retraso por debajo de 10 s. Marca la réplica como caída tras 2 fallos seguidos.
  - **Exclusión inmediata:** cuando una lectura no consigue conexión, la réplica sale de la rotación en el acto y la lectura se atiende en otra.

  Aquí actuó primero la segunda, 6 s después del `stop`. El único efecto visible fue una lectura de 3 s, sin error. Si la réplica cae a mitad de una lectura (`FATAL 57P01` en la parada rápida), la transacción de solo lectura se reintenta en otra réplica (`DB retry`). Sin ninguna réplica disponible, las lecturas se degradarían al primario.

## P07 · Consistencia de lectura (Integrante 2)

- **Objetivo:** crear un registro y consultarlo inmediatamente mediante una operación crítica dirigida al primario.
- **Procedimiento:** ejecutar `scripts/p07-consistencia.sh`.
  1. **Escritura y lectura crítica, 20 veces:** crear un pedido (`POST /api/pedidos`) y consultarlo en ese mismo instante con `GET /api/pedidos/{id}`. Esa consulta es la operación crítica de `servicio-pedidos` (`PedidoService.obtener`, con `@ReadFromPrimary`).
  2. **Contraste:** pausar el replay de WAL en las réplicas en marcha (`pg_wal_replay_pause()`) para provocar retraso. Crear un pedido y consultarlo con la lectura crítica y con la ordinaria (`GET /api/pedidos`, en réplica). Después, reanudar el replay (`pg_wal_replay_resume()`) y repetir la lectura ordinaria.

  Se ejecutó después de P06, con `replica1` detenida, así que el replay se pausó solo en `replica2`.
- **Resultado esperado:** la lectura crítica encuentra el pedido las 20 veces y la atiende el primario (`read=critical`). En el contraste, la lectura ordinaria no ve el pedido hasta que la réplica aplica el WAL; la crítica sí lo ve.
- **Resultado obtenido:** ejecución del 2026-11-18 a las 23:41:17 UTC.
  - **Lecturas críticas:** 20 de 20 respondieron `200` con el pedido recién creado. Las 20 las atendió `DB route=PRIMARY node=primary tx=read-only read=critical`.
  - **Contraste con `replica2` pausada:** el pedido 50 se creó a las 23:41:58.
    - La lectura crítica lo devolvió (`200`, en el primario).
    - La lectura ordinaria, atendida por `replica2`, **no lo incluía**. Por SQL, el primario tenía el pedido (`count = 1`) y `replica2` no (`count = 0`).
    - Al reanudar el replay, la misma lectura ordinaria ya lo incluía.

  ```
  p07-crear-1 -> DB route=PRIMARY node=primary tx=read-write | p07-leer-1 -> DB route=PRIMARY node=primary tx=read-only read=critical
  23:41:58 GET /api/pedidos/50 (crítica) [p07-contraste-critica] -> 200
  23:41:58 GET /api/pedidos (ordinaria) [p07-contraste-ordinaria] -> 200, incluye el pedido 50: no
  23:42:01 GET /api/pedidos (ordinaria) [p07-contraste-despues] -> 200, incluye el pedido 50: si
  ```
- **Evidencia:** [salida completa](evidencias/P07/salida.txt) y [log de enrutamiento](evidencias/P07/logs-servicio-pedidos.txt).
- **Análisis:** la replicación es asíncrona, así que una réplica puede no tener todavía un dato recién escrito; el contraste lo muestra. **Política aplicada:**
  - **Lecturas ordinarias:** aceptan consistencia eventual. Las réplicas con más de 10 s de retraso (`max-lag`) salen de la rotación, así que ese retraso queda acotado.
  - **Lecturas que deben ver una escritura anterior:** se declaran críticas y van al primario, que siempre tiene el dato. En el sistema, la única es consultar un pedido por id, que es lo que hace un cliente justo después de crearlo.

  La marca `@ReadFromPrimary` se aplica antes de que la transacción obtenga su conexión, y el log la distingue con `read=critical`.

## P08 · Recuperación de réplica (Integrante 2)

- **Objetivo:** reiniciar la réplica detenida, comprobar su recuperación y verificar que vuelve a participar en las consultas.
- **Procedimiento:** ejecutar `scripts/p08-recuperacion.sh`. Si `replica1` está en marcha, el script la detiene primero. Después:
  1. con `replica1` detenida, crea 5 pedidos y muestra el WAL que retiene su slot en el primario;
  2. ejecuta `docker compose start replica1` y hace lecturas hasta que `replica1` atiende una;
  3. consulta `pg_stat_replication`, `pg_replication_slots` y, en `replica1`, el estado del receptor de WAL y los 5 pedidos;
  4. hace 10 lecturas más y extrae los registros `DB health`.
- **Resultado esperado:** `replica1` recibe por streaming el WAL que se generó mientras estaba detenida (incluidos los 5 pedidos). El módulo la reincorpora solo cuando responde, está en recuperación y su retraso es aceptable. Después vuelve a recibir lecturas, alternándose con `replica2`.
- **Resultado obtenido:** ejecución del 2026-11-18 a las 23:42:07 UTC.
  - Con `replica1` detenida se crearon los pedidos 51-55. Su slot estaba `active = f`, `wal_status = reserved`, con 22 kB de WAL retenido.
  - `docker compose start replica1` a las 23:42:11.
  - A las 23:42:23, **12 s después**, el módulo registró `estado=UP recovery=true lag=0.0s -> reincorporada a la rotación`. Hasta entonces, las lecturas las atendió `replica2`.
  - `replica1` atendió de nuevo una lectura a los 16 s.
  - `pg_stat_replication` mostró las dos réplicas en `streaming`, con `replay_lsn` igual a `sent_lsn`. Los dos slots quedaron `active = t` sin WAL retenido.
  - En `replica1`: `pg_is_in_recovery() = t`, receptor de WAL en `streaming` y **los 5 pedidos presentes**.
  - Las 10 lecturas posteriores se repartieron 5 y 5.

  ```
  23:42:11 docker compose start replica1
  23:42:23 DB health node=replica1 estado=UP recovery=true lag=0.0s -> reincorporada a la rotación; rotación=[replica1, replica2]
   application_name |   state   | sent_lsn  | replay_lsn
   replica1         | streaming | 0/6E1A198 | 0/6E1A198
   replica2         | streaming | 0/6E1A198 | 0/6E1A198
  Pedidos creados con replica1 caída presentes en replica1: 5 de 5
  ```
- **Evidencia:** [salida completa](evidencias/P08/salida.txt) y [log de enrutamiento](evidencias/P08/logs-servicio-pedidos.txt).
- **Análisis:** la réplica se pone al día sola gracias a su slot de replicación. El primario conserva exactamente el WAL que la réplica no confirmó, y al volver lo recibe por streaming. La reincorporación es responsabilidad del módulo, no de Docker: `ReplicaHealthMonitor` solo devuelve la réplica a la rotación tras una verificación apta (conexión, `pg_is_in_recovery() = true` y retraso bajo `max-lag`). Por eso no recibe lecturas mientras arranca. Los 12 s se explican así: el arranque de PostgreSQL, el intervalo de verificación de 5 s y la caché de DNS de Docker. Al volver, el nombre `replica1` se resuelve de nuevo cuando el contenedor tiene red.

## Limitaciones observadas

### Descubrimiento y balanceo (P01-P03)

- **Errores transitorios al caer una instancia sin avisar (P03, `kill`):** 8 a 11 solicitudes `500` de ~130 (6 a 9 %) en los ~27 s siguientes al fallo, y ninguna antes ni después. Cada una tarda ~6,2 s, porque agota tres intentos de 2 s contra la instancia caída. El reintento de los `GET` rescató entre el 78 % y el 85 % de las solicitudes que primero cayeron en ella.
- **Ventana de detección:** Eureka retiró la instancia a los 24,5 a 27,4 s, y el Gateway siguió eligiéndola hasta ~30,7 s.
- **Las escrituras no se reintentan:** P03 solo usó `GET`. Un `POST`, `PUT` o `PATCH` que caiga en la instancia caída falla y el cliente debe reintentarlo (ver [arquitectura](arquitectura.md#gateway-rutas-y-balanceo)).
- **Parada ordenada y registro:** con `docker compose stop` la instancia queda `DOWN` a los ~2 s y el Gateway deja de usarla, pero Eureka la retira del registro más tarde: a los ~25 s en P03 y antes de 21 s en P04 (con las dos instancias detenidas). No se investigó la causa.
- **Recuperación:** una instancia que vuelve tarda 15 a 21 s en figurar `UP` y 19 a 28 s en recibir tráfico.
- **Reparto no estrictamente igual:** 47 / 53 con 100 solicitudes (converge a 49,7 / 50,3 con 300). No se investigó la causa.
- **Autopreservación de Eureka desactivada:** se hizo solo para el laboratorio, para que el registro no conserve instancias muertas durante las demostraciones. En un despliegue real se mantendría activa.
- **Alcance de las mediciones:** una sola máquina, con un generador de carga en `bash` sobre Git Bash en Windows que logra ~3,5 a 5 solicitudes por segundo. Los tiempos son una referencia y no un valor fijo.

### Comunicación entre servicios (P04)

- **Sin transacciones distribuidas:** `servicio-pedidos` descuenta el stock en `servicio-productos` antes de guardar el pedido en su propia base. Si el guardado falla después de descontar, el stock queda descontado sin pedido (se registra un `ERROR` y la corrección es manual). Si el `PATCH` de stock agota el tiempo, el resultado es incierto: `servicio-productos` pudo descontar y `servicio-pedidos` responde `503` sin crear el pedido. P04 no provocó estos fallos; el comportamiento esperado está en [docs/api.md](api.md#limitación-sin-transacciones-distribuidas).
- **Cancelar no repone stock:** `servicio-productos` solo ofrece una operación para descontar.
- **Verificación previa con datos atrasados:** `servicio-pedidos` consulta el producto con un `GET` que atiende una réplica. Si está atrasada, podría rechazar un pedido que sí tenía stock, o no ver un producto recién creado (por eso el script de P04 espera 2 s tras crearlo). El `PATCH` de stock va al primario y es la validación definitiva, así que no se vende más de lo que hay.
- **`503` lento al principio:** justo después de detener `servicio-productos`, el LoadBalancer aún conserva sus instancias y la respuesta tarda ~2 s (2,06 s en P04); cuando el registro ya no las tiene, responde al instante. El `PATCH` de stock no se reintenta, así que el cliente debe reintentar el pedido.

### Generales

- **Sin alta disponibilidad:** Eureka, el Gateway y `servicio-pedidos` tienen una sola instancia, y PostgreSQL tiene un único primario. Si cae cualquiera de ellos, el sistema pierde esa función.
- **Sin seguridad:** el Gateway no autentica y no hay TLS. Es un laboratorio, no un despliegue de producción.
- **Sin integración continua:** el repositorio no ejecuta las verificaciones automáticamente (`./mvnw verify`, `docker compose config`); se ejecutan a mano antes de cada PR.

### Persistencia (P05-P08)

- **Retención de WAL:**
  - Mientras una réplica está detenida, su slot obliga al primario a conservar el WAL que no recibió (22 kB en P08, con 5 pedidos).
  - Con mucha actividad o una caída larga, el WAL crece en el disco del primario. Por eso se limita con `max_slot_wal_keep_size = 1GB`.
  - Si una réplica supera ese tope, su slot pasa a `lost` y hay que reconstruirla (ver [arquitectura](arquitectura.md#retención-de-wal-slots-de-replicación)).
- **Primera lectura tras una caída:** la lectura a la que le toca la réplica caída puede esperar hasta el `connection-timeout` del pool (3 s en P06) antes de excluirla. No falla, pero tarda.
- **Ventana de detección:** entre la caída y la exclusión pasan unos segundos (6 s en P06). Una réplica que vuelve tarda unos 12 s en reincorporarse (P08).
- **Lecturas ordinarias con datos atrasados:** una lectura ordinaria puede no ver una escritura reciente (contraste de P07). El retraso aceptado está acotado por `max-lag` (10 s). Las lecturas que no lo toleran deben ser críticas.
- **Réplica desconectada del primario:** si una réplica sigue en pie pero pierde el streaming, su retraso medido no crece y seguiría en la rotación con datos cada vez más antiguos. En este despliegue solo ocurre si cae el primario.
- **Sin failover:** si cae el primario, las escrituras y las lecturas críticas fallan. No hay promoción automática de una réplica (el enunciado no la exige).

## Mejoras posibles

| Mejora | Limitación que atiende |
|---|---|
| Circuit breaker y timeouts más cortos (por ejemplo, Resilience4j) en el Gateway y en `servicio-pedidos`, para no esperar ~6 s por una instancia caída | Errores transitorios y `503` lento |
| Clave de idempotencia en `POST /api/pedidos` y en el `PATCH` de stock, para poder reintentar sin descontar dos veces | Las escrituras no se reintentan |
| Saga u *outbox* con compensaciones: reponer el stock cuando falle el guardado del pedido o se cancele | Sin transacciones distribuidas; cancelar no repone stock |
| Operación en `servicio-productos` para devolver stock | Cancelar no repone stock |
| Permitir que la consulta de stock que hace `servicio-pedidos` lea del primario | Verificación previa con datos atrasados |
| Eureka en clúster y varias instancias del Gateway y de `servicio-pedidos` detrás de un balanceador | Sin alta disponibilidad |
| Failover automático de PostgreSQL (por ejemplo, Patroni o repmgr) y detección de una réplica que perdió el streaming | Sin failover; réplica desconectada del primario |
| Investigar por qué una instancia detenida con `stop` sigue ~25 s en el registro y ajustar la baja ordenada | Parada ordenada y registro |
| Trazas distribuidas y métricas (Micrometer Tracing con Zipkin, Prometheus) además de los logs con `X-Request-Id` | Alcance de las mediciones |
| Autenticación en el Gateway, TLS y gestión de secretos | Sin seguridad |
| GitHub Actions que ejecute `./mvnw verify` y `docker compose config` en cada PR, y pruebas de integración con Testcontainers | Sin integración continua |
