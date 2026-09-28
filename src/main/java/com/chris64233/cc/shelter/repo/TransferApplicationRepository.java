package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.TransferApplication;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferApplicationRepository extends JpaRepository<TransferApplication, Long> {

    Optional<TransferApplication> findByTransferNo(String transferNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransferApplication t where t.transferNo = :transferNo")
    Optional<TransferApplication> findByTransferNoForUpdate(@Param("transferNo") String transferNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransferApplication t where t.id = :id")
    Optional<TransferApplication> findByIdForUpdate(@Param("id") Long id);

    List<TransferApplication> findByHouseholdNoOrderByCreatedAtAsc(String householdNo);

    /** 已到交接时限但仍处于活动状态（REQUESTED/ACCEPTED）的转移 */
    @Query("select t from TransferApplication t "
            + "where t.status in (com.chris64233.cc.shelter.domain.TransferStatus.REQUESTED, "
            + "com.chris64233.cc.shelter.domain.TransferStatus.ACCEPTED) "
            + "and t.expiresAt <= :now order by t.id asc")
    List<TransferApplication> findDueForExpiry(@Param("now") Instant now);
}
