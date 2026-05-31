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
      double inflow,
      List<TrendPoint> trends) {}

  public record TrendPoint(String time, double price, double average) {}

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

  public record GlobalMarketItem(
      String group,
      String code,
      String name,
      double price,
      double pct,
      double change,
      String updatedAt) {}

  public record CalendarItem(
      String code,
      String name,
      String earningsDate,
      String note) {}

  public record PreopenBrief(
      String tradeDate,
      List<GlobalMarketItem> overnightUs,
      List<GlobalMarketItem> chineseAdr,
      List<GlobalMarketItem> commodities,
      List<GlobalMarketItem> fx,
      List<String> importantEvents,
      List<CalendarItem> earningsCalendar,
      List<String> hotPredictions,
      String summary,
      String generatedAt) {}

  public record NorthboundItem(
      String name,
      String code,
      double dayNetAmtIn,
      double monthNetAmtIn,
      double yearNetAmtIn,
      String date,
      String date2) {}

  public record MainFundItem(
      String code,
      String name,
      String type,
      double inflow,
      double changePct,
      String date) {}

  public record DragonTigerItem(
      String code,
      String name,
      String reason,
      double closePrice,
      double changePct,
      double netBuy,
      double buyAmt,
      double sellAmt,
      double totalAmount,
      double turnoverRate,
      String tradeDate) {}

  public record FundFlowOverview(
      String tradeDate,
      List<NorthboundItem> northbound,
      List<MainFundItem> mainFunds,
      List<DragonTigerItem> dragonTiger,
      String updatedAt) {}

  public record LadderRow(
      String code,
      String name,
      String industry,
      boolean promoted,
      int todayDays,
      int yesterdayDays,
      double pct,
      String firstLimit,
      boolean intradayBroken) {}

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
      List<GlobalMarketItem> globalMarkets,
      MarketMood mood,
      String analysis,
      LadderSummary ladder,
      String tradeDate,
      String updatedAt) {}

  public record BoardResponse(List<QuoteItem> rows) {}

  public record AiReviewRequest(
      String tradeDate,
      String viewpoint,
      String operation,
      String position,
      String concern,
      String marketContext) {}

  public record AiReviewResponse(
      boolean fallback,
      String model,
      String reply,
      String generatedAt) {}
}
