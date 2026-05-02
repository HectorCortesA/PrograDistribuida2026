import grpc
import sys
import calculadora_pb2
import calculadora_pb2_grpc

def ejecutar_calculo(ip_orquestador, expresion):
    # El Orquestador escuchará en el puerto 50051
    direccion = f'{ip_orquestador}:50051'
    
    with grpc.insecure_channel(direccion) as channel:
        stub = calculadora_pb2_grpc.CalculadoraServiceStub(channel)
        
        print(f"[*] Enviando expresión al Orquestador: {expresion}")
        
        # Llamada gRPC
        try:
            solicitud = calculadora_pb2.SolicitudCadena(expresion=expresion)
            respuesta = stub.ProcesarExpresion(solicitud)
            
            if respuesta.error:
                print(f"[!] Error recibido: {respuesta.error}")
            else:
                print(f"[+] Resultado Final: {respuesta.data}")
                
        except grpc.RpcError as e:
            print(f"[!] No se pudo conectar con el Orquestador: {e.details()}")

if __name__ == "__main__":
    # Uso: python cliente.py [IP_ORQUESTADOR] "5+3*2-1"
    ip = sys.argv[1] if len(sys.argv) > 1 else 'localhost'
    exp = sys.argv[2] if len(sys.argv) > 2 else "5+3*2-1"
    ejecutar_calculo(ip, exp)