FROM --platform=$BUILDPLATFORM bellsoft/liberica-openjdk-debian:26-37 AS builder

WORKDIR /app

COPY gradlew ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY backend/build.gradle.kts backend/
COPY webui/build.gradle.kts webui/

RUN chmod +x gradlew

COPY webui/package*.json webui/

RUN ./gradlew :backend:dependencies --no-daemon || true

COPY backend/src backend/src
COPY webui webui

RUN ./gradlew build --no-daemon

FROM bellsoft/liberica-openjdk-alpine:21.0.7

WORKDIR /app

COPY --from=builder /app/backend/build/libs/TelegramWebHookProxy-*-all.jar app.jar

EXPOSE 10178
VOLUME [ "/app/config" ]

ENTRYPOINT ["java", "-jar", "app.jar"]
