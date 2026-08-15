# ARSW - Laboratorio 2

## Almacen autonomo: condiciones de carrera, regiones criticas y coordinacion de hilos

**Asignatura:** Arquitecturas de Software - ARSW

**Tecnologia:** Java 21, Maven y JUnit 5

**Modalidad:** equipo de proyecto de tres integrantes

**Estado:** solucion final implementada y verificada

---

## Descripcion

Este proyecto simula un centro de distribucion en el que varios robots autonomos, representados mediante hilos de plataforma `Thread`, procesan paquetes concurrentemente.

Cada robot solicita un paquete, lo procesa, registra su posicion de entrega, actualiza las estadisticas y repite el ciclo hasta que no queden paquetes. Los robots comparten `PackageQueue`, `DeliveryRegistry`, `WarehouseStatistics` y `SimulationControl`.

La version inicial contenia condiciones de carrera, actualizaciones perdidas, posiciones duplicadas, reportes prematuros y espera activa. La solucion conserva la concurrencia y protege unicamente las regiones criticas necesarias.

---

## Distribucion del equipo

| Integrante | Responsabilidad principal |
|---|---|
| Juan Sebastian Murcia Yanquen | Diagnostico, inventario de estado compartido, interleaving, invariantes y correccion de `PackageQueue`. |
| Jhonatan stiven peña mora | Sincronizacion de `DeliveryRegistry` y `WarehouseStatistics`, y finalizacion correcta mediante `join()`. |
| Jhonatan Madero | Pausa/reanudacion con monitores, pruebas concurrentes, verificacion, analisis arquitectonico y ADR. |

La integracion y revision final se realizaron en equipo.

---

## Problemas identificados

La version inicial permitia que:

- dos robots seleccionaran el mismo paquete;
- un paquete desapareciera sin ser procesado;
- `ArrayList` produjera `IndexOutOfBoundsException` por modificaciones concurrentes;
- varias entregas recibieran la misma posicion;
- se perdieran incrementos de estadisticas;
- el reporte final se imprimiera antes de terminar los robots;
- los robots pausados consumieran CPU mediante espera activa;
- una instantanea pausada observara un estado intermedio.

La evidencia, los comandos y el interleaving completo se encuentran en [`docs/REPORT.md`](docs/REPORT.md).

---

## Solucion implementada

| Componente | Problema corregido | Mecanismo |
|---|---|---|
| `PackageQueue` | Carrera entre comprobar y eliminar un paquete. | `synchronized (pending)` para una extraccion atomica. |
| `DeliveryRegistry` | Posiciones duplicadas y escritura concurrente. | Reserva de posicion e insercion bajo el mismo monitor. |
| `WarehouseStatistics` | Incrementos y acumulaciones perdidas. | Monitor privado para actualizar contador y tiempo. |
| `WarehouseMain` | Reporte final prematuro. | `awaitCompletion()` y `join()` antes del reporte. |
| `SimulationControl` | Espera activa y pausa inconsistente. | `synchronized`, `wait()` y `notifyAll()`. |
| `WarehouseRobot` | Barrera esperando robots terminados. | `workerFinished()` ejecutado dentro de `finally`. |

No se utilizo un bloqueo global. Cada objeto protege su propio estado mutable y el procesamiento del paquete ocurre fuera de las regiones criticas.

---

## Invariantes principales

1. Cada paquete se extrae y procesa como maximo una vez.
2. Ningun paquete desaparece del sistema.
3. Los paquetes y las posiciones registrados son unicos.
4. Las posiciones forman la secuencia `1..N`.
5. Cada entrega actualiza las estadisticas exactamente una vez.
6. En una pausa confirmada ningun robot modifica el estado observado.
7. Al finalizar:

```text
deliveries == processedParcels == initialParcels
pendingParcels == 0
```

La justificacion completa esta en [`docs/REPORT.md`](docs/REPORT.md).

---

## Requisitos

- JDK 21;
- Maven 3.9 o superior;
- Git.

```bash
java -version
mvn -version
```

---

## Compilacion y pruebas

```bash
mvn clean test
```

Las pruebas verifican instantaneas consistentes, posiciones unicas, diez simulaciones concurrentes con 32 robots y 500 paquetes, estabilidad durante la pausa y finalizacion posterior sin violar invariantes.

---

## Ejecucion

### Simulacion principal

```bash
java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain
```

Configuracion personalizada:

```bash
java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain 24 250
```

El programa espera a todos los robots y presenta exactamente un reporte final.

### Sonda de condiciones de carrera

```bash
java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 100 32 500
```

Resultado esperado:

```text
Anomalous runs: 0/100
```

### Pausa y reanudacion

```bash
java -cp target/classes edu.eci.arsw.warehouse.app.PauseResumeDemo
```

`pause()` retorna cuando todos los robots activos llegaron a un punto seguro. Los trabajadores bloqueados ejecutan `wait()`, sin consumir CPU mediante espera activa. `resume()` utiliza `notifyAll()` para despertarlos.

---

## Resultados

| Robots | Paquetes | Runs antes | Anomalias antes | Runs despues | Anomalias despues |
|---:|---:|---:|---:|---:|---:|
| 8 | 100 | 10 | 10 | 30 | 0 |
| 16 | 250 | 10 | 10 | 30 | 0 |
| 32 | 500 | 10 | 10 | 100 | 0 |

Verificacion final:

```text
pending=0
processedCounter=500
registry=500
uniqueParcels=500
uniquePositions=500
positionsContiguous=true
Anomalous runs: 0/100
```

Ejemplo de pausa:

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

---

## Estructura

```text
.
|-- README.md
|-- pom.xml
|-- src/
|   |-- main/java/edu/eci/arsw/warehouse/
|   |   |-- app/
|   |   |-- core/
|   |   |-- model/
|   |   |-- verification/
|   |   `-- worker/
|   `-- test/java/edu/eci/arsw/warehouse/
`-- docs/
    |-- ADR-001-concurrency-control.md
    `-- REPORT.md
```

### Documentacion

- [Informe completo](docs/REPORT.md)
- [ADR-001: control de concurrencia](docs/ADR-001-concurrency-control.md)

---

## Decisiones arquitectonicas

La solucion utiliza monitores independientes y regiones criticas pequenas. Esto define puntos de linealizacion claros, conserva el paralelismo del procesamiento, evita contencion entre componentes independientes y relaciona cada bloqueo con el invariante protegido.

Una region critica innecesariamente grande reduciria el throughput al serializar trabajo que puede ejecutarse en paralelo.

### Limite entre JVM

`synchronized` solo coordina hilos de una misma JVM. En un despliegue con varias instancias se requeririan transacciones con restricciones unicas, una cola distribuida con confirmacion, operaciones idempotentes y algun mecanismo de coordinacion distribuida.

---

## Restricciones respetadas

- Java 21 y hilos de plataforma `Thread`;
- sin pools de hilos ni hilos virtuales;
- sin eliminar la concurrencia;
- sin ejecucion secuencial como solucion;
- sin un unico bloqueo global;
- sin espera activa;
- finalizacion mediante `join()`;
- pausa/reanudacion mediante `wait()` y `notifyAll()`.

---

## Entregables

```text
README.md
pom.xml
src/
docs/ADR-001-concurrency-control.md
docs/REPORT.md
```

