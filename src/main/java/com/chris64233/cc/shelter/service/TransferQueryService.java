package com.chris64233.cc.shelter.service;

import com.chris64233.cc.shelter.domain.Member;
import com.chris64233.cc.shelter.domain.Room;
import com.chris64233.cc.shelter.domain.StayEvent;
import com.chris64233.cc.shelter.domain.TransferApplication;
import com.chris64233.cc.shelter.domain.TransferRoomReservation;
import com.chris64233.cc.shelter.domain.TransferStatus;
import com.chris64233.cc.shelter.error.ApiException;
import com.chris64233.cc.shelter.repo.HouseholdRepository;
import com.chris64233.cc.shelter.repo.MemberRepository;
import com.chris64233.cc.shelter.repo.RoomRepository;
import com.chris64233.cc.shelter.repo.StayEventRepository;
import com.chris64233.cc.shelter.repo.TransferApplicationRepository;
import com.chris64233.cc.shelter.repo.TransferRoomReservationRepository;
import com.chris64233.cc.shelter.web.dto.StayEventResponse;
import com.chris64233.cc.shelter.web.dto.TransferResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 家庭转移的只读查询：转移进度、两端房间占用、成员交接清单、跨安置点入住时间线。
 */
@Service
public class TransferQueryService {

    private final TransferApplicationRepository transferApplications;
    private final TransferRoomReservationRepository reservations;
    private final HouseholdRepository households;
    private final MemberRepository members;
    private final RoomRepository rooms;
    private final StayEventRepository stayEvents;

    public TransferQueryService(TransferApplicationRepository transferApplications,
                                TransferRoomReservationRepository reservations,
                                HouseholdRepository households, MemberRepository members,
                                RoomRepository rooms, StayEventRepository stayEvents) {
        this.transferApplications = transferApplications;
        this.reservations = reservations;
        this.households = households;
        this.members = members;
        this.rooms = rooms;
        this.stayEvents = stayEvents;
    }

    /** 单笔转移进度（含冻结成员清单） */
    @Transactional(readOnly = true)
    public TransferResponse progress(String transferNo) {
        TransferApplication application = requireTransfer(transferNo);
        List<Map<String, Object>> frozenViews = application.getFrozenMembers().stream().map(m -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("identityNo", m.getIdentityNo());
            view.put("age", m.getAge());
            view.put("needsAccessible", m.isNeedsAccessible());
            view.put("verificationStatus", m.getVerificationStatus().name());
            return view;
        }).toList();
        return TransferResponse.of(application, frozenViews);
    }

    /** 家庭的全部转移记录，按创建时间升序 */
    @Transactional(readOnly = true)
    public List<TransferResponse> byHousehold(String householdNo) {
        if (households.findByHouseholdNo(householdNo).isEmpty()) {
            throw ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + householdNo);
        }
        return transferApplications.findByHouseholdNoOrderByCreatedAtAsc(householdNo).stream()
                .map(application -> TransferResponse.of(application, null))
                .toList();
    }

    /**
     * 一笔转移两端房间的占用情况：来源房间实际占用；目标房间实际占用 + 本笔预留床位。
     * 活动转移期间来源房间占用保持不变，目标房间以“占用 + 预留”展示。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> roomOccupancy(String transferNo) {
        TransferApplication application = requireTransfer(transferNo);
        Room originRoom = rooms.findById(application.getOriginRoomId()).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferNo", transferNo);
        body.put("origin", roomView(originRoom, 0, application.getOriginShelterId()));

        Map<String, Object> target = new LinkedHashMap<>();
        int reserved = reservations.findByTransferApplicationId(application.getId())
                .map(TransferRoomReservation::getBedCount).orElse(0);
        if (application.getReservedRoomId() != null && reserved > 0) {
            Room targetRoom = rooms.findById(application.getReservedRoomId()).orElseThrow();
            target = roomView(targetRoom, reserved, application.getTargetShelterId());
        } else {
            target.put("shelterId", application.getTargetShelterId());
            target.put("reserved", false);
            if (application.getReservedRoomId() != null) {
                target.put("roomId", application.getReservedRoomId());
                Room targetRoom = rooms.findById(application.getReservedRoomId()).orElseThrow();
                target.put("roomNumber", targetRoom.getRoomNumber());
                target.put("occupied", targetRoom.getOccupied());
            }
        }
        body.put("target", target);
        return body;
    }

    /**
     * 成员交接清单：冻结成员逐项给出交接状态。
     * FROZEN（待交接）/ HANDED_OVER（已迁入目标入住）/ DEPARTED（交接前退出，旧交接失败）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> handoverList(String transferNo) {
        TransferApplication application = requireTransfer(transferNo);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var frozen : application.getFrozenMembers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("identityNo", frozen.getIdentityNo());
            item.put("age", frozen.getAge());
            item.put("needsAccessible", frozen.isNeedsAccessible());
            item.put("verificationStatus", frozen.getVerificationStatus().name());
            if (application.getStatus() == TransferStatus.ARRIVED) {
                item.put("handoverStatus", "HANDED_OVER");
                item.put("targetStayId", application.getResultStayId());
            } else {
                boolean stillStaying = members
                        .findFirstByIdentityNoAndDepartedAtIsNull(frozen.getIdentityNo())
                        .map(m -> m.getHousehold().getHouseholdNo().equals(application.getHouseholdNo()))
                        .orElse(false);
                item.put("handoverStatus", stillStaying ? "FROZEN" : "DEPARTED");
                item.put("targetStayId", null);
            }
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferNo", transferNo);
        body.put("householdNo", application.getHouseholdNo());
        body.put("status", application.getStatus().name());
        body.put("members", items);
        return body;
    }

    /**
     * 跨安置点入住时间线：按家庭入住事件流（CHECK_IN/TRANSFER/MERGE/CHECKOUT 等）
     * 还原完整迁移链，事件中包含来源与目标安置点/房间。
     */
    @Transactional(readOnly = true)
    public List<StayEventResponse> timeline(String householdNo) {
        if (households.findByHouseholdNo(householdNo).isEmpty()) {
            throw ApiException.notFound("HOUSEHOLD_NOT_FOUND", "家庭不存在: " + householdNo);
        }
        return stayEvents.findByHouseholdNoOrderByIdAsc(householdNo).stream()
                .map(StayEventResponse::of).toList();
    }

    private TransferApplication requireTransfer(String transferNo) {
        return transferApplications.findByTransferNo(transferNo)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "转移申请不存在: " + transferNo));
    }

    private Map<String, Object> roomView(Room room, int reservedByThisTransfer, Long shelterId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("shelterId", shelterId);
        view.put("roomId", room.getId());
        view.put("roomNumber", room.getRoomNumber());
        view.put("bedCount", room.getBedCount());
        view.put("accessible", room.isAccessible());
        view.put("occupied", room.getOccupied());
        view.put("reservedBeds", reservedByThisTransfer);
        view.put("availableBeds", room.getBedCount() - room.getOccupied() - reservedByThisTransfer);
        return view;
    }
}
