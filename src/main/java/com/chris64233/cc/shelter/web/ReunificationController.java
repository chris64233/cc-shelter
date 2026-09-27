package com.chris64233.cc.shelter.web;

import com.chris64233.cc.shelter.service.QueryService;
import com.chris64233.cc.shelter.service.ReunificationService;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.MergeRequest;
import com.chris64233.cc.shelter.web.dto.TemporaryCheckInRequest;
import com.chris64233.cc.shelter.web.dto.VerificationEventResponse;
import com.chris64233.cc.shelter.web.dto.VerifyIdentityRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReunificationController {

    private final ReunificationService reunificationService;
    private final QueryService queryService;

    public ReunificationController(ReunificationService reunificationService, QueryService queryService) {
        this.reunificationService = reunificationService;
        this.queryService = queryService;
    }

    @PostMapping("/temporary-check-ins")
    public ResponseEntity<String> temporaryCheckIn(@Valid @RequestBody TemporaryCheckInRequest request) {
        return toResponse(reunificationService.temporaryCheckIn(request));
    }

    @PostMapping("/identity-verifications")
    public ResponseEntity<String> verifyIdentity(@Valid @RequestBody VerifyIdentityRequest request) {
        return toResponse(reunificationService.verifyIdentity(request));
    }

    @PostMapping("/merges")
    public ResponseEntity<String> merge(@Valid @RequestBody MergeRequest request) {
        return toResponse(reunificationService.merge(request));
    }

    @GetMapping("/members/{identityNo}/verifications")
    public List<VerificationEventResponse> verifications(@PathVariable String identityNo) {
        return queryService.verificationsOf(identityNo);
    }

    private ResponseEntity<String> toResponse(IdempotentResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
