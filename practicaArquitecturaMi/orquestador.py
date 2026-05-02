import pika, uuid, json, sys

class Orquestador:
    def __init__(self, rabbit_ip):
        self.connection = pika.BlockingConnection(pika.ConnectionParameters(host=rabbit_ip))
        self.channel = self.connection.channel()
        result = self.channel.queue_declare(queue='', exclusive=True)
        self.callback_queue = result.method.queue
        self.channel.basic_consume(queue=self.callback_queue, on_message_callback=self.on_response, auto_ack=True)

    def on_response(self, ch, method, props, body):
        if self.corr_id == props.correlation_id:
            self.response = json.loads(body)

    def enviar_tarea(self, cola, n1, n2):
        self.response = None
        self.corr_id = str(uuid.uuid4())
        self.channel.basic_publish(
            exchange='', routing_key=cola,
            properties=pika.BasicProperties(reply_to=self.callback_queue, correlation_id=self.corr_id),
            body=json.dumps({'num1': n1, 'num2': n2}))
        while self.response is None:
            self.connection.process_data_events()
        return self.response

    def ejecutar_cadena(self, cadena):
        # Ejemplo: 5+3*2-1 siguiendo tu lógica:
        # 1. Multiplicación(3,2)
        resp1 = self.enviar_tarea('multiplicacion', 3, 2)
        # 2. Suma(5, resp1)
        resp2 = self.enviar_tarea('suma', 5, resp1['data'])
        # 3. Resta(resp2, 1)
        resp3 = self.enviar_tarea('resta', resp2['data'], 1)
        
        return resp3['data']

if __name__ == "__main__":
    ip_rabbit = sys.argv[1]
    orc = Orquestador(ip_rabbit)
    resultado = orc.ejecutar_cadena("5+3*2-1")
    print(f"Resultado final: {resultado}")