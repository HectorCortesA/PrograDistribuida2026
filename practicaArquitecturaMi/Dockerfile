# Usa una imagen ligera de Python
FROM python:3.10-slim

# Evita que Python genere archivos .pyc y permite ver logs en tiempo real
ENV PYTHONDONTWRITEBYTECODE 1
ENV PYTHONUNBUFFERED 1

WORKDIR /app

# Instalar dependencias necesarias
RUN pip install --no-cache-dir pika grpcio grpcio-tools

# Copiar todos los archivos del proyecto al contenedor
COPY . .

# Comando por defecto (será sobrescrito en el docker-compose o al correrlo)
# Pasamos la IP de RabbitMQ como argumento al script
ENTRYPOINT ["python"]