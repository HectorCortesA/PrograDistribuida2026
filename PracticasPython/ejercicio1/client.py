
import threading
import xmlrpc.client
from xmlrpc.server import SimpleXMLRPCServer
import random
import time
import sys
import logging

SERVER_HOST    = "192.168.100.18"                              # IP del servidor RPC
SERVER_PORT    = 8000
CLIENT_HOST    = "127.0.0.1"                              # IP local para el callback
CLIENT_PORT    = int(sys.argv[1]) if len(sys.argv) > 1 else 9000
NUM_OPERATIONS = 12                                       # Operaciones a generar
INSERT_RATIO   = 0.60                                     # 60 % inserciones, 40 % consultas

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  [%(threadName)-16s]  %(message)s"
)
log = logging.getLogger(__name__)

# (id, nombre, precio)
CATALOG = [
    ("P001", "Laptop Ultrabook",    15_999.99),
    ("P002", "Mouse Inalámbrico",      299.50),
    ("P003", "Teclado Mecánico",       849.00),
    ("P004", "Monitor 27\" 4K",      6_500.00),
    ("P005", "Audífonos Bluetooth",    999.00),
    ("P006", "Webcam 1080p",         1_200.00),
    ("P007", "USB Hub 7 puertos",      450.00),
    ("P008", "SSD NVMe 1 TB",        1_800.00),
    ("P009", "Silla Ergonómica",     4_200.00),
    ("P010", "Lámpara LED USB",        180.00),
    # Productos inventados (para generar misses en consultas)
    ("P099", "Producto Fantasma",        0.00),
    ("P098", "Artículo Inexistente",     0.00),
]

#  ESTADO DE RESULTADOS  (thread-safe)

results      = []
results_lock = threading.Lock()
# Evento que se activa cuando se reciben todos los resultados esperados
all_received = threading.Event()
expected_count = 0          # Se establece antes de enviar las solicitudes

#  SERVIDOR DE CALLBACK DEL CLIENTE

def client_callback(operation: str, position: int, message: str):
    """
    Función RPC expuesta por el cliente.

    El servidor la invoca de forma asíncrona cuando termina
    de procesar una tarea (insert o query).
    operation : str → "insert" o "query".
    position  : int → Posición (0-index) en el XML, o -1 si no existe.
    message   : str → Descripción legible del resultado.
    None  (el servidor descarta la respuesta del callback).
    """
    icon = "✔" if position >= 0 else "✘"
    log.info(f"[CALLBACK {icon}]  op={operation:<7s}  pos={position:>3d}  │  {message}")

    with results_lock:
        results.append({
            "op":  operation,
            "pos": position,
            "msg": message,
        })
        # Señalizar cuando se completen todas las respuestas esperadas
        if len(results) >= expected_count:
            all_received.set()


# Levantar el servidor de callback en un hilo daemon
callback_server = SimpleXMLRPCServer(
    (CLIENT_HOST, CLIENT_PORT),
    allow_none=True,
    logRequests=False,
)
callback_server.register_function(client_callback, "client_callback")


def _run_callback_server() -> None:
    log.info(f"Servidor de callback escuchando en {CLIENT_HOST}:{CLIENT_PORT}")
    callback_server.serve_forever()


threading.Thread(
    target=_run_callback_server,
    name="CallbackSrv",
    daemon=True,
).start()


_rpc_server  = xmlrpc.client.ServerProxy(
    f"http://{SERVER_HOST}:{SERVER_PORT}/",
    allow_none=True,
)
CALLBACK_URL = f"http://{CLIENT_HOST}:{CLIENT_PORT}/"

def generate_operations(n: int) -> list:
    """
    Genera una lista de n operaciones aleatorias mezcladas.

    Cada entrada es una tupla: ("insert" | "query", (id, nombre, precio)).

    Se mezclan al azar para simular múltiples clientes enviando
    solicitudes en orden impredecible; el servidor reordenará
    según prioridad.

    Parámetros
    ----------
    n : int → Número total de operaciones a generar.

    Retorna
    -------
    list[tuple] : Lista de operaciones aleatorias.
    """
    ops = []
    for _ in range(n):
        product  = random.choice(CATALOG)
        op_type  = "insert" if random.random() < INSERT_RATIO else "query"
        ops.append((op_type, product))
    random.shuffle(ops)
    return ops


def _send_operation(op_type: str, product: tuple, idx: int) -> None:
    """
    Envía una operación al servidor RPC y regresa de inmediato.

    El servidor encola la tarea y devuelve "Queued" sin procesar;
    el resultado real llega vía callback.

    Parámetros
    ----------
    op_type : str   → "insert" o "query".
    product : tuple → (prod_id, name, price).
    idx     : int   → Número de operación (para logs).
    """
    prod_id, name, price = product
    try:
        if op_type == "insert":
            ack = _rpc_server.insert_product(prod_id, name, price, CALLBACK_URL)
            log.info(f"[OP-{idx:02d}] SEND insert_product({prod_id})  →  {ack}")
        else:
            ack = _rpc_server.query_product(prod_id, CALLBACK_URL)
            log.info(f"[OP-{idx:02d}] SEND query_product({prod_id})   →  {ack}")
    except Exception as e:
        log.error(f"[OP-{idx:02d}] ERROR al enviar al servidor: {e}")
        # Registrar error para que el contador no quede bloqueado
        with results_lock:
            results.append({"op": op_type, "pos": -1, "msg": f"Error de red: {e}"})
            if len(results) >= expected_count:
                all_received.set()

if __name__ == "__main__":
    log.info(f"Cliente iniciado en puerto {CLIENT_PORT}.")
    time.sleep(0.5)   # Pequeña espera para que el callback server arranque

    operations     = generate_operations(NUM_OPERATIONS)
    expected_count = len(operations)   # Cuántos callbacks esperamos

    log.info(f"Enviando {expected_count} operaciones aleatorias al servidor...")
    log.info(f"  Insertions ≈ {sum(1 for o,_ in operations if o=='insert')}  "
             f"Queries ≈ {sum(1 for o,_ in operations if o=='query')}")

    send_threads = []
    for idx, (op_type, product) in enumerate(operations, start=1):
        t = threading.Thread(
            target=_send_operation,
            args=(op_type, product, idx),
            name=f"Send-{idx:02d}",
            daemon=True,
        )
        send_threads.append(t)
        t.start()
        time.sleep(random.uniform(0.05, 0.3))   # Escalonar envíos para simular tráfico real

    # Esperar a que todos los hilos de envío terminen (ACK del servidor)
    for t in send_threads:
        t.join()

    log.info("Todas las solicitudes enviadas. Esperando callbacks del servidor...")

    # Esperar callbacks con timeout de seguridad
    timeout_seconds = 90
    received = all_received.wait(timeout=timeout_seconds)

   
    print(f"  RESUMEN DE RESULTADOS  (cliente :{CLIENT_PORT})")
    with results_lock:
        for i, r in enumerate(results, start=1):
            icon = "" if r["pos"] >= 0 else "✘"
            print(f"  {i:2d}. [{icon}] {r['op']:<7s}  pos={r['pos']:>3d}  │  {r['msg']}")
        total    = len(results)
        hits     = sum(1 for r in results if r["pos"] >= 0)
        misses   = total - hits
    print(f"  Total: {total}   Encontrados/Insertados: {hits}   No encontrados: {misses}")
    if not received:
        print(f"  ⚠  Timeout alcanzado ({timeout_seconds}s). "
              f"Faltan {expected_count - total} respuestas.")

