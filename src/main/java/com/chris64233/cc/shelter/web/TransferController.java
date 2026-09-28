package com.chris64233.cc.shelter.web;

import com.chris64233.cc.shelter.service.TransferQueryService;
import com.chris64233.cc.shelter.service.TransferService;
import com.chris64233.cc.shelter.web.dto.AcceptTransferRequest;
import com.chris64233.cc.shelter.web.dto.ArriveTransferRequest;
import com.chris64233.cc.shelter.web.dto.CancelTransferRequest;
import com.chris64233.cc.shelter.web.dto.CreateTransferRequest;
import com.chris64233.cc.shelter.web.dto.ExpireTransferRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.RejectTransferRequest;
import com.chris64233.cc.shelter.web.dto.StayEventResponse;
import com.chris64233.cc.shelter.web.dto.TransferResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 家庭跨安置点整体转移的三阶段接口与查询接口。
 * 资源前缀 /api/transfer-applications，与即时整体转移 POST /api/transfers 区分。
 */
@RestController
@RequestMapping("/api/transfer-applications")
public class TransferController {

    private final TransferService transferService;
    private final TransferQueryService queryService;

    public TransferController(TransferService transferService, TransferQueryService queryService) {
        this.transferService = transferService;
        this.queryService = queryService;
    }

    /** 阶段一：创建转移申请（转移业务号幂等） */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateTransferRequest request) {
        return toResponse(transferService.create(request));
    }

    /** 阶段二：目标安置点接受并预留房间 */
    @PostMapping("/accept")
    public ResponseEntity<String> accept(@Valid @RequestBody AcceptTransferRequest request) {
        return toResponse(transferService.accept(request));
    }

    /** 阶段三：到达确认/交接（交接事件号幂等） */
    @PostMapping("/arrive")
    public ResponseEntity<String> arrive(@Valid @RequestBody ArriveTransferRequest request) {
        return toResponse(transferService.arrive(request));
    }

    @PostMapping("/reject")
    public ResponseEntity<String> reject(@Valid @RequestBody RejectTransferRequest request) {
        return toResponse(transferService.reject(request));
    }

    @PostMapping("/cancel")
    public ResponseEntity<String> cancel(@Valid @RequestBody CancelTransferRequest request) {
        return toResponse(transferService.cancel(request));
    }

    @PostMapping("/expire")
    public ResponseEntity<String> expire(@Valid @RequestBody ExpireTransferRequest request) {
        return toResponse(transferService.expire(request));
    }

    /** 批量处理所有已过交接时限的转移，返回处理笔数（供定时任务/运维调用） */
    @PostMapping("/expire-due")
    public Map<String, Object> expireDue() {
        return Map.of("expiredCount", transferService.expireDue());
    }

    /** 转移进度 */
    @GetMapping("/{transferNo}")
    public TransferResponse progress(@PathVariable String transferNo) {
        return queryService.progress(transferNo);
    }

    /** 两端房间占用 */
    @GetMapping("/{transferNo}/rooms")
    public Map<String, Object> rooms(@PathVariable String transferNo) {
        return queryService.roomOccupancy(transferNo);
    }

    /** 成员交接清单 */
    @GetMapping("/{transferNo}/handover")
    public Map<String, Object> handover(@PathVariable String transferNo) {
        return queryService.handoverList(transferNo);
    }

    /** 某家庭的全部转移记录 */
    @GetMapping("/households/{householdNo}")
    public List<TransferResponse> byHousehold(@PathVariable String householdNo) {
        return queryService.byHousehold(householdNo);
    }

    /** 跨安置点入住时间线（家庭入住事件流） */
    @GetMapping("/households/{householdNo}/timeline")
    public List<StayEventResponse> timeline(@PathVariable String householdNo) {
        return queryService.timeline(householdNo);
    }

    private ResponseEntity<String> toResponse(IdempotentResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
