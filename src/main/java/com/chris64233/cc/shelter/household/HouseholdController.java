package com.chris64233.cc.shelter.household;

import com.chris64233.cc.shelter.household.dto.HouseholdResponse;
import com.chris64233.cc.shelter.household.dto.RegisterHouseholdRequest;
import jakarta.validation.Valid;
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

    private final HouseholdService householdService;

    public HouseholdController(HouseholdService householdService) {
        this.householdService = householdService;
    }

    @PostMapping
    public ResponseEntity<HouseholdResponse> register(@Valid @RequestBody RegisterHouseholdRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(householdService.register(request));
    }

    @GetMapping("/{householdNumber}")
    public HouseholdResponse get(@PathVariable String householdNumber) {
        return householdService.getByNumber(householdNumber);
    }
}
