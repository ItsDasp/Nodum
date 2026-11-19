FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget && addgroup -S nodum && adduser -S -G nodum nodum
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
RUN chown -R nodum:nodum /app
USER nodum
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
