package com.chris64233.cc.shelter.web;

import com.chris64233.cc.shelter.service.TransferService;
import com.chris64233.cc.shelter.web.dto.AcceptTransferRequest;
import com.chris64233.cc.shelter.web.dto.ArriveTransferRequest;
import com.chris64233.cc.shelter.web.dto.CancelTransferRequest;
import com.chris64233.cc.shelter.web.dto.CreateTransferRequest;
import com.chris64233.cc.shelter.web.dto.IdempotentResponse;
import com.chris64233.cc.shelter.web.dto.RejectTransferRequest;
import com.chris64233.cc.shelter.web.dto.TransferApplicationResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 家庭跨安置点整体转移与交接（两阶段申请流程）：
 * 创建申请 → 目标接受（预留房间）→ 到达确认（一次性迁移），支持拒绝、取消、超时。
 *
 * <p>注意与 {@code POST /api/transfers} 的即时转移区分：即时转移当场完成搬迁，
 * 本资源面向跨安置点、需要目标点提前预留房间的正式交接流程。
 */
@RestController
@RequestMapping("/api/transfer-requests")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody CreateTransferRequest request) {
        return toResponse(transferService.create(request));
    }

    @PostMapping("/accept")
    public ResponseEntity<String> accept(@Valid @RequestBody AcceptTransferRequest request) {
        return toResponse(transferService.accept(request));
    }

    @PostMapping("/reject")
    public ResponseEntity<String> reject(@Valid @RequestBody RejectTransferRequest request) {
        return toResponse(transferService.reject(request));
    }

    @PostMapping("/cancel")
    public ResponseEntity<String> cancel(@Valid @RequestBody CancelTransferRequest request) {
        return toResponse(transferService.cancel(request));
    }

    @PostMapping("/arrive")
    public ResponseEntity<String> arrive(@Valid @RequestBody ArriveTransferRequest request) {
        return toResponse(transferService.arrive(request));
    }

    /** 管理接口：扫描并终结已超时的待交接申请（也可由外部定时器周期调用） */
    @PostMapping("/timeouts")
    public Map<String, Object> timeoutOverdue(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        List<String> timedOut = transferService.timeoutOverdue(cutoff);
        return Map.of("timedOut", timedOut, "count", timedOut.size());
    }

    /** 转移进度：状态、两端安置点/房间、预留房间、冻结成员、各阶段时间 */
    @GetMapping("/{transferNo}")
    public TransferApplicationResponse progress(@PathVariable String transferNo) {
        return transferService.progress(transferNo);
    }

    /** 成员交接清单：冻结成员与当前实际在住状态的逐项核对结果 */
    @GetMapping("/{transferNo}/handover")
    public Map<String, Object> handover(@PathVariable String transferNo) {
        return transferService.handoverList(transferNo);
    }

    private ResponseEntity<String> toResponse(IdempotentResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
