package io.jdda.takeout.menu.api;

import io.jdda.takeout.menu.application.MenuCatalog;
import io.jdda.takeout.menu.domain.MenuItem;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menu")
public class MenuApi {

    private final MenuCatalog menuCatalog;

    public MenuApi(MenuCatalog menuCatalog) {
        this.menuCatalog = menuCatalog;
    }

    @GetMapping
    public ResponseEntity<List<MenuItem>> getMenu() {
        MenuCatalog.MenuResult result = menuCatalog.readMenu();
        return ResponseEntity.ok()
                .header("X-Cache", result.cacheStatus())
                .body(result.items());
    }
}
