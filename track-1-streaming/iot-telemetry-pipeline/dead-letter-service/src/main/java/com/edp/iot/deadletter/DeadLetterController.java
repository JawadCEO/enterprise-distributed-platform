package com.edp.iot.deadletter;

import com.edp.iot.common.security.ApiKeys;
import com.edp.iot.common.security.AuditLog;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operator API for inspecting and replaying dead letters (FR-12). */
@RestController
@RequestMapping("/v1/dead-letters")
class DeadLetterController {

    private static final int MAX_BULK_REPLAY = 1000;

    private final DeadLetterRepository repository;
    private final DeadLetterService service;
    private final AuditLog audit;

    DeadLetterController(DeadLetterRepository repository, DeadLetterService service, AuditLog audit) {
        this.repository = repository;
        this.service = service;
        this.audit = audit;
    }

    private static ApiKeys.Principal actor() {
        return (ApiKeys.Principal) RequestContextHolder.currentRequestAttributes()
                .getAttribute(SecurityConfig.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    }

    record Page(List<DeadLetterRepository.Summary> items, String nextCursor) {
    }

    record ReplayRequest(String reasonCode, String tenantId, String deviceId, Instant from, Instant to, Integer limit) {
    }

    @GetMapping
    Page list(@RequestParam(required = false) String reasonCode,
              @RequestParam(required = false) String tenantId,
              @RequestParam(required = false) String deviceId,
              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
              @RequestParam(required = false) String cursor,
              @RequestParam(defaultValue = "50") int limit) {
        int size = Math.min(Math.max(limit, 1), 500);
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null) {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            beforeAt = Instant.parse(parts[0]);
            beforeId = UUID.fromString(parts[1]);
        }
        var filter = new DeadLetterRepository.Filter(reasonCode, tenantId, deviceId, from, to);
        List<DeadLetterRepository.Summary> items = repository.find(filter, beforeAt, beforeId, size);
        String next = null;
        if (items.size() == size) {
            var last = items.get(items.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.createdAt() + "|" + last.deadLetterId()).getBytes(StandardCharsets.UTF_8));
        }
        return new Page(items, next);
    }

    @GetMapping("/{id}")
    DeadLetterRepository.Summary get(@PathVariable UUID id) {
        return repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/{id}/replay")
    ResponseEntity<Map<String, Object>> replay(@PathVariable UUID id) {
        var item = repository.findReplayable(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!service.replay(item)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("replayed", 0));
        }
        audit.record(actor(), null, "DEAD_LETTER_REPLAYED", id.toString(), Map.of());
        return ResponseEntity.accepted().body(Map.of("replayed", 1));
    }

    @PostMapping("/replay")
    ResponseEntity<Map<String, Object>> replayMatching(@RequestBody ReplayRequest request) {
        int limit = Math.min(request.limit() == null ? 100 : request.limit(), MAX_BULK_REPLAY);
        var filter = new DeadLetterRepository.Filter(request.reasonCode(), request.tenantId(), request.deviceId(),
                request.from(), request.to());
        var items = repository.findReplayable(filter, limit);
        int replayed = service.replayAll(items);
        audit.record(actor(), request.tenantId(), "DEAD_LETTERS_REPLAYED", null,
                Map.of("matched", items.size(), "replayed", replayed, "reasonCode", String.valueOf(request.reasonCode())));
        return ResponseEntity.accepted().body(Map.of("matched", items.size(), "replayed", replayed));
    }
}
