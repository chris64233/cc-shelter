package com.chris64233.cc.shelter.web.dto;

import java.util.List;

public record MergeResponse(String targetHouseholdNo,
                            Long stayId,
                            Long shelterId,
                            Long roomId,
                            int roomNumber,
                            int memberCount,
                            List<String> mergedTemporaryHouseholdNos,
                            boolean roomChanged) {
}
