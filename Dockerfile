FROM node:22-bookworm-slim AS reader
WORKDIR /src/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS server
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src src
COPY --from=reader /src/frontend/dist /src/frontend/dist
RUN mvn -B package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=server /src/backend/target/devflash-1.0.0.jar /app/devflash.jar
ENV SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=62 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/devflash.jar"]
