package kr.shnea.assets;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class Failure extends RuntimeException {
    final int status;
    final String code;
    Failure(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    static Failure missing() { return new Failure(404,"NOT_FOUND","자료를 찾을 수 없습니다."); }
    static Failure invalid() { return new Failure(400,"INVALID_INPUT","입력 내용을 확인해 주세요."); }
    static Failure conflict() { return new Failure(409,"REVISION_CHANGED","다른 화면에서 변경되었습니다. 현재 내용을 보관한 뒤 다시 불러와 주세요."); }
}

@RestControllerAdvice
class Errors {
    @ExceptionHandler(Failure.class)
    ResponseEntity<?> failure(Failure error) {
        return ResponseEntity.status(error.status).body(Map.of("code",error.code,"message",error.getMessage()));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<?> input(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message","입력 내용을 확인해 주세요."));
    }
}
