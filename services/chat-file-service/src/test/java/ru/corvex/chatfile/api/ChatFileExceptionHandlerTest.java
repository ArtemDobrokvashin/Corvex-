package ru.corvex.chatfile.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFileExceptionHandlerTest {

    private final ChatFileExceptionHandler handler = new ChatFileExceptionHandler();

    @Test
    void maxUploadSizeMapsToPayloadTooLarge() {
        ProblemDetail problem = handler.tooLarge(new MaxUploadSizeExceededException(1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), problem.getStatus());
        assertEquals("Файл превышает допустимый размер", problem.getDetail());
    }

    @Test
    void malformedMultipartMapsToBadRequestWithOriginalReason() {
        ProblemDetail problem = handler.badMultipart(new MultipartException("boundary is missing"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.getStatus());
        assertTrue(problem.getDetail().contains("boundary is missing"));
    }

    @Test
    void missingPartAndParameterMapToBadRequest() {
        ProblemDetail missingPart = handler.badMultipart(new MissingServletRequestPartException("file"));
        ProblemDetail missingParameter = handler.badMultipart(
                new MissingServletRequestParameterException("roomId", "UUID"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), missingPart.getStatus());
        assertTrue(missingPart.getDetail().contains("file"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), missingParameter.getStatus());
        assertTrue(missingParameter.getDetail().contains("roomId"));
    }
}
