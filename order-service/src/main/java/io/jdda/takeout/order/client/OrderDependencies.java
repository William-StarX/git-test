package io.jdda.takeout.order.client;

import io.jdda.takeout.OrderModels.OrderItemRequest;
import io.jdda.takeout.order.domain.OrderProblem;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Bounded HTTP clients for menu pricing and inventory reservation.
 * Never touches catalog or inventory databases directly.
 */
@Component
public class OrderDependencies {

    public record MenuItem(int menuItemId, String name, long priceCents) {
    }

    private final RestClient menuClient;
    private final RestClient inventoryClient;

    public OrderDependencies(
            @Value("${MENU_BASE_URL:http://localhost:8081}") String menuBaseUrl,
            @Value("${INVENTORY_BASE_URL:http://localhost:8082}") String inventoryBaseUrl) {
        JdkClientHttpRequestFactory menuFactory = new JdkClientHttpRequestFactory();
        menuFactory.setReadTimeout(Duration.ofSeconds(2));
        this.menuClient = RestClient.builder().baseUrl(menuBaseUrl).requestFactory(menuFactory).build();

        JdkClientHttpRequestFactory inventoryFactory = new JdkClientHttpRequestFactory();
        inventoryFactory.setReadTimeout(Duration.ofSeconds(2));
        this.inventoryClient = RestClient.builder().baseUrl(inventoryBaseUrl).requestFactory(inventoryFactory).build();
    }

    @SuppressWarnings("unchecked")
    public Map<Integer, MenuItem> fetchMenu() {
        try {
            List<Map<String, Object>> body = menuClient.get()
                    .uri("/api/menu")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(List.class);
            Map<Integer, MenuItem> result = new HashMap<>();
            if (body != null) {
                for (Map<String, Object> entry : body) {
                    Object idObj = entry.get("id");
                    Object nameObj = entry.get("name");
                    Object priceObj = entry.get("priceCents");
                    if (idObj == null || priceObj == null) {
                        continue;
                    }
                    int id = ((Number) idObj).intValue();
                    long price = ((Number) priceObj).longValue();
                    String name = nameObj == null ? "" : nameObj.toString();
                    result.put(id, new MenuItem(id, name, price));
                }
            }
            return result;
        } catch (RestClientResponseException ex) {
            throw OrderProblem.serviceUnavailable("menu service unavailable");
        } catch (RuntimeException ex) {
            throw OrderProblem.serviceUnavailable("menu service unavailable");
        }
    }

    public void reserve(UUID orderId, List<OrderItemRequest> items) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("items", items.stream()
                .map(i -> Map.of("menuItemId", i.menuItemId(), "quantity", i.quantity()))
                .toList());
        try {
            inventoryClient.post()
                    .uri("/internal/reservations/{orderUuid}", orderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
            if (status == HttpStatus.NOT_FOUND) {
                throw OrderProblem.menuItemNotFound();
            }
            if (status == HttpStatus.CONFLICT) {
                throw OrderProblem.insufficientStock();
            }
            throw OrderProblem.serviceUnavailable("inventory service unavailable");
        } catch (RuntimeException ex) {
            throw OrderProblem.serviceUnavailable("inventory service unavailable");
        }
    }

    public void release(UUID orderId) {
        try {
            inventoryClient.delete()
                    .uri("/internal/reservations/{orderUuid}", orderId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
            if (status == HttpStatus.NOT_FOUND) {
                // Tombstone: already released, treat as success.
                return;
            }
            throw OrderProblem.serviceUnavailable("inventory release failed");
        } catch (RuntimeException ex) {
            throw OrderProblem.serviceUnavailable("inventory release failed");
        }
    }
}
