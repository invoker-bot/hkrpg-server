FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle gradle
COPY lib lib
COPY src src
RUN chmod +x gradlew && ./gradlew test shadowJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /srv/hkrpg
COPY --from=build /src/build/libs/hkrpg-server.jar server.jar
COPY LICENSE NOTICE.md ./
EXPOSE 8080/tcp 23301/udp
ENTRYPOINT ["java", "-jar", "server.jar"]
CMD ["--mode=dispatch", "--no-console"]
