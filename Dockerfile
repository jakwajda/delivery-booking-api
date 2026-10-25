FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/delivery-booking-api-0.0.1-SNAPSHOT.jar app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
