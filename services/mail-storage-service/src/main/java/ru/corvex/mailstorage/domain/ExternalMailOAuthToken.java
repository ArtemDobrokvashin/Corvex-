package ru.corvex.mailstorage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Токены OAuth подключённого внешнего ящика. Один ящик — одна строка.
 *
 * <p>{@code reauthRequired} ставится, когда провайдер отказал в обновлении токена
 * ({@code invalid_grant}): это не временный сбой, а отозванное согласие или сменённый пароль.
 * Сбор останавливается до повторного входа, иначе он будет бесконечно получать 401.
 */
@Entity
@Table(name = "external_mail_oauth_tokens")
@Getter
@Setter
@NoArgsConstructor
public class ExternalMailOAuthToken {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

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

    @Column(name = "reauth_required", nullable = false)
    private boolean reauthRequired;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
