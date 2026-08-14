# Laboratorio 2: concurrencia en un almacén autónomo

## Equipo y distribución del trabajo

| Integrante                                       | Responsabilidad principal | Evidencia de aporte |
|--------------------------------------------------|---|---|
| Integrante 1 — **Juan sebastian Murcia Yanaquen** | Diagnóstico, inventario de estado compartido, anomalías, interleaving e invariantes | Secciones 1–4; probes sobre el commit base `c449528` |
| Integrante 2 — **Jhonatan Stiven Peña Mora**     | Regiones críticas, sincronización de cola/registro/estadísticas y terminación con `join()` | Cambios en `PackageQueue`, `DeliveryRegistry`, `WarehouseStatistics` y revisión de `WarehouseSimulation` |
| Integrante 3 — **Jhonatan**                      | Pausa/reanudación, pruebas, verificación, análisis arquitectónico y ADR | `SimulationControl`, `WarehouseRobot`, `WarehouseMain`, pruebas, secciones 6–8 y ADR-001 |

La integración y revisión final se realizó en equipo. Antes de entregar se deben reemplazar los tres marcadores **[Nombre]**.

## 1. Inventario del estado compartido

| Objeto compartido | Estado mutable | Lectores | Escritores | Invariante posible |
|---|---|---|---|---|
| `PackageQueue` | `List<Parcel> pending` | Robots por `takeNext()`; coordinador por `pendingCount()` | Robots por `takeNext()` | Una extracción exitosa elimina y retorna exactamente un paquete; ningún paquete se asigna dos veces. |
| `DeliveryRegistry` | `int nextPosition`; lista `deliveries` | Coordinador/verificador por `snapshot()` | Robots por `register()` | Los paquetes y posiciones son únicos; las posiciones forman `1..N`; `nextPosition == N + 1`. |
| `WarehouseStatistics` | `processedParcels`; `totalProcessingMillis` | Coordinador por getters | Robots por `recordProcessed()` | Cada entrega incrementa y acumula exactamente una vez; en un punto quiescente, contador y registro coinciden. |
| `SimulationControl` | `paused`; `activeWorkers`; `pausedWorkers` | Robots y coordinador | Robots al llegar/terminar; coordinador al pausar/reanudar | Una pausa confirmada significa que todo robot activo está bloqueado en el punto seguro, sin espera activa. |

## 2. Anomalías observadas en la versión inicial

### Procedencia

La evidencia se tomó antes de modificar el código, desde el commit base `c449528`, compilado con Java 21. Una ejecución correcta aislada no se consideró prueba de seguridad.

### Evidencia 1: reporte final prematuro

Comando:

```text
java -cp <classes-c449528> edu.eci.arsw.warehouse.app.WarehouseMain 32 500
```

Salida relevante:

```text
--- STARTER REPORT (intentionally premature) ---
Initial parcels : 500
Pending parcels : 411
Processed count : 55
Registry size   : 59
```

Clase/método sospechoso: `WarehouseMain.main()`.

La espera fija de 60 ms no demuestra que los robots terminaron. El coordinador imprimió un supuesto reporte final con 411 paquetes pendientes y, además, alcanzó a leer registro y contador entre dos actualizaciones relacionadas.

### Evidencia 2: paquetes duplicados, perdidos y excepción de índice

Comando:

```text
java -cp <classes-c449528> edu.eci.arsw.warehouse.verification.RaceConditionProbe 10 8 100
```

Ejecución 1:

```text
Run 01 -> RACE/ANOMALY | pending=0, processedCounter=96,
registry=100, uniqueParcels=96, uniquePositions=95,
positionsContiguous=false
[warehouse-robot-7] Queue anomaly: IndexOutOfBoundsException
```

Clase/método sospechoso: `PackageQueue.takeNext()`.

Los 100 registros contienen apenas 96 identificadores distintos: hubo duplicados y otros paquetes desaparecieron. Otro hilo podía modificar el `ArrayList` entre `isEmpty()`, `get(0)` y `remove(0)`.

### Evidencia 3: posiciones duplicadas y no contiguas

En la ejecución 4 del comando anterior:

```text
Run 04 -> RACE/ANOMALY | pending=0, processedCounter=94,
registry=100, uniqueParcels=92, uniquePositions=90,
positionsContiguous=false
```

Clase/método sospechoso: `DeliveryRegistry.register()`.

Dos robots podían leer el mismo `nextPosition`. Al separar la reserva, el incremento y la inserción, el registro produjo 100 entradas pero solo 90 posiciones distintas.

### Evidencia 4: incrementos perdidos

Comando y ejecución 1:

```text
java -cp <classes-c449528> edu.eci.arsw.warehouse.verification.RaceConditionProbe 10 16 250
Run 01 -> RACE/ANOMALY | pending=0, processedCounter=224,
registry=251, uniqueParcels=222, uniquePositions=221,
positionsContiguous=false
```

Clase/método sospechoso: `WarehouseStatistics.recordProcessed()`.

Dos robots podían leer el mismo contador y escribir ambos `valor + 1`; uno de los incrementos se perdía. El mismo patrón afectaba la suma de tiempos.

