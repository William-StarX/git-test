package io.jdda.takeout.menu.controller;

import io.jdda.takeout.menu.service.MenuService;
import io.jdda.takeout.menu.domain.MenuItem;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final MenuService menuCatalog;

    public MenuController(MenuService menuCatalog) {
        this.menuCatalog = menuCatalog;
    }

    @GetMapping
    public ResponseEntity<List<MenuItem>> getMenu() {
        MenuService.MenuResult result = menuCatalog.readMenu();
        return ResponseEntity.ok()
                .header("X-Cache", result.cacheStatus())
                .body(result.items());
    }
}
