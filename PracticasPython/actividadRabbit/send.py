import pika

connection = pika.BlockingConnection(pika.ConnectionParameters(host='rabbitmq-broker'))
channel = connection.channel()

channel.queue_declare(queue='hello')

message = "¡Hola Mundo desde el proyecto actividadRabbit!"
channel.basic_publish(exchange='', routing_key='hello', body=message)

print(f" [x] Enviado: '{message}'")
connection.close()