Reproducibilidad inicial:

| Robots | Paquetes | Ejecuciones | Anomalías antes |
|---:|---:|---:|---:|
| 8 | 100 | 10 | 10 |
| 16 | 250 | 10 | 10 |
| 32 | 500 | 10 | 10 |

## 3. Análisis de interleaving

Estado inicial: `pending = [P1, P2]`.

| Paso | Robot A | Robot B | Estado compartido |
|---:|---|---|---|
| 1 | `isEmpty()` da `false` | — | `[P1, P2]` |
| 2 | Guarda `pending.get(0) = P1` y cede | — | `[P1, P2]` |
| 3 | — | `isEmpty()` da `false` | `[P1, P2]` |
| 4 | — | También guarda `pending.get(0) = P1` | `[P1, P2]` |
| 5 | Elimina el índice 0 y retorna su referencia `P1` | — | `[P2]` |
| 6 | — | Elimina el índice 0 (`P2`), pero retorna su referencia guardada `P1` | `[]` |
| 7 | Procesa `P1` | Procesa `P1` | Dos entregas de `P1` |
| 8 | Obtiene `null` | Obtiene `null` | `P2` desapareció |

El resultado depende del planificador porque no había exclusión mutua ni una relación *happens-before* que mantuviera unidas selección y eliminación. Si A completa ambas antes de que B seleccione, reciben `P1` y `P2`; con el interleaving anterior, ambos reciben `P1`.

## 4. Invariantes del sistema

### Evaluación de candidatos

| Candidato | Clasificación | Justificación |
|---|---|---|
| Cada paquete se procesa como máximo una vez | Requerido | Evita entregas duplicadas. |
| Ningún paquete desaparece | Incompleto | Debe considerar también los paquetes en proceso. |
| Las posiciones son únicas | Requerido | Dos entregas no pueden tener el mismo puesto. |
| Las posiciones forman `1..N` | Derivado | Se desprende de reservar desde 1, de forma atómica y sin saltos. |
| Contador y registro coinciden | Incompleto | Es obligatorio en puntos quiescentes; durante una transacción de robot puede existir un estado intermedio. |
| Al reportar finalización no quedan pendientes | Requerido | Debe comprobarse después de `join()` para todos los robots. |

### Conjunto final

```text
I1. Los paquetes iniciales están particionados entre pendientes, en proceso y
    entregados; los conjuntos son disjuntos y su unión es el conjunto inicial.

I2. Cada takeNext() exitoso elimina y retorna exactamente un paquete, y ninguna
    otra llamada puede retornar el mismo paquete.

I3. El registro contiene paquetes y posiciones únicos; las posiciones son
    exactamente 1..|deliveries| y nextPosition == |deliveries| + 1.

I4. Cada entrega incrementa processedParcels y acumula su duración exactamente
    una vez, sin actualizaciones perdidas.

I5. En una pausa confirmada o al terminar tras join():
    pending + deliveries == initialParcels,
    processedParcels == deliveries,
    y ningún robot modifica el estado observado.

I6. Al finalizar: pending == 0 y
    deliveries == processedParcels == initialParcels.

I7. Una pausa confirmada implica que todos los robots activos están bloqueados
    en un punto seguro sin espera activa; resume() los despierta a todos.
```

## 5. Regiones críticas y decisiones de sincronización

| Clase | Región crítica | Invariante | Mecanismo | Razón de la granularidad |
|---|---|---|---|---|
| `PackageQueue` | Comprobación de vacío + eliminación; lectura de tamaño | I1, I2 | `synchronized (pending)` | Solo serializa el acceso a la lista; el procesamiento del paquete ocurre fuera. |
| `DeliveryRegistry` | Reserva de posición + inserción; copia de la lista | I3 | `synchronized (deliveries)` | La posición y su registro son una unidad lógica; el resto del robot no requiere el bloqueo. |
| `WarehouseStatistics` | Incremento del contador + suma de duración; getters | I4 | Monitor privado `lock` | Los dos acumuladores se actualizan como un estado coherente sin bloquear cola o registro. |
| `SimulationControl` | Estado de pausa y contadores de la barrera | I5, I7 | Métodos `synchronized`, `wait()`, `notifyAll()` | Es un monitor independiente dedicado a coordinación, no un bloqueo global de negocio. |

No se sincronizó todo el ciclo del robot ni se usó un bloqueo global. Una región innecesariamente grande serializaría procesamiento que puede ejecutarse en paralelo, aumentaría la contención y el tiempo de espera, reduciría el throughput y elevaría el riesgo de interbloqueos futuros.

## 6. Terminación y coordinación PAUSE/RESUME

### Terminación con `join()`

`WarehouseSimulation.awaitCompletion()` recorre todos los `WarehouseRobot` y ejecuta `join()`. `WarehouseMain` ahora llama ese método y luego imprime exactamente un reporte final.

`Thread.sleep(...)` no sustituye a `join()`: el tiempo de ejecución depende de la carga, el planificador y el hardware. Dormir puede ser insuficiente o desperdiciar tiempo. `join()` expresa la condición correcta y crea la visibilidad de memoria necesaria entre la terminación del trabajador y el coordinador.

