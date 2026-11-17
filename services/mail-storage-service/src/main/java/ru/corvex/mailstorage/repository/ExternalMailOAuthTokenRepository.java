package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.mailstorage.domain.ExternalMailOAuthToken;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ExternalMailOAuthTokenRepository extends JpaRepository<ExternalMailOAuthToken, UUID> {

    /** Список ящиков показывает состояние входа без запроса на каждую строку. */
    List<ExternalMailOAuthToken> findByAccountIdIn(Collection<UUID> accountIds);
}
