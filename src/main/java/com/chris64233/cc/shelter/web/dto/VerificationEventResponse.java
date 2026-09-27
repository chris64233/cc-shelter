package com.chris64233.cc.shelter.web.dto;

import com.chris64233.cc.shelter.domain.IdentityVerification;
import java.time.Instant;

public record VerificationEventResponse(Long eventId,
                                        String idempotencyKey,
                                        String identityNo,
                                        String householdNo,
                                        String result,
                                        Instant occurredAt) {

    public static VerificationEventResponse of(IdentityVerification event) {
        return new VerificationEventResponse(event.getId(), event.getIdemKey(), event.getIdentityNo(),
                event.getHouseholdNo(), event.getResult(), event.getOccurredAt());
    }
}
