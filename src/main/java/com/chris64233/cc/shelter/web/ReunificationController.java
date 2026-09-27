package com.chris64233.cc.shelter.web;

import com.chris64233.cc.shelter.service.MergeService;
import com.chris64233.cc.shelter.service.TemporaryStayService;
import com.chris64233.cc.shelter.web.dto.CheckoutRequest;
import com.chris64233.cc.shelter.web.dto.ConfirmMergeRequest;
import com.chris64233.cc.shelter.web.dto.CreateMergeRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.TemporaryCheckInRequest;
import com.chris64233.cc.shelter.web.dto.VerifyIdentityRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 家庭成员走散后的临时入住、身份核验、安全合并与退住。
 */
@RestController
@RequestMapping("/api")
public class ReunificationController {

    private final TemporaryStayService temporaryStayService;
    private final MergeService mergeService;

    public ReunificationController(TemporaryStayService temporaryStayService, MergeService mergeService) {
        this.temporaryStayService = temporaryStayService;
        this.mergeService = mergeService;
    }

    @PostMapping("/temporary-check-ins")
    public ResponseEntity<String> temporaryCheckIn(@Valid @RequestBody TemporaryCheckInRequest request) {
        return toResponse(temporaryStayService.temporaryCheckIn(request));
    }

    @PostMapping("/identity-verifications")
    public ResponseEntity<String> verifyIdentity(@Valid @RequestBody VerifyIdentityRequest request) {
        return toResponse(temporaryStayService.verifyIdentity(request));
    }

    @PostMapping("/checkouts")
    public ResponseEntity<String> checkout(@Valid @RequestBody CheckoutRequest request) {
        return toResponse(temporaryStayService.checkout(request));
    }

    @PostMapping("/merges")
    public ResponseEntity<String> createMerge(@Valid @RequestBody CreateMergeRequest request) {
        return toResponse(mergeService.createApplication(request));
    }

    @PostMapping("/merges/confirm")
    public ResponseEntity<String> confirmMerge(@Valid @RequestBody ConfirmMergeRequest request) {
        return toResponse(mergeService.confirm(request));
    }

    private ResponseEntity<String> toResponse(IdempotentResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
