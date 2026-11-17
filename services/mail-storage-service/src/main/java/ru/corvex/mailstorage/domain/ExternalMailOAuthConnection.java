package ru.corvex.mailstorage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Токены, полученные после согласия в Google или Microsoft, но ещё не привязанные к внешнему
 * ящику. Пользователь возвращается в мастер и выбирает папку, режим и объём переноса;
 * подключение при этом уже существует и живёт ограниченное время.
 */
@Entity
@Table(name = "external_mail_oauth_connections")
@Getter
@Setter
@NoArgsConstructor
public class ExternalMailOAuthConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 24)
    private String provider;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "access_token_enc", nullable = false, columnDefinition = "TEXT")
    private String accessTokenEnc;

    @Column(name = "refresh_token_enc", columnDefinition = "TEXT")
    private String refreshTokenEnc;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(length = 512)
    private String scope;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
