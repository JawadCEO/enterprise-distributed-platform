package com.edp.iot.common.line;

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
 * Read-through cache of the line registry. Entries, including "not registered" answers, are
 * reloaded in the background once they are older than {@code ttl}, so a deactivation reaches every
 * service within about that time and a flood of unknown ICCIDs does not reach the database.
 *
 * <p>While the database cannot be reached, a failed reload keeps the entry it had, so lines already
 * seen keep working (the database drill in part 3). An entry is dropped only after
 * {@code staleLimit} without a successful reload.
 */
public class LineRegistry {

    private static final String SELECT = """
            SELECT l.iccid, l.customer_id, l.sim_type, l.device_kind, l.line_state,
                   COALESCE(l.keep_alive_seconds, k.keep_alive_seconds) AS keep_alive_seconds,
                   l.data_allowance_bytes, l.home_region, c.rate_limit_per_sec, c.auto_suspend_on_sim_move
            FROM line l
            JOIN device_kind k ON k.device_kind = l.device_kind
            JOIN customer c ON c.customer_id = l.customer_id
            WHERE l.iccid = ANY(?)
            """;

    private final JdbcTemplate jdbc;
    private final LoadingCache<String, Optional<LineInfo>> cache;

    /** How long an entry is kept when it cannot be reloaded. */
    public static final Duration DEFAULT_STALE_LIMIT = Duration.ofHours(1);

    public LineRegistry(JdbcTemplate jdbc, Duration ttl, long maxSize) {
        this(jdbc, ttl, DEFAULT_STALE_LIMIT, maxSize);
    }

    public LineRegistry(JdbcTemplate jdbc, Duration ttl, Duration staleLimit, long maxSize) {
        this.jdbc = jdbc;
        this.cache = Caffeine.newBuilder()
                .refreshAfterWrite(ttl)
                .expireAfterWrite(staleLimit.compareTo(ttl) > 0 ? staleLimit : ttl.multipliedBy(2))
                .maximumSize(maxSize)
                .build(new Loader());
    }

    public Optional<LineInfo> find(String iccid) {
        return cache.get(iccid);
    }

    /** Looks up many lines with at most one query for the ones not cached. */
    public Map<String, LineInfo> findAll(Collection<String> iccids) {
        Map<String, LineInfo> result = new HashMap<>();
        cache.getAll(Set.copyOf(iccids)).forEach((id, info) -> info.ifPresent(i -> result.put(id, i)));
        return result;
    }

    public void invalidate(String iccid) {
        cache.invalidate(iccid);
    }

    private Map<String, Optional<LineInfo>> query(Set<? extends String> ids) {
        Map<String, Optional<LineInfo>> found = new HashMap<>();
        for (String id : ids) {
            found.put(id, Optional.empty());
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(SELECT);
            Array array = con.createArrayOf("text", ids.toArray());
            ps.setArray(1, array);
            return ps;
        }, (ResultSet rs) -> {
            LineInfo info = map(rs);
            found.put(info.iccid(), Optional.of(info));
        });
        return found;
    }

    public static LineInfo map(ResultSet rs) throws SQLException {
        return new LineInfo(
                rs.getString("iccid"),
                rs.getString("customer_id"),
                SimType.valueOf(rs.getString("sim_type")),
                rs.getString("device_kind"),
                LineState.valueOf(rs.getString("line_state")),
                rs.getInt("keep_alive_seconds"),
                rs.getLong("data_allowance_bytes"),
                rs.getString("home_region"),
                rs.getInt("rate_limit_per_sec"),
                rs.getBoolean("auto_suspend_on_sim_move"));
    }

    private final class Loader implements CacheLoader<String, Optional<LineInfo>> {

        @Override
        public Optional<LineInfo> load(String key) {
            return query(Set.of(key)).get(key);
        }

        @Override
        public Map<String, Optional<LineInfo>> loadAll(Set<? extends String> keys) {
            return query(keys);
        }
    }
}
