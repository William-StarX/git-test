package io.jdda.takeout.menu.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jdda.takeout.menu.domain.MenuItem;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MenuCatalog {

    private static final Logger log = LoggerFactory.getLogger(MenuCatalog.class);

    static final String CACHE_KEY = "takeout:menu:v1";
    static final Duration CACHE_TTL = Duration.ofSeconds(120);

    private static final String SELECT_MENU =
            "SELECT id, name, price_cents FROM catalog.menu_item ORDER BY id";

    private static final TypeReference<List<MenuItem>> MENU_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public MenuCatalog(JdbcTemplate jdbcTemplate,
                       StringRedisTemplate redisTemplate,
                       ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public MenuResult readMenu() {
        List<MenuItem> cached = readFromCache();
        if (cached != null) {
            return new MenuResult(cached, "HIT");
        }

        List<MenuItem> items = readFromDatabase();
        boolean cacheWritten = writeToCache(items);
        return new MenuResult(items, cacheWritten ? "MISS" : "BYPASS");
    }

    private List<MenuItem> readFromCache() {
        try {
            String payload = redisTemplate.opsForValue().get(CACHE_KEY);
            if (payload == null) {
                return null;
            }
            List<MenuItem> items = objectMapper.readValue(payload, MENU_LIST_TYPE);
            if (items == null) {
                return null;
            }
            return items;
        } catch (Exception ex) {
            log.warn("Menu cache read failed, falling back to catalog: {}", ex.toString());
            return null;
        }
    }

    private boolean writeToCache(List<MenuItem> items) {
        try {
            String payload = objectMapper.writeValueAsString(items);
            redisTemplate.opsForValue().set(CACHE_KEY, payload, CACHE_TTL);
            return true;
        } catch (Exception ex) {
            log.warn("Menu cache write failed, serving catalog result: {}", ex.toString());
            return false;
        }
    }

    private List<MenuItem> readFromDatabase() {
        return jdbcTemplate.query(SELECT_MENU, (rs, rowNum) -> new MenuItem(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getLong("price_cents")));
    }

    public record MenuResult(List<MenuItem> items, String cacheStatus) {
    }
}
