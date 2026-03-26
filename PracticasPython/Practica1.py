"""
Cliente de prueba de estrés para el servidor XML-RPC concurrente.
Lanza N hilos en paralelo y mide peticiones enviadas vs. respondidas.
"""

import xmlrpc.client
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

SERVER_URL = "http://172.26.160.230:8000"

# ─── Configuración de la prueba ──────────────────────────────────────────────
PRUEBAS = [
    # (nombre,        función,      args,   total_peticiones, hilos_paralelos)
    ("suma",          "suma",       (10, 4),      500,  50),
    ("resta",         "resta",      (10, 4),      500,  50),
    ("multiplica",    "multiplica", (10, 4),      500,  50),
    ("factorial(10)", "factorial",  (10,),         300,  30),
    ("factorial(15)", "factorial",  (15,),         200,  20),
    ("fibonacci(20)", "fibonacci",  (20,),         100,  10),
    ("fibonacci(25)", "fibonacci",  (25,),          50,   5),
]

# ─── Llamada individual ───────────────────────────────────────────────────────
def llamar(funcion: str, args: tuple):
    proxy = xmlrpc.client.ServerProxy(SERVER_URL)
    metodo = getattr(proxy, funcion)
    inicio = time.perf_counter()
    try:
        resultado = metodo(*args)
        return True, time.perf_counter() - inicio
    except Exception as e:
        return False, time.perf_counter() - inicio

# ─── Ejecutar una prueba ──────────────────────────────────────────────────────
def ejecutar_prueba(nombre, funcion, args, total, hilos):
    exitosos = 0
    fallidos  = 0
    tiempos   = []

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=hilos) as executor:
        futuros = [executor.submit(llamar, funcion, args) for _ in range(total)]
        for futuro in as_completed(futuros):
            ok, dt = futuro.result()
            tiempos.append(dt)
            if ok:
                exitosos += 1
            else:
                fallidos += 1
    t_total = time.perf_counter() - t0

    avg = sum(tiempos) / len(tiempos) if tiempos else 0
    tps = exitosos / t_total if t_total > 0 else 0

    return {
        "prueba":     nombre,
        "enviadas":   total,
        "exitosas":   exitosos,
        "fallidas":   fallidos,
        "perdidas%":  round(fallidos / total * 100, 2),
        "tiempo_s":   round(t_total, 3),
        "avg_ms":     round(avg * 1000, 2),
        "req/s":      round(tps, 1),
    }

# Tabla de resultados
def imprimir_tabla(resultados):
    cols = ["prueba", "enviadas", "exitosas", "fallidas",
            "perdidas%", "tiempo_s", "avg_ms", "req/s"]
    headers = {
        "prueba":    "Función",
        "enviadas":  "Enviadas",
        "exitosas":  "Exitosas",
        "fallidas":  "Fallidas",
        "perdidas%": "Perdidas %",
        "tiempo_s":  "Tiempo (s)",
        "avg_ms":    "Avg ms/req",
        "req/s":     "Req/s",
    }
    anchos = {c: max(len(headers[c]), max(len(str(r[c])) for r in resultados))
              for c in cols}

    sep  = "+-" + "-+-".join("-" * anchos[c] for c in cols) + "-+"
    head = "| " + " | ".join(headers[c].ljust(anchos[c]) for c in cols) + " |"

    print("\n" + "=" * len(sep))
    print("  RESULTADOS DE LA PRUEBA DE ESTRÉS")
    print("=" * len(sep))
    print(sep)
    print(head)
    print(sep)
    for r in resultados:
        fila = "| " + " | ".join(str(r[c]).ljust(anchos[c]) for c in cols) + " |"
        print(fila)
    print(sep)

    total_env  = sum(r["enviadas"]  for r in resultados)
    total_exit = sum(r["exitosas"]  for r in resultados)
    total_fall = sum(r["fallidas"]  for r in resultados)
    print(f"\n  TOTAL peticiones enviadas : {total_env}")
    print(f"  TOTAL peticiones exitosas : {total_exit}")
    print(f"  TOTAL peticiones fallidas : {total_fall}")
    print(f"  Tasa de éxito global      : {total_exit/total_env*100:.2f}%\n")

if __name__ == "__main__":
    print(f"Conectando a {SERVER_URL} ...")
    resultados = []

    for prueba in PRUEBAS:
        nombre, funcion, args, total, hilos = prueba
        print(f"  Ejecutando: {nombre:20s} | {total} peticiones | {hilos} hilos", end="", flush=True)
        r = ejecutar_prueba(nombre, funcion, args, total, hilos)
        resultados.append(r)
        print(f"   ({r['tiempo_s']} s, perdidas: {r['perdidas%']}%)")

    imprimir_tabla(resultados)


"""

Visto en clase
import time
import xmlrpc.client
proxy = xmlrpc.client.ServerProxy("http://172.26.165.55:8000")
proxy2 = xmlrpc.client.ServerProxy("http://172.26.160.230:8000")

start_time = time.perf_counter()
result  = proxy2.suma(10, 4)
print("El resultado de la suma es: ", result)
end_time = time.perf_counter()
elapsed_time = end_time - start_time
print(f"Elapsed time: {elapsed_time} seconds")

start_time = time.perf_counter()
result  = proxy2.resta(10, 4)
print("El resultado de la resta es: ", result)
end_time = time.perf_counter()

elapsed_time = end_time - start_time
print(f"Elapsed time: {elapsed_time} seconds")


start_time = time.perf_counter()
result  = proxy2.multiplica(10, 4)
print("El resultado de la multiplicacion es: ", result)
end_time = time.perf_counter()

elapsed_time = end_time - start_time
print(f"Elapsed time: {elapsed_time} seconds")

start_time = time.perf_counter()
result  = proxy2.factorial(8)
print("El resultado del factorial es: ", result)
end_time = time.perf_counter()

elapsed_time = end_time - start_time
print(f"Elapsed time: {elapsed_time} seconds")

start_time = time.perf_counter()
result = proxy2.fibonacci(10)
print("El resultado de la serie de fibonacci es: ", result)
end_time = time.perf_counter()

elapsed_time = end_time - start_time
print(f"Elapsed time: {elapsed_time} seconds")
"""
