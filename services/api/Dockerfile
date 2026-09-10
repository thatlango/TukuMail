FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY services /src/services
RUN cd services && mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 tukumail
WORKDIR /app
COPY --from=build /src/services/api/target/api-0.1.0-SNAPSHOT.jar /app/tukumail-api.jar
USER tukumail
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/tukumail-api.jar"]
