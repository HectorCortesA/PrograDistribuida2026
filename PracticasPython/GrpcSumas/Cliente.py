import grpc
import sys
sys.path.append("Protos")
import numeros_pb2
import numeros_pb2_grpc
import time
import random

def run(cliente_id):
    channel = grpc.insecure_channel('localhost:50051')
    stub = numeros_pb2_grpc.NumeroServiceStub(channel)

    while True:
        # Solicita vector
        vector = stub.ObtenerVector(numeros_pb2.ClienteInfo(id=cliente_id))
        numeros = list(vector.numeros)
        resultado = sum(numeros)  # función matemática

        # Envía resultado
        respuesta = stub.EnviarResultado(numeros_pb2.Resultado(
            id=cliente_id,
            resultado=resultado
        ))

        print(f"[{cliente_id}] -> {numeros} = {resultado}, total={respuesta.total_resultados}")

        if respuesta.total_resultados >= 100:
            print(f"[{cliente_id}] Servidor alcanzó el límite.")
            break

        time.sleep(random.uniform(0.1, 0.5))


if __name__ == '__main__':
    import sys
    cliente_id = sys.argv[1] if len(sys.argv) > 1 else f"cliente_{random.randint(1,100)}"
    run(cliente_id)
