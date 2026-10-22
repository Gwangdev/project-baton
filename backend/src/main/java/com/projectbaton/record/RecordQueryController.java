package com.projectbaton.record;

import java.time.LocalDate;
import java.util.List;

import com.projectbaton.identity.CurrentActor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecordQueryController {

    private final RecordQueryService records;

    public RecordQueryController(RecordQueryService records) {
        this.records = records;
    }

    @GetMapping("/api/v1/projects/{projectId}/records")
    public RecordQueryService.RecordList list(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                              @RequestParam(required = false) List<String> recordType,
                                              @RequestParam(required = false) List<String> trustStatus,
                                              @RequestParam(required = false) LocalDate occurredFrom,
                                              @RequestParam(required = false) LocalDate occurredTo,
                                              @RequestParam(defaultValue = "20") int limit,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestParam(required = false) String cursor) {
        return records.list(actor.userId(), projectId, recordType, trustStatus, occurredFrom, occurredTo, limit, offset, cursor);
    }

    @GetMapping("/api/v1/records/{recordId}")
    public ResponseEntity<RecordQueryService.RecordDetail> detail(@AuthenticationPrincipal CurrentActor actor,
                                                                    @PathVariable long recordId) {
        RecordQueryService.RecordDetail record = records.detail(actor.userId(), recordId);
        return ResponseEntity.ok().eTag("\"" + record.version() + "\"").body(record);
    }
}
