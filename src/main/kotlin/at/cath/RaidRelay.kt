package at.cath

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.time.Instant
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val WEBHOOK_PATTERN = "https://(?:[\\w-]+\\.)?discord\\.com/api/webhooks/\\d+/[\\w-]+".toRegex()
private val client = HttpClient(CIO)

data class RaidInfo(val id: Int, val imageUrl: String)
private val raids = mapOf(
    "The Canyon Colossus" to RaidInfo(1, "https://static.wikia.nocookie.net/wynncraft_gamepedia_en/images/2/2d/TheCanyonColossusIcon.png"),
    "The Nameless Anomaly" to RaidInfo(2, "https://static.wikia.nocookie.net/wynncraft_gamepedia_en/images/9/92/TheNamelessAnomalyIcon.png"),
    "Orphion's Nexus of Light" to RaidInfo(3, "https://static.wikia.nocookie.net/wynncraft_gamepedia_en/images/6/63/Orphion%27sNexusofLightIcon.png"),
    "Nest of the Grootslangs" to RaidInfo(4, "https://static.wikia.nocookie.net/wynncraft_gamepedia_en/images/5/52/NestoftheGrootslangsIcon.png")
)

@Serializable
data class WebsiteRaidPayload(
    val raidId: Int,
    val completedDate: String,
    val minecraftUsernames: List<String>
)

@Volatile
private var lastGuildUpdate = 0L
private val guildMembers = mutableMapOf<String, String>()

private val guildUpdateLock = Mutex()

private val logger = org.slf4j.LoggerFactory.getLogger("RaidProcessor")
private val jsonConfig = Json { encodeDefaults = true }



@Serializable
data class RaidReport(
    val raidType: String,
    val players: List<String>,
    val reporterUuid: String,
    val gxpGained: String,
    val srGained: Int,
)
{
    override fun hashCode(): Int {
        val hash = 31 * raidType.hashCode() + players.hashCode()
        return hash
    }

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is RaidReport -> false
        else -> raidType == other.raidType && players == other.players
    }
}

// raid hashcode -> timestamp
private val cooldowns = ConcurrentHashMap<Int, Long>()
private val cooldownDuration = TimeUnit.MINUTES.toMillis(1)

fun shouldProcess(raidReport: RaidReport): Boolean {
    val now = System.currentTimeMillis()
    val raidKey = raidReport.hashCode()

    logger.debug("Processing raid report: type='{}', players={}", raidReport.raidType, raidReport.players)

    val previous = cooldowns.putIfAbsent(raidKey, now) ?: return true

    val timeDiff = now - previous

    if (timeDiff > cooldownDuration) {
        val replaced = cooldowns.replace(raidKey, previous, now)
        logger.debug("Cooldown expired, replace operation succeeded: $replaced")
        return replaced
    }

    logger.debug("Raid blocked by cooldown: ${cooldownDuration - timeDiff}ms remaining")
    return false
}

suspend fun updateGuild() {
    val guild = System.getenv("GUILD")
    val response: HttpResponse = client.get("https://api.wynncraft.com/v3/guild/$guild?identifier=uuid")

    if (response.status.isSuccess()) {
        val jsonResponse = response.body<String>()
        val parsedJson = Json.parseToJsonElement(jsonResponse).jsonObject

        val members = parsedJson["members"]!!.jsonObject
        guildMembers.clear()
        // first key is "total", skip
        for (rank in members.keys.drop(1)) {
            val rankObject = members[rank]?.jsonObject ?: continue
            val playerNames = rankObject.values.mapNotNull { playerElement ->
                playerElement.jsonObject["username"]?.jsonPrimitive?.content
            }
            val uuids = rankObject.keys

            guildMembers.putAll(uuids.zip(playerNames))
        }
        lastGuildUpdate = System.currentTimeMillis()
    } else {
        throw IllegalStateException("Failed to update guild members for guild '$guild'")
    }
}

suspend fun isInGuild(identifier: String): Boolean {
    try {
        guildUpdateLock.lock()
        if (System.currentTimeMillis() - lastGuildUpdate > TimeUnit.MINUTES.toMillis(10)) {
            updateGuild()
        }

        return (identifier in guildMembers) || guildMembers.containsValue(identifier)

    } finally {
        guildUpdateLock.unlock()
    }
}

fun main() {
    val webhookUrl = System.getenv("DISCORD_WEBHOOK_URL").takeIf {
        it.matches(WEBHOOK_PATTERN)
    } ?: throw IllegalArgumentException("DISCORD_WEBHOOK_URL is required")

    System.getenv("GUILD") ?: throw IllegalArgumentException("GUILD environment variable is required")
    installShutdownHook()

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { json(jsonConfig) }

        routing {
            post("/raid") {
                // Raid type check
                val raidReport = call.receive<RaidReport>()
                val raidImg = raids[raidReport.raidType] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Unknown raid type")
                    logger.error("Unknown raid type: ${raidReport.raidType}")
                    return@post
                }

                // Guild check
                val guildPlayers =
                    raidReport.players.toMutableSet().apply { add(raidReport.reporterUuid) }.filter { !isInGuild(it) }
                if (guildPlayers.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        "Unauthorized players in raid report: ${guildPlayers.joinToString(", ")}"
                    )
                    logger.error(
                        "Unauthorized players in raid report from ${raidReport.reporterUuid}: ${
                            guildPlayers.joinToString(
                                ", "
                            )
                        }"
                    )
                    return@post
                }

                // Cooldown check
                if (!isInGuild(raidReport.reporterUuid)) {
                    call.respond(HttpStatusCode.Forbidden, "Unauthorized")
                    logger.error("Unauthorized raid report from UUID: ${raidReport.reporterUuid}")
                    return@post
                }

                if (!shouldProcess(raidReport)) {
                    logger.error("Raid message from ${raidReport.reporterUuid} ignored due to cooldown")
                    call.respond(HttpStatusCode.TooManyRequests, "Raid message ignored due to cooldown")
                    return@post
                }


                val rabbitError = tryRabbitPublish(raidReport)

                val response = sendDiscordWebhook(
                    webhookUrl,
                    raidMsg(
                        raidReport,
                        raidImg.imageUrl
                    )
                )
                if (!response.status.isSuccess()) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to send discord raid message")
                    logger.error("Failed to send discord raid message: ${response.status}")
                    return@post
                }

                logger.info(
                    "Processed discord raid completion reported by ${raidReport.reporterUuid} " +
                            "for '${raidReport.raidType}' with players: ${raidReport.players}"
                )

                // Final response
                val resultMessage =
                    if (rabbitError != null) "Raid processed, but RabbitMQ publish failed: $rabbitError"
                    else "Raid message processed successfully"


                call.respond(HttpStatusCode.OK, resultMessage)
            }
        }
    }.start(wait = true)
}

