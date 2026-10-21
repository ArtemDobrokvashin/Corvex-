package ru.corvex.admin.rbac;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Наследование прав: пользователь может входить в несколько групп, права СУММИРУЮТСЯ
 * (union). Запрещающих («deny») прав в системе нет осознанно — deny-правила в union-модели
 * дают трудно предсказуемые комбинации; ограничение доступа достигается исключением из группы.
 * Результат кладётся auth-сервисом в claim {@code permissions} JWT (см. TokenService).
 */
@Service
public class EffectivePermissionService {

    /** Группа — минимальная проекция, нужная для расчёта (JPA-сущность UserGroup маппится на неё). */
    public record GroupPermissions(String groupCode, Set<Permission> permissions, boolean enabled) {
    }

    public Set<Permission> effectivePermissions(Collection<GroupPermissions> groups) {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        for (GroupPermissions g : groups) {
            if (g.enabled()) {
                result.addAll(g.permissions());
            }
        }
        return result;
    }

    /** Группы по умолчанию из ТЗ §6.1 — сидируются Flyway-миграцией V2 и доступны для правки чекбоксами. */
    public static Set<Permission> defaultPermissionsOf(String groupCode) {
        return switch (groupCode) {
            case "SUPER_ADMIN" -> EnumSet.allOf(Permission.class);
            case "ADMIN" -> EnumSet.of(
                    Permission.MAIL_READ_OWN, Permission.MAIL_SEND_INTERNAL, Permission.MAIL_SEND_EXTERNAL,
                    Permission.MAIL_MANAGE_FOLDERS, Permission.MAIL_EXTERNAL_ACCOUNTS, Permission.CHAT_READ, Permission.CHAT_WRITE,
                    Permission.CHAT_CREATE_GROUP, Permission.CHAT_CREATE_CHANNEL, Permission.CHAT_MODERATE,
                    Permission.CHAT_SEND_FILES, Permission.ADMIN_USERS_MANAGE, Permission.ADMIN_GROUPS_MANAGE,
                    Permission.ADMIN_QUOTAS_MANAGE, Permission.ADMIN_SECURITY_POLICY, Permission.ADMIN_MAIL_POLICY,
                    Permission.ADMIN_BACKUP, Permission.ANALYTICS_VIEW_COMPANY, Permission.AUDIT_VIEW);
            case "DIRECTOR" -> EnumSet.of(
                    Permission.MAIL_READ_OWN, Permission.MAIL_SEND_INTERNAL, Permission.MAIL_SEND_EXTERNAL,
                    Permission.MAIL_MANAGE_FOLDERS, Permission.MAIL_EXTERNAL_ACCOUNTS,
                    Permission.MAIL_DELEGATE_ACCESS, Permission.MAIL_EXPORT,
                    Permission.CHAT_READ, Permission.CHAT_WRITE, Permission.CHAT_CREATE_GROUP,
                    Permission.CHAT_SEND_FILES, Permission.CHAT_VOICE_VIDEO,
                    Permission.ANALYTICS_VIEW_COMPANY);
            case "ACCOUNTANT" -> EnumSet.of(
                    Permission.MAIL_READ_OWN, Permission.MAIL_SEND_INTERNAL, Permission.MAIL_SEND_EXTERNAL,
                    Permission.MAIL_MANAGE_FOLDERS, Permission.MAIL_EXTERNAL_ACCOUNTS, Permission.MAIL_BULK_SEND,
                    Permission.CHAT_READ, Permission.CHAT_WRITE, Permission.CHAT_CREATE_GROUP, Permission.CHAT_SEND_FILES);
            case "MANAGER" -> EnumSet.of(
                    Permission.MAIL_READ_OWN, Permission.MAIL_SEND_INTERNAL, Permission.MAIL_SEND_EXTERNAL,
                    Permission.MAIL_MANAGE_FOLDERS, Permission.MAIL_EXTERNAL_ACCOUNTS,
                    Permission.CHAT_READ, Permission.CHAT_WRITE, Permission.CHAT_CREATE_GROUP,
                    Permission.CHAT_SEND_FILES, Permission.CHAT_VOICE_VIDEO,
                    Permission.ANALYTICS_VIEW_OWN_UNIT);
            case "USER" -> EnumSet.of(
                    Permission.MAIL_READ_OWN, Permission.MAIL_SEND_INTERNAL, Permission.MAIL_SEND_EXTERNAL,
                    Permission.MAIL_MANAGE_FOLDERS, Permission.MAIL_EXTERNAL_ACCOUNTS,
                    Permission.CHAT_READ, Permission.CHAT_WRITE, Permission.CHAT_CREATE_GROUP, Permission.CHAT_SEND_FILES);
            default -> Set.of();
        };
    }
}
