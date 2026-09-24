FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY refactor/diagnosis-service/pom.xml ./pom.xml
RUN mvn -B dependency:go-offline
COPY refactor/diagnosis-service/src ./src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/diagnosis-service-0.1.0.jar /app/diagnosis.jar
USER 10001:10001
ENTRYPOINT ["java","-jar","/app/diagnosis.jar"]
