Simple ktor server for the [Wynn Raid Reporter mod](https://github.com/otcathatsya/wynn-raid-reporter).
This server accepts POST requests from the mod and relays them to a discord webhook.
If configured, it can also sync raid data to an external website API.

## Setup

### 1. Copy the example env file:
```
cp .env.example .env
```
### 2. Fill in your values inside `.env`.
Your `.env` should look like:

```
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/xxx
GUILD=Imperial
IMPERIAL_WEBSITE_URL=https://mywebsite.com/api/raids   # optional
```

### 3. Run with Docker:
``` bash
docker compose up --build
```

   

