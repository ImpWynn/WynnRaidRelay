package at.cath

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Channel

class RabbitPublisher(
    host: String,
    port: Int,
    username: String,
    password: String,
    virtualHost: String
) {
    private val factory = ConnectionFactory().apply {
        this.host = host
        this.port = port
        this.username = username
        this.password = password
        this.virtualHost = virtualHost
        isAutomaticRecoveryEnabled = true
        networkRecoveryInterval = 5000
    }

    private val connection: Connection = factory.newConnection("raid-bot")
    private val channel: Channel = connection.createChannel()

    fun ensureTopology(exchange: String) {
        channel.exchangeDeclare(exchange, "topic", true)
    }

    fun publishJson(exchange: String, routingKey: String, json: String) {
        val props = AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .deliveryMode(2) // 2 = persistent
            .build()

        channel.basicPublish(exchange, routingKey, props, json.toByteArray(Charsets.UTF_8))
    }

    fun close() {
        try { channel.close() } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }
}
