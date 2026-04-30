import pika
import sys
import os

def main():
    # Conectamos a la red donde vive RabbitMQ
    connection = pika.BlockingConnection(pika.ConnectionParameters(host='rabbitmq-broker'))
    channel = connection.channel()

    # Declaramos la misma cola 'hello'
    channel.queue_declare(queue='hello')

    def callback(ch, method, properties, body):
        print(f" [x] Consumidor PYTHON recibió: {body.decode()}")

    channel.basic_consume(queue='hello', 
                          on_message_callback=callback, 
                          auto_ack=True)

    print(' [*] Consumidor PYTHON esperando mensajes. Para salir presiona CTRL+C')
    channel.start_consuming()

if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('Interrumpido')
        try:
            sys.exit(0)
        except SystemExit:
            os._exit(0)
    except Exception as e:
        print(f"Error de conexión: {e}")
        sys.exit(1) # Salimos con error para que Docker reinicie el contenedor si RabbitMQ no está listo