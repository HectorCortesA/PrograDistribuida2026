from protos import caluladora2_pb2
from protos import caluladora2_pb2_grpc
import grpc

def run_client():
    with grpc.insecure_channel('localhost:8000') as channel :
        stub  = caluladora2_pb2_grpc.calculadoraStub(channel)

        numero1= 2.5
        numero =10
        peticion = caluladora2_pb2.MensajeSuma(numero1=numero1, numero=numero)
        try:
            respuesta =stub.Sumar(peticion)
            print(f"Error al llamar al servidor: {respuesta.resultado}")
        except grpc.RpcError as e: 

            print(f"error al llamar al servidor: {e.status()}- {e.details()}")
            if __name__ == '__main__' :
                run_clientx