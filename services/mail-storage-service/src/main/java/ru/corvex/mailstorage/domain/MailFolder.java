package ru.corvex.mailstorage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Папка ящика. Системные папки помечены SPECIAL-USE (RFC 6154): \\Inbox, \\Sent, \\Drafts, \\Trash, \\Junk, \\Archive. */
@Entity
@Table(name = "mail_folders",
        uniqueConstraints = @UniqueConstraint(name = "uq_folder_name", columnNames = {"owner_id", "parent_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class MailFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private MailFolder parent;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(name = "special_use", length = 16)
    private String specialUse;

    /** Следующий свободный IMAP UID. Выделение UID — только атомарным UPDATE ... RETURNING (см. repository). */
    @Column(name = "uid_next", nullable = false)
    private long uidNext = 1;

    @Column(name = "uid_validity", nullable = false)
    private long uidValidity = 1;
}
