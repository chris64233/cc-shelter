package com.chris64233.cc.shelter.shelter.dto;

import com.chris64233.cc.shelter.shelter.Shelter;

public record ShelterResponse(Long id, String name) {

    public static ShelterResponse from(Shelter shelter) {
        return new ShelterResponse(shelter.getId(), shelter.getName());
    }
}
