from concurrent import futures
import grpc
import time
import random
import threading
from queue import Queue, Empty
import sys

import generator_pb2
import generator_pb2_grpc

MAX_RESULTADOS = 1_000_000 
RESULTADOS_PROCESADOS = 0
SUMA_TOTAL_RESULTADOS = 0
HISTORIAL_CLIENTES = {} 

cola_numeros = Queue()
vectores_unicos = set()
lock = threading.Lock() 


def generador_de_tareas():
    global RESULTADOS_PROCESADOS

    while RESULTADOS_PROCESADOS < MAX_RESULTADOS: 

        if cola_numeros.qsize() < 1000:

            num_a = random.randint(1, 1000)
            num_b = random.randint(1, 1000)
            num_c = random.randint(1, 1000)

            vector = tuple(sorted((num_a, num_b, num_c)))

            with lock:
                if vector not in vectores_unicos:
                    vectores_unicos.add(vector)

                    peticion = generator_pb2.NumeroRequest(
                        num_a=num_a, 
                        num_b=num_b, 
                        num_c=num_c
                    )
                    cola_numeros.put(peticion)
        else:
            time.sleep(0.001)

    print(f"[Generator] Cola inicializada con {cola_numeros.qsize()} tareas.")


class GeneradorServicer(generator_pb2_grpc.GeneradorServicioServicer):

    def ProcesarNumero(self, request, context):
        global RESULTADOS_PROCESADOS
        global SUMA_TOTAL_RESULTADOS
        
        with lock:
            if RESULTADOS_PROCESADOS >= MAX_RESULTADOS:
                return generator_pb2.Confirmacion(
                    exito=True, 
                    mensaje="Limite alcanzado.", 
                    detener=True
                )
        
        if request.resultado_suma == 0:
    
            try:
                tarea = cola_numeros.get(timeout=0.1)
                return generator_pb2.Confirmacion(
                    exito=True,
                    mensaje=f"{tarea.num_a},{tarea.num_b},{tarea.num_c}",
                    detener=False
                )
            except Empty:
                return generator_pb2.Confirmacion(
                    exito=False,
                    mensaje="Cola vacia.",
                    detener=False
                )
        
        else: 
            with lock:
                if RESULTADOS_PROCESADOS >= MAX_RESULTADOS:
                    return generator_pb2.Confirmacion(exito=True, mensaje="Limite alcanzado.", detener=True)

                RESULTADOS_PROCESADOS += 1
                SUMA_TOTAL_RESULTADOS += request.resultado_suma                
                cliente_id = request.id_cliente
                HISTORIAL_CLIENTES[cliente_id] = HISTORIAL_CLIENTES.get(cliente_id, 0)+1
                
                if RESULTADOS_PROCESADOS % 100000 == 0:
                    print(f"\nRESULTADOS PARCIALES ALCANZADOS:")
                    print(f"Resultados Procesados: {RESULTADOS_PROCESADOS:,}")
                    print(f"Suma Acumulada: {SUMA_TOTAL_RESULTADOS:,}")
                    
                return generator_pb2.Confirmacion(
                    exito=True, 
                    mensaje="Resultado procesado correctamente.", 
                    detener=RESULTADOS_PROCESADOS >= MAX_RESULTADOS
                )

def servidor():
    generator_thread = threading.Thread(target=generador_de_tareas)
    generator_thread.daemon = True 
    generator_thread.start()
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=50))
    generator_pb2_grpc.add_GeneradorServicioServicer_to_server(
        GeneradorServicer(), server
    )
    server.add_insecure_port('[::]:50051')
    server.start()
    print("Servidor gRPC iniciado en puerto")
    print(f"Objetivo: {MAX_RESULTADOS:,} resultados.")
    
    try:
        while RESULTADOS_PROCESADOS < MAX_RESULTADOS:
            time.sleep(1)
        
        print(f"LIMITE DE {MAX_RESULTADOS:,} RESULTADOS ALCANZADO")
        
        if HISTORIAL_CLIENTES:
            cliente_mas_productivo = max(HISTORIAL_CLIENTES.items(), key=lambda item: item[1])
            print(f"Cliente mas productivo (ID): {cliente_mas_productivo[0]}")
            print(f"Resultados Resueltos: {cliente_mas_productivo[1]:,}")
        
    except KeyboardInterrupt:
        pass
    finally:
        server.stop(0)
        print("Servidor detenido.")

if __name__ == '__main__':
    servidor()