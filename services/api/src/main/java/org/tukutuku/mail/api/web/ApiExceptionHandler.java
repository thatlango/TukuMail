package org.tukutuku.mail.api.web;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.tukutuku.mail.engine.MailEngineException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> badRequest(Exception e){ return Map.of("error", e.getMessage()==null?"Invalid request":e.getMessage()); }
    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,String> conflict(Exception e){ return Map.of("error",e.getMessage()); }
    @ExceptionHandler(MailEngineException.class) @ResponseStatus(HttpStatus.BAD_GATEWAY)
    Map<String,String> engine(Exception e){ return Map.of("error","Mail engine unavailable","detail",e.getMessage()); }
}
