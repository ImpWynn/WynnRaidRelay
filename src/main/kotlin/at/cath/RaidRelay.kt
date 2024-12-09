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

@Serializable
data class RaidReport(val raidType: String, val players: List<String>, val reporterUuid: String)

data class PlayerInfo(val name: String, val guild: String?)

class RaidCooldownManager {
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val cooldownDuration = TimeUnit.SECONDS.toMillis(10)

    fun shouldProcess(raidType: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = cooldowns.putIfAbsent(raidType, now) ?: return true

        if (now - previous > cooldownDuration) {
            return cooldowns.replace(raidType, previous, now)
        }

        return false
    }
}

suspend fun fetchPlayerInfo(uuid: String): PlayerInfo? {
    return try {
        val response: HttpResponse = client.get("https://api.wynncraft.com/v3/player/$uuid")
        if (response.status.isSuccess()) {
            val jsonResponse = response.body<String>()
            val parsedJson = Json.parseToJsonElement(jsonResponse).jsonObject

            PlayerInfo(
                name = parsedJson["username"]?.jsonPrimitive?.content ?: "Unknown",
                guild = parsedJson["guild"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            )
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

fun main() {
    val webhookUrl = System.getenv("DISCORD_WEBHOOK_URL").takeIf {
        it.matches(WEBHOOK_PATTERN)
    } ?: throw IllegalArgumentException("DISCORD_WEBHOOK_URL is required")

    val expectedGuild = System.getenv("GUILD")
        ?: throw IllegalArgumentException("GUILD environment variable is required")

    val cooldownManager = RaidCooldownManager()

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json(json = Json)
        }
        routing {
            post("/raid") {
                val raidReport = call.receive<RaidReport>()

                val playerInfo = fetchPlayerInfo(raidReport.reporterUuid)
                if (playerInfo == null || playerInfo.guild != expectedGuild) {
                    call.respond(HttpStatusCode.Forbidden, "Unauthorized")
                    log.error("Unauthorized raid report from UUID: ${raidReport.reporterUuid}")
                    return@post
                }

                if (!cooldownManager.shouldProcess(raidReport.raidType)) {
                    call.respond(HttpStatusCode.TooManyRequests, "Raid message ignored due to cooldown")
                    return@post
                }

                val response = sendDiscordWebhook(
                    webhookUrl,
                    raidMsg(
                        raidReport.raidType,
                        raidReport.players,
                        raids[raidReport.raidType] ?: run {
                            call.respond(HttpStatusCode.BadRequest, "Unknown raid type")
                            log.error("Unknown raid type: ${raidReport.raidType}")
                            return@post
                        }
                    )
                )
                if (!response.status.isSuccess()) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to send raid message")
                    log.error("Failed to send raid message: ${response.status}")
                    return@post
                }
                log.info(
                    "Processed raid completion reported by ${playerInfo.name} " +
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

private fun raidMsg(raidName: String, players: List<String>, raidImgUrl: String): String {
    return """
        {
            "content": null,
            "embeds": [
                {
                    "title": "Completion: $raidName",
                    "color": null,
                    "fields": [
                        {
                            "name": "Player 1",
                            "value": "${players.getOrElse(0) { "N/A" }}",
                            "inline": true
                        },
                        {
                            "name": "Player 2",
                            "value": "${players.getOrElse(1) { "N/A" }}",
                            "inline": true
                        },
                        {
                            "name": "\t",
                            "value": "\t"
                        },
                        {
                            "name": "Player 3",
                            "value": "${players.getOrElse(2) { "N/A" }}",
                            "inline": true
                        },
                        {
                            "name": "Player 4",
                            "value": "${players.getOrElse(3) { "N/A" }}",
                            "inline": true
                        }
                    ],
                    "author": {
                        "name": "Guild Raid Notification",
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