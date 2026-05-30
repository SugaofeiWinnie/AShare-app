package com.ashare.app.service;

import com.ashare.app.dto.MarketDtos.BoardResponse;
import com.ashare.app.dto.MarketDtos.LadderRow;
import com.ashare.app.dto.MarketDtos.LadderSummary;
import com.ashare.app.dto.MarketDtos.LimitStock;
import com.ashare.app.dto.MarketDtos.MarketMood;
import com.ashare.app.dto.MarketDtos.MarketOverview;
import com.ashare.app.dto.MarketDtos.QuoteItem;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class MarketService {
  private static final List<String> INDEX_IDS = List.of(
      "1.000001", "0.399001", "0.399006", "1.000688", "1.000016", "1.000300");
  private static final DateTimeFormatter TRADE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

  private final RestTemplate restTemplate;
  private final String quoteBaseUrl;
  private final String limitPoolUrl;

  public MarketService(
      RestTemplate restTemplate,
      @Value("${ashare.eastmoney.quote-base-url}") String quoteBaseUrl,
      @Value("${ashare.eastmoney.limit-pool-url}") String limitPoolUrl) {
    this.restTemplate = restTemplate;
    this.quoteBaseUrl = quoteBaseUrl;
    this.limitPoolUrl = limitPoolUrl;
  }

  public MarketOverview overview() {
    List<QuoteItem> indices = indices();
    List<QuoteItem> industries = boards("industry", "top", 80).rows();
    List<QuoteItem> concepts = boards("concept", "top", 80).rows();
    LadderSummary ladder = ladder();
    MarketMood mood = mood(indices, industries, concepts);
    return new MarketOverview(
        indices,
        industries,
        concepts,
        mood,
        buildAnalysis(mood, industries, concepts),
        ladder,
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
  }

  public List<QuoteItem> indices() {
    return quoteRows("ulist.np/get", Map.of("secids", String.join(",", INDEX_IDS)))
        .stream()
        .map(this::quoteItem)
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
        .map(this::quoteItem)
        .sorted(comparator)
        .limit(Math.max(1, Math.min(limit, 80)))
        .toList();
    return new BoardResponse(rows);
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
        .sorted(Comparator.comparing(LadderRow::promoted).reversed()
            .thenComparing(Comparator.comparingInt(LadderRow::todayDays).reversed())
            .thenComparing(Comparator.comparingInt(LadderRow::yesterdayDays).reversed()))
        .toList();

    int promotedCount = (int) rows.stream().filter(LadderRow::promoted).count();
    int maxDays = rows.stream().mapToInt(LadderRow::todayDays).max().orElse(0);
    double rate = rows.isEmpty() ? 0 : promotedCount * 100.0 / rows.size();
    return new LadderSummary(today.date(), yesterday.date(), rows.size(), promotedCount, rate, maxDays, rows);
  }

  private List<JsonNode> quoteRows(String path, Map<String, String> params) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(quoteBaseUrl + "/" + path)
        .queryParam("fltt", "2")
        .queryParam("invt", "2")
        .queryParam("fields", "f12,f14,f2,f3,f4,f5,f6,f8,f15,f16,f17,f18,f20,f21,f62");
    params.forEach(builder::queryParam);
    JsonNode payload = restTemplate.getForObject(builder.toUriString(), JsonNode.class);
    return rowsFrom(payload.path("data").path("diff"));
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
    JsonNode payload = restTemplate.getForObject(builder.toUriString(), JsonNode.class);
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

  private QuoteItem quoteItem(JsonNode item) {
    return new QuoteItem(
        text(item, "f12"),
        text(item, "f14"),
        number(item, "f2"),
        number(item, "f3"),
        number(item, "f4"),
        number(item, "f6"),
        number(item, "f8"),
        number(item, "f62"));
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
    String label = heat >= 66 ? "偏热" : heat <= 38 ? "偏冷" : "震荡";
    String detail = String.format(Locale.CHINA,
        "板块上涨 %d 个、下跌 %d 个，主要指数平均涨跌幅 %+.2f%%。", up, down, avgIndexPct);
    return new MarketMood(label, heat, up, down, flat, avgIndexPct, detail);
  }

  private String buildAnalysis(MarketMood mood, List<QuoteItem> industries, List<QuoteItem> concepts) {
    String topIndustries = topText(industries, false);
    String weakIndustries = topText(industries, true);
    String topConcepts = topText(concepts, false);
    String breadth = mood.up() > mood.down() * 1.4
        ? "上涨板块明显多于下跌板块，市场扩散度较好"
        : mood.down() > mood.up() * 1.4
            ? "下跌板块明显多于上涨板块，资金防守意愿更强"
            : "涨跌板块数量接近，市场仍处于结构分化";
    String indexTone = mood.avgIndexPct() > 0.45
        ? "主要指数整体偏强，权重与成长方向至少有一端在提供支撑"
        : mood.avgIndexPct() < -0.45
            ? "主要指数整体承压，说明风险偏好仍需修复"
            : "主要指数波动不大，盘面更依赖板块轮动";
    return "%s，%s。行业层面，领涨方向集中在 %s，弱势方向主要是 %s。概念层面，热点包括 %s。短线看量能和扩散，中线看盈利与政策，长期看产业升级与资本回报。"
        .formatted(breadth, indexTone, fallback(topIndustries, "暂无明显方向"), fallback(weakIndustries, "暂无明显方向"), fallback(topConcepts, "暂无明显方向"));
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
        .collect(Collectors.joining("、"));
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
}
