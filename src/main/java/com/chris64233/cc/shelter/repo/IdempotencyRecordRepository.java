package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.IdempotencyRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdemKey(String idemKey);
}
