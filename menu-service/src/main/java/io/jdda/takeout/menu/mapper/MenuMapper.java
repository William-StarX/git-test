package io.jdda.takeout.menu.mapper;

import io.jdda.takeout.menu.domain.MenuItem;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MenuMapper {
    private final JdbcTemplate jdbc;

    public MenuMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MenuItem> selectCatalogItemsById() {
        return jdbc.query(SELECT_MENU, (rs, rowNum) -> new MenuItem(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getLong("price_cents")));
    }

    private static final String SELECT_MENU =
            "SELECT id, name, price_cents FROM catalog.menu_item ORDER BY id";
}
