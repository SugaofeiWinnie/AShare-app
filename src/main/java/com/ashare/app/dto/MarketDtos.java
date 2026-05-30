package com.ashare.app.dto;

import java.util.List;

public final class MarketDtos {
  private MarketDtos() {}

  public record QuoteItem(
      String code,
      String name,
      double price,
      double pct,
      double change,
      double amount,
      double turnover,
      double inflow) {}

  public record LimitStock(
      String code,
      String market,
      String name,
      double price,
      double pct,
      double amount,
      double turnover,
      String industry,
      int lbc,
      int days,
      String firstLimit,
      String lastLimit,
      int openCount) {}

  public record LadderRow(
      String code,
      String name,
      String industry,
      boolean promoted,
      int todayDays,
      int yesterdayDays,
      double pct,
      String firstLimit) {}

  public record LadderSummary(
      String todayDate,
      String yesterdayDate,
      int yesterdayLimitCount,
      int promotedCount,
      double promotionRate,
      int maxLadderDays,
      List<LadderRow> rows) {}

  public record MarketMood(
      String label,
      int heat,
      int up,
      int down,
      int flat,
      double avgIndexPct,
      String detail) {}

  public record MarketOverview(
      List<QuoteItem> indices,
      List<QuoteItem> industries,
      List<QuoteItem> concepts,
      MarketMood mood,
      String analysis,
      LadderSummary ladder,
      String updatedAt) {}

  public record BoardResponse(List<QuoteItem> rows) {}
}
