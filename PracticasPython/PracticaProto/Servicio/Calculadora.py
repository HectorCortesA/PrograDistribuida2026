from protos import caluladora_pb2
from protos import caluladora_pb2_grpc

class CalculadoraServicer(caluladora_pb2_grpc.calculadoraServicer):
    
    def Sumar(selft, request, context):
        number1 = request.numero1
        number2 = request.numero
        resultado = number1 + number2
        return caluladora_pb2.RespuestaOperacion(resultado=resultado)
    
    