package ru.corvex.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.auth.domain.AppPassword;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppPasswordRepository extends JpaRepository<AppPassword, UUID> {

    List<AppPassword> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<AppPassword> findByIdAndUserId(UUID id, UUID userId);
}
