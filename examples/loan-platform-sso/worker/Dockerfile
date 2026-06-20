FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/loan-platform-sso /app
ENV ENGINE_REST_URL=http://operaton:8080/engine-rest
ENTRYPOINT ["/app/bin/loan-platform-sso"]
