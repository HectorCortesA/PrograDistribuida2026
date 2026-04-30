# Proyecto gRPC - Sumas

Este es un proyecto de prueba con gRPC en Python que implementa un cliente-servidor para enviar vectores de números y recibir sus sumas.

## Requisitos

- Python 3.9+
- pip

## Instalación

1. **Crear y activar virtual environment:**

```bash
python3 -m venv .venv
source .venv/bin/activate  # En macOS/Linux
# o
.venv\Scripts\activate  # En Windows
```

2. **Instalar dependencias:**

```bash
pip install grpcio grpcio-tools
```

3. **Compilar archivos proto (si es necesario):**

```bash
python3 -m grpc_tools.protoc -I./Protos --python_out=./Protos --grpc_python_out=./Protos ./Protos/numeros.proto
```

## Ejecución

### Terminal 1 - Iniciar el servidor:

```bash
source .venv/bin/activate
python3 Server.py
```

### Terminal 2 - Ejecutar cliente(s):

**Cliente con ID automático:**

```bash
source .venv/bin/activate
python3 Cliente.py
```

**Cliente con ID específico:**

```bash
source .venv/bin/activate
python3 Cliente.py cliente_1
python3 Cliente.py cliente_2
# Ejecuta más instancias en más terminales
```

## Características

- El servidor genera vectores aleatorios de 3 números
- Los clientes reciben el vector, calculan la suma y envían el resultado
- El servidor detiene cuando alcanza 1,000,000 resultados procesados
- Múltiples clientes pueden conectarse simultáneamente
- El servidor mantiene un historial de los clientes

## Puertos

- El servidor escucha en: **localhost:50051**
- Los clientes se conectan a: **localhost:50051**
