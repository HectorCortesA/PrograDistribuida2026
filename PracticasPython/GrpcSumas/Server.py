import random
import queue
import threading
import grpc
from concurrent import futures
import sys
sys.path.append("Protos")
import numeros_pb2
import numeros_pb2_grpc

cola = queue.Queue()
historial = {}
total_resultados = 0
MAX_RESULTADOS = 1_000_000
lock = threading.Lock()


class NumeroServiceServicer(numeros_pb2_grpc.NumeroServiceServicer):
    def ObtenerVector(self, request, context):
        # Genera un vector único
        numeros = random.sample(range(1, 1001), 3)
        cola.put(numeros)
        print(f"[Servidor] Enviado a {request.id}: {numeros}")
        return numeros_pb2.Vector(numeros=numeros)

    def EnviarResultado(self, request, context):
        global total_resultados
        with lock:
            total_resultados += 1
            historial[request.id] = historial.get(request.id, 0) + 1

        print(f"[Servidor] Recibido de {request.id}: {request.resultado}")
        if total_resultados >= MAX_RESULTADOS:
            print("🚨 Límite alcanzado. Cerrando servidor...")
            stop_event.set()

        return numeros_pb2.RespuestaServidor(
            mensaje="Resultado recibido",
            total_resultados=total_resultados
        )


def serve():
    global stop_event
    stop_event = threading.Event()

    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    numeros_pb2_grpc.add_NumeroServiceServicer_to_server(NumeroServiceServicer(), server)
    server.add_insecure_port('[::]:50051')
    server.start()
    print("Servidor iniciado en puerto 50051...")

    stop_event.wait()
    server.stop(0)
    print("Servidor detenido.")
    print("Historial de clientes:", historial)


if __name__ == '__main__':
    serve()
