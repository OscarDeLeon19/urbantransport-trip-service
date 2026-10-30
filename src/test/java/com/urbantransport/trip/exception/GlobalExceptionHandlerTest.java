package com.urbantransport.trip.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private static final String PATH = "/trips/11111111-1111-1111-1111-111111111111/departure";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);

    @Test
    void apiException_keepsItsStatusCodeAndMessage() {
        ApiException exception = new ApiException(HttpStatus.CONFLICT, ErrorCodes.TRIP_ALREADY_STARTED,
                "El viaje ya inició o terminó");

        ResponseEntity<ErrorResponse> response = handler.handleApi(exception, request);

        assertError(response, HttpStatus.CONFLICT, ErrorCodes.TRIP_ALREADY_STARTED, "El viaje ya inició o terminó");
    }

    @Test
    void apiExceptionWithoutCode_returnsNullCode() {
        ResponseEntity<ErrorResponse> response = handler.handleApi(
                new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Error inesperado"), request);

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, null, "Error inesperado");
    }

    @Test
    void validationErrors_areJoinedByField() {
        BindingResult bindingResult = new BeanPropertyBindingResult(Map.of(), "request");
        bindingResult.addError(new FieldError("request", "actualDepartureAt", "no debe ser nulo"));
        bindingResult.addError(new FieldError("request", "odometerStart", "debe ser mayor que o igual a 0"));
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(exception, request);

        assertError(response, HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                "actualDepartureAt: no debe ser nulo; odometerStart: debe ser mayor que o igual a 0");
    }

    @Test
    void unreadableBody_returnsValidationError() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException("JSON inválido",
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ErrorResponse> response = handler.handleUnreadable(exception, request);

        assertError(response, HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Cuerpo de la petición inválido");
    }

    @Test
    void accessDenied_returnsForbidden() {
        ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(new AccessDeniedException("denegado"),
                request);

        assertError(response, HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED, "No tienes permiso para esta operación");
    }

    @Test
    void integrityViolation_returnsConflictWithoutCode() {
        ResponseEntity<ErrorResponse> response = handler.handleIntegrity(
                new DataIntegrityViolationException("duplicate key value"), request);

        assertError(response, HttpStatus.CONFLICT, null, "El registro ya existe o viola una restricción");
    }

    private static void assertError(ResponseEntity<ErrorResponse> response, HttpStatus status, String code,
                                    String message) {
        assertEquals(status, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getTimestamp());
        assertEquals(status.value(), body.getStatus());
        assertEquals(code, body.getCode());
        assertEquals(message, body.getMessage());
        assertEquals(PATH, body.getPath());
    }
}
