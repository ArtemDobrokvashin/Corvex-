package ru.corvex.mailstorage.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Текст доменной ошибки обязан доезжать до клиента. Без совета Spring Boot отдаёт 409
 * без detail, и интерфейс показывает «HTTP 409» вместо причины — на стенде так потерялся
 * текст про отозванное согласие OAuth.
 */
class MailStorageExceptionHandlerTest {

    private static final String REASON =
            "Согласие Google/Microsoft отозвано или истекло: подключите ящик заново через кнопку входа";

    @RestController
    static class ThrowingController {
        @GetMapping("/conflict")
        String conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, REASON);
        }

        @GetMapping("/no-reason")
        String noReason() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new MailStorageExceptionHandler())
            .build();

    @Test
    void responseStatusReasonBecomesProblemDetail() throws Exception {
        mvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(REASON))
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void missingReasonFallsBackToStatusCode() throws Exception {
        mvc.perform(get("/no-reason"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("404 NOT_FOUND"));
    }
}
