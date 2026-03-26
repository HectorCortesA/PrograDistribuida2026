
from xmlrpc.server import SimpleXMLRPCServer, SimpleXMLRPCRequestHandler
from socketserver import ThreadingMixIn
import threading

class ThreadedXMLRPCServer(ThreadingMixIn, SimpleXMLRPCServer):
    daemon_threads = True        

def suma(x, y):
    return x + y

def resta(x, y):
    return x - y

def multiplica(x, y):
    return x * y

def divide(x, y):
    if y == 0:
        raise ValueError("División por cero")
    return x / y

def factorial(n):
    if n < 0:
        raise ValueError("n debe ser >= 0")
    if n == 0:
        return 1
    return n * factorial(n - 1)

def fibonacci(n):
    if n <= 0:
        return 0
    if n == 1:
        return 1
    return fibonacci(n - 1) + fibonacci(n - 2)

def info_hilo():
    return threading.current_thread().name


HOST = '172.26.160.230'  
PORT = 8000

with ThreadedXMLRPCServer(
        (HOST, PORT),
        requestHandler=SimpleXMLRPCRequestHandler,
        allow_none=True) as server:

    server.register_function(suma,       'suma')
    server.register_function(resta,      'resta')
    server.register_function(multiplica, 'multiplica')
    server.register_function(divide,     'divide')
    server.register_function(factorial,  'factorial')
    server.register_function(fibonacci,  'fibonacci')
    server.register_function(info_hilo,  'info_hilo')

    print(f"Servidor XML-RPC concurrente escuchando en {HOST}:{PORT}")
    print("Presiona Ctrl+C para detener.\n")
    server.serve_forever()

    """
    visto en clase
    from xmlrpc.server import SimpleXMLRPCServer
from xmlrpc.server import SimpleXMLRPCRequestHandler

def suma(x, y):
    return x + y

def resta(x, y):
    return x - y

def multiplica(x, y):
    return x * y

def divide(x, y):
    return x / y

def factorial(n):
    if n == 0:
        return 1
    else:
        return n * factorial(n-1)
    
def fibonacci(n):
    if n <= 0:
        return 0
    elif n == 1:
        return 1
    else:
        return fibonacci(n-1) + fibonacci(n-2)

with SimpleXMLRPCServer(('172.26.160.230', 8000), requestHandler=SimpleXMLRPCRequestHandler) as server:
    server.register_function(suma, 'suma')
    server.register_function(resta, 'resta')
    server.register_function(multiplica, 'multiplica')
    server.register_function(divide, 'divide')
    server.register_function(factorial, 'factorial')
    server.register_function(fibonacci, 'fibonacci')
    
 
    print("Conectado")
    server.serve_forever() """