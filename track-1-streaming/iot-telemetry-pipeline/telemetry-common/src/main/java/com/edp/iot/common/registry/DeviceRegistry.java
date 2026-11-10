package com.edp.iot.common.registry;

import com.edp.iot.common.status.Thresholds;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-through cache of the device registry. Entries, including "not registered" answers, expire
 * after {@code ttl}, so a deactivation reaches every service within that time (FR-14, FR-21) and
 * a flood of unknown device IDs does not reach the database.
 */
public class DeviceRegistry {

    private static final String SELECT = """
            SELECT d.device_id, d.tenant_id, d.device_type, d.active, d.credential_hash,
                   COALESCE(d.heartbeat_seconds, t.heartbeat_seconds) AS heartbeat_seconds,
                   d.home_region, tn.rate_limit_per_sec, t.battery_degraded_pct, t.signal_degraded_dbm
            FROM device d
            JOIN device_type t ON t.device_type = d.device_type
            JOIN tenant tn ON tn.tenant_id = d.tenant_id
            WHERE d.device_id = ANY(?)
            """;

    private final JdbcTemplate jdbc;
    private final LoadingCache<String, Optional<DeviceInfo>> cache;

    public DeviceRegistry(JdbcTemplate jdbc, Duration ttl, long maxSize) {
        this.jdbc = jdbc;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build(new Loader());
    }

    public Optional<DeviceInfo> find(String deviceId) {
        return cache.get(deviceId);
    }

    /** Looks up many devices with at most one query for the ones not cached. */
    public Map<String, DeviceInfo> findAll(Collection<String> deviceIds) {
        Map<String, DeviceInfo> result = new HashMap<>();
        cache.getAll(Set.copyOf(deviceIds)).forEach((id, info) -> info.ifPresent(i -> result.put(id, i)));
        return result;
    }

    public void invalidate(String deviceId) {
        cache.invalidate(deviceId);
    }

    private Map<String, Optional<DeviceInfo>> query(Set<? extends String> ids) {
        Map<String, Optional<DeviceInfo>> found = new HashMap<>();
        for (String id : ids) {
            found.put(id, Optional.empty());
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(SELECT);
            Array array = con.createArrayOf("text", ids.toArray());
            ps.setArray(1, array);
            return ps;
        }, (ResultSet rs) -> {
            DeviceInfo info = map(rs);
            found.put(info.deviceId(), Optional.of(info));
        });
        return found;
    }

    private static DeviceInfo map(ResultSet rs) throws SQLException {
        return new DeviceInfo(
                rs.getString("device_id"),
                rs.getString("tenant_id"),
                rs.getString("device_type"),
                rs.getBoolean("active"),
                rs.getString("credential_hash"),
                rs.getInt("heartbeat_seconds"),
                rs.getString("home_region"),
                rs.getInt("rate_limit_per_sec"),
                new Thresholds(rs.getInt("battery_degraded_pct"), rs.getInt("signal_degraded_dbm")));
    }

    private final class Loader implements CacheLoader<String, Optional<DeviceInfo>> {

        @Override
        public Optional<DeviceInfo> load(String key) {
            return query(Set.of(key)).get(key);
        }

        @Override
        public Map<String, Optional<DeviceInfo>> loadAll(Set<? extends String> keys) {
            return query(keys);
        }
    }
}
