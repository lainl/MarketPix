FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends imagemagick libheif1 curl \
    && (apt-get install -y --no-install-recommends libheif-plugin-libde265 libheif-plugin-dav1d || true) \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin marketpix
WORKDIR /app
COPY app.jar /app/app.jar
COPY ui /app/ui
USER 10001
EXPOSE 3008
CMD ["java", "-jar", "/app/app.jar"]
