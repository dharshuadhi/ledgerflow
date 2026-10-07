# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Cache dependencies first for faster rebuilds.
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN addgroup --system ledgerflow && adduser --system --ingroup ledgerflow ledgerflow
COPY --from=build /app/target/ledgerflow-*.jar app.jar
USER ledgerflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
