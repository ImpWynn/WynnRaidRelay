package at.cath

import com.google.common.util.concurrent.Striped
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

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

// multiple locks to allow different raid keys to process concurrently
// collisions possible but does not matter since worst case it will just
// process two different raid parties sequentially
private val raidKeyLocks = Striped.lock(32)

@Serializable
data class RaidReport(val raidType: String, val players: List<String>, val reporterUuid: String)

// we check the first player name since many parties may be running the same raid
data class UniqueRaidParty(val raidName: String, val firstPlayerName: String)

private val cooldowns = ConcurrentHashMap<UniqueRaidParty, Long>()
private val cooldownDuration = TimeUnit.MINUTES.toMillis(1)

fun shouldProcess(raidKey: UniqueRaidParty): Boolean {
    raidKeyLocks.get(raidKey).withLock {
        val now = System.currentTimeMillis()
        val previous = cooldowns[raidKey]
        if (previous == null) {
            cooldowns[raidKey] = now
            return true
        }
        if (now - previous <= cooldownDuration) return false
        cooldowns[raidKey] = now
        return true
    }
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
            guildMembers.addAll(members[rank]?.jsonObject?.keys.orEmpty())
        }
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
                val raidParty = UniqueRaidParty(raidReport.raidType, raidReport.players.first())

                val raidImg = raids[raidReport.raidType] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Unknown raid type")
                    log.error("Unknown raid type: ${raidReport.raidType}")
                    return@post
                }

                if (!isInGuild(raidReport.reporterUuid)) {
                    call.respond(HttpStatusCode.Forbidden, "Unauthorized")
                    log.error("Unauthorized raid report from UUID: ${raidReport.reporterUuid}")
                    return@post
                }

                if (!shouldProcess(raidParty)) {
                    log.error("Raid message from ${raidReport.reporterUuid} ignored due to cooldown")
                    call.respond(HttpStatusCode.TooManyRequests, "Raid message ignored due to cooldown")
                    return@post
                }

                val response = sendDiscordWebhook(
                    webhookUrl,
                    raidMsg(
                        raidReport.raidType,
                        raidReport.players,
                        raidImg
                    )
                )
                if (!response.status.isSuccess()) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to send raid message")
                    log.error("Failed to send raid message: ${response.status}")
                    return@post
                }
                log.info(
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
