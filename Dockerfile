FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
RUN chmod +x mvnw
RUN ./mvnw -B -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home relay
COPY --from=build /workspace/target/upi-offline-mesh-0.0.1-SNAPSHOT.jar /app/relay.jar
RUN mkdir -p /app/data && chown -R relay:relay /app
USER relay
EXPOSE 10000
ENV SERVER_ADDRESS=0.0.0.0
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/relay.jar"]
