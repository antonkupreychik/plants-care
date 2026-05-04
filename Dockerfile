# =====================================================
# Stage 1: Build
# =====================================================
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Сначала копируем только pom.xml — слой кэшируется отдельно от исходников.
# Зависимости перекачиваются заново только если меняется pom.xml.
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Теперь копируем исходники и собираем
COPY src ./src
RUN mvn clean package -DskipTests -B

# =====================================================
# Stage 2: Runtime
# =====================================================
FROM eclipse-temurin:21-jre-alpine

# Создаём непривилегированного пользователя — не запускаем приложение от root
RUN addgroup -S spring && adduser -S spring -G spring

WORKDIR /app

# Копируем jar из builder-стадии
COPY --from=builder /build/target/*.jar app.jar

# Устанавливаем владельца на не-root юзера
RUN chown -R spring:spring /app
USER spring

EXPOSE 8080

# Healthcheck через actuator (требует curl — но в alpine его нет, используем wget из busybox)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# JVM-флаги для контейнера: уважать лимиты памяти, использовать /dev/urandom для рандома
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
