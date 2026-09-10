FROM maven:3.9.11-eclipse-temurin-21 AS extensions
WORKDIR /build
COPY services/pom.xml services/pom.xml
COPY services/mail-extensions/pom.xml services/mail-extensions/pom.xml
RUN mvn -B -f services/pom.xml -pl mail-extensions dependency:go-offline
COPY services/mail-extensions services/mail-extensions
RUN mvn -B -f services/pom.xml -pl mail-extensions package -DskipTests

FROM apache/james:postgres-3.9.0
COPY --from=extensions /build/services/mail-extensions/target/mail-extensions-0.1.0-SNAPSHOT.jar /root/extensions-jars/tukumail-mail-extensions.jar
