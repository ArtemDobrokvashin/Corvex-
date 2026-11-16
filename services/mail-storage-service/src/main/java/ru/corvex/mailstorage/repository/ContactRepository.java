package ru.corvex.mailstorage.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.mailstorage.domain.Contact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    Optional<Contact> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<Contact> findByOwnerIdAndEmailIgnoreCase(UUID ownerId, String email);

    long countByOwnerId(UUID ownerId);

    List<Contact> findAllByOwnerIdOrderByDisplayNameAsc(UUID ownerId);

    List<Contact> findByOwnerIdOrderByDisplayNameAsc(UUID ownerId, Pageable pageable);

    /**
     * Поиск по имени, адресу и организации. Пустой запрос обрабатывается вызывающим кодом:
     * `like '%%'` здесь не используется, чтобы не мешать индексу.
     */
    @Query("""
            select c from Contact c
            where c.ownerId = :ownerId
              and (lower(c.displayName) like :pattern
                   or lower(c.email) like :pattern
                   or lower(coalesce(c.organization, '')) like :pattern)
            order by c.displayName asc
            """)
    List<Contact> search(@Param("ownerId") UUID ownerId,
                         @Param("pattern") String pattern,
                         Pageable pageable);
}
