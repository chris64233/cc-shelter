package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.StayStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.MemberEventRepository;
import com.chris64233.cc.shelter.repo.MemberRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.ShelterRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.StayRepository;
import com.chris64233.cc.shelter.web.dto.HouseholdResponse;
import com.chris64233.cc.shelter.web.dto.MemberEventResponse;
import com.chris64233.cc.shelter.web.dto.RoomResponse;
import com.chris64233.cc.shelter.web.dto.StayEventResponse;
import com.chris64233.cc.shelter.web.dto.StayResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueryService {

    private final ShelterRepository shelters;
    private final HouseholdRepository households;
    private final RoomRepository rooms;
    private final StayRepository stays;
    private final StayEventRepository events;
    private final MemberEventRepository memberEvents;
    private final MemberRepository members;

    public QueryService(ShelterRepository shelters, HouseholdRepository households, RoomRepository rooms,
                        StayRepository stays, StayEventRepository events,
                        MemberEventRepository memberEvents, MemberRepository members) {
        this.shelters = shelters;
        this.households = households;
        this.rooms = rooms;
        this.stays = stays;
        this.events = events;
        this.memberEvents = memberEvents;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public HouseholdResponse household(String householdNo) {
        return households.findByHouseholdNo(householdNo)
                .map(HouseholdResponse::of)
                .orElseThrow(() -> ApiException.notFound("HOUSEHOLD_NOT_FOUND",
                        "家庭不存在: " + householdNo));
    }

    @Transactional(readOnly = true)
    public StayResponse currentStay(String householdNo) {
        if (households.findByHouseholdNo(householdNo).isEmpty()) {
            throw ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + householdNo);
        }
        return stays.findByHouseholdHouseholdNoAndStatus(householdNo, StayStatus.ACTIVE)
                .map(StayResponse::of)
                .orElseThrow(() -> ApiException.notFound("NO_ACTIVE_STAY", "家庭当前没有有效入住"));
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> roomsOf(Long shelterId) {
        if (!shelters.existsById(shelterId)) {
            throw ApiException.notFound("SHELTER_NOT_FOUND", "安置点不存在: " + shelterId);
        }
        return rooms.findByShelterIdOrderByRoomNumberAsc(shelterId).stream()
                .map(RoomResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StayEventResponse> eventsOf(String householdNo) {
        if (households.findByHouseholdNo(householdNo).isEmpty()) {
            throw ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + householdNo);
        }
        return events.findByHouseholdNoOrderByIdAsc(householdNo).stream()
                .map(StayEventResponse::of)
                .toList();
    }

    /**
     * 成员维度事件流：包含临时入住、身份核验、合并（家庭关系变化）、退住，
     * 事件中的房间信息按序组成该成员的完整房间迁移链。
     * 按身份标识查询可跨合并后的新成员行串联历史。
     */
    @Transactional(readOnly = true)
    public List<MemberEventResponse> memberEventsOf(String identityNo) {
        if (members.findFirstByIdentityNoAndDepartedAtIsNull(identityNo).isEmpty()
                && memberEvents.findByIdentityNoOrderByIdAsc(identityNo).isEmpty()) {
            throw ApiException.notFound("MEMBER_NOT_FOUND", "成员不存在: " + identityNo);
        }
        return memberEvents.findByIdentityNoOrderByIdAsc(identityNo).stream()
                .map(MemberEventResponse::of)
                .toList();
    }
}
