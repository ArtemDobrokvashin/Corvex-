package ru.corvex.mailstorage.james;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Disabled-by-default administrative API. Cannot start without a separately
 * provided, fully configured JamesProvisioningAccess bean. Never enable until
 * provisioning database, mTLS administrative channel and audit are reviewed.
 * Created/bound do NOT mean activated, migrated or ready for delivery.
 */
@RestController
@RequestMapping("/api/v1/mail/james/admin/provisioning")
@ConditionalOnProperty(name = "corvex.james.provision.enabled", havingValue = "true")
class JamesProvisioningController {
    private final JamesProvisioningAccess access;
    JamesProvisioningController(JamesProvisioningAccess access) { this.access = access; }

    record Operation(UUID operationId, String state) { }

    @PostMapping("/users/{ownerId}")
    @PreAuthorize("hasAuthority('ADMIN_USERS_MANAGE')")
    ResponseEntity<Operation> create(Authentication authentication, @PathVariable UUID ownerId) {
        try {
            var intent = access.create(authentication, ownerId);
            return response(HttpStatus.CREATED, new Operation(intent.operationId(), "created"));
        } catch (JamesProvisioningUncertainException uncertain) {
            // Client must query the operation, never repeat create blindly.
            return response(HttpStatus.ACCEPTED, new Operation(uncertain.operationId(), "uncertain"));
        } catch (SecurityException e) {
            throw denied();
        } catch (IOException | RuntimeException e) {
            throw unavailable();
        }
    }

    @GetMapping("/operations/{operationId}")
    @PreAuthorize("hasAuthority('ADMIN_USERS_MANAGE')")
    ResponseEntity<Operation> status(Authentication authentication, @PathVariable UUID operationId) {
        try {
            var operation = access.status(authentication, operationId);
            return response(HttpStatus.OK, new Operation(operation.operationId(), viewState(operation.phase())));
        } catch (SecurityException e) {
            throw denied();
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    @GetMapping("/users/{ownerId}/operation")
    @PreAuthorize("hasAuthority('ADMIN_USERS_MANAGE')")
    ResponseEntity<Operation> statusForOwner(Authentication authentication, @PathVariable UUID ownerId) {
        try {
            var operation = access.statusForOwner(authentication, ownerId);
            return response(HttpStatus.OK, new Operation(operation.operationId(), viewState(operation.phase())));
        } catch (SecurityException e) {
            throw denied();
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    @PostMapping("/operations/{operationId}/discover")
    @PreAuthorize("hasAuthority('ADMIN_USERS_MANAGE')")
    ResponseEntity<Operation> discover(Authentication authentication, @PathVariable UUID operationId) {
        try {
            access.discover(authentication, operationId);
            return response(HttpStatus.OK, new Operation(operationId, "bound-disabled"));
        } catch (SecurityException e) {
            throw denied();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw unavailable();
        }
    }

    private static String viewState(String phase) {
        return "bound".equals(phase) ? "bound-disabled" : phase;
    }

    private static ResponseEntity<Operation> response(HttpStatus status, Operation body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff").body(body);
    }
    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Provisioning denied");
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Provisioning unavailable; check operation status before retrying");
    }
}
