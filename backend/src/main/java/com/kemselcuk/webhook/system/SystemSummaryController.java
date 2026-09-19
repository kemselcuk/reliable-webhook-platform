package com.kemselcuk.webhook.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemSummaryController {

    private final SystemSummaryService summaryService;

    public SystemSummaryController(SystemSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @GetMapping("/summary")
    public SystemSummaryResponse summary() {
        return summaryService.currentSummary();
    }
}
