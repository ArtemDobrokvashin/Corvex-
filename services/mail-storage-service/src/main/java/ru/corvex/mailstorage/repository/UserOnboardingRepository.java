package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.mailstorage.domain.UserOnboarding;

import java.util.UUID;

public interface UserOnboardingRepository extends JpaRepository<UserOnboarding, UUID> {
}
