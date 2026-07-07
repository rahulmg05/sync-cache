FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY checkstyle/ checkstyle/
RUN mvn dependency:go-offline -q
COPY src/ src/
RUN mvn package -q -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/sync-cache.jar sync-cache.jar
COPY config.yml config.yml
EXPOSE 6379
ENTRYPOINT ["java", "--enable-preview", "-jar", "sync-cache.jar", "--config", "config.yml"]
