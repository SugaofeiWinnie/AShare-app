package com.ashare.app.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarketSnapshotJob {
  private final MarketService marketService;

  public MarketSnapshotJob(MarketService marketService) {
    this.marketService = marketService;
  }

  @Scheduled(cron = "${ashare.scheduler.snapshot-cron}", zone = "Asia/Shanghai")
  public void captureAfterClose() {
    marketService.captureTodaySnapshot();
  }
}
