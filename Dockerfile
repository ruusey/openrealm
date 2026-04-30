FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre
COPY --from=build /app/target/openrealm-0.6.0-shaded.jar /openrealm.jar
EXPOSE 2222
ENV DATA_SERVER_ADDR=host.docker.internal
ENTRYPOINT ["sh", "-c", "java -jar /openrealm.jar $DATA_SERVER_ADDR"]
