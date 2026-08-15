# ADR-001: Control de concurrencia del estado compartido del almacén

## Contexto

Múltiples robots, implementados como `Thread`, comparten una cola de paquetes, un registro de entregas, estadísticas y control de pausa. La versión inicial separaba operaciones *check-then-act* y *read-modify-write*, imprimía antes de terminar y usaba espera activa. Deben preservarse unicidad, contabilidad completa y snapshots quiescentes sin eliminar la concurrencia.

## Decisión

Usar un monitor independiente por agregado mutable y proteger únicamente su transición lógica mínima:

- cola: comprobar y extraer atómicamente;
- registro: reservar posición e insertar atómicamente;
- estadísticas: actualizar juntos contador y suma;
- coordinación: monitor común con `wait()`/`notifyAll()` y una barrera basada en trabajadores activos/pausados;
- finalización: `join()` de cada robot antes del único reporte final.

El procesamiento del paquete permanece fuera de cualquier región crítica.

## Alternativas consideradas

- **Un lock global:** sencillo, pero serializa componentes independientes y reduce throughput.
- **Sincronizar todos los métodos:** protege de más y oculta cuáles son las verdaderas operaciones atómicas.
- **Solo `AtomicInteger`:** sirve para contadores aislados, pero no mantiene juntos posición/registro ni los dos acumuladores.
- **`BlockingQueue` y `Lock/Condition`:** opciones válidas para evolución; no se adoptan porque el laboratorio exige practicar monitores Java.
- **Ejecución secuencial:** descartada porque cambia el modelo solicitado.

## Atributos de calidad afectados

- **Confiabilidad:** elimina duplicados, pérdidas y actualizaciones perdidas; los reportes tienen una condición de consistencia explícita.
- **Rendimiento:** las regiones son breves y los locks no abarcan el procesamiento; existe contención inevitable en cada recurso compartido.
- **Mantenibilidad:** cada clase encapsula su lock e invariante; la barrera requiere mantener correctamente los contadores de trabajadores.

## Evidencia

En el commit base, las tres configuraciones produjeron anomalías en 10/10 ejecuciones. Después:

```text
8 robots, 100 paquetes, 30 ejecuciones   -> 0/30 anomalías
16 robots, 250 paquetes, 30 ejecuciones -> 0/30 anomalías
32 robots, 500 paquetes, 100 ejecuciones -> 0/100 anomalías
```

La demostración pausada mostró contador y registro iguales, sin cambios hasta `resume()`, y estado final de 180/180 entregas.

## Consecuencias

Las operaciones de cola, registro y estadísticas se linealizan localmente. El sistema conserva paralelismo durante el trabajo costoso. `pause()` es una operación síncrona: puede tardar hasta que el robot más lento finalice su paquete actual, lo cual es deliberado para garantizar el snapshot.

## Riesgos

- Olvidar `workerFinished()` bloquearía la barrera; se mitiga con `finally`.
- Añadir trabajo lento dentro de un bloque sincronizado aumentaría contención.
- Un nuevo estado compartido debe incorporarse al protocolo de punto seguro.
- Estos monitores no protegen invariantes entre JVM; una arquitectura distribuida necesita almacenamiento/colas transaccionales, idempotencia y coordinación distribuida.
