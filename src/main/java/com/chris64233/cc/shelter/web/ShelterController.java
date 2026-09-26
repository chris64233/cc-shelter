package com.chris64233.cc.shelter.web;

import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.service.QueryService;
import com.chris64233.cc.shelter.service.RegistrationService;
import com.chris64233.cc.shelter.web.dto.CreateRoomRequest;
import com.chris64233.cc.shelter.web.dto.CreateShelterRequest;
import com.chris64233.cc.shelter.web.dto.RoomResponse;
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
@RequestMapping("/api/shelters")
public class ShelterController {

    private final RegistrationService registrationService;
    private final QueryService queryService;

    public ShelterController(RegistrationService registrationService, QueryService queryService) {
        this.registrationService = registrationService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createShelter(@Valid @RequestBody CreateShelterRequest request) {
        Shelter shelter = registrationService.createShelter(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("shelterId", shelter.getId(), "name", shelter.getName()));
    }

    @PostMapping("/{shelterId}/rooms")
    public ResponseEntity<RoomResponse> addRoom(@PathVariable Long shelterId,
                                                @Valid @RequestBody CreateRoomRequest request) {
        Room room = registrationService.addRoom(shelterId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(RoomResponse.of(room));
    }

    @GetMapping("/{shelterId}/rooms")
    public List<RoomResponse> rooms(@PathVariable Long shelterId) {
        return queryService.roomsOf(shelterId);
    }
}
