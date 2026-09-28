package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.MergeApplication;
import com.chris64233.cc.shelter.domain.MergeStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
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

    /** 未完成（PENDING）的团聚申请；发起转移时家庭存在未完成团聚要拒绝 */
    @Query("select m from MergeApplication m where m.status = :status")
    List<MergeApplication> findByStatus(@Param("status") MergeStatus status);
}
