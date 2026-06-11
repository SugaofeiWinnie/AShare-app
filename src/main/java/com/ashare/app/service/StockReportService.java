package com.ashare.app.service;

import com.ashare.app.dto.MarketDtos.BuyZone;
import com.ashare.app.dto.MarketDtos.InvestorView;
import com.ashare.app.dto.MarketDtos.ReportMetric;
import com.ashare.app.dto.MarketDtos.ScanDimension;
import com.ashare.app.dto.MarketDtos.StockReportContent;
import com.ashare.app.dto.MarketDtos.StockReportResponse;
import com.ashare.app.dto.MarketDtos.ValuationModel;
import com.ashare.app.repository.StockReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class StockReportService {
  private static final DateTimeFormatter REPORT_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private final RestTemplate restTemplate;
  private final OpenAiService openAiService;
  private final StockReportRepository repository;
  private final String quoteBaseUrl;

  public StockReportService(
      RestTemplate restTemplate,
      OpenAiService openAiService,
      StockReportRepository repository,
      @Value("${ashare.eastmoney.quote-base-url}") String quoteBaseUrl) {
    this.restTemplate = restTemplate;
    this.openAiService = openAiService;
    this.repository = repository;
    this.quoteBaseUrl = quoteBaseUrl;
  }

  public Optional<StockReportResponse> latest(String code) {
    return repository.findLatest(normalizeCode(code));
  }

  public Optional<StockReportResponse> find(String reportId) {
    return repository.findByReportId(reportId);
  }

  public StockReportResponse generate(String rawCode, boolean forceRefresh) {
    String code = normalizeCode(rawCode);
    LocalDate today = LocalDate.now();
    if (!forceRefresh) {
      Optional<StockReportResponse> existing = repository.findLatest(code)
          .filter(report -> today.toString().equals(report.reportDate()));
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    QuoteSnapshot quote = quote(code);
    int score = score(quote);
    String verdict = verdict(score);
    StockReportContent content = buildContent(quote, score, verdict);
    boolean aiGenerated = openAiService.enabled();
    if (aiGenerated) {
      String aiSummary = openAiService.generateText(buildPrompt(quote, score, verdict));
      if (!aiSummary.isBlank()) {
        content = withAiSummary(content, aiSummary);
      } else {
        aiGenerated = false;
      }
    }

    String reportId = code + "-" + LocalDateTime.now().format(REPORT_ID_TIME);
    return repository.save(
        reportId,
        code,
        quote.name(),
        today,
        quote.price(),
        quote.pct(),
        verdict,
        score,
        aiGenerated,
        aiGenerated ? openAiService.model() : "local",
        content);
  }

  private StockReportContent buildContent(QuoteSnapshot quote, int score, String verdict) {
    double price = quote.price();
    double bear = round(price * 0.82);
    double base = round(price * 1.03);
    double bull = round(price * 1.22);
    String trend = quote.pct() >= 3 ? "短线偏强" : quote.pct() <= -3 ? "短线承压" : "震荡观察";
    String valueTone = quote.pe() > 80 ? "估值偏高" : quote.pe() > 0 && quote.pe() < 25 ? "估值相对克制" : "估值需要结合成长验证";

    return new StockReportContent(
        String.format(Locale.CHINA, "%s 当前评分 %d 分，结论为“%s”。系统先基于行情、估值和资金信号生成简版报告，后续可继续接入财报、公告、研报和新闻数据增强。",
            quote.name(), score, verdict),
        List.of(
            new ReportMetric("最新价", formatPrice(price), quote.pct() >= 0 ? "up" : "down"),
            new ReportMetric("涨跌幅", signed(quote.pct()) + "%", quote.pct() >= 0 ? "up" : "down"),
            new ReportMetric("市盈率", quote.pe() > 0 ? formatNumber(quote.pe()) : "--", quote.pe() > 80 ? "warn" : "neutral"),
            new ReportMetric("市净率", quote.pb() > 0 ? formatNumber(quote.pb()) : "--", quote.pb() > 8 ? "warn" : "neutral"),
            new ReportMetric("成交额", formatAmount(quote.amount()), "neutral"),
            new ReportMetric("主力净流入", formatAmount(quote.inflow()), quote.inflow() > 0 ? "up" : quote.inflow() < 0 ? "down" : "neutral")),
        List.of(
            trend + "：今日涨跌幅为 " + signed(quote.pct()) + "%，需要结合板块强弱和成交量确认持续性。",
            valueTone + "：PE/PB 只能作为第一层过滤，真正的关键仍是利润增速、现金流和订单兑现。",
            "资金信号：" + fundSignal(quote.inflow())),
        investorViews(quote, score),
        List.of(
            new ScanDimension("趋势", clamp(score + (quote.pct() >= 0 ? 8 : -8)), trend, "以当日涨跌幅和价格位置作为 MVP 阶段的趋势代理指标。"),
            new ScanDimension("估值", clamp(quote.pe() <= 0 ? 55 : quote.pe() > 80 ? 38 : quote.pe() < 30 ? 72 : 58), valueTone, "后续接入财务预测后，可升级为 PE/PEG/DCF 联合评分。"),
            new ScanDimension("资金", clamp(quote.inflow() >= 0 ? 66 : 42), quote.inflow() >= 0 ? "资金偏暖" : "资金偏弱", "以主力净流入作为短线资金代理指标。"),
            new ScanDimension("波动", clamp(70 - (int) Math.abs(quote.pct() * 5)), "波动观察", "涨跌幅越大，追高或杀跌的风险越需要单独评估。")),
        new ValuationModel(
            bear,
            base,
            bull,
            "MVP 阶段采用行情锚定区间法，后续升级为 DCF / PE Band / 同业估值。",
            List.of("熊市情景：当前价格下修约 18%。", "基准情景：当前价格上浮约 3%。", "乐观情景：当前价格上浮约 22%。")),
        List.of(
            "行情和财务数据可能存在延迟或缺失，不能只依赖单日信号。",
            quote.pe() > 80 ? "估值对业绩兑现非常敏感，一旦增长低于预期容易出现估值压缩。" : "估值判断仍需补充行业对比和未来盈利预测。",
            "后续需要接入公告、新闻、财报和机构预期，降低单一行情数据带来的偏差。"),
        List.of(
            "财报披露、业绩预告或机构调研可能成为重新定价窗口。",
            "板块资金回流、成交额放大和突破关键均线可作为右侧确认信号。",
            "若行业政策或订单验证改善，报告评分应及时刷新。"),
        List.of(
            new BuyZone("防守观察区", bear, round(price * 0.92), "适合等待风险释放，不追求立刻买入。"),
            new BuyZone("合理跟踪区", round(price * 0.92), base, "适合结合基本面和板块强度分批观察。"),
            new BuyZone("强势确认区", base, bull, "需要成交量和业绩预期同步确认，避免单纯追高。")),
        "本报告由系统基于公开行情和 AI/规则模型生成，仅供研究参考，不构成投资建议。");
  }

  private List<InvestorView> investorViews(QuoteSnapshot quote, int score) {
    return List.of(
        new InvestorView("价值派", "巴菲特视角", score >= 70 ? "关注" : "观望", clamp(score - 8),
            "先看护城河和现金流，再决定价格是否值得。", "MVP 阶段财务数据不足，价值派会要求更多利润质量证据。"),
        new InvestorView("成长派", "彼得林奇视角", score >= 65 ? "关注" : "观望", clamp(score + 2),
            "如果增长逻辑能被财报验证，可以进入跟踪名单。", "当前先用估值和行情做代理，后续需要接入营收和利润增速。"),
        new InvestorView("趋势派", "欧奈尔视角", quote.pct() > 0 ? "偏多" : "等待", clamp(score + (quote.pct() > 0 ? 8 : -8)),
            "趋势派更重视价格强度和成交量确认。", "今日涨跌幅和主力资金是第一层信号。"),
        new InvestorView("风险控制", "霍华德马克斯视角", score < 55 ? "谨慎" : "中性", clamp(100 - Math.abs(score - 55)),
            "先问下行风险，再谈上行空间。", "估值、波动和数据缺口都需要在仓位上体现。"));
  }

  private StockReportContent withAiSummary(StockReportContent content, String aiSummary) {
    return new StockReportContent(
        aiSummary,
        content.metrics(),
        content.coreConclusions(),
        content.investorViews(),
        content.deepScan(),
        content.valuation(),
        content.risks(),
        content.catalysts(),
        content.buyZones(),
        content.disclaimer());
  }

  private String buildPrompt(QuoteSnapshot quote, int score, String verdict) {
    return """
        你是A股研究助手。请基于以下结构化行情，生成一段150字以内的中文个股研究摘要。
        要求：谨慎、具体、说明风险，不构成投资建议。

        股票：%s(%s)
        最新价：%.2f
        涨跌幅：%+.2f%%
        PE：%.2f
        PB：%.2f
        主力净流入：%.2f
        系统评分：%d
        系统结论：%s
        """.formatted(quote.name(), quote.code(), quote.price(), quote.pct(), quote.pe(), quote.pb(), quote.inflow(), score, verdict);
  }

  private QuoteSnapshot quote(String code) {
    String secid = marketPrefix(code) + "." + code;
    String url = UriComponentsBuilder.fromHttpUrl(quoteBaseUrl + "/stock/get")
        .queryParam("secid", secid)
        .queryParam("fields", "f57,f58,f43,f170,f135,f162,f167,f116,f117")
        .toUriString();
    JsonNode root = restTemplate.getForObject(url, JsonNode.class);
    JsonNode data = root == null ? null : root.path("data");
    if (data == null || data.isMissingNode() || data.isNull()) {
      throw new IllegalArgumentException("未找到股票行情：" + code);
    }
    return new QuoteSnapshot(
        text(data, "f57", code),
        text(data, "f58", code),
        number(data, "f43") / 100.0,
        number(data, "f170") / 100.0,
        number(data, "f135"),
        number(data, "f162") / 100.0,
        number(data, "f167") / 100.0,
        number(data, "f116"),
        number(data, "f117"),
        0);
  }

  private String normalizeCode(String rawCode) {
    if (rawCode == null) {
      throw new IllegalArgumentException("股票代码不能为空");
    }
    String code = rawCode.trim().replaceAll("[^0-9]", "");
    if (code.length() != 6) {
      throw new IllegalArgumentException("请输入6位A股股票代码");
    }
    return code;
  }

  private String marketPrefix(String code) {
    return code.startsWith("6") || code.startsWith("9") ? "1" : "0";
  }

  private int score(QuoteSnapshot quote) {
    int score = 55;
    score += quote.pct() >= 0 ? Math.min(18, (int) Math.round(quote.pct() * 3)) : Math.max(-18, (int) Math.round(quote.pct() * 3));
    if (quote.inflow() > 0) {
      score += 8;
    } else if (quote.inflow() < 0) {
      score -= 8;
    }
    if (quote.pe() > 0 && quote.pe() < 35) {
      score += 8;
    } else if (quote.pe() > 90) {
      score -= 12;
    }
    if (quote.pb() > 10) {
      score -= 6;
    }
    return clamp(score);
  }

  private String verdict(int score) {
    if (score >= 75) return "积极跟踪";
    if (score >= 60) return "谨慎关注";
    if (score >= 45) return "中性观察";
    return "风险优先";
  }

  private String fundSignal(double inflow) {
    if (inflow > 0) {
      return "主力资金净流入，对短线情绪有支撑。";
    }
    if (inflow < 0) {
      return "主力资金净流出，短线需要防守。";
    }
    return "MVP 阶段暂未接入个股主力净流入，资金方向先保持中性观察。";
  }

  private static int clamp(int value) {
    return Math.max(0, Math.min(100, value));
  }

  private static double round(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  private static String formatPrice(double value) {
    return value <= 0 ? "--" : String.format(Locale.CHINA, "¥%.2f", value);
  }

  private static String formatNumber(double value) {
    return value <= 0 ? "--" : String.format(Locale.CHINA, "%.2f", value);
  }

  private static String signed(double value) {
    return String.format(Locale.CHINA, "%+.2f", value);
  }

  private static String formatAmount(double value) {
    double abs = Math.abs(value);
    if (abs >= 100_000_000) {
      return String.format(Locale.CHINA, "%+.2f亿", value / 100_000_000);
    }
    if (abs >= 10_000) {
      return String.format(Locale.CHINA, "%+.2f万", value / 10_000);
    }
    return String.format(Locale.CHINA, "%+.0f", value);
  }

  private static String text(JsonNode node, String field, String fallback) {
    String value = node.path(field).asText("");
    return value.isBlank() || "-".equals(value) ? fallback : value;
  }

  private static double number(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isNumber()) {
      return value.asDouble();
    }
    String text = value.asText("");
    if (text.isBlank() || "-".equals(text)) {
      return 0;
    }
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private record QuoteSnapshot(
      String code,
      String name,
      double price,
      double pct,
      double amount,
      double pe,
      double pb,
      double totalMarketValue,
      double floatMarketValue,
      double inflow) {}
}
