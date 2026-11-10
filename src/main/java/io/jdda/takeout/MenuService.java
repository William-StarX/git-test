package io.jdda.takeout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import static io.jdda.takeout.OrderModels.*;

@Service
class MenuService {
    record Catalog(List<MenuItem> items, String cache) {}

    private static final String CACHE_KEY = "takeout:menu:v1";
    private static final TypeReference<List<MenuItem>> MENU_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    MenuService(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper json) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.json = json;
    }

    Catalog catalog() {
        try {
            String cached = redis.opsForValue().get(CACHE_KEY);
            if (cached != null) {
                List<MenuItem> items = json.readValue(cached, MENU_TYPE);
                if (items != null) return new Catalog(items, "HIT");
            }
        } catch (DataAccessException unavailable) {
            return new Catalog(readCatalog(), "BYPASS");
        } catch (JsonProcessingException malformed) {
            // A malformed cache entry is replaced with a fresh database snapshot.
        }

        List<MenuItem> items = readCatalog();
        try {
            redis.opsForValue().set(CACHE_KEY, json.writeValueAsString(items), Duration.ofMinutes(2));
        } catch (DataAccessException unavailable) {
            return new Catalog(items, "BYPASS");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize the menu.", error);
        }
        return new Catalog(items, "MISS");
    }

    private List<MenuItem> readCatalog() {
        return jdbc.query("SELECT id, name, price_cents FROM menu_item ORDER BY id",
                (rs, row) -> new MenuItem(rs.getLong("id"), rs.getString("name"), rs.getLong("price_cents")));
    }

    Stock stock(long id) {
        var values = jdbc.queryForList("SELECT stock FROM menu_item WHERE id=?", Integer.class, id);
        if (values.isEmpty()) throw new OrderFailure(HttpStatus.NOT_FOUND, "MENU_ITEM_NOT_FOUND", "Menu item does not exist.");
        return new Stock(id, values.getFirst());
    }
}
