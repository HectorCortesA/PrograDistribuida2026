import grpc
import time
import random
import threading
import generator_pb2
import generator_pb2_grpc

def _suma_3num(a, b, c):
    return a + b + c
def suma1(a, b, c):
    return 1

def run_client(client_id):

    channel = grpc.insecure_channel('192.168.100.18:50051')
    stub = generator_pb2_grpc.GeneradorServicioStub(channel)
    
    print(f"[Cliente {client_id}] Iniciado.")
    
    while True:
        peticion_tarea = generator_pb2.ResultadoResponse(
            id_cliente=client_id, 
            resultado_suma=0 
        )
        
        try:
            confirmacion = stub.ProcesarNumero(peticion_tarea)
            
            if confirmacion.detener:
                print(f"[Cliente {client_id}] Servidor ha alcanzado el limite.")
                break

            if confirmacion.exito and confirmacion.mensaje != "Resultado procesado.":
            
                try:
                    nums_str = confirmacion.mensaje.split(',')
                    num_a = int(nums_str[0])
                    num_b = int(nums_str[1])
                    num_c = int(nums_str[2])
                    
                    #resultado = _suma_3num(num_a, num_b, num_c)
                    resultado = suma1(num_a, num_b, num_c)  # Usando la funcion alternativa para pruebas
                    
                    respuesta_resultado = generator_pb2.ResultadoResponse(
                        id_cliente=client_id,
                        resultado_suma=resultado
                    )
                    
                    confirmacion_envio = stub.ProcesarNumero(respuesta_resultado)
                    
                    if confirmacion_envio.detener:
                         print(f"[Cliente {client_id}] Limite alcanzado durante el envio.")
                         break
                         
                except Exception as e:
                    print(f"[Cliente {client_id}] Error al procesar tarea: {e}")
                    time.sleep(0.5) 

            elif not confirmacion.exito:
                time.sleep(0.01) 
            
        except grpc.RpcError as e:
            # Manejo de errores de conexion o cierre del servidor
            print(f"[Cliente {client_id}] Error de conexión: {e}")
            time.sleep(1)
            break

if __name__ == '__main__':
    NUM_CLIENTES = 3 
    cliente_threads = []
    
    for i in range(1, NUM_CLIENTES+1):
        t = threading.Thread(target=run_client, args=(i,))
        cliente_threads.append(t)
        t.start()

    for t in cliente_threads:
        t.join()
        
    print("Todos los clientes han finalizado.")