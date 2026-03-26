from concurrent import futures
import grpc
from protos import caluladora_pb2_grpc
from Servicio.Calculadora import CalculadoraServicer

def server():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    caluladora_pb2_grpc.add_calculadoraServicer_to_server(CalculadoraServicer(), server)
    
    server.add_insecure_port('localhost:8000')
    server.start()
    print("Servidor iniciando")
    server.wait_for_termination()

if __name__ == '__main__':
    server()

    