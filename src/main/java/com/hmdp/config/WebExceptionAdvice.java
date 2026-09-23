package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.ratelimit.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /**
     * 限流是预期内的保护行为，因此用 WARN 记录、返回 429 与 Retry-After，
     * 而不是像系统异常那样打 ERROR 堆栈；响应体仍保持统一的 Result 结构。
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result> handleRateLimitException(RateLimitException e) {
        long retryAfterSeconds = Math.max(1L, (e.getRetryAfterMillis() + 999L) / 1000L);
        log.warn("[RATE-LIMIT] 请求被限流: {} retryAfter={}s", e.getMessage(), retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
