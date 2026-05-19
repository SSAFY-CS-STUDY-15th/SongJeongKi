package com.example.aotlab.product;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ErrorResponse handleNotFound(ProductNotFoundException exception) {
        return new ErrorResponse("PRODUCT_NOT_FOUND", exception.getMessage(), Instant.now());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleInvalidRequest(MethodArgumentNotValidException exception) {
        return new ErrorResponse("INVALID_REQUEST", exception.getMessage(), Instant.now());
    }

    record ErrorResponse(String code, String message, Instant timestamp) {
    }
}
