package com.ashare.app.controller;

import com.ashare.app.dto.MarketDtos.AiReviewRequest;
import com.ashare.app.dto.MarketDtos.AiReviewResponse;
import com.ashare.app.dto.MarketDtos.BoardResponse;
import com.ashare.app.dto.MarketDtos.FundFlowOverview;
import com.ashare.app.dto.MarketDtos.LadderSummary;
import com.ashare.app.dto.MarketDtos.MarketOverview;
import com.ashare.app.dto.MarketDtos.PreopenBrief;
import com.ashare.app.dto.MarketDtos.QuoteItem;
import com.ashare.app.service.MarketService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequestMapping("/api/market")
public class MarketController {
  private final MarketService marketService;

  public MarketController(MarketService marketService) {
    this.marketService = marketService;
  }

  @GetMapping("/overview")
  public MarketOverview overview(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    try {
      return marketService.overview(date);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
    }
  }

  @GetMapping("/indices")
  public List<QuoteItem> indices(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return marketService.indices(date);
  }

  @GetMapping("/boards")
  public BoardResponse boards(
      @RequestParam(defaultValue = "industry") String type,
      @RequestParam(defaultValue = "top") String order,
      @RequestParam(defaultValue = "20") @Min(1) @Max(80) int limit) {
    return marketService.boards(type, order, limit);
  }

  @GetMapping("/ladder")
  public LadderSummary ladder() {
    return marketService.ladder();
  }

  @GetMapping("/preopen")
  public PreopenBrief preopen() {
    return marketService.preopen();
  }

  @GetMapping("/funds")
  public FundFlowOverview funds() {
    return marketService.funds();
  }

  @PostMapping("/ai-review")
  public AiReviewResponse aiReview(@RequestBody AiReviewRequest request) {
    return marketService.aiReview(request);
  }
}
