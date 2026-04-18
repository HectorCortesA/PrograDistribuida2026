import grpc
import calculator_pb2
import calculator_pb2_grpc


def evaluar(stub, expresion: str, nombre: str = "Usuario"):
    request = calculator_pb2.ExpressionRequest(
        expression=expresion,
        client_name=nombre,
    )
    resp = stub.Evaluate(request)

    which = resp.WhichOneof("result")
    if which == "success":
        print(f"\n  Resultado : {resp.success.parsed_expression}")
        print("  Pasos:")
        for step in resp.success.steps:
            print(f"    {step}")
    else:
        print(f"\n  [ERROR] [{resp.error.code}] {resp.error.message}")
    print()


def run():
    with grpc.insecure_channel("localhost:50051") as channel:
        stub = calculator_pb2_grpc.CalculatorServiceStub(channel)

        print("═" * 45)
        print("  Calculadora gRPC")
        print("  Operadores válidos: + - * x / ^")
        print("  Ejemplo: 2+3+5-2x7  |  10/2^3+1")
        print("  Escribe 'salir' para terminar")
        print("═" * 45)

        nombre = input("  Tu nombre: ").strip() or "Usuario"

        while True:
            expresion = input("\n  Expresión: ").strip()

            if expresion.lower() in ("salir", "exit", "q"):
                print("  Hasta luego!")
                break

            if not expresion:
                print("  Escribe una expresión.")
                continue

            evaluar(stub, expresion, nombre)


if __name__ == "__main__":
    run()