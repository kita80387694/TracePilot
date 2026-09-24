package com.tracepilot.observability;

import com.zaxxer.hikari.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Separate bounded connection budget keeps monitoring and emergency stop usable during saturation.
 */
@Component
public class AuxiliaryDatabase {
  private final HikariDataSource pool;
  public final JdbcTemplate jdbc;

  public AuxiliaryDatabase(DataSourceProperties properties) {
    var config = new HikariConfig();
    config.setJdbcUrl(properties.determineUrl());
    config.setUsername(properties.determineUsername());
    config.setPassword(properties.determinePassword());
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(1500);
    config.setPoolName("control-observer");
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    jdbc.setQueryTimeout(2);
  }

  @PreDestroy
  public void close() {
    pool.close();
  }
}
