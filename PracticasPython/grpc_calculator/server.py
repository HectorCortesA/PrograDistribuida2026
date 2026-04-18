import grpc
import math
import re
from concurrent import futures

import calculator_pb2
import calculator_pb2_grpc



#   OPERACIONES PRIVADAS

def _add(a: float, b: float) -> float:      return a + b
def _subtract(a: float, b: float) -> float: return a - b
def _multiply(a: float, b: float) -> float: return a * b
def _divide(a: float, b: float) -> float:
    if b == 0:
        raise ZeroDivisionError("División entre cero")
    return a / b
def _power(a: float, b: float) -> float:    return math.pow(a, b)

_OPS = {
    "+": _add,
    "-": _subtract,
    "*": _multiply,
    "x": _multiply,   # alias para 'x'
    "X": _multiply,
    "/": _divide,
    "^": _power,
}

#   PARSER PRIVADO  →  tokeniza la cadena

# Operadores válidos como string para el regex
_OP_CHARS = r"[\+\-\*xX/\^]"

def _parse(expression: str) -> tuple[list[float], list[str]]:
    """
    Recibe '2+3+5-2x7' y devuelve:
      numbers  = [2, 3, 5, 2, 7]
      operators= ['+', '+', '-', 'x']

    Imprime cada token en la terminal.
    """
    # Normalizar espacios y separar tokens
    expr = expression.strip()

    # Regex: número (entero o decimal) o operador
    token_pattern = re.compile(
        r'(-?\d+\.?\d*)'   # número (incluye negativos al inicio)
        r'|'
        r'([\+\-\*xX/\^])' # operador
    )

    tokens = token_pattern.findall(expr)
    # findall devuelve lista de tuplas (num_group, op_group)
    flat = []
    for num, op in tokens:
        if num:
            flat.append(("NUM", num))
        elif op:
            flat.append(("OP", op))

    if not flat:
        raise ValueError("No se encontraron tokens en la expresión")

    # Imprimir tokens en terminal
    print("\n" + "═" * 45)
    print(f"  EXPRESIÓN RECIBIDA : {expr}")
    print("  TOKENS DETECTADOS  :")
    for kind, val in flat:
        label = "Número   " if kind == "NUM" else "Operador "
        print(f"    [{label}] → {val}")
    print("═" * 45)

    # Validar estructura: debe ser NUM (OP NUM)*
    if flat[0][0] != "NUM":
        raise ValueError("La expresión debe iniciar con un número")
    if flat[-1][0] != "OP":
        pass  # ok si termina en número
    if flat[-1][0] == "OP":
        raise ValueError("La expresión no puede terminar con un operador")

    numbers   = []
    operators = []

    for i, (kind, val) in enumerate(flat):
        expected = "NUM" if i % 2 == 0 else "OP"
        if kind != expected:
            raise ValueError(
                f"Estructura inválida en posición {i+1}: "
                f"se esperaba {expected}, se encontró '{val}'"
            )
        if kind == "NUM":
            numbers.append(float(val))
        else:
            if val not in _OPS:
                raise ValueError(f"Operador desconocido: '{val}'")
            operators.append(val)

    return numbers, operators

#   EVALUADOR CON PRECEDENCIA  (privado)

def _evaluate(numbers: list[float], operators: list[str]) -> tuple[float, list[str]]:
    """
    Evalúa con precedencia: ^  >  * / x X  >  + -
    Devuelve (resultado, lista_de_pasos).
    Cada paso se imprime en la terminal.
    """
    nums = list(numbers)
    ops  = list(operators)
    steps = []

    def _fmt(n_list, o_list) -> str:
        parts = [str(n_list[0])]
        for op, n in zip(o_list, n_list[1:]):
            parts += [op, str(n)]
        return " ".join(parts)

    def _apply(op, a, b):
        return _OPS[op](a, b)

    def _pass(name, targets):
        nonlocal nums, ops
        changed = True
        while changed:
            changed = False
            i = 0 if name != "^" else len(ops) - 1
            step_range = range(len(ops) - 1, -1, -1) if name == "^" else range(len(ops))
            for i in step_range:
                if ops[i] in targets:
                    res = _apply(ops[i], nums[i], nums[i + 1])
                    step = (
                        f"  [{name}] {nums[i]} {ops[i]} {nums[i+1]} = {res}"
                    )
                    print(step)
                    steps.append(step.strip())
                    nums[i] = res
                    del nums[i + 1]
                    del ops[i]
                    changed = True
                    break

    print("\n  EVALUACIÓN CON PRECEDENCIA:")
    print(f"  Expresión inicial : {_fmt(nums, ops)}")

    _pass("^",    {"^"})
    _pass("* /",  {"*", "/", "x", "X"})
    _pass("+ -",  {"+", "-"})

    print(f"  Resultado final   : {nums[0]}")
    print("═" * 45 + "\n")

    return nums[0], steps


#   SERVICER  gRPC

class CalculatorServicer(calculator_pb2_grpc.CalculatorServiceServicer):

    def Evaluate(self, request, context):
        client = request.client_name if request.client_name else "Anónimo"
        print(f"\n  Cliente : {client}")

        try:
            numbers, operators = _parse(request.expression)
            result, steps      = _evaluate(numbers, operators)

            # Reconstruir expresión normalizada
            parts = [str(int(n) if n == int(n) else n) for n in numbers]
            expr_str = f" {operators[0]} ".join(
                [parts[0]] +
                [f"{operators[i]} {parts[i+1]}" for i in range(len(operators))]
            )
            # forma más simple:
            rebuilt = parts[0]
            for i, op in enumerate(operators):
                rebuilt += f" {op} {parts[i+1]}"

            return calculator_pb2.ExpressionResponse(
                success=calculator_pb2.SuccessResult(
                    value=result,
                    parsed_expression=f"{rebuilt} = {result}",
                    steps=steps,
                )
            )

        except ZeroDivisionError as e:
            return calculator_pb2.ExpressionResponse(
                error=calculator_pb2.ErrorResult(
                    code="DIVISION_BY_ZERO", message=str(e)
                )
            )
        except ValueError as e:
            return calculator_pb2.ExpressionResponse(
                error=calculator_pb2.ErrorResult(
                    code="INVALID_EXPRESSION", message=str(e)
                )
            )


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    calculator_pb2_grpc.add_CalculatorServiceServicer_to_server(
        CalculatorServicer(), server
    )
    server.add_insecure_port("[::]:50051")
    server.start()
    print("Servidor gRPC listo en puerto 50051")
    server.wait_for_termination()


if __name__ == "__main__":
    serve()