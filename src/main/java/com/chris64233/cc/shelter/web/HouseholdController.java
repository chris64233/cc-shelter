package com.chris64233.cc.shelter.web;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.service.QueryService;
import com.chris64233.cc.shelter.service.RegistrationService;
import com.chris64233.cc.shelter.service.TransferService;
import com.chris64233.cc.shelter.web.dto.HouseholdResponse;
import com.chris64233.cc.shelter.web.dto.MemberEventResponse;
import com.chris64233.cc.shelter.web.dto.RegisterHouseholdRequest;
import com.chris64233.cc.shelter.web.dto.RegisterTemporaryHouseholdRequest;
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
    private final TransferService transferService;

    public HouseholdController(RegistrationService registrationService, QueryService queryService,
                               TransferService transferService) {
        this.registrationService = registrationService;
        this.queryService = queryService;
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterHouseholdRequest request) {
        Household household = registrationService.registerHousehold(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("householdNo", household.getHouseholdNo(),
                        "memberCount", household.getMembers().size()));
    }

    @PostMapping("/temporary")
    public ResponseEntity<Map<String, Object>> registerTemporary(
            @Valid @RequestBody RegisterTemporaryHouseholdRequest request) {
        Household household = registrationService.registerTemporaryHousehold(request);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("householdNo", household.getHouseholdNo());
        body.put("type", household.getType().name());
        body.put("claimedOriginalHouseholdNo", household.getClaimedOriginalHouseholdNo());
        body.put("memberCount", household.getMembers().size());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{householdNo}")
    public HouseholdResponse household(@PathVariable String householdNo) {
        return queryService.household(householdNo);
    }

    @GetMapping("/{householdNo}/stay")
    public StayResponse currentStay(@PathVariable String householdNo) {
        return queryService.currentStay(householdNo);
    }

    @GetMapping("/{householdNo}/events")
    public List<StayEventResponse> events(@PathVariable String householdNo) {
        return queryService.eventsOf(householdNo);
    }

    /** 跨安置点入住时间线：入住/转移/合并/退住事件按时间排列，含两端安置点与房间 */
    @GetMapping("/{householdNo}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable String householdNo) {
        return transferService.stayTimeline(householdNo);
    }

    @GetMapping("/members/{identityNo}/events")
    public List<MemberEventResponse> memberEvents(@PathVariable String identityNo) {
        return queryService.memberEventsOf(identityNo);
    }
}
