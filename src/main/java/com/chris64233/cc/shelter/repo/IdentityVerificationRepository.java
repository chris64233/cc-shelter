package com.chris64233.cc.shelter.repo;

import com.chris64233.cc.shelter.domain.IdentityVerification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityVerificationRepository extends JpaRepository<IdentityVerification, Long> {

    Optional<IdentityVerification> findByIdemKey(String idemKey);

    List<IdentityVerification> findByIdentityNoOrderByIdAsc(String identityNo);
}
