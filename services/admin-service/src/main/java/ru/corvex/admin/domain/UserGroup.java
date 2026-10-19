package ru.corvex.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.corvex.admin.rbac.Permission;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Группа пользователей и её чекбоксы прав. Состав пользователей (user_group_members)
 * намеренно не JPA-связь, а JDBC в AdminGroupService: user_id логически ссылается на
 * auth_db, и составная связь без второй сущности только усложнила бы модель.
 */
@Entity
@Table(name = "user_groups")
@Getter
@Setter
@NoArgsConstructor
public class UserGroup {

    public static final String SUPER_ADMIN_CODE = "SUPER_ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(nullable = false)
    private boolean enabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "group_permissions", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "permission", length = 64)
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = EnumSet.noneOf(Permission.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isSuperAdmin() {
        return SUPER_ADMIN_CODE.equals(code);
    }
}
