package com.ashare.app.service;

import com.ashare.app.dto.MarketDtos.AiReviewRequest;
import com.ashare.app.dto.MarketDtos.AiReviewResponse;
import com.ashare.app.dto.MarketDtos.BoardResponse;
import com.ashare.app.dto.MarketDtos.CalendarItem;
import com.ashare.app.dto.MarketDtos.DragonTigerItem;
import com.ashare.app.dto.MarketDtos.FundFlowOverview;
import com.ashare.app.dto.MarketDtos.GlobalMarketItem;
import com.ashare.app.dto.MarketDtos.LadderRow;
import com.ashare.app.dto.MarketDtos.LadderSummary;
import com.ashare.app.dto.MarketDtos.LimitStock;
import com.ashare.app.dto.MarketDtos.MainFundItem;
import com.ashare.app.dto.MarketDtos.MarketMood;
import com.ashare.app.dto.MarketDtos.MarketOverview;
import com.ashare.app.dto.MarketDtos.NorthboundItem;
import com.ashare.app.dto.MarketDtos.PreopenBrief;
import com.ashare.app.dto.MarketDtos.QuoteItem;
import com.ashare.app.dto.MarketDtos.TrendPoint;
import com.ashare.app.repository.MarketSnapshotRepository;
import com.ashare.app.repository.MarketSnapshotRepository.SnapshotType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class MarketService {
  private static final List<String> INDEX_IDS = List.of(
      "1.000001", "0.399001", "0.399006", "1.000688", "1.000016", "1.000300");

  private static final List<FeedSpec> OVERNIGHT_US = List.of(
      new FeedSpec("stockanalysis", "etf/spy/", "SPY", "SPY ETF", "US"),
      new FeedSpec("stockanalysis", "etf/qqq/", "QQQ", "QQQ ETF", "US"),
      new FeedSpec("stockanalysis", "etf/dia/", "DIA", "DIA ETF", "US"),
      new FeedSpec("stockanalysis", "etf/iwm/", "IWM", "IWM ETF", "US"));

  private static final List<FeedSpec> CHINESE_ADR = List.of(
      new FeedSpec("stockanalysis", "stocks/baba/", "BABA", "Alibaba", "ADR"),
      new FeedSpec("stockanalysis", "stocks/pdd/", "PDD", "PDD", "ADR"),
      new FeedSpec("stockanalysis", "stocks/jd/", "JD", "JD.com", "ADR"),
      new FeedSpec("stockanalysis", "stocks/bidu/", "BIDU", "Baidu", "ADR"),
      new FeedSpec("stockanalysis", "stocks/nio/", "NIO", "NIO", "ADR"),
      new FeedSpec("stockanalysis", "stocks/xpev/", "XPEV", "Xpeng", "ADR"));

  private static final List<FeedSpec> COMMODITIES = List.of(
      new FeedSpec("stooq", "^xauusd", "XAU/USD", "Gold", "Commodity"),
      new FeedSpec("stooq", "cl.f", "CL.F", "WTI Crude", "Commodity"),
      new FeedSpec("stooq", "si.f", "SI.F", "Silver", "Commodity"),
      new FeedSpec("stooq", "hg.f", "HG.F", "Copper", "Commodity"));

  private static final List<FeedSpec> FX = List.of(
      new FeedSpec("stooq", "usdcny", "USDCNY", "USD/CNY", "FX"),
      new FeedSpec("stooq", "usdcnh", "USDCNH", "USD/CNH", "FX"),
      new FeedSpec("stooq", "eurusd", "EURUSD", "EUR/USD", "FX"),
      new FeedSpec("stooq", "usdjpy", "USDJPY", "USD/JPY", "FX"));

  private static final List<FeedSpec> EARNINGS_WATCHLIST = List.of(
      new FeedSpec("stockanalysis", "stocks/baba/", "BABA", "Alibaba", "Earnings"),
      new FeedSpec("stockanalysis", "stocks/pdd/", "PDD", "PDD", "Earnings"),
      new FeedSpec("stockanalysis", "stocks/jd/", "JD", "JD.com", "Earnings"),
      new FeedSpec("stockanalysis", "stocks/bidu/", "BIDU", "Baidu", "Earnings"),
      new FeedSpec("stockanalysis", "stocks/nio/", "NIO", "NIO", "Earnings"),
      new FeedSpec("stockanalysis", "stocks/xpev/", "XPEV", "Xpeng", "Earnings"));

  private static final Pattern STOCK_ANALYSIS_PRICE = Pattern.compile(
      "Real-Time Price.*?([0-9.,]+)\\s+([+-]?[0-9.,]+) \\(([+-]?[0-9.,]+)%\\)",
      Pattern.DOTALL);
  private static final Pattern STOCK_ANALYSIS_PREVIOUS = Pattern.compile(
      "Previous Close\\s+([0-9.,]+)");
  private static final Pattern STOCK_ANALYSIS_EARNINGS = Pattern.compile(
      "Earnings Date\\s+([A-Za-z]{3,9} \\d{1,2}, \\d{4})");
  private static final DateTimeFormatter TRADE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;
  private final MarketSnapshotRepository snapshotRepository;
  private final OpenAiService openAiService;
  private final String quoteBaseUrl;
  private final String trendBaseUrl;
  private final String limitPoolUrl;

  public MarketService(
      RestTemplate restTemplate,
      ObjectMapper objectMapper,
      MarketSnapshotRepository snapshotRepository,
      OpenAiService openAiService,
      @Value("${ashare.eastmoney.quote-base-url}") String quoteBaseUrl,
      @Value("${ashare.eastmoney.trend-base-url}") String trendBaseUrl,
      @Value("${ashare.eastmoney.limit-pool-url}") String limitPoolUrl) {
    this.restTemplate = restTemplate;
    this.objectMapper = objectMapper;
    this.snapshotRepository = snapshotRepository;
    this.openAiService = openAiService;
    this.quoteBaseUrl = quoteBaseUrl;
    this.trendBaseUrl = trendBaseUrl;
    this.limitPoolUrl = limitPoolUrl;
  }

  public MarketOverview overview() {
    MarketOverview overview = liveOverview();
    if (shouldPersistLiveSnapshot(overview)) {
      snapshot(overview);
    }
    return overview;
  }

  public MarketOverview overview(LocalDate date) {
    if (date == null || !date.isBefore(LocalDate.now())) {
      return overview();
    }
    return snapshotOverview(date);
  }

  public void captureTodaySnapshot() {
    snapshot(liveOverview());
  }

  public List<QuoteItem> indices() {
    return liveIndices();
  }

  public List<QuoteItem> indices(LocalDate date) {
    if (date == null || !date.isBefore(LocalDate.now())) {
      return liveIndices();
    }
    LocalDate resolved = snapshotRepository.resolveTradeDate(date)
        .orElseThrow(() -> new IllegalArgumentException("no historical snapshot available"));
    return snapshotRepository.find(resolved, SnapshotType.INDEX);
  }

  private List<QuoteItem> liveIndices() {
    Map<String, List<TrendPoint>> trends = INDEX_IDS.stream()
        .collect(Collectors.toMap(this::plainCode, this::trendRows));
    return quoteRows("ulist.np/get", Map.of("secids", String.join(",", INDEX_IDS))).stream()
        .map(item -> quoteItem(item, trends.getOrDefault(text(item, "f12"), List.of())))
        .toList();
  }

  public BoardResponse boards(String type, String order, int limit) {
    String fs = "concept".equalsIgnoreCase(type) ? "m:90+t:3" : "m:90+t:2";
    Comparator<QuoteItem> comparator = Comparator.comparingDouble(QuoteItem::pct);
    if (!"bottom".equalsIgnoreCase(order)) {
      comparator = comparator.reversed();
    }
    List<QuoteItem> rows = quoteRows("clist/get", Map.of(
            "fs", fs,
            "pn", "1",
            "pz", String.valueOf(Math.max(limit, 80)),
            "po", "1",
            "fid", "f3"))
        .stream()
        .map(item -> quoteItem(item, List.of()))
        .sorted(comparator)
        .limit(Math.max(1, Math.min(limit, 80)))
        .toList();
    return new BoardResponse(rows);
  }

  public PreopenBrief preopen() {
    List<GlobalMarketItem> overnightUs = overnightUs();
    List<GlobalMarketItem> chineseAdr = chineseAdr();
    List<GlobalMarketItem> commodities = commodities();
    List<GlobalMarketItem> fx = fx();
    List<CalendarItem> earningsCalendar = earningsCalendar();
    List<String> importantEvents = buildImportantEvents(overnightUs, chineseAdr, commodities, fx, earningsCalendar);
    List<String> hotPredictions = buildHotPredictions(overnightUs, chineseAdr, commodities, fx, earningsCalendar);
    String summary = buildPreopenSummary(overnightUs, chineseAdr, commodities, fx, earningsCalendar, hotPredictions);
    return new PreopenBrief(
        LocalDate.now().toString(),
        overnightUs,
        chineseAdr,
        commodities,
        fx,
        importantEvents,
        earningsCalendar,
        hotPredictions,
        summary,
        LocalDateTime.now().format(DISPLAY_TIME));
  }

  public FundFlowOverview funds() {
    List<NorthboundItem> northbound = northbound();
    List<MainFundItem> mainFunds = mainFunds();
    List<DragonTigerItem> dragonTiger = dragonTiger();
    return new FundFlowOverview(
        northbound.stream().findFirst().map(NorthboundItem::date2).orElse(LocalDate.now().toString()),
        northbound,
        mainFunds,
        dragonTiger,
        LocalDateTime.now().format(DISPLAY_TIME));
  }

  public AiReviewResponse aiReview(AiReviewRequest request) {
    AiReviewRequest safe = request == null
        ? new AiReviewRequest(null, "", "", "", "", "")
        : request;
    String prompt = buildAiReviewPrompt(safe);
    String reply = openAiService.generateText(prompt);
    if (reply == null || reply.isBlank()) {
      return new AiReviewResponse(true, openAiService.model(), fallbackReview(safe), LocalDateTime.now().format(DISPLAY_TIME));
    }
    return new AiReviewResponse(false, openAiService.model(), reply.trim(), LocalDateTime.now().format(DISPLAY_TIME));
  }

  private MarketOverview liveOverview() {
    List<QuoteItem> indices = indices();
    List<QuoteItem> industries = boards("industry", "top", 80).rows();
    List<QuoteItem> concepts = boards("concept", "top", 80).rows();
    List<GlobalMarketItem> globalMarkets = globalMarkets();
    LadderSummary ladder = ladder();
    return buildOverview(indices, industries, concepts, globalMarkets, ladder, resolveTradeDate(indices));
  }

  private MarketOverview snapshotOverview(LocalDate date) {
    LocalDate resolvedDate = snapshotRepository.resolveTradeDate(date)
        .orElseThrow(() -> new IllegalArgumentException("no historical snapshot available"));
    List<QuoteItem> indices = snapshotRepository.find(resolvedDate, SnapshotType.INDEX);
    List<QuoteItem> industries = snapshotRepository.find(resolvedDate, SnapshotType.INDUSTRY);
    List<QuoteItem> concepts = snapshotRepository.find(resolvedDate, SnapshotType.CONCEPT);
    List<GlobalMarketItem> globalMarkets = globalMarkets();
    LadderSummary ladder = new LadderSummary(
        resolvedDate.format(TRADE_DATE),
        resolvedDate.minusDays(1).format(TRADE_DATE),
        0,
        0,
        0,
        0,
        List.of());
    return buildOverview(indices, industries, concepts, globalMarkets, ladder, resolvedDate);
  }

  private MarketOverview buildOverview(
      List<QuoteItem> indices,
      List<QuoteItem> industries,
      List<QuoteItem> concepts,
      List<GlobalMarketItem> globalMarkets,
      LadderSummary ladder,
      LocalDate tradeDate) {
    MarketMood mood = mood(indices, industries, concepts);
    return new MarketOverview(
        indices,
        industries,
        concepts,
        globalMarkets,
        mood,
        buildAnalysis(mood, industries, concepts),
        ladder,
        tradeDate.toString(),
        LocalDateTime.now().format(DISPLAY_TIME));
  }

  private void snapshot(MarketOverview overview) {
    LocalDate tradeDate = LocalDate.parse(overview.tradeDate());
    snapshotRepository.save(tradeDate, SnapshotType.INDEX, overview.indices());
    snapshotRepository.save(tradeDate, SnapshotType.INDUSTRY, overview.industries());
    snapshotRepository.save(tradeDate, SnapshotType.CONCEPT, overview.concepts());
  }

  private boolean shouldPersistLiveSnapshot(MarketOverview overview) {
    LocalDate tradeDate = LocalDate.parse(overview.tradeDate());
    return tradeDate.isBefore(LocalDate.now()) || LocalTime.now().isAfter(LocalTime.of(15, 35));
  }

  private LocalDate resolveTradeDate(List<QuoteItem> indices) {
    return indices.stream()
        .flatMap(item -> item.trends().stream())
        .map(TrendPoint::time)
        .filter(time -> time != null && time.length() >= 10)
        .map(time -> LocalDate.parse(time.substring(0, 10)))
        .findFirst()
        .orElse(LocalDate.now());
  }

  public LadderSummary ladder() {
    Pool today = findLimitPool(LocalDate.now(), 0);
    Pool yesterday = findLimitPool(LocalDate.parse(today.date(), TRADE_DATE), -1);
    Map<String, LimitStock> todayMap = today.pool().stream()
        .collect(Collectors.toMap(LimitStock::code, Function.identity(), (a, b) -> a, LinkedHashMap::new));

    List<LadderRow> rows = yesterday.pool().stream()
        .map(stock -> {
          LimitStock promoted = todayMap.get(stock.code());
          return new LadderRow(
              stock.code(),
              stock.name(),
              promoted == null ? stock.industry() : promoted.industry(),
              promoted != null,
              promoted == null ? 0 : Math.max(promoted.lbc(), stock.lbc() + 1),
              stock.lbc(),
              promoted == null ? stock.pct() : promoted.pct(),
              promoted == null ? stock.firstLimit() : promoted.firstLimit());
        })
        .sorted(Comparator.comparing((LadderRow row) -> row.promoted()).reversed()
            .thenComparing(Comparator.comparingInt(LadderRow::todayDays).reversed())
            .thenComparing(Comparator.comparingInt(LadderRow::yesterdayDays).reversed()))
        .toList();

    int promotedCount = (int) rows.stream().filter(LadderRow::promoted).count();
    int maxDays = rows.stream().mapToInt(LadderRow::todayDays).max().orElse(0);
    double rate = rows.isEmpty() ? 0 : promotedCount * 100.0 / rows.size();
    return new LadderSummary(today.date(), yesterday.date(), rows.size(), promotedCount, rate, maxDays, rows);
  }

  private List<GlobalMarketItem> globalMarkets() {
    List<GlobalMarketItem> rows = new ArrayList<>();
    rows.addAll(overnightUs());
    rows.addAll(chineseAdr());
    rows.addAll(commodities());
    rows.addAll(fx());
    return rows;
  }

  private List<GlobalMarketItem> overnightUs() {
    return OVERNIGHT_US.stream().map(this::safeFetchMarketQuote).toList();
  }

  private List<GlobalMarketItem> chineseAdr() {
    return CHINESE_ADR.stream().map(this::safeFetchMarketQuote).toList();
  }

  private List<GlobalMarketItem> commodities() {
    return COMMODITIES.stream().map(this::safeFetchMarketQuote).toList();
  }

  private List<GlobalMarketItem> fx() {
    return FX.stream().map(this::safeFetchMarketQuote).toList();
  }

  private List<CalendarItem> earningsCalendar() {
    return EARNINGS_WATCHLIST.stream()
        .map(spec -> {
          String earningsDate = fetchEarningsDate(spec);
          if (earningsDate.isBlank()) {
            return null;
          }
          GlobalMarketItem quote = fetchStockAnalysisQuote(spec);
          String note = quote.pct() > 0
              ? "The stock looks stronger into earnings."
              : quote.pct() < 0
                  ? "The stock is softer into earnings."
                  : "The stock is stable into earnings.";
          return new CalendarItem(spec.code(), spec.name(), earningsDate, note);
        })
        .filter(item -> item != null)
        .toList();
  }

  private List<String> buildImportantEvents(
      List<GlobalMarketItem> overnightUs,
      List<GlobalMarketItem> chineseAdr,
      List<GlobalMarketItem> commodities,
      List<GlobalMarketItem> fx,
      List<CalendarItem> earningsCalendar) {
    GlobalMarketItem usLead = overnightUs.stream().max(Comparator.comparingDouble(item -> Math.abs(item.pct()))).orElse(null);
    GlobalMarketItem adrLead = chineseAdr.stream().max(Comparator.comparingDouble(item -> Math.abs(item.pct()))).orElse(null);
    GlobalMarketItem commodityLead = commodities.stream().max(Comparator.comparingDouble(item -> Math.abs(item.pct()))).orElse(null);
    GlobalMarketItem fxLead = fx.stream().max(Comparator.comparingDouble(item -> Math.abs(item.pct()))).orElse(null);
    List<String> items = new ArrayList<>();
    if (usLead != null) {
      items.add("Overnight US lead: " + usLead.name() + formatMove(usLead));
    }
    if (adrLead != null) {
      items.add("China ADR lead: " + adrLead.name() + formatMove(adrLead));
    }
    if (commodityLead != null) {
      items.add("Commodity focus: " + commodityLead.name() + formatMove(commodityLead));
    }
    if (fxLead != null) {
      items.add("FX watch: " + fxLead.name() + formatMove(fxLead));
    }
    if (!earningsCalendar.isEmpty()) {
      CalendarItem first = earningsCalendar.get(0);
      items.add("Earnings watch: " + first.name() + " (" + first.earningsDate() + ")");
    }
    while (items.size() < 4) {
      items.add("Watch volume, leadership rotation and index breadth after the open.");
    }
    return items.stream().limit(5).toList();
  }

  private List<String> buildHotPredictions(
      List<GlobalMarketItem> overnightUs,
      List<GlobalMarketItem> chineseAdr,
      List<GlobalMarketItem> commodities,
      List<GlobalMarketItem> fx,
      List<CalendarItem> earningsCalendar) {
    List<String> items = new ArrayList<>();
    GlobalMarketItem us = overnightUs.stream().max(Comparator.comparingDouble(item -> Math.abs(item.pct()))).orElse(null);
    GlobalMarketItem adr = chineseAdr.stream().max(Comparator.comparingDouble(item -> Math.abs(item.pct()))).orElse(null);
    GlobalMarketItem comm = commodities.stream().max(Comparator.comparingDouble(item -> Math.abs(item.pct()))).orElse(null);
    GlobalMarketItem rate = fx.stream().max(Comparator.comparingDouble(item -> Math.abs(item.pct()))).orElse(null);
    if (us != null && adr != null) {
      items.add("If US tech stays firm, China tech and AI names may keep repairing.");
    }
    if (comm != null) {
      items.add(comm.name() + " swings may keep energy and resources active.");
    }
    if (rate != null) {
      items.add("A one-way FX move can accelerate sector rotation.");
    }
    if (!earningsCalendar.isEmpty()) {
      items.add("Ahead of earnings, quality names with clear guidance may attract premium.");
    }
    if (items.isEmpty()) {
      items.add("The open looks neutral; wait for volume confirmation and the first leadership trade.");
    }
    return items.stream().limit(5).toList();
  }

  private String buildPreopenSummary(
      List<GlobalMarketItem> overnightUs,
      List<GlobalMarketItem> chineseAdr,
      List<GlobalMarketItem> commodities,
      List<GlobalMarketItem> fx,
      List<CalendarItem> earningsCalendar,
      List<String> hotPredictions) {
    String us = overnightUs.stream().map(this::briefMove).collect(Collectors.joining(", "));
    String adr = chineseAdr.stream().limit(3).map(this::briefMove).collect(Collectors.joining(", "));
    String comm = commodities.stream().limit(2).map(this::briefMove).collect(Collectors.joining(", "));
    String fxText = fx.stream().limit(2).map(this::briefMove).collect(Collectors.joining(", "));
    String earnings = earningsCalendar.stream().limit(3)
        .map(item -> item.name() + " " + item.earningsDate())
        .collect(Collectors.joining(", "));
    String predict = hotPredictions.stream().limit(3).collect(Collectors.joining("; "));
    return String.format(Locale.CHINA,
        "Overnight US: %s. ADRs: %s. Commodities: %s. FX: %s. Earnings: %s. Predictions: %s.",
        us, adr, comm, fxText, earnings, predict);
  }

  private List<NorthboundItem> northbound() {
    JsonNode data = getJson(
        "https://push2.eastmoney.com/api/qt/kamt/get?fields1=f1,f2,f3,f4&fields2=f51,f52,f53,f54,f55,f56,f57,f58&ut=7eea3edcaed734bea9cbfc24409ed989",
        "https://data.eastmoney.com/");
    JsonNode root = data.path("data");
    if (root.isMissingNode()) {
      return List.of();
    }
    List<NorthboundItem> rows = new ArrayList<>();
    rows.add(toNorthbound("Northbound Shanghai", "hk2sh", root.path("hk2sh")));
    rows.add(toNorthbound("Northbound Shenzhen", "hk2sz", root.path("hk2sz")));
    rows.add(toNorthbound("Southbound Shanghai", "sh2hk", root.path("sh2hk")));
    rows.add(toNorthbound("Southbound Shenzhen", "sz2hk", root.path("sz2hk")));
    return rows;
  }

  private NorthboundItem toNorthbound(String name, String code, JsonNode node) {
    return new NorthboundItem(
        name,
        code,
        number(node, "dayNetAmtIn"),
        number(node, "monthNetAmtIn"),
        number(node, "yearNetAmtIn"),
        text(node, "date"),
        text(node, "date2"));
  }

  private List<MainFundItem> mainFunds() {
    List<MainFundItem> items = new ArrayList<>();
    items.addAll(boardByInflow("industry", "Industry", 8));
    items.addAll(boardByInflow("concept", "Concept", 8));
    return items.stream()
        .sorted(Comparator.comparingDouble(MainFundItem::inflow).reversed())
        .limit(16)
        .toList();
  }

  private List<MainFundItem> boardByInflow(String type, String label, int limit) {
    String fs = "concept".equalsIgnoreCase(type) ? "m:90+t:3" : "m:90+t:2";
    return quoteRows("clist/get", Map.of(
            "fs", fs,
            "pn", "1",
            "pz", "80",
            "po", "1",
            "fid", "f62"))
        .stream()
        .map(item -> new MainFundItem(
            text(item, "f12"),
            text(item, "f14"),
            label,
            number(item, "f62"),
            number(item, "f3"),
            LocalDate.now().toString()))
        .sorted(Comparator.comparingDouble(MainFundItem::inflow).reversed())
        .limit(limit)
        .toList();
  }

  private List<DragonTigerItem> dragonTiger() {
    try {
      String html = fetchText("https://data.eastmoney.com/stock/lhb.html?isappinstalled=0", "https://data.eastmoney.com/");
      Matcher matcher = Pattern.compile("var\\s+pagedata\\s*=\\s*(\\{.*?\\});\\s*</script>", Pattern.DOTALL).matcher(html);
      if (!matcher.find()) {
        return List.of();
      }
      JsonNode root;
      try {
        root = objectMapper.readTree(matcher.group(1));
      } catch (Exception ex) {
        return List.of();
      }
      JsonNode data = root.path("jgmmqk").path("result").path("data");
      if (!data.isArray()) {
        data = root.path("sbgg_all").path("result").path("data");
      }
      if (!data.isArray()) {
        return List.of();
      }
      List<DragonTigerItem> rows = new ArrayList<>();
      for (JsonNode row : data) {
        rows.add(new DragonTigerItem(
            text(row, "SECURITY_CODE"),
            text(row, "SECURITY_NAME_ABBR"),
            fallback(text(row, "EXPLANATION"), "LHB observation"),
            number(row, "CLOSE_PRICE"),
            number(row, "CHANGE_RATE"),
            number(row, "NET_BUY_AMT") != 0 ? number(row, "NET_BUY_AMT") : number(row, "BILLBOARD_NET_AMT"),
            number(row, "BUY_AMT") != 0 ? number(row, "BUY_AMT") : number(row, "BILLBOARD_BUY_AMT"),
            number(row, "SELL_AMT") != 0 ? number(row, "SELL_AMT") : number(row, "BILLBOARD_SELL_AMT"),
            number(row, "ACCUM_AMOUNT") != 0 ? number(row, "ACCUM_AMOUNT") : number(row, "BILLBOARD_DEAL_AMT"),
            number(row, "TURNOVERRATE"),
            fallback(text(row, "TRADE_DATE"), LocalDate.now().toString())));
      }
      return rows.stream()
          .sorted(Comparator.comparingDouble(DragonTigerItem::netBuy).reversed())
          .limit(10)
          .toList();
    } catch (RuntimeException ex) {
      return List.of();
    }
  }

  private String buildAiReviewPrompt(AiReviewRequest request) {
    return """
        You are a Chinese A-share post-market review assistant.
        Please answer in Chinese.
        Requirements:
        1. Judge whether the user's view and actions match the current market tone.
        2. Point out the key risks and the signals worth watching.
        3. Give 3 actionable suggestions for tomorrow.
        4. Keep the tone professional and concise.

        Date: %s
        View: %s
        Action: %s
        Position: %s
        Concern: %s
        Market context: %s
        """
        .formatted(
            fallback(request.tradeDate(), LocalDate.now().toString()),
            fallback(request.viewpoint(), "n/a"),
            fallback(request.operation(), "n/a"),
            fallback(request.position(), "n/a"),
            fallback(request.concern(), "n/a"),
            fallback(request.marketContext(), "n/a"));
  }

  private String fallbackReview(AiReviewRequest request) {
    return """
        OPENAI_API_KEY is not configured yet, so here is a local review:

        Your view: %s
        Your action: %s

        Market context: %s

        Suggestions:
        1. Check whether your action is aligned with the market leader.
        2. Keep the position size flexible if the money flow is not persistent.
        3. Focus on continuity in the strongest sector tomorrow.
        """
        .formatted(
            fallback(request.viewpoint(), "n/a"),
            fallback(request.operation(), "n/a"),
            fallback(request.marketContext(), "n/a"));
  }

  private String plainCode(String secid) {
    int index = secid.indexOf('.');
    return index >= 0 ? secid.substring(index + 1) : secid;
  }

  private String briefMove(GlobalMarketItem item) {
    return item.name() + formatMove(item);
  }

  private String formatMove(GlobalMarketItem item) {
    return String.format(Locale.CHINA, "%s%.2f%%", item.pct() >= 0 ? "+" : "", item.pct());
  }

  private String formatMove(double value) {
    return String.format(Locale.CHINA, "%s%.2f", value >= 0 ? "+" : "", value);
  }

  private List<JsonNode> quoteRows(String path, Map<String, String> params) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(quoteBaseUrl + "/" + path)
        .queryParam("fltt", "2")
        .queryParam("invt", "2")
        .queryParam("fields", "f12,f14,f2,f3,f4,f5,f6,f8,f15,f16,f17,f18,f20,f21,f62");
    params.forEach(builder::queryParam);
    JsonNode payload = getJson(builder.toUriString(), "https://data.eastmoney.com/");
    return rowsFrom(payload.path("data").path("diff"));
  }

  private List<TrendPoint> trendRows(String secid) {
    try {
      UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(trendBaseUrl)
          .queryParam("secid", secid)
          .queryParam("fields1", "f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11")
          .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58")
          .queryParam("ut", "7eea3edcaed734bea9cbfc24409ed989")
          .queryParam("iscr", "0")
          .queryParam("iscca", "0")
          .queryParam("ndays", "1");
      JsonNode payload = getJson(builder.toUriString(), "https://quote.eastmoney.com/");
      JsonNode trends = payload.path("data").path("trends");
      if (!trends.isArray()) {
        return List.of();
      }
      List<TrendPoint> rows = new ArrayList<>();
      trends.forEach(row -> {
        String[] parts = row.asText().split(",");
        if (parts.length >= 8) {
          rows.add(new TrendPoint(parts[0], parseDouble(parts[2]), parseDouble(parts[7])));
        }
      });
      return rows;
    } catch (RuntimeException ignored) {
      return List.of();
    }
  }

  private Pool findLimitPool(LocalDate startDate, int direction) {
    LocalDate cursor = startDate;
    for (int i = 0; i < 12; i += 1) {
      if (direction != 0) {
        cursor = cursor.plusDays(direction);
      }
      String date = cursor.format(TRADE_DATE);
      List<LimitStock> pool = limitPool(date);
      if (!pool.isEmpty()) {
        return new Pool(date, pool);
      }
    }
    return new Pool(startDate.format(TRADE_DATE), List.of());
  }

  private List<LimitStock> limitPool(String date) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(limitPoolUrl)
        .queryParam("ut", "7eea3edcaed734bea9cbfc24409ed989")
        .queryParam("dpt", "wz.ztzt")
        .queryParam("Pageindex", "0")
        .queryParam("pagesize", "500")
        .queryParam("sort", "fbt:asc")
        .queryParam("date", date);
    JsonNode payload = getJson(builder.toUriString(), "https://data.eastmoney.com/");
    return rowsFrom(payload.path("data").path("pool")).stream().map(this::limitStock).toList();
  }

  private List<JsonNode> rowsFrom(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return List.of();
    }
    List<JsonNode> rows = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(rows::add);
    } else if (node.isObject()) {
      node.fields().forEachRemaining(entry -> rows.add(entry.getValue()));
    }
    return rows;
  }

  private QuoteItem quoteItem(JsonNode item, List<TrendPoint> trends) {
    return new QuoteItem(
        text(item, "f12"),
        text(item, "f14"),
        number(item, "f2"),
        number(item, "f3"),
        number(item, "f4"),
        number(item, "f6"),
        number(item, "f8"),
        number(item, "f62"),
        trends);
  }

  private LimitStock limitStock(JsonNode item) {
    JsonNode zttj = item.path("zttj");
    int lbc = integer(item, "lbc", integer(zttj, "ct", 1));
    return new LimitStock(
        text(item, "c"),
        text(item, "m"),
        text(item, "n"),
        number(item, "p") / 1000.0,
        number(item, "zdp"),
        number(item, "amount"),
        number(item, "hs"),
        fallback(text(item, "hybk"), "--"),
        lbc,
        integer(zttj, "days", lbc),
        text(item, "fbt"),
        text(item, "lbt"),
        integer(item, "zbc", 0));
  }

  private MarketMood mood(List<QuoteItem> indices, List<QuoteItem> industries, List<QuoteItem> concepts) {
    List<QuoteItem> boards = new ArrayList<>();
    boards.addAll(industries);
    boards.addAll(concepts);
    int up = (int) boards.stream().filter(item -> item.pct() > 0).count();
    int down = (int) boards.stream().filter(item -> item.pct() < 0).count();
    int flat = boards.size() - up - down;
    double avgIndexPct = indices.stream().mapToDouble(QuoteItem::pct).average().orElse(0);
    int heat = (int) Math.round(((up + flat * 0.5) / Math.max(boards.size(), 1)) * 70
        + clamp(avgIndexPct * 6, -15, 15) + 15);
    heat = (int) clamp(heat, 0, 100);
    String label = heat >= 66 ? "Hot" : heat <= 38 ? "Cold" : "Range";
    String detail = String.format(Locale.CHINA,
        "Boards up %d, down %d; average index change %+.2f%%.",
        up, down, avgIndexPct);
    return new MarketMood(label, heat, up, down, flat, avgIndexPct, detail);
  }

  private String buildAnalysis(MarketMood mood, List<QuoteItem> industries, List<QuoteItem> concepts) {
    String topIndustries = topText(industries, false);
    String weakIndustries = topText(industries, true);
    String topConcepts = topText(concepts, false);
    String breadth = mood.down() > mood.up() * 1.4
        ? "Down boards clearly outnumber up boards."
        : mood.up() > mood.down() * 1.4
            ? "Up boards clearly outnumber down boards."
            : "Breadth is still split and rotational.";
    String indexTone = mood.avgIndexPct() > 0.45
        ? "Major indices are leaning stronger."
        : mood.avgIndexPct() < -0.45
            ? "Major indices are under pressure."
            : "Major indices are mostly range-bound.";
    return "%s %s Leaders: %s. Weakness: %s. Concepts: %s. Short term focuses on volume and breadth, medium term on earnings and policy, long term on industrial upgrade and capital returns."
        .formatted(
            breadth,
            indexTone,
            fallback(topIndustries, "n/a"),
            fallback(weakIndustries, "n/a"),
            fallback(topConcepts, "n/a"));
  }

  private String topText(List<QuoteItem> rows, boolean weakest) {
    Comparator<QuoteItem> comparator = Comparator.comparingDouble(QuoteItem::pct);
    if (!weakest) {
      comparator = comparator.reversed();
    }
    return rows.stream()
        .sorted(comparator)
        .limit(3)
        .map(item -> "%s%+.2f%%".formatted(item.name(), item.pct()))
        .collect(Collectors.joining(", "));
  }

  private String fetchEarningsDate(FeedSpec spec) {
    try {
      String html = fetchText("https://stockanalysis.com/" + spec.resource(), "https://stockanalysis.com/");
      Matcher matcher = STOCK_ANALYSIS_EARNINGS.matcher(normalizeHtmlText(html));
      return matcher.find() ? matcher.group(1) : "";
    } catch (RuntimeException ignored) {
      return "";
    }
  }

  private GlobalMarketItem safeFetchMarketQuote(FeedSpec spec) {
    try {
      return switch (spec.source()) {
        case "stooq" -> fetchStooqQuote(spec);
        case "stockanalysis" -> fetchStockAnalysisQuote(spec);
        default -> zeroQuote(spec);
      };
    } catch (RuntimeException ex) {
      return zeroQuote(spec);
    }
  }

  private GlobalMarketItem zeroQuote(FeedSpec spec) {
    return new GlobalMarketItem(spec.group(), spec.code(), spec.name(), 0, 0, 0, LocalDateTime.now().format(DISPLAY_TIME));
  }

  private GlobalMarketItem fetchStooqQuote(FeedSpec spec) {
    String url = "https://stooq.com/q/l/?s=" + URLEncoder.encode(spec.resource(), StandardCharsets.UTF_8) + "&i=d";
    String text = fetchText(url, "https://stooq.com/");
    String[] lines = text == null ? new String[0] : text.trim().split("\\R+");
    if (lines.length < 2) {
      return zeroQuote(spec);
    }
    String[] parts = lines[1].split(",");
    if (parts.length < 8) {
      return zeroQuote(spec);
    }
    double close = parseDouble(parts[6]);
    double open = parseDouble(parts[3]);
    double change = close - open;
    double pct = open == 0 ? 0 : change / open * 100.0;
    return new GlobalMarketItem(spec.group(), spec.code(), spec.name(), close, pct, change, LocalDateTime.now().format(DISPLAY_TIME));
  }

  private GlobalMarketItem fetchStockAnalysisQuote(FeedSpec spec) {
    String url = "https://stockanalysis.com/" + spec.resource();
    String html = fetchText(url, "https://stockanalysis.com/");
    String text = normalizeHtmlText(html);
    Matcher priceMatcher = STOCK_ANALYSIS_PRICE.matcher(text);
    if (priceMatcher.find()) {
      double price = parseDouble(priceMatcher.group(1));
      double change = parseDouble(priceMatcher.group(2));
      double pct = parseDouble(priceMatcher.group(3));
      return new GlobalMarketItem(spec.group(), spec.code(), spec.name(), price, pct, change, LocalDateTime.now().format(DISPLAY_TIME));
    }
    Matcher previousMatcher = STOCK_ANALYSIS_PREVIOUS.matcher(text);
    double previous = previousMatcher.find() ? parseDouble(previousMatcher.group(1)) : 0;
    if (priceMatcher.reset().find()) {
      double price = parseDouble(priceMatcher.group(1));
      double change = previous == 0 ? parseDouble(priceMatcher.group(2)) : price - previous;
      double pct = previous == 0 ? parseDouble(priceMatcher.group(3)) : (change / previous) * 100.0;
      return new GlobalMarketItem(spec.group(), spec.code(), spec.name(), price, pct, change, LocalDateTime.now().format(DISPLAY_TIME));
    }
    return zeroQuote(spec);
  }

  private String fetchText(String url, String referer) {
    HttpHeaders headers = new HttpHeaders();
    headers.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
    if (referer != null && !referer.isBlank()) {
      headers.add("Referer", referer);
    }
    HttpEntity<Void> entity = new HttpEntity<>(headers);
    return restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class).getBody();
  }

  private JsonNode getJson(String url, String referer) {
    String text = fetchText(url, referer);
    try {
      if (text == null || text.isBlank()) {
        return objectMapper.createObjectNode();
      }
      String trimmed = text.trim();
      if (trimmed.startsWith("cb(") && trimmed.endsWith(");")) {
        trimmed = trimmed.substring(3, trimmed.length() - 2);
      } else if (trimmed.contains("(") && trimmed.endsWith(")")) {
        int start = trimmed.indexOf('(') + 1;
        int end = trimmed.lastIndexOf(')');
        if (start > 0 && end > start) {
          trimmed = trimmed.substring(start, end);
        }
      }
      return objectMapper.readTree(trimmed);
    } catch (Exception ex) {
      return objectMapper.createObjectNode();
    }
  }

  private String normalizeHtmlText(String html) {
    if (html == null) {
      return "";
    }
    return html.replaceAll("(?is)<script.*?</script>", " ")
        .replaceAll("(?is)<style.*?</style>", " ")
        .replaceAll("<[^>]+>", " ")
        .replace("&nbsp;", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private String text(JsonNode item, String field) {
    JsonNode node = item.path(field);
    return node.isMissingNode() || node.isNull() ? "" : node.asText();
  }

  private double number(JsonNode item, String field) {
    JsonNode node = item.path(field);
    if (node.isNumber()) {
      return node.asDouble();
    }
    try {
      return Double.parseDouble(node.asText());
    } catch (RuntimeException ignored) {
      return 0;
    }
  }

  private double parseDouble(String value) {
    try {
      if (value == null || value.isBlank()) {
        return 0;
      }
      return Double.parseDouble(value.replace(",", ""));
    } catch (RuntimeException ignored) {
      return 0;
    }
  }

  private int integer(JsonNode item, String field, int fallback) {
    JsonNode node = item.path(field);
    if (node.isInt() || node.isLong()) {
      return node.asInt();
    }
    try {
      return Integer.parseInt(node.asText());
    } catch (RuntimeException ignored) {
      return fallback;
    }
  }

  private String fallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private record Pool(String date, List<LimitStock> pool) {}
  private record FeedSpec(String source, String resource, String code, String name, String group) {}
}
