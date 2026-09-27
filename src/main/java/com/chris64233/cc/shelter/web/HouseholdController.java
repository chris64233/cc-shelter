package com.chris64233.cc.shelter.web;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.service.QueryService;
import com.chris64233.cc.shelter.service.RegistrationService;
import com.chris64233.cc.shelter.web.dto.MemberResponse;
import com.chris64233.cc.shelter.web.dto.RegisterHouseholdRequest;
import com.chris64233.cc.shelter.web.dto.StayEventResponse;
import com.chris64233.cc.shelter.web.dto.StayResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/households")
public class HouseholdController {

    private final RegistrationService registrationService;
    private final QueryService queryService;

    public HouseholdController(RegistrationService registrationService, QueryService queryService) {
        this.registrationService = registrationService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterHouseholdRequest request) {
        Household household = registrationService.registerHousehold(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("householdNo", household.getHouseholdNo(),
                        "memberCount", household.getMembers().size()));
    }

    @GetMapping("/{householdNo}/stay")
    public StayResponse currentStay(@PathVariable String householdNo) {
        return queryService.currentStay(householdNo);
    }

    @GetMapping("/{householdNo}/events")
    public List<StayEventResponse> events(@PathVariable String householdNo) {
        return queryService.eventsOf(householdNo);
    }

    @GetMapping("/{householdNo}/members")
    public List<MemberResponse> members(@PathVariable String householdNo) {
        return queryService.membersOf(householdNo);
    }
}
