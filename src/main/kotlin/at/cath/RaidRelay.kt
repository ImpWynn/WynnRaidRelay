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

private val WEBHOOK_PATTERN = "https://(?:[\\w-]+\\.)?discord\\.com/api/webhooks/\\d+/[\\w-]+".toRegex()
private val client = HttpClient(CIO)
private val raids = mapOf(
    "The Canyon Colossus" to "https://static.wikia.nocookie.net/wynncraft_gamepedia_en/images/2/2d/TheCanyonColossusIcon.png",
    "The Nameless Anomaly" to "https://static.wikia.nocookie.net/wynncraft_gamepedia_en/images/9/92/TheNamelessAnomalyIcon.png",
    "Orphion's Nexus of Light" to "https://static.wikia.nocookie.net/wynncraft_gamepedia_en/images/6/63/Orphion%27sNexusofLightIcon.png",
    "Nest of the Grootslangs" to "https://static.wikia.nocookie.net/wynncraft_gamepedia_en/images/5/52/NestoftheGrootslangsIcon.png"
)

@Volatile
private var lastGuildUpdate = 0L
private val guildMembers = mutableSetOf<String>()

private val guildUpdateLock = Mutex()

private val logger = org.slf4j.LoggerFactory.getLogger("RaidProcessor")

@Serializable
data class RaidReport(
    val raidType: String,
    val players: List<String>,
    val reporterUuid: String,
    val gxpGained: String,
    val srGained: Int,
) {
    override fun hashCode(): Int {
        val hash = 31 * raidType.hashCode() + players.hashCode()
        logger.debug("Generated hash for raid '{}' with players {}: {}", raidType, players, hash)
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
    logger.debug("Current cooldowns map state: {}", cooldowns.toMap())

    val previous = cooldowns.putIfAbsent(raidKey, now)
    logger.debug("putIfAbsent for key $raidKey returned previous value: $previous")

    if (previous == null) {
        logger.debug("No previous timestamp found, allowing raid")
        return true
    }

    val timeDiff = now - previous
    logger.debug("Time difference: ${timeDiff}ms (cooldown: ${cooldownDuration}ms)")

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
    val response: HttpResponse = client.get("https://api.wynncraft.com/v3/guild/$guild")
    if (response.status.isSuccess()) {
        val jsonResponse = response.body<String>()
        val parsedJson = Json.parseToJsonElement(jsonResponse).jsonObject

        val members = parsedJson["members"]!!.jsonObject
        val updatedMembers = mutableListOf<String>()

        for (rank in members.keys.drop(1)) {
            val rankObject = members[rank]?.jsonObject ?: continue
            val uuids = rankObject.values.mapNotNull { playerElement ->
                playerElement.jsonObject["uuid"]?.jsonPrimitive?.content
            }

            updatedMembers.addAll(uuids)
        }

        guildMembers.clear()
        guildMembers.addAll(updatedMembers)
        lastGuildUpdate = System.currentTimeMillis()
    } else {
        throw IllegalStateException("Failed to update guild members for guild '$guild'")
    }
}

suspend fun isInGuild(uuid: String): Boolean {
    try {
        guildUpdateLock.lock()
        if (System.currentTimeMillis() - lastGuildUpdate > TimeUnit.MINUTES.toMillis(10))
            updateGuild()
        return uuid in guildMembers
    } finally {
        guildUpdateLock.unlock()
    }
}

fun main() {
    val webhookUrl = System.getenv("DISCORD_WEBHOOK_URL").takeIf {
        it.matches(WEBHOOK_PATTERN)
    } ?: throw IllegalArgumentException("DISCORD_WEBHOOK_URL is required")

    System.getenv("GUILD") ?: throw IllegalArgumentException("GUILD environment variable is required")

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json(json = Json)
        }
        routing {
            post("/raid") {
                val raidReport = call.receive<RaidReport>()
                val raidImg = raids[raidReport.raidType] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Unknown raid type")
                    logger.error("Unknown raid type: ${raidReport.raidType}")
                    return@post
                }

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

                val response = sendDiscordWebhook(
                    webhookUrl,
                    raidMsg(
                        raidReport,
                        raidImg
                    )
                )
                if (!response.status.isSuccess()) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to send raid message")
                    logger.error("Failed to send raid message: ${response.status}")
                    return@post
                }
                logger.info(
                    "Processed raid completion reported by ${raidReport.reporterUuid} " +
                            "for '${raidReport.raidType}' with players: ${raidReport.players}"
                )
                call.respond(HttpStatusCode.OK, "Raid message processed")
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
