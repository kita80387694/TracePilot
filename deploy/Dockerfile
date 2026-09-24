FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY business-service/pom.xml ./pom.xml
RUN mvn -B dependency:go-offline
COPY business-service/src ./src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/business-service-0.1.0.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
