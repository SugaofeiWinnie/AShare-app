package com.ashare.app.repository;

import com.ashare.app.dto.MarketDtos.QuoteItem;
import com.ashare.app.dto.MarketDtos.TrendPoint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
public class MarketSnapshotRepository {
  private static final TypeReference<List<TrendPoint>> TREND_LIST = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public MarketSnapshotRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public List<QuoteItem> find(LocalDate tradeDate, SnapshotType type) {
    return jdbcTemplate.query(
        """
        select code, name, price, pct, price_change, amount, turnover, inflow, trends_json
        from market_snapshots
        where trade_date = ? and snapshot_type = ?
        order by sort_order asc, code asc
        """,
        (rs, rowNum) -> toQuoteItem(rs),
        tradeDate,
        type.name());
  }

  public Optional<LocalDate> resolveTradeDate(LocalDate requested) {
    List<LocalDate> earlierOrEqual = jdbcTemplate.query(
        """
        select trade_date
        from market_snapshots
        where trade_date <= ?
        group by trade_date
        order by trade_date desc
        limit 1
        """,
        (rs, rowNum) -> rs.getDate("trade_date").toLocalDate(),
        requested);
    if (!earlierOrEqual.isEmpty()) {
      return Optional.of(earlierOrEqual.get(0));
    }

    List<LocalDate> later = jdbcTemplate.query(
        """
        select trade_date
        from market_snapshots
        where trade_date >= ?
        group by trade_date
        order by trade_date asc
        limit 1
        """,
        (rs, rowNum) -> rs.getDate("trade_date").toLocalDate(),
        requested);
    return later.stream().findFirst();
  }

  public void save(LocalDate tradeDate, SnapshotType type, List<QuoteItem> rows) {
    String sql = """
        insert into market_snapshots
          (trade_date, snapshot_type, code, name, price, pct, price_change, amount, turnover, inflow, trends_json, sort_order, captured_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on duplicate key update
          name = values(name),
          price = values(price),
          pct = values(pct),
          price_change = values(price_change),
          amount = values(amount),
          turnover = values(turnover),
          inflow = values(inflow),
          trends_json = values(trends_json),
          sort_order = values(sort_order),
          captured_at = values(captured_at)
        """;
    LocalDateTime capturedAt = LocalDateTime.now();
    for (int i = 0; i < rows.size(); i += 1) {
      QuoteItem item = rows.get(i);
      jdbcTemplate.update(
          sql,
          tradeDate,
          type.name(),
          item.code(),
          item.name(),
          item.price(),
          item.pct(),
          item.change(),
          item.amount(),
          item.turnover(),
          item.inflow(),
          writeTrends(item.trends()),
          i,
          capturedAt);
    }
  }

  private QuoteItem toQuoteItem(ResultSet rs) throws SQLException {
    return new QuoteItem(
        rs.getString("code"),
        rs.getString("name"),
        rs.getDouble("price"),
        rs.getDouble("pct"),
        rs.getDouble("price_change"),
        rs.getDouble("amount"),
        rs.getDouble("turnover"),
        rs.getDouble("inflow"),
        readTrends(rs.getString("trends_json")));
  }

  private String writeTrends(List<TrendPoint> trends) {
    try {
      return objectMapper.writeValueAsString(trends == null ? List.of() : trends);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize trend points", ex);
    }
  }

  private List<TrendPoint> readTrends(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, TREND_LIST);
    } catch (JsonProcessingException ex) {
      return List.of();
    }
  }

  public enum SnapshotType {
    INDEX,
    INDUSTRY,
    CONCEPT
  }
}
