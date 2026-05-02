import pika, json, sys

def callback(ch, method, props, body):
    data = json.loads(body)
    resultado = data['num1'] - data['num2']
    print(f" RESTA {data['num1']} - {data['num2']} = {resultado}")
    
    ch.basic_publish(exchange='', routing_key=props.reply_to,
                     properties=pika.BasicProperties(correlation_id=props.correlation_id),
                     body=json.dumps({"data": resultado}))
    ch.basic_ack(delivery_tag=method.delivery_tag)

ip = sys.argv[1] if len(sys.argv) > 1 else 'localhost'
conn = pika.BlockingConnection(pika.ConnectionParameters(host=ip))
channel = conn.channel()
channel.queue_declare(queue='resta')
channel.basic_consume(queue='resta', on_message_callback=callback)
channel.start_consuming()