### Pausa sin espera activa

`SimulationControl` usa un monitor común:

1. `pause()` establece la solicitud y espera en un `while` hasta que `pausedWorkers == activeWorkers`.
2. Cada robot llama `awaitIfPaused()` solo al inicio de una iteración, después de completar registro y estadísticas del paquete anterior.
3. Si hay pausa, el robot incrementa `pausedWorkers`, notifica al coordinador y ejecuta `wait()` dentro de un `while`.
4. `resume()` limpia la bandera y usa un solo `notifyAll()` para despertar a todos.
5. Un robot que termina ejecuta `workerFinished()` en `finally`, reduciendo `activeWorkers` para que la barrera no espere hilos ya terminados.

El `while` protege contra despertares espurios. Los hilos que esperan liberan el monitor y no consumen CPU.

### Consistencia de la instantánea pausada

Cuando `pause()` retorna, cada robot activo ya alcanzó el punto seguro o terminó. Ese punto está después de la transacción completa del paquete (`register` + `recordProcessed`) y antes de extraer el siguiente. Por tanto, no hay robots modificando cola, registro ni estadísticas. Los getters de cada objeto adquieren su monitor y observan sus últimas escrituras. La barrera permite comparar de forma coherente los valores de objetos que usan monitores distintos.

Una prueba toma una instantánea, espera 100 ms durante la pausa y toma otra: pendientes, contador y entregas permanecen iguales. Luego `resume()` permite terminar y el verificador acepta el estado final.

## 7. Verificación

### Pruebas automatizadas

Se agregó `ConcurrencyBehaviorTest` con:

- diez simulaciones concurrentes de 32 robots y 500 paquetes;
- una prueba de estabilidad de la instantánea pausada y terminación posterior.

La compilación de las 15 clases productivas con Java 21 fue correcta. En este entorno aislado, Maven no pudo cerrar algunos JAR de JUnit debido a un `AccessDeniedException` de `zipfs`; por ello la validación ejecutada aquí se completó con las sondas Java y las pruebas de comportamiento equivalentes. En una estación normal, el comando de entrega es:

```text
mvn clean test
```

### Sondas repetidas después de la corrección

| Robots | Paquetes | Runs antes | Anomalías antes | Runs después | Anomalías después |
|---:|---:|---:|---:|---:|---:|
| 8 | 100 | 10 | 10 | 30 | 0 |
| 16 | 250 | 10 | 10 | 30 | 0 |
| 32 | 500 | 10 | 10 | 100 | 0 |

Comando objetivo y resultado:

```text
java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 100 32 500

Anomalous runs: 0/100
```

Demostración de pausa:

```text
--- PAUSED SNAPSHOT ---
Initial parcels : 180
Pending parcels : 56
Processed count : 124
Registry size   : 124
Simulation paused = true

--- FINAL SNAPSHOT ---
Initial parcels : 180
Pending parcels : 0
Processed count : 180
Registry size   : 180
```

## 8. Análisis arquitectónico y atributos de calidad

### Decisión principal

**Problema.** Operaciones lógicas compuestas sobre estado mutable eran interrumpibles y perdían actualizaciones.

**Invariantes.** Extracción única, posiciones únicas/contiguas, acumuladores exactos y estados quiescentes coherentes.

**Alternativas consideradas.** Un bloqueo global, sincronizar todos los métodos públicos, `AtomicInteger`, colecciones concurrentes, `Lock/Condition` y `BlockingQueue`.

**Decisión.** Monitores independientes por objeto, con regiones críticas que coinciden con cada operación lógica, y un monitor de coordinación separado para la pausa.

**Consecuencias.** Se conserva el paralelismo del procesamiento, pero las extracciones, registros y acumulaciones se serializan brevemente. La barrera de pausa añade contadores y protocolo, a cambio de una semántica verificable.

### Impacto

- **Correctitud/confiabilidad:** las transiciones tienen puntos de linealización claros; `join()` y la barrera producen reportes repetibles.
- **Rendimiento/throughput:** no se mantiene ningún lock durante `sleep()`/procesamiento; los monitores independientes evitan contención cruzada. `ArrayList.remove(0)` sigue siendo O(n), una oportunidad futura de optimización.
- **Mantenibilidad:** cada clase es responsable de su invariante. El costo es documentar el protocolo de pausa y conservar `workerFinished()` en `finally`.

### Límite de la solución en tres JVM

Los bloques `synchronized` solo coordinan hilos que comparten el mismo objeto dentro de una JVM. Tres instancias tendrían heap y monitores independientes; dos podrían asignar el mismo paquete o posición sin conocerse.

Para preservar el invariante entre instancias se necesita un mecanismo distribuido y una fuente de verdad compartida: por ejemplo, transacciones con aislamiento y restricciones únicas en una base de datos, una cola con entrega/confirmación y consumidores competitivos, o un coordinador de locks/consenso. La elección debe considerar fallos, reintentos, idempotencia y particiones de red.