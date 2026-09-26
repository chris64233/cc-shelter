package com.chris64233.cc.shelter.stay;

import com.chris64233.cc.shelter.stay.dto.CheckInRequest;
import com.chris64233.cc.shelter.stay.dto.StayEventResponse;
import com.chris64233.cc.shelter.stay.dto.StayResponse;
import com.chris64233.cc.shelter.stay.dto.TransferRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class StayController {

    private final StayService stayService;
    private final StayQueryService stayQueryService;

    public StayController(StayService stayService, StayQueryService stayQueryService) {
        this.stayService = stayService;
        this.stayQueryService = stayQueryService;
    }

    @PostMapping("/api/checkins")
    public ResponseEntity<StayResponse> checkIn(@Valid @RequestBody CheckInRequest request) {
        StayOutcome outcome = stayService.checkIn(request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    @PostMapping("/api/households/{householdNumber}/transfers")
    public ResponseEntity<StayResponse> transfer(@PathVariable String householdNumber,
                                                 @Valid @RequestBody TransferRequest request) {
        StayOutcome outcome = stayService.transfer(householdNumber, request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    @GetMapping("/api/households/{householdNumber}/stay")
    public StayResponse currentStay(@PathVariable String householdNumber) {
        return stayQueryService.currentStay(householdNumber);
    }

    @GetMapping("/api/households/{householdNumber}/events")
    public List<StayEventResponse> events(@PathVariable String householdNumber) {
        return stayQueryService.events(householdNumber);
    }
}
