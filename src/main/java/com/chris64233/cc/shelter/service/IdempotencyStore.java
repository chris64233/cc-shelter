package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.IdempotencyRecord;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.IdempotencyRecordRepository;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 幂等记录的统一存取：相同幂等键 + 相同指纹返回首次结果，指纹不同返回 409。
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyStore(IdempotencyRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Optional<IdempotencyRecord> find(String idempotencyKey) {
        return repository.findByIdemKey(idempotencyKey);
    }

    public IdempotentResponse replay(IdempotencyRecord record, String fingerprint) {
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同幂等键携带了不同的请求内容");
        }
        return new IdempotentResponse(record.getResponseStatus(), record.getResponseBody());
    }

    public IdempotentResponse save(String idempotencyKey, String fingerprint, int status, Object body) {
        String json = toJson(body);
        repository.save(new IdempotencyRecord(idempotencyKey, fingerprint, status, json));
        return new IdempotentResponse(status, json);
    }

    public String toJson(Object body) {
        return objectMapper.writeValueAsString(body);
    }
}
