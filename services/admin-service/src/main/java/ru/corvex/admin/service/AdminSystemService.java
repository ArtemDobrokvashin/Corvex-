package ru.corvex.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import ru.corvex.admin.api.ApiException;
import ru.corvex.admin.api.dto.AdminDtos.AnalyticsResponse;
import ru.corvex.admin.api.dto.AdminDtos.BackupResponse;
import ru.corvex.admin.api.dto.AdminDtos.HealthResponse;
import ru.corvex.admin.api.dto.AdminDtos.ServiceHealth;
import ru.corvex.admin.client.AuthServiceClient;
import ru.corvex.admin.client.AuthServiceClient.RemoteStats;
import ru.corvex.admin.service.AuditService.Actor;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Серверные настройки (server_settings), сводное здоровье сервисов для дашборда,
 * базовая аналитика по пользователям и запрос резервного копирования.
 * Честные границы этапа 1: аналитика почтовой/чатовой активности появится с этапами
 * 2 и 4 (данных ещё нет), бэкап физически выполняет хостовый скрипт по расписанию —
 * здесь фиксируется запрос в аудите, а не имитируется выполнение.
 */
@Service
public class AdminSystemService {

    private static final Logger log = LoggerFactory.getLogger(AdminSystemService.class);

    /** Известные настройки и значения по умолчанию (неизвестные ключи PATCH отклоняет). */
    private static final Map<String, String> DEFAULT_SETTINGS = new LinkedHashMap<>(Map.of(
            "mail.maxMessageSizeMb", "25",
            "mail.defaultQuotaBytes", "5368709120",
            "mail.spamThreshold", "5.0",
            "mail.pop3.enabled", "true",
            "security.passwordMinLength", "10",
            "security.requireTotp", "false",
            "chat.maxFileSizeMb", "25"
    ));

    private record Probe(String name, String healthUrl) {
    }

    private static final List<Probe> PROBES = List.of(
            new Probe("web-gateway", "http://web-gateway:8080/actuator/health"),
            new Probe("auth-service", "http://auth-service:8081/actuator/health"),
            new Probe("admin-service", "http://localhost:8090/actuator/health"),
            new Probe("mail-smtp-service", "http://mail-smtp-service:8082/actuator/health"),
            new Probe("mail-imap-service", "http://mail-imap-service:8083/actuator/health"),
            new Probe("mail-pop3-service", "http://mail-pop3-service:8084/actuator/health"),
            new Probe("mail-storage-service", "http://mail-storage-service:8085/actuator/health"),
            new Probe("mail-spam-service", "http://mail-spam-service:8086/actuator/health"),
            new Probe("mail-antivirus-service", "http://mail-antivirus-service:8087/actuator/health"),
            new Probe("chat-service", "http://chat-service:8088/actuator/health"),
            new Probe("chat-file-service", "http://chat-file-service:8089/actuator/health"),
            new Probe("notification-service", "http://notification-service:8091/actuator/health"));

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AuthServiceClient authClient;
    private final RestClient probeClient;
    private final String rabbitHost;
    private final String rabbitUser;
    private final String rabbitPassword;

