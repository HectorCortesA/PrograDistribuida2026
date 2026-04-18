# 1. Instalar dependencias
pip install grpcio grpcio-tools

# 2. Generar archivos Python desde el .proto
python -m grpc_tools.protoc \
    -I. \
    --python_out=. \
    --grpc_python_out=. \
    calculator.proto

# 3. Iniciar servidor (en una terminal)
python server.py

# 4. Ejecutar cliente (en otra terminal)
python client.py