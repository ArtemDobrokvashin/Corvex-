package ru.corvex.admin.api;

import org.springframework.http.HttpStatus;

/** Доменная ошибка с готовым HTTP-статусом; GlobalExceptionHandler превращает её в RFC 7807. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    public static ApiException badGateway(String detail) {
        return new ApiException(HttpStatus.BAD_GATEWAY, detail);
    }
}
