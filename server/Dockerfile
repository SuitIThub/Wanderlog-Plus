# Build-Kontext = Repo-Root: docker build -f server/Dockerfile -t wanderlog-plus-server .
FROM gradle:8.11.1-jdk17 AS build
# Build-Stage läuft als root (nur das Laufzeit-Image zählt; dort uid 10001).
USER root
WORKDIR /src
COPY . .
RUN gradle :server:installDist --no-daemon

FROM eclipse-temurin:17-jre
# ffmpeg zerlegt Reels in Einzelbilder und Ton, yt-dlp lädt sie. yt-dlp kommt bewusst als aktuelle Release-Datei
# statt aus apt: Instagram ändert sich oft, ältere Versionen scheitern dann. Neu bauen holt ein neues yt-dlp.
RUN apt-get update && apt-get install -y --no-install-recommends curl ffmpeg ca-certificates \
    && curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux -o /usr/local/bin/yt-dlp \
    && chmod 755 /usr/local/bin/yt-dlp \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin wlp \
    && mkdir /data && chown 10001 /data
COPY --from=build /src/server/build/install/server /app
USER 10001
ENV WLP_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
# Healthcheck setzt den Default-Port 8080 voraus (WLP_PORT im Container nicht ändern).
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD curl -fs http://localhost:8080/api/health || exit 1
ENTRYPOINT ["/app/bin/server"]
