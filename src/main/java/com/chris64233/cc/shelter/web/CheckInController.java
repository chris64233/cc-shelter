package com.chris64233.cc.shelter.web;

import com.chris64233.cc.shelter.service.CheckInService;
import com.chris64233.cc.shelter.web.dto.CheckInRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.TransferRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CheckInController {

    private final CheckInService checkInService;

    public CheckInController(CheckInService checkInService) {
        this.checkInService = checkInService;
    }

    @PostMapping("/check-ins")
    public ResponseEntity<String> checkIn(@Valid @RequestBody CheckInRequest request) {
        return toResponse(checkInService.checkIn(request));
    }

    @PostMapping("/transfers")
    public ResponseEntity<String> transfer(@Valid @RequestBody TransferRequest request) {
        return toResponse(checkInService.transfer(request));
    }

    private ResponseEntity<String> toResponse(IdempotentResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
