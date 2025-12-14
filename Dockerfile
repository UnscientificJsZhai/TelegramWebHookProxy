FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle gradle
COPY backend/build.gradle.kts backend/
COPY webui/build.gradle.kts webui/

COPY backend/src backend/src
COPY webui webui

RUN chmod +x gradlew

RUN ./gradlew build --no-daemon

FROM eclipse-temurin:17-jre-alpine

WORKDIR /

COPY --from=builder /app/backend/build/libs/TelegramWebHookProxy-*-all.jar app.jar

EXPOSE 10178
VOLUME [ "config" ]

ENTRYPOINT ["java", "-jar", "app.jar"]
