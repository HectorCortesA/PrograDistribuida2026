

import threading
import xmlrpc.client
import xml.etree.ElementTree as ET
from xmlrpc.server import SimpleXMLRPCServer
import time
import queue
import os
import logging

SERVER_HOST   = "172.26.161.183"   # Escuchar en todas las interfaces
SERVER_PORT   = 8000
XML_FILE      = "products.xml"
NUM_WORKERS   = 3           # Hilos de trabajo concurrentes
INSERT_DELAY  = 3.0         # Segundos que simula carga de inserción
QUERY_DELAY   = 1.0         # Segundos que simula carga de consulta

# Prioridades de la cola (número menor = mayor prioridad)
PRIORITY_INSERT = 0         # Inserciones tienen máxima prioridad
PRIORITY_QUERY  = 1         # Consultas tienen menor prioridad

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  [%(threadName)-14s]  %(message)s"
)
log = logging.getLogger(__name__)

task_queue    = queue.PriorityQueue()   # Cola con prioridad
file_lock     = threading.Lock()        # Protege el archivo XML
counter_lock  = threading.Lock()        # Protege el contador de tareas
_task_counter = 0                       # Desempate FIFO dentro de igual prioridad


def _next_seq() -> int:
    """Devuelve un número de secuencia único y creciente (thread-safe)."""
    global _task_counter
    with counter_lock:
        _task_counter += 1
        return _task_counter


def init_xml() -> None:
    """Crea el archivo XML con el elemento raíz si no existe."""
    if not os.path.exists(XML_FILE):
        root = ET.Element("products")
        _write_tree(ET.ElementTree(root), root)
        log.info(f"Archivo XML '{XML_FILE}' creado.")


def _indent(elem, level: int = 0) -> None:
    """Añade sangría al árbol XML para facilitar la lectura del archivo."""
    indent = "\n" + "  " * level
    if len(elem):
        if not elem.text or not elem.text.strip():
            elem.text = indent + "  "
        for child in elem:
            _indent(child, level + 1)
            if not child.tail or not child.tail.strip():
                child.tail = indent + "  "
        if not child.tail or not child.tail.strip():
            child.tail = indent
    if level and (not elem.tail or not elem.tail.strip()):
        elem.tail = indent


def _write_tree(tree: ET.ElementTree, root: ET.Element) -> None:
    """Escribe el árbol XML al disco con sangría."""
    _indent(root)
    tree.write(XML_FILE, encoding="utf-8", xml_declaration=True)



def _do_insert(prod_id: str, name: str, price: float) -> int:
    """
    Inserta un producto en el XML (thread-safe).

    Parámetros
    ----------
    prod_id : str   → Identificador único del producto.
    name    : str   → Nombre del producto.
    price   : float → Precio del producto.

    Retorna
    -------
    int : Posición (0-index) del producto en el XML.
          Si el ID ya existía, devuelve su posición actual.
    """
    time.sleep(INSERT_DELAY)   # Simula carga intensa del servidor

    with file_lock:
        tree = ET.parse(XML_FILE)
        root = tree.getroot()
        products = root.findall("product")

        # ── Verificar si el producto ya existe ──────────────
        for i, p in enumerate(products):
            if p.findtext("id") == str(prod_id):
                log.info(f"DUPLICATE  id={prod_id}  →  pos={i}")
                return i

        # ── Insertar nuevo producto ─────────────────────────
        elem = ET.SubElement(root, "product")
        ET.SubElement(elem, "id").text    = str(prod_id)
        ET.SubElement(elem, "name").text  = str(name)
        ET.SubElement(elem, "price").text = str(price)
        _write_tree(tree, root)

        pos = len(root.findall("product")) - 1
        log.info(f"INSERT OK  id={prod_id}  name='{name}'  price={price}  →  pos={pos}")
        return pos


