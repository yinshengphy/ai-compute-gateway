FROM m.daocloud.io/docker.io/library/maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM m.daocloud.io/docker.io/library/eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/ai-compute-gateway-0.1.0.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
