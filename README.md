Simple ktor server for the [Wynn Raid Reporter mod](https://github.com/otcathatsya/wynn-raid-reporter).
This server accepts POST requests from the mod and relays them to a discord webhook.

Run it with docker:
```docker
DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/your/webhook" GUILD="GuildName" docker compose up --build
```
