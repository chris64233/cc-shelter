package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.Member;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByIdentityNo(String identityNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.identityNo = :identityNo")
    Optional<Member> findByIdentityNoForUpdate(@Param("identityNo") String identityNo);
}
