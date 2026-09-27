package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.IdempotencyRecord;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.IdempotencyRecordRepository;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import tools.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 幂等键通用处理：相同键 + 相同内容重放首次结果，相同键 + 不同内容拒绝。
 */
@Component
public class IdempotencyService {

    private final IdempotencyRecordRepository records;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository records, ObjectMapper objectMapper) {
        this.records = records;
        this.objectMapper = objectMapper;
    }

    /** 已存在相同幂等键时返回首次结果；键冲突时抛 409；不存在时返回空。 */
    public Optional<IdempotentResponse> replayIfExists(String key, String fingerprint) {
        return records.findByIdemKey(key).map(record -> {
            if (!record.getRequestFingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同幂等键携带了不同的请求内容");
            }
            return new IdempotentResponse(record.getResponseStatus(), record.getResponseBody());
        });
    }

    public IdempotentResponse record(String key, String fingerprint, int status, Object body) {
        String json = objectMapper.writeValueAsString(body);
        records.save(new IdempotencyRecord(key, fingerprint, status, json));
        return new IdempotentResponse(status, json);
    }

    public String fingerprint(String operation, Object... parts) {
        StringBuilder builder = new StringBuilder(operation);
        for (Object part : parts) {
            builder.append('|').append(part);
        }
        return builder.toString();
    }
}
