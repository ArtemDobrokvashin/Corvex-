package ru.corvex.mailstorage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Контакт личной адресной книги. Данные принадлежат только владельцу: чужие контакты
 * не видны ни пользователю, ни администратору через API.
 *
 * <p>Таблица `contacts` существует с V1 как заготовка; поля телефона, организации и
 * заметки добавлены в V9 вместе с импортом из vCard/CSV.
 */
@Entity
@Table(name = "contacts")
@Getter
@Setter
@NoArgsConstructor
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "group_name", length = 128)
    private String groupName;

    @Column(name = "display_name", nullable = false, length = 256)
    private String displayName;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(length = 64)
    private String phone;

    @Column(length = 256)
    private String organization;

    @Column(name = "job_title", length = 256)
    private String jobTitle;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** MANUAL, VCARD или CSV — откуда контакт появился. */
    @Column(nullable = false, length = 16)
    private String source = "MANUAL";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
