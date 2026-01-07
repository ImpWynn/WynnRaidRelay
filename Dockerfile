FROM gradle:8.5-jdk21 AS build

WORKDIR /home/gradle/src

COPY build.gradle.kts settings.gradle.kts gradle.properties ./

COPY src ./src

RUN gradle buildFatJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /home/gradle/src/build/libs/*.jar ./app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]