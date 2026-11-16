package ru.corvex.mailstorage.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MigrationDtos;
import ru.corvex.mailstorage.migration.MailMigrationService;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailMigrationControllerTest {

    private static final String CAN_READ =
            "hasAnyAuthority('MAIL_EXTERNAL_ACCOUNTS','ADMIN_MAIL_POLICY')";

    private final MailMigrationService service = mock(MailMigrationService.class);
    private final MailMigrationController controller = new MailMigrationController(service);

    private static Jwt jwt(String subject, List<String> permissions) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("permissions", permissions)
                .claim("exp", Instant.now().plusSeconds(600));
        return builder.build();
    }

    @Test
    void createPassesOwnerAndPermissionsAndReturns202() {
        UUID owner = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        MigrationDtos.ScopeRequest request = new MigrationDtos.ScopeRequest(accountId, true, List.of("Архив"));
        MigrationDtos.JobView job = new MigrationDtos.JobView(UUID.randomUUID(), accountId, "me@example.com",
                "QUEUED", "FULL", true, 10, 1024, 0, 0, null,
                Instant.now(), null, null, List.of());
        when(service.create(owner, Set.of("MAIL_EXTERNAL_ACCOUNTS"), request)).thenReturn(job);

        var response = controller.create(jwt(owner.toString(), List.of("MAIL_EXTERNAL_ACCOUNTS")), request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(job, response.getBody());
        verify(service).create(owner, Set.of("MAIL_EXTERNAL_ACCOUNTS"), request);
    }

    @Test
    void everyOperationRequiresExternalMailAuthority() throws Exception {
        for (Method method : MailMigrationController.class.getDeclaredMethods()) {
            if (method.getAnnotation(PreAuthorize.class) != null) {
                assertEquals(CAN_READ, method.getAnnotation(PreAuthorize.class).value(), method.getName());
            }
        }
    }

    @Test
    void nonUuidSubjectIsUnauthorized() {
        var response = assertThrows(ResponseStatusException.class,
                () -> controller.get(jwt("not-a-uuid", List.of("MAIL_EXTERNAL_ACCOUNTS")), UUID.randomUUID()));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void missingPermissionsClaimMeansEmptyAuthorities() {
        UUID owner = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        MigrationDtos.JobView job = new MigrationDtos.JobView(jobId, UUID.randomUUID(), "me@example.com",
                "PAUSED", "FULL", false, 1, 1, 1, 0, null,
                Instant.now(), Instant.now(), null, List.of());
        when(service.get(eq(owner), any(), eq(jobId))).thenReturn(job);

        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject(owner.toString()).build();

        assertEquals(job, controller.get(jwt, jobId));
        verify(service).get(owner, Set.of(), jobId);
    }
}
