package com.ashare.app.controller;

import com.ashare.app.dto.MarketDtos.StockReportGenerateRequest;
import com.ashare.app.dto.MarketDtos.StockReportResponse;
import com.ashare.app.service.StockReportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reports")
public class StockReportController {
  private final StockReportService stockReportService;

  public StockReportController(StockReportService stockReportService) {
    this.stockReportService = stockReportService;
  }

  @PostMapping("/generate")
  public StockReportResponse generate(@RequestBody StockReportGenerateRequest request) {
    try {
      return stockReportService.generate(request.code(), request.forceRefresh());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @GetMapping("/latest")
  public StockReportResponse latest(@RequestParam String code) {
    try {
      return stockReportService.latest(code)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "暂无该股票报告"));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @GetMapping("/{reportId}")
  public StockReportResponse find(@PathVariable String reportId) {
    return stockReportService.find(reportId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在"));
  }
}
