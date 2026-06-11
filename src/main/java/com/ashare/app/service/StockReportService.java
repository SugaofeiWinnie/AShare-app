package com.ashare.app.service;

import com.ashare.app.dto.MarketDtos.StockReportContent;
import com.ashare.app.dto.MarketDtos.StockReportResponse;
import com.ashare.app.repository.StockReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StockReportService {
  private static final DateTimeFormatter REPORT_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private final StockReportRepository repository;
  private final ObjectMapper objectMapper;
  private final String workerCwd;
  private final String pythonCommand;

  public StockReportService(
      StockReportRepository repository,
      ObjectMapper objectMapper,
      @Value("${ashare.worker.cwd:/root/projects/AShare-worker}") String workerCwd,
      @Value("${ashare.worker.python-command:python3}") String pythonCommand) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.workerCwd = workerCwd;
    this.pythonCommand = pythonCommand;
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

    WorkerReport report = runWorker(code);
    String reportId = code + "-" + LocalDateTime.now().format(REPORT_ID_TIME);
    return repository.save(
        reportId,
        report.stockCode(),
        report.stockName(),
        LocalDate.parse(report.reportDate()),
        report.price(),
        report.pct(),
        report.verdict(),
        report.score(),
        report.aiGenerated(),
        report.model(),
        report.content());
  }

  private WorkerReport runWorker(String code) {
    ProcessBuilder builder = new ProcessBuilder(
        pythonCommand,
        "-m",
        "ashare_worker.report_cli",
        "--code",
        code);
    builder.directory(new java.io.File(workerCwd));
    builder.environment().put("PYTHONIOENCODING", "UTF-8");

    try {
      Process process = builder.start();
      String stdout;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        stdout = reader.lines().collect(Collectors.joining("\n"));
      }
      String stderr;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
        stderr = reader.lines().collect(Collectors.joining("\n"));
      }
      boolean finished = process.waitFor(25, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new IllegalStateException("Python worker 执行超时");
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException(stderr.isBlank() ? "Python worker 执行失败" : stderr);
      }
      if (stdout.isBlank()) {
        throw new IllegalStateException("Python worker 未返回报告内容");
      }
      return parseWorkerReport(stdout);
    } catch (IOException ex) {
      throw new IllegalStateException("无法启动 Python worker", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Python worker 调用被中断", ex);
    }
  }

  private WorkerReport parseWorkerReport(String stdout) throws IOException {
    JsonNode root = objectMapper.readTree(stdout);
    StockReportContent content = objectMapper.treeToValue(root.path("content"), StockReportContent.class);
    return new WorkerReport(
        text(root, "stockCode"),
        text(root, "stockName"),
        text(root, "reportDate"),
        root.path("price").asDouble(),
        root.path("pct").asDouble(),
        text(root, "verdict"),
        root.path("score").asInt(),
        root.path("aiGenerated").asBoolean(false),
        text(root, "model"),
        content);
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

  private static String text(JsonNode root, String field) {
    String value = root.path(field).asText("");
    return value == null ? "" : value;
  }

  private record WorkerReport(
      String stockCode,
      String stockName,
      String reportDate,
      double price,
      double pct,
      String verdict,
      int score,
      boolean aiGenerated,
      String model,
      StockReportContent content) {}
}
