package com.chris64233.cc.shelter.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateShelterRequest(@NotBlank String name) {
}
