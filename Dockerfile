FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /src
COPY MarketPix/Java/pom.xml pom.xml
COPY MarketPix/Java/src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
# ImageMagick converts formats Java cannot read, such as iPhone HEIC and AVIF. Newer libheif ships its
# HEVC (HEIC) and AV1 (AVIF) decoders as separate plugin packages; older releases have them built in.
RUN apt-get update \
    && apt-get install -y --no-install-recommends imagemagick libheif1 \
    && (apt-get install -y --no-install-recommends libheif-plugin-libde265 libheif-plugin-dav1d || true) \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/marketpix-api-*.jar /app/app.jar
COPY MarketPix/UserInterface /app/ui
EXPOSE 3008
CMD ["java", "-jar", "/app/app.jar"]
