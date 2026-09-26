package com.chris64233.cc.shelter.shelter;

import com.chris64233.cc.shelter.common.BusinessException;
import com.chris64233.cc.shelter.shelter.dto.CreateRoomRequest;
import com.chris64233.cc.shelter.shelter.dto.CreateShelterRequest;
import com.chris64233.cc.shelter.shelter.dto.RoomResponse;
import com.chris64233.cc.shelter.shelter.dto.ShelterResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ShelterService {

    private final ShelterRepository shelterRepository;
    private final RoomRepository roomRepository;

    public ShelterService(ShelterRepository shelterRepository, RoomRepository roomRepository) {
        this.shelterRepository = shelterRepository;
        this.roomRepository = roomRepository;
    }

    @Transactional
    public ShelterResponse createShelter(CreateShelterRequest request) {
        return ShelterResponse.from(shelterRepository.save(new Shelter(request.name())));
    }

    @Transactional
    public RoomResponse addRoom(Long shelterId, CreateRoomRequest request) {
        Shelter shelter = shelterRepository.findById(shelterId)
                .orElseThrow(() -> BusinessException.notFound("SHELTER_NOT_FOUND", "安置点不存在: " + shelterId));
        Room room = new Room(shelter, request.roomNumber(), request.bedCapacity(), request.accessible());
        return RoomResponse.from(roomRepository.save(room));
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> listRooms(Long shelterId) {
        if (!shelterRepository.existsById(shelterId)) {
            throw BusinessException.notFound("SHELTER_NOT_FOUND", "安置点不存在: " + shelterId);
        }
        return roomRepository.findByShelterIdOrderByRoomNumberAsc(shelterId).stream()
                .map(RoomResponse::from)
                .toList();
    }
}
