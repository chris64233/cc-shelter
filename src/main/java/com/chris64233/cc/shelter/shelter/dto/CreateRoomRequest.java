package com.chris64233.cc.shelter.shelter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateRoomRequest(
        @NotNull Integer roomNumber,
        @NotNull @Min(1) Integer bedCapacity,
        boolean accessible) {
}