    public AdminSystemService(JdbcTemplate jdbc, AuditService audit, AuthServiceClient authClient,
                              @Value("${spring.rabbitmq.host:rabbitmq}") String rabbitHost,
                              @Value("${spring.rabbitmq.username:corvex}") String rabbitUser,
                              @Value("${spring.rabbitmq.password:}") String rabbitPassword) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.authClient = authClient;
        this.rabbitHost = rabbitHost;
        this.rabbitUser = rabbitUser;
        this.rabbitPassword = rabbitPassword;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(800).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(1200).toMillis());
        this.probeClient = RestClient.builder().requestFactory(factory).build();
    }

    // ---------- Настройки ----------

    public Map<String, String> settings() {
        Map<String, String> result = new LinkedHashMap<>(DEFAULT_SETTINGS);
        jdbc.query("SELECT key, value FROM server_settings",
                rs -> {
                    result.put(rs.getString("key"), rs.getString("value"));
                });
        return result;
    }

    @Transactional
    public Map<String, String> patchSettings(Map<String, String> patch, Actor actor) {
        if (patch == null || patch.isEmpty()) {
            throw ApiException.badRequest("Пустой набор настроек");
        }
        // Сначала валидируем и нормализуем ВЕСЬ набор. Иначе ошибка на последнем
        // ключе оставила бы часть настроек уже записанными без аудита/целостного PATCH.
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : patch.entrySet()) {
            if (!DEFAULT_SETTINGS.containsKey(e.getKey())) {
                throw ApiException.unprocessable("Неизвестная настройка: " + e.getKey());
            }
            if (e.getValue() == null) {
                throw ApiException.badRequest("Значение настройки не может быть null: " + e.getKey());
            }
            normalized.put(e.getKey(), validateSettingValue(e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, String> e : normalized.entrySet()) {
            jdbc.update("""
                    INSERT INTO server_settings(key, value, updated_by, updated_at)
                    VALUES (?, ?, CAST(? AS uuid), now())
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()
                    """, e.getKey(), e.getValue(),
                    actor == null || actor.id() == null ? null : actor.id().toString());
        }
        audit.record(actor, "SETTINGS_CHANGED", "settings", null, Map.of("keys", List.copyOf(normalized.keySet())));
        return settings();
    }

    private static String validateSettingValue(String key, String rawValue) {
        String value = rawValue.trim();
        if (value.isBlank()) {
            throw invalidSettingValue(key, rawValue);
        }
        try {
            return switch (key) {
                case "mail.maxMessageSizeMb", "chat.maxFileSizeMb" -> {
                    int parsed = Integer.parseInt(value);
                    if (parsed < 1 || parsed > 1024) throw new NumberFormatException("out of range");
                    yield Integer.toString(parsed);
                }
                case "mail.defaultQuotaBytes" -> {
                    long parsed = Long.parseLong(value);
                    if (parsed < 0 || parsed > 1_000_000_000_000_000L) throw new NumberFormatException("out of range");
                    yield Long.toString(parsed);
                }
                case "mail.spamThreshold" -> {
                    double parsed = Double.parseDouble(value);
                    if (!Double.isFinite(parsed) || parsed < -100 || parsed > 100) throw new NumberFormatException("out of range");
                    yield Double.toString(parsed);
                }
                case "security.passwordMinLength" -> {
                    int parsed = Integer.parseInt(value);
                    if (parsed < 8 || parsed > 128) throw new NumberFormatException("out of range");
                    yield Integer.toString(parsed);
                }
                case "mail.pop3.enabled", "security.requireTotp" -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        throw new NumberFormatException("not a boolean");
                    }
                    yield Boolean.toString(Boolean.parseBoolean(value));
                }
                default -> value;
            };
        } catch (NumberFormatException e) {
            throw invalidSettingValue(key, rawValue);
        }
    }

    private static ApiException invalidSettingValue(String key, String value) {
        return ApiException.unprocessable("Некорректное значение настройки " + key + ": " + value);
    }

    // ---------- Здоровье ----------

    public HealthResponse health() {
        List<ServiceHealth> services = PROBES.parallelStream().map(this::probe).toList();
        long diskFree = new File("/").getFreeSpace();
        return new HealthResponse(services, diskFree, mailQueueDepth());
    }

    private ServiceHealth probe(Probe probe) {
        try {
            Map<String, Object> body = probeClient.get().uri(probe.healthUrl())
                    .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            String status = body == null ? "DOWN" : String.valueOf(body.getOrDefault("status", "DOWN"));
            return new ServiceHealth(probe.name(), "UP".equals(status) ? "UP" : "DEGRADED", null);
        } catch (Exception e) {
            return new ServiceHealth(probe.name(), "DOWN", null);
        }
    }

    private long mailQueueDepth() {
        try {
            List<Map<String, Object>> queues = probeClient.get()
                    .uri("http://" + rabbitHost + ":15672/api/queues/%2F")
                    .headers(h -> h.setBasicAuth(rabbitUser, rabbitPassword))
                    .retrieve().body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    });
            if (queues == null) {
                return -1;
            }
            long sum = 0;
            for (Map<String, Object> q : queues) {
                Object messages = q.get("messages");
                if (messages instanceof Number n) {
                    sum += n.longValue();
                }
            }
            return sum;
        } catch (Exception e) {
            log.debug("RabbitMQ management API unavailable: {}", e.getMessage());
            return -1; // -1 = неизвестно (management API может быть закрыт)
        }
    }

    // ---------- Аналитика и бэкап ----------

    public AnalyticsResponse analytics(String scope, String department) {
        RemoteStats stats = authClient.stats(department);
        return new AnalyticsResponse(scope, stats.department(),
                stats.totalUsers(), stats.activeUsers(), stats.blockedUsers(), stats.disabledUsers(),
                stats.loginsLast24h(), stats.loginsLast7d(), Instant.now());
    }

    public BackupResponse requestBackup(Actor actor) {
        audit.record(actor, "BACKUP_REQUESTED", "backup", null, Map.of());
        return new BackupResponse("REQUESTED",
                "Запрос зафиксирован в аудите. Физическое резервное копирование выполняет хостовый "
                        + "scripts/backup.ps1 по расписанию Планировщика Windows (из контейнера тома недоступны).");
    }
}