def _do_query(prod_id: str) -> int:

    time.sleep(QUERY_DELAY)   # Simula carga de consulta

    with file_lock:
        tree = ET.parse(XML_FILE)
        root = tree.getroot()
        for i, p in enumerate(root.findall("product")):
            if p.findtext("id") == str(prod_id):
                log.info(f"QUERY HIT  id={prod_id}  →  pos={i}")
                return i

    log.info(f"QUERY MISS id={prod_id}  →  pos=-1")
    return -1


def _send_callback(callback_url: str, operation: str, position: int, message: str) -> None:
    """
    Invoca la función 'client_callback' en el servidor de callback del cliente.

    Parámetros
    ----------
    callback_url : str → URL http://host:port/ del cliente.
    operation    : str → "insert" o "query".
    position     : int → Posición en el XML, o -1.
    message      : str → Descripción legible del resultado.
    """
    try:
        proxy = xmlrpc.client.ServerProxy(callback_url, allow_none=True)
        proxy.client_callback(operation, position, message)
        log.info(f"CALLBACK SENT  →  {callback_url}  op={operation}  pos={position}")
    except Exception as e:
        log.error(f"CALLBACK ERROR  →  {callback_url}  {e}")


def worker() -> None:
    """
    Ciclo infinito del hilo de trabajo.

    Extrae la tarea de mayor prioridad de la cola (bloqueante),
    la ejecuta y envía el resultado al cliente vía callback.
    La PriorityQueue garantiza que los INSERTs (prioridad 0) se
    procesen antes que las QUERYs (prioridad 1).
    """
    while True:
        priority, seq, task_type, args = task_queue.get()
        log.info(f"DEQUEUE  type={task_type}  priority={priority}  seq={seq}")
        try:
            if task_type == "insert":
                prod_id, name, price, callback_url = args
                pos = _do_insert(prod_id, name, price)
                msg = (f"Inserción id={prod_id} nombre='{name}' precio={price} → "
                       f"{'posición ' + str(pos) if pos >= 0 else 'ya existía en pos ' + str(pos)}")
                _send_callback(callback_url, "insert", pos, msg)

            elif task_type == "query":
                prod_id, callback_url = args
                pos = _do_query(prod_id)
                msg = (f"Consulta id={prod_id} → "
                       f"{'posición ' + str(pos) if pos >= 0 else 'no encontrado (-1)'}")
                _send_callback(callback_url, "query", pos, msg)

        except Exception as e:
            log.error(f"WORKER ERROR  seq={seq}  {e}")
        finally:
            task_queue.task_done()


def rpc_insert(prod_id: str, name: str, price: float, callback_url: str) -> str:
    seq = _next_seq()
    task_queue.put((PRIORITY_INSERT, seq, "insert", (prod_id, name, price, callback_url)))
    log.info(f"ENQUEUE INSERT  id={prod_id}  seq={seq}  client={callback_url}")
    return "Queued"


def rpc_query(prod_id: str, callback_url: str) -> str:
 
    seq = _next_seq()
    task_queue.put((PRIORITY_QUERY, seq, "query", (prod_id, callback_url)))
    log.info(f"ENQUEUE QUERY   id={prod_id}  seq={seq}  client={callback_url}")
    return "Queued"


if __name__ == "__main__":
    init_xml()

    # Lanzar hilos de trabajo
    for i in range(NUM_WORKERS):
        t = threading.Thread(target=worker, name=f"Worker-{i + 1}", daemon=True)
        t.start()
        log.info(f"Worker-{i + 1} iniciado.")

    # Iniciar servidor RPC
    rpc_server = SimpleXMLRPCServer(
        (SERVER_HOST, SERVER_PORT),
        allow_none=True,
        logRequests=False
    )
    rpc_server.register_function(rpc_insert, "insert_product")
    rpc_server.register_function(rpc_query,  "query_product")
    rpc_server.register_multicall_functions()

    log.info(f"Servidor RPC listo en {SERVER_HOST}:{SERVER_PORT} "
             f"con {NUM_WORKERS} workers.")
    rpc_server.serve_forever()
