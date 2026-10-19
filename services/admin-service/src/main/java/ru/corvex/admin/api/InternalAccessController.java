package ru.corvex.admin.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.admin.api.dto.AdminDtos.AccessView;
import ru.corvex.admin.service.AdminGroupService;

import java.util.UUID;

/**
 * Эффективные группы/права пользователя для auth-service (выпуск JWT и /me).
 * Только внутренняя сеть: gateway маршрутизирует исключительно /api/v1/**.
 */
@RestController
@RequestMapping("/internal")
public class InternalAccessController {

    private final AdminGroupService groups;

    public InternalAccessController(AdminGroupService groups) {
        this.groups = groups;
    }

    @GetMapping("/users/{userId}/access")
    AccessView access(@PathVariable UUID userId) {
        return groups.accessOf(userId);
    }
}
