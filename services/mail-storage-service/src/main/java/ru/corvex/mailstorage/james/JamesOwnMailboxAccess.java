package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.api.dto.MailDtos;

/**
 * Disconnected experiment. Authentication MUST come from a correctly configured
 * Spring resource server security context, never be built from request input.
 * Authentication signature/key/issuer must already be verified by that filter;
 * type and claim checks here do not verify a JWT. No Spring wiring or HTTP API.
 */
final class JamesOwnMailboxAccess {
    private final AuthUserClient users;
    private final JamesMailboxRegistry registry;
    private final CurrentMailPermissionLookup permissions;
    private final JamesOwnMailboxReader reader;
    private final JamesFolderMapper folderMapper;
    private final Clock clock;

    JamesOwnMailboxAccess(AuthUserClient users, JamesMailboxRegistry registry,
                          CurrentMailPermissionLookup permissions, JamesOwnMailboxReader reader,
                          JamesFolderMapper folderMapper, Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.registry = Objects.requireNonNull(registry);
        this.permissions = Objects.requireNonNull(permissions);
        this.reader = Objects.requireNonNull(reader);
        this.folderMapper = Objects.requireNonNull(folderMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    private record Authorized(JamesMailboxBinding.Principal principal,
                              JamesMailboxBinding.ProvisionedMailbox mapping) { }

    private Authorized authorize(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken auth) || !auth.isAuthenticated()
                || !"corvex-auth".equals(auth.getToken().getClaimAsString("iss"))
                || auth.getToken().getExpiresAt() == null
                || !clock.instant().isBefore(auth.getToken().getExpiresAt())
                || (auth.getToken().getNotBefore() != null && clock.instant().isBefore(auth.getToken().getNotBefore()))
                || auth.getAuthorities().stream().noneMatch(a -> "MAIL_READ_OWN".equals(a.getAuthority()))) {
            throw new SecurityException("James access denied");
        }
        final UUID owner;
        try { owner = UUID.fromString(auth.getToken().getSubject()); }
        catch (IllegalArgumentException | NullPointerException e) { throw new SecurityException("James access denied"); }
        var user = users.findById(owner).orElseThrow(() -> new SecurityException("James access denied"));
        if (!owner.equals(user.id()) || !user.active()) throw new SecurityException("James access denied");
        if (!permissions.canReadOwnMail(owner)) throw new SecurityException("James access denied");
        var mapping = registry.find(owner).orElseThrow(() -> new SecurityException("James access denied"));
        var principal = new JamesMailboxBinding.Principal(owner, Set.of("MAIL_READ_OWN"));
        // Checks mapping owner and enabled before any token is issued.
        JamesMailboxBinding.requireEligible(principal, mapping);
        return new Authorized(principal, mapping);
    }

    List<MailDtos.FolderView> listOwnFolders(Authentication authentication)
            throws IOException, GeneralSecurityException, InterruptedException {
        Authorized authorized = authorize(authentication);
        return folderMapper.map(authorized.principal().verifiedOwnerId(), authorized.mapping().accountId(),
                reader.listFolders(authorized.principal(), authorized.mapping()));
    }

    record FolderMessagePage(List<JamesMessagePreviewMapper.Preview> messages, String queryState, boolean continuationUnavailable) { }

    /** Internal experimental view; it is not the legacy UID-based public API. */
    FolderMessagePage messagePage(Authentication authentication, UUID publicFolderId, int limit,
                                  int position, String expectedState)
            throws IOException, GeneralSecurityException, InterruptedException {
        Authorized authorized = authorize(authentication);
        var page = reader.messagePage(authorized.principal(), authorized.mapping(), publicFolderId,
                limit, folderMapper, position, expectedState);
        var previews = new JamesMessagePreviewMapper().map(authorized.principal().verifiedOwnerId(),
                authorized.mapping().accountId(), publicFolderId, page.items());
        return new FolderMessagePage(previews, page.queryState(), page.continuationUnavailable());
    }

    /** Internal JMAP metadata only: never expose without DTO conversion and AV policy. */
    JsonNode firstMessagePage(Authentication authentication, UUID publicFolderId, int limit)
            throws IOException, GeneralSecurityException, InterruptedException {
        Authorized authorized = authorize(authentication);
        return reader.firstMessagePage(authorized.principal(), authorized.mapping(), publicFolderId, limit, folderMapper);
    }
}
