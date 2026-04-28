# Multi-stage build: compile with full JDK, run with JRE only
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Cloud Run sends SIGTERM, Spring Boot handles graceful shutdown
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
