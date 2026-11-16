package ru.corvex.mailstorage.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Состояние онбординга: показывать ли предложение переноса и что уже подключено. */
public final class OnboardingDtos {

    private OnboardingDtos() {
    }

    public record AccountRef(UUID id, String emailAddress, String provider) {
    }

    public record OnboardingView(String migrationState,
                                 boolean migrationSuggested,
                                 UUID activeJobId,
                                 String lastJobStatus,
                                 Instant dismissedAt,
                                 List<AccountRef> accounts,
                                 String suggestedEmail,
                                 String suggestedSourceHost,
                                 String suggestedSourceLogin) {
    }
}
