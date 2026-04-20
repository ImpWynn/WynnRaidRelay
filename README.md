Simple ktor server for the [Wynn Raid Reporter mod](https://github.com/otcathatsya/wynn-raid-reporter).
This server accepts POST requests from the mod and relays them to a discord webhook.
It also produces messages to RabbitMQ for processing by a consumer.

## Setup

### 1. Copy the example env file:
```
cp .env.example .env
```
### 2. Fill in your values inside `.env`:
Your `.env` should look like:

```
GUILD=YourGuildName

DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/your/webhook

RABBIT_HOST=rabbit-hostname
RABBIT_PORT=0000
RABBIT_USER=rabbit-username
RABBIT_PASS=rabbit-password
RABBIT_VHOST=rabbit-vhost
```
### 3. Run with Docker:
``` bash
docker compose up --build
```

   

