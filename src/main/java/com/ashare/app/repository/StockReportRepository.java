package com.ashare.app.repository;

import com.ashare.app.dto.MarketDtos.StockReportContent;
import com.ashare.app.dto.MarketDtos.StockReportResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StockReportRepository {
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public StockReportRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public Optional<StockReportResponse> findByReportId(String reportId) {
    List<StockReportResponse> rows = jdbcTemplate.query(
        """
        select report_id, stock_code, stock_name, report_date, price, pct, verdict, score,
          ai_generated, model, report_json, created_at, updated_at
        from stock_reports
        where report_id = ?
        limit 1
        """,
        (rs, rowNum) -> toResponse(rs),
        reportId);
    return rows.stream().findFirst();
  }

  public Optional<StockReportResponse> findLatest(String stockCode) {
    List<StockReportResponse> rows = jdbcTemplate.query(
        """
        select report_id, stock_code, stock_name, report_date, price, pct, verdict, score,
          ai_generated, model, report_json, created_at, updated_at
        from stock_reports
        where stock_code = ?
        order by report_date desc, created_at desc
        limit 1
        """,
        (rs, rowNum) -> toResponse(rs),
        stockCode);
    return rows.stream().findFirst();
  }

  public StockReportResponse save(
      String reportId,
      String stockCode,
      String stockName,
      LocalDate reportDate,
      double price,
      double pct,
      String verdict,
      int score,
      boolean aiGenerated,
      String model,
      StockReportContent content) {
    jdbcTemplate.update(
        """
        insert into stock_reports
          (report_id, stock_code, stock_name, report_date, price, pct, verdict, score,
           ai_generated, model, report_json, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on duplicate key update
          stock_name = values(stock_name),
          report_date = values(report_date),
          price = values(price),
          pct = values(pct),
          verdict = values(verdict),
          score = values(score),
          ai_generated = values(ai_generated),
          model = values(model),
          report_json = values(report_json),
          updated_at = values(updated_at)
        """,
        reportId,
        stockCode,
        stockName,
        reportDate,
        price,
        pct,
        verdict,
        score,
        aiGenerated,
        model == null ? "" : model,
        writeContent(content),
        LocalDateTime.now(),
        LocalDateTime.now());
    return findByReportId(reportId)
        .orElseThrow(() -> new IllegalStateException("报告保存后读取失败"));
  }

  private StockReportResponse toResponse(ResultSet rs) throws SQLException {
    return new StockReportResponse(
        rs.getString("report_id"),
        rs.getString("stock_code"),
        rs.getString("stock_name"),
        rs.getDate("report_date").toLocalDate().toString(),
        rs.getDouble("price"),
        rs.getDouble("pct"),
        rs.getString("verdict"),
        rs.getInt("score"),
        rs.getBoolean("ai_generated"),
        rs.getString("model"),
        readContent(rs.getString("report_json")),
        rs.getTimestamp("created_at").toLocalDateTime().toString(),
        rs.getTimestamp("updated_at").toLocalDateTime().toString());
  }

  private String writeContent(StockReportContent content) {
    try {
      return objectMapper.writeValueAsString(content);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("报告内容序列化失败", ex);
    }
  }

  private StockReportContent readContent(String value) {
    try {
      return objectMapper.readValue(value, StockReportContent.class);
    } catch (JsonProcessingException ex) {
      return new StockReportContent(
          "报告内容解析失败，请重新生成。",
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          null,
          List.of(),
          List.of(),
          List.of(),
          "本报告由系统生成，仅供研究参考，不构成投资建议。");
    }
  }
}
