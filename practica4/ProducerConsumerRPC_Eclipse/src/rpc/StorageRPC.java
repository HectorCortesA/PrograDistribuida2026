package rpc;

import java.util.List;

/**
 * Interfaz RPC (Remote Procedure Call) para el Storage.
 * Define los contratos que el Productor y el Consumidor
 * pueden invocar de forma remota sobre el Storage.
 *
 * En una arquitectura distribuida real, esta interfaz sería
 * implementada sobre sockets, HTTP/REST o gRPC. Aquí se simula
 * con llamadas directas a través de esta abstracción.
 */
public interface StorageRPC {

    /**
     * Permite al Productor enviar un vector al Storage.
     * El Storage verifica que el vector sea único antes de aceptarlo.
     *
     * @param vector arreglo de 3 enteros únicos en rango [1, 1000]
     * @return true si el vector fue aceptado (único), false si ya existía
     */
    boolean pushVector(int[] vector);

    /**
     * Permite al Consumidor obtener el siguiente vector disponible.
     * Si no hay vectores en la cola, el consumidor esperará (bloqueante).
     *
     * @return arreglo de 3 enteros para procesar
     * @throws InterruptedException si el hilo es interrumpido mientras espera
     */
    int[] pullVector() throws InterruptedException;

    /**
     * Permite al Consumidor enviar el resultado del procesamiento de un vector.
     *
     * @param result resultado numérico obtenido al aplicar la función matemática
     */
    void pushResult(long result);

    /**
     * Retorna la cantidad total de resultados almacenados hasta el momento.
     *
     * @return número de resultados en la lista de resultados
     */
    int getResultCount();

    /**
     * Retorna la suma acumulada de todos los resultados almacenados.
     *
     * @return suma total de todos los resultados
     */
    long getTotalSum();

    /**
     * Indica al Storage que el sistema debe detenerse.
     * Usado para notificar a los hilos bloqueados que deben terminar.
     */
    void shutdown();

    /**
     * Verifica si el sistema ya fue apagado.
     *
     * @return true si el sistema fue detenido
     */
    boolean isShutdown();

    /**
     * Retorna todos los resultados almacenados (para reporte final).
     *
     * @return lista inmutable de resultados
     */
    List<Long> getResults();
}
