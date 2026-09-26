package com.chris64233.cc.shelter.shelter;

import com.chris64233.cc.shelter.shelter.dto.CreateRoomRequest;
import com.chris64233.cc.shelter.shelter.dto.CreateShelterRequest;
import com.chris64233.cc.shelter.shelter.dto.RoomResponse;
import com.chris64233.cc.shelter.shelter.dto.ShelterResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shelters")
public class ShelterController {

    private final ShelterService shelterService;

    public ShelterController(ShelterService shelterService) {
        this.shelterService = shelterService;
    }

    @PostMapping
    public ResponseEntity<ShelterResponse> createShelter(@Valid @RequestBody CreateShelterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shelterService.createShelter(request));
    }

    @PostMapping("/{shelterId}/rooms")
    public ResponseEntity<RoomResponse> addRoom(@PathVariable Long shelterId,
                                                @Valid @RequestBody CreateRoomRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shelterService.addRoom(shelterId, request));
    }

    @GetMapping("/{shelterId}/rooms")
    public List<RoomResponse> listRooms(@PathVariable Long shelterId) {
        return shelterService.listRooms(shelterId);
    }
}
