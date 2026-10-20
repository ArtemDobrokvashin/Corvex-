package ru.corvex.auth.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.auth.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    /** Вход возможен по логину или по e-mail. */
    default Optional<User> findByLogin(String login) {
        return findByUsernameIgnoreCase(login).or(() -> findByEmailIgnoreCase(login));
    }

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    @Query("""
            select u from User u
            where (:status is null or u.status = :status)
              and (:department is null or u.department = :department)
              and (:q is null or :q = ''
                   or lower(u.username) like lower(concat('%', :q, '%'))
                   or lower(u.email) like lower(concat('%', :q, '%'))
                   or lower(u.displayName) like lower(concat('%', :q, '%'))
                   or lower(coalesce(u.department, '')) like lower(concat('%', :q, '%')))
            """)
    Page<User> search(@Param("q") String q,
                      @Param("status") User.Status status,
                      @Param("department") String department,
                      Pageable pageable);

    long countByStatus(User.Status status);

    @Query("select count(u) from User u where (:department is null or u.department = :department)")
    long countAllByDepartment(@Param("department") String department);

    @Query("""
            select count(u) from User u
            where u.status = :status and (:department is null or u.department = :department)
            """)
    long countByStatusAndDepartment(@Param("status") User.Status status,
                                    @Param("department") String department);
}
