# Runtime image for any one service. Build the jars first:  ./mvnw package -DskipTests
# then: docker build --build-arg MODULE=ingestion-gateway -t iot/ingestion-gateway .
FROM eclipse-temurin:17-jre-alpine

ARG MODULE
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY ${MODULE}/target/${MODULE}.jar app.jar
USER app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
