import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

public class Recv {
    private final static String QUEUE_NAME = "hello";

    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("rabbitmq-broker");

        Connection connection = factory.newConnection();

        Channel channel = connection.createChannel();
        // Declarar la cola (si no existe)
        channel.queueDeclare(QUEUE_NAME, false, false, false, null);
        // Mostrar mensaje de espera
        System.out
                .println(" [*] Java Consumer (Proyecto Independiente) esperando mensajes. Para salir presiona CTRL+C");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            // Convertir el mensaje recibido a String y mostrarlo
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println(" [x] Recibido en Java: '" + message + "'");
        };

        channel.basicConsume(QUEUE_NAME, true, deliverCallback, consumerTag -> {
        });
    }
}