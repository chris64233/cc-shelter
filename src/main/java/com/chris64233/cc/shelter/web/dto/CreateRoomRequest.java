package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.Positive;

public record CreateRoomRequest(@Positive int roomNumber,
                                @Positive int bedCount,
                                Boolean accessible) {

    public boolean isAccessible() {
        return Boolean.TRUE.equals(accessible);
    }
}
