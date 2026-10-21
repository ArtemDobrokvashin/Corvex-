package ru.corvex.mailstorage.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Глобальная политика External Mail Connector. Значения по умолчанию безопасны:
 * custom endpoints в приватные сети и PLAIN без TLS запрещены, пока администратор
 * явно не разрешит их для внутреннего legacy-сервера.
 */
@Component
public class ExternalMailProperties {

    private final boolean enabled;
    private final boolean allowCustomHosts;
    private final boolean allowPrivateNetworks;
    private final boolean allowInsecurePlain;
    private final int maxAccountsPerUser;
    private final int batchSize;
    private final int maxFolders;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final int schedulerThreads;

    public ExternalMailProperties(
            @Value("${corvex.external-mail.enabled:${CORVEX_EXTERNAL_MAIL_ENABLED:true}}") boolean enabled,
            @Value("${corvex.external-mail.allow-custom-hosts:${CORVEX_EXTERNAL_MAIL_ALLOW_CUSTOM_HOSTS:true}}") boolean allowCustomHosts,
            @Value("${corvex.external-mail.allow-private-networks:${CORVEX_EXTERNAL_MAIL_ALLOW_PRIVATE_NETWORKS:false}}") boolean allowPrivateNetworks,
            @Value("${corvex.external-mail.allow-insecure-plain:${CORVEX_EXTERNAL_MAIL_ALLOW_INSECURE_PLAIN:false}}") boolean allowInsecurePlain,
            @Value("${corvex.external-mail.max-accounts-per-user:${CORVEX_EXTERNAL_MAIL_MAX_ACCOUNTS_PER_USER:10}}") int maxAccountsPerUser,
            @Value("${corvex.external-mail.batch-size:${CORVEX_EXTERNAL_MAIL_BATCH_SIZE:200}}") int batchSize,
            @Value("${corvex.external-mail.max-folders:${CORVEX_EXTERNAL_MAIL_MAX_FOLDERS:200}}") int maxFolders,
            @Value("${corvex.external-mail.connect-timeout-seconds:${CORVEX_EXTERNAL_MAIL_CONNECT_TIMEOUT_SECONDS:15}}") int connectTimeoutSeconds,
            @Value("${corvex.external-mail.read-timeout-seconds:${CORVEX_EXTERNAL_MAIL_READ_TIMEOUT_SECONDS:60}}") int readTimeoutSeconds,
            @Value("${corvex.external-mail.scheduler-threads:${CORVEX_EXTERNAL_MAIL_SCHEDULER_THREADS:2}}") int schedulerThreads) {
        this.enabled = enabled;
        this.allowCustomHosts = allowCustomHosts;
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.allowInsecurePlain = allowInsecurePlain;
        this.maxAccountsPerUser = maxAccountsPerUser;
        this.batchSize = batchSize;
        this.maxFolders = maxFolders;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.schedulerThreads = schedulerThreads;
    }

    public boolean enabled() { return enabled; }

    public boolean allowCustomHosts() { return allowCustomHosts; }

    public boolean allowPrivateNetworks() { return allowPrivateNetworks; }

    public boolean allowInsecurePlain() { return allowInsecurePlain; }

    public int maxAccountsPerUser() { return maxAccountsPerUser; }

    public int batchSize() { return batchSize; }

    public int maxFolders() { return maxFolders; }

    public int connectTimeoutSeconds() { return connectTimeoutSeconds; }

    public int readTimeoutSeconds() { return readTimeoutSeconds; }

    public int schedulerThreads() { return schedulerThreads; }

    public int safeBatchSize() {
        return Math.max(1, Math.min(batchSize, 2000));
    }

    public int safeMaxFolders() {
        return Math.max(1, Math.min(maxFolders, 1000));
    }

    public int safeSchedulerThreads() {
        return Math.max(1, Math.min(schedulerThreads, 16));
    }
}
