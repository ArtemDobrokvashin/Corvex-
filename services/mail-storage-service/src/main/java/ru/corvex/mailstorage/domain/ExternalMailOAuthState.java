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
 * Состояние начатого входа через OAuth.
 *
 * <p>В базе лежит только SHA-256 от {@code state}: сама строка уходит в браузер и в журналы
 * провайдера, поэтому хранить её как ключ нельзя. PKCE-verifier зашифрован тем же ключом,
 * что и пароли внешних ящиков. Запись одноразовая и живёт минуты.
 */
@Entity
@Table(name = "external_mail_oauth_states")
@Getter
@Setter
@NoArgsConstructor
public class ExternalMailOAuthState {

    @Id
    @Column(name = "state_hash", nullable = false, length = 64)
    private String stateHash;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    /** GOOGLE или MICROSOFT: значения хранятся строкой, чтобы domain не зависел от API-слоя. */
    @Column(nullable = false, length = 24)
    private String provider;

    @Column(name = "code_verifier_enc", nullable = false, columnDefinition = "TEXT")
    private String codeVerifierEnc;

    @Column(name = "return_path", nullable = false, length = 200)
    private String returnPath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;
}
