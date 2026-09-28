package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.MergeApplication;
import com.chris64233.cc.shelter.domain.MergeStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MergeApplicationRepository extends JpaRepository<MergeApplication, Long> {

    Optional<MergeApplication> findByMergeNo(String mergeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MergeApplication m where m.mergeNo = :mergeNo")
    Optional<MergeApplication> findByMergeNoForUpdate(@Param("mergeNo") String mergeNo);

    /**
     * 家庭是否存在未完成（PENDING）的团聚申请：作为正式家庭目标或临时家庭参与方均算。
     * 存在未完成团聚申请时不得发起跨安置点转移。
     */
    @Query("select count(m) > 0 from MergeApplication m left join m.temporaryHouseholdNos t "
            + "where m.status = :status "
            + "and (m.targetHouseholdNo = :householdNo or t = :householdNo)")
    boolean existsByHouseholdNoAndStatus(@Param("householdNo") String householdNo,
                                         @Param("status") MergeStatus status);
}

