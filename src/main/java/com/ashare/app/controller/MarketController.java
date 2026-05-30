package com.ashare.app.controller;

import com.ashare.app.dto.MarketDtos.BoardResponse;
import com.ashare.app.dto.MarketDtos.LadderSummary;
import com.ashare.app.dto.MarketDtos.MarketOverview;
import com.ashare.app.dto.MarketDtos.QuoteItem;
import com.ashare.app.service.MarketService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/market")
public class MarketController {
  private final MarketService marketService;

  public MarketController(MarketService marketService) {
    this.marketService = marketService;
  }

  @GetMapping("/overview")
  public MarketOverview overview() {
    return marketService.overview();
  }

  @GetMapping("/indices")
  public List<QuoteItem> indices() {
    return marketService.indices();
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
}
