package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.Household;
import com.chris64233.cc.shelter.domain.HouseholdType;
import com.chris64233.cc.shelter.domain.Member;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.Shelter;
import com.chris64233.cc.shelter.domain.VerificationStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.MemberRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.ShelterRepository;
import com.chris64233.cc.shelter.web.dto.CreateRoomRequest;
import com.chris64233.cc.shelter.web.dto.CreateShelterRequest;
import com.chris64233.cc.shelter.web.dto.RegisterHouseholdRequest;
import com.chris64233.cc.shelter.web.dto.RegisterTemporaryHouseholdRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final ShelterRepository shelters;
    private final RoomRepository rooms;
    private final HouseholdRepository households;
    private final MemberRepository members;

    public RegistrationService(ShelterRepository shelters, RoomRepository rooms,
                               HouseholdRepository households, MemberRepository members) {
        this.shelters = shelters;
        this.rooms = rooms;
        this.households = households;
        this.members = members;
    }

    @Transactional
    public Shelter createShelter(CreateShelterRequest request) {
        return shelters.save(new Shelter(request.name()));
    }

    @Transactional
    public Room addRoom(Long shelterId, CreateRoomRequest request) {
        Shelter shelter = shelters.findById(shelterId)
                .orElseThrow(() -> ApiException.notFound("SHELTER_NOT_FOUND", "安置点不存在: " + shelterId));
        return rooms.save(new Room(shelter, request.roomNumber(), request.bedCount(), request.isAccessible()));
    }

    @Transactional
    public Household registerHousehold(RegisterHouseholdRequest request) {
        Household household = new Household(request.householdNo());
        java.util.Set<String> seen = new java.util.HashSet<>();
        request.members().forEach(member -> {
            ensureIdentityAvailable(member.identityNo(), seen);
            household.addMember(new Member(member.identityNo(), member.age(), member.requiresAccessible()));
        });
        // 立即 flush，让唯一约束冲突在事务边界内抛出并翻译为 409
        return households.saveAndFlush(household);
    }

    @Transactional
    public Household registerTemporaryHousehold(RegisterTemporaryHouseholdRequest request) {
        if (request.householdNo().equals(request.claimedOriginalHouseholdNo())) {
            throw ApiException.conflict("INVALID_TEMPORARY_HOUSEHOLD",
                    "临时家庭号不能与声明的原家庭号相同");
        }
        Household household = new Household(request.householdNo(),
                HouseholdType.TEMPORARY, request.claimedOriginalHouseholdNo());
        java.util.Set<String> seen = new java.util.HashSet<>();
        request.members().forEach(member -> {
            ensureIdentityAvailable(member.identityNo(), seen);
            household.addMember(new Member(
                    member.identityNo(), member.age(), member.requiresAccessible(), VerificationStatus.UNVERIFIED));
        });
        return households.saveAndFlush(household);
    }

    private void ensureIdentityAvailable(String identityNo, java.util.Set<String> seenInRequest) {
        if (!seenInRequest.add(identityNo) || members.existsByIdentityNoAndDepartedAtIsNull(identityNo)) {
            throw ApiException.conflict("DUPLICATE_MEMBER_IDENTITY",
                    "成员身份标识重复或已在住登记中存在: " + identityNo);
        }
    }
}
