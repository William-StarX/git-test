ARG MODULE

FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build

ARG MODULE

WORKDIR /build

COPY pom.xml ./
COPY ${MODULE}/pom.xml ${MODULE}/pom.xml
COPY ${MODULE}/src ${MODULE}/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f ${MODULE}/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a

ARG MODULE

WORKDIR /app

COPY --from=build /build/${MODULE}/target/${MODULE}-0.1.0.jar /app/app.jar

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid 10001 --no-create-home app \
    && chown -R 10001:10001 /app

USER 10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
