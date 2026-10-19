package ru.corvex.admin.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.admin.domain.UserGroup;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {

    Optional<UserGroup> findByCode(String code);

    boolean existsByCode(String code);

    List<UserGroup> findAllByOrderByCodeAsc();
}
