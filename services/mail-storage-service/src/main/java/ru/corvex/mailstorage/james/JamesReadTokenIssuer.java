package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Disconnected experimental issuer. It does not verify the caller's JWT or
 * load current user status/mapping. Never expose it as an endpoint. Only use
 * an independently generated, dedicated James signing key, never Corvex's key.
 * James 3.9.1's basic JWT verifier must NOT be assumed to enforce issuer or aud.
 * The token is a bearer credential with full James account authority despite
 * the name: read-only is enforced by the transport, not the James token.
 */
final class JamesReadTokenIssuer {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String HEADER = ENCODER.encodeToString(
            "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private final RSAPrivateKey key;
    private final Clock clock;
    private final ObjectMapper mapper;

    JamesReadTokenIssuer(RSAPrivateKey key, Clock clock, ObjectMapper mapper) {
        this.key = Objects.requireNonNull(key);
        if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA key too small");
        this.clock = Objects.requireNonNull(clock);
        this.mapper = Objects.requireNonNull(mapper);
    }

    String issue(JamesMailboxBinding.Principal principal, JamesMailboxBinding.ProvisionedMailbox mapping)
            throws IOException, GeneralSecurityException {
        var binding = JamesMailboxBinding.requireEligible(principal, mapping);
        Instant now = clock.instant();
        byte[] payload = mapper.writeValueAsBytes(Map.of(
                "sub", binding.jamesLogin(), "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(60).getEpochSecond(),
                "iss", "corvex-james-adapter", "aud", "james-jmap"));
        String signed = HEADER + "." + ENCODER.encodeToString(payload);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(signed.getBytes(StandardCharsets.US_ASCII));
        return signed + "." + ENCODER.encodeToString(signer.sign());
    }
}