suspend fun sendDiscordWebhook(webhookUrl: String, message: String): HttpResponse {
    return try {
        client.post(webhookUrl) {
            contentType(ContentType.Application.Json)
            setBody(message)
        }
    } catch (e: Exception) {
        throw e
    }
}

private fun escapeMarkdown(text: String): String {
    return text.replace(Regex("([\\\\_*~`>|])")) { "\\\\${it.value}" }
}

private fun raidMsg(raidObj: RaidReport, raidImgUrl: String): String {
    return """
        {
            "content": null,
            "embeds": [
                {
                    "title": "Completion: ${escapeMarkdown(raidObj.raidType)}",
                    "color": null,
                    "fields": [
                        {
                            "name": "Player 1",
                            "value": "${escapeMarkdown(raidObj.players.getOrElse(0) { "N/A" })}",
                            "inline": true
                        },
                        {
                            "name": "Player 2",
                            "value": "${escapeMarkdown(raidObj.players.getOrElse(1) { "N/A" })}",
                            "inline": true
                        },
                        {
                            "name": "\t",
                            "value": "\t"
                        },
                        {
                            "name": "Player 3",
                            "value": "${escapeMarkdown(raidObj.players.getOrElse(2) { "N/A" })}",
                            "inline": true
                        },
                        {
                            "name": "Player 4",
                            "value": "${escapeMarkdown(raidObj.players.getOrElse(3) { "N/A" })}",
                            "inline": true
                        }
                    ],
                    "footer": {
                            "text": "+${raidObj.srGained} SR, +${raidObj.gxpGained} GXP",
                            "icon_url": "https://wynncraft.wiki.gg/images/RaidSigil2.png"
                    },
                    "author": {
                        "name": "Guild Raid Notification (+ ${raidObj.srGained}SR)",
                        "icon_url": "https://i.imgur.com/PTI0zxK.png"
                    },
                    "thumbnail": {
                        "url": "$raidImgUrl"
                    }
                }
            ],
            "attachments": []
        }
    """
}

private fun createRabbitMQPayload(raidName: String, players: List<String>): String {
    val raidInfo = raids[raidName]
        ?: throw IllegalArgumentException("Unknown raid: $raidName")

    val completedDate = Instant.now().toString()

    logger.debug("Publishing rabbit payload raidId={}, players={}, date={}", raidInfo.id, players, completedDate)

    return jsonConfig.encodeToString(
        WebsiteRaidPayload(
            raidId = raidInfo.id,
            completedDate = completedDate,
            minecraftUsernames = players
        )
    )
}

private const val EXCHANGE_NAME = "raids.exchange"
private const val ROUTING_KEY = "raid.completed"

private fun env(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }


private fun rabbitFactory(): ConnectionFactory {
    val host = env("RABBIT_HOST") ?: "raid-rabbit"
    val port = (env("RABBIT_PORT") ?: "5672").toInt()
    val user = env("RABBIT_USER") ?: "imperial"
    val pass = env("RABBIT_PASS") ?: "ImperialGuild"
    val vhost = env("RABBIT_VHOST") ?: "imperial"

    return ConnectionFactory().apply {
        this.host = host
        this.port = port
        username = user
        password = pass
        virtualHost = vhost
        requestedHeartbeat = 30
        connectionTimeout = 10_000
        isAutomaticRecoveryEnabled = true
        networkRecoveryInterval = 5_000
    }
}

private val rabbitConn by lazy {
    rabbitFactory().newConnection("raid-relay")
}

private fun publishRaidCompleted(payloadJson: String) {
    rabbitConn.createChannel().use { ch ->
        // Enable publisher confirms BEFORE publishing
        ch.confirmSelect()


        val props = AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .deliveryMode(2) // persistent
            .build()

        ch.basicPublish(
            EXCHANGE_NAME,
            ROUTING_KEY,
            props,
            payloadJson.toByteArray(Charsets.UTF_8)
        )

        // Wait for broker confirm (throws if nack / timeout)
        ch.waitForConfirmsOrDie(5_000)
    }
}


private fun installShutdownHook() {
    Runtime.getRuntime().addShutdownHook(Thread {
        try { rabbitConn.close() } catch (_: Exception) {}
    })
}

private suspend fun tryRabbitPublish(raidReport: RaidReport): String? {
    return try {
        val payload = createRabbitMQPayload(raidReport.raidType, raidReport.players)
        withContext(Dispatchers.IO) { publishRaidCompleted(payload) }
        null
    } catch (ex: Exception) {
        val msg = "Rabbit publish error: ${ex.message}"
        logger.error(msg, ex)
        msg
    }
}


