package io.jdda.takeout.order.service;

import io.jdda.takeout.order.domain.OrderModels.CreateOrderRequest;
import io.jdda.takeout.order.domain.OrderModels.OrderItemRequest;
import io.jdda.takeout.order.domain.OrderModels.OrderItemResponse;
import io.jdda.takeout.order.domain.OrderModels.OrderResponse;
import io.jdda.takeout.order.client.OrderDependencies;
import io.jdda.takeout.order.domain.OrderProblem;
import io.jdda.takeout.order.mapper.OrderMapper.IntentRow;
import io.jdda.takeout.order.mapper.OrderMapper.OrderKey;
import io.jdda.takeout.order.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    private static final String STATUS_OPEN = "OPEN";

    private final OrderMapper mapper;
    private final TransactionTemplate tx;
    private final OrderDependencies deps;

    public OrderService(OrderMapper mapper, PlatformTransactionManager txManager, OrderDependencies deps) {
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        this.deps = deps;
    }

    public record CreateResult(OrderResponse order, boolean created) {
    }

public CreateResult create(String idempotencyKey, CreateOrderRequest request) {
    validate(idempotencyKey, request);
    String customerId = request.customerId();
    String requestHash = hash(request.items());
    OrderResponse existing = findOrder(customerId, idempotencyKey, requestHash);
    if (existing != null) {
        return new CreateResult(existing, false);
    }
    UUID intentId = UUID.randomUUID();
    mapper.insertIntentIfAbsent(intentId, customerId, idempotencyKey, requestHash, STATUS_OPEN);
    return tx.execute(status -> {
        List<IntentRow> rows = mapper.lockIntentByCustomerAndKey(customerId, idempotencyKey);
        if (rows.isEmpty()) {
            throw OrderProblem.serviceUnavailable("intent unavailable");
        }
        IntentRow intent = rows.get(0);
        if (!requestHash.equals(intent.requestHash())) {
            throw OrderProblem.idempotencyConflict();
        }
        OrderResponse replay = findOrder(customerId, idempotencyKey, requestHash);
        if (replay != null) {
            return new CreateResult(replay, false);
        }
        if (!STATUS_OPEN.equals(intent.status())) {
            throw OrderProblem.idempotencyConflict();
        }
        UUID orderId = intent.orderId();
        Map<Integer, OrderDependencies.MenuItem> menu = deps.fetchMenu();
        List<OrderItemResponse> snapshots = new ArrayList<>();
        long total = 0L;
        for (OrderItemRequest item : request.items()) {
            OrderDependencies.MenuItem menuItem = menu.get(item.menuItemId());
            if (menuItem == null) {
                throw OrderProblem.menuItemNotFound();
            }
            long lineTotal = menuItem.priceCents() * item.quantity();
            total += lineTotal;
            snapshots.add(new OrderItemResponse(item.menuItemId(), menuItem.name(), menuItem.priceCents(), item.quantity()));
        }
        deps.reserve(orderId, request.items());
        mapper.insertPendingOrder(orderId, customerId, idempotencyKey, requestHash, total);
        for (OrderItemResponse snapshot : snapshots) {
            mapper.insertOrderLine(orderId, snapshot);
        }
        mapper.insertOrderPlacedOutboxEvent(UUID.randomUUID(), orderId);
        mapper.completeIntent(intent.id());
        OrderResponse response = mapper.loadOrder(orderId);
        return new CreateResult(response, true);
    });
}
    public OrderResponse get(UUID id) {
        OrderResponse order = mapper.loadOrder(id);
        if (order == null) {
            throw OrderProblem.orderNotFound();
        }
        return order;
    }

public OrderResponse cancel(UUID id) {
    Boolean firstCancel = tx.execute(status -> {
        List<String> statuses = mapper.lockOrderStatus(id);
        if (statuses.isEmpty()) {
            throw OrderProblem.orderNotFound();
        }
        String current = statuses.get(0);
        if ("CANCELLED".equals(current)) {
            return Boolean.FALSE;
        }
        mapper.markOrderCancelled(id);
        mapper.enqueueStockReleaseIfAbsent(id);
        return Boolean.TRUE;
    });
    if (Boolean.TRUE.equals(firstCancel)) {
        try {
            release(id);
        } catch (RuntimeException ignored) {
        }
    }
    return get(id);
}

private void release(UUID orderId) {
    deps.release(orderId);
    mapper.markStockReleaseCompleted(orderId);
}

@org.springframework.scheduling.annotation.Scheduled(fixedDelay = 2000)
public void retryReleases() {
    List<UUID> pending = mapper.listPendingStockReleases();
    for (UUID orderId : pending) {
        try {
            release(orderId);
        } catch (RuntimeException ignored) {
        }
    }
}

@org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
public void abandonStaleIntents() {
    tx.execute(status -> {
        List<IntentRow> rows = mapper.lockStaleIntentSkipLocked(STATUS_OPEN);
        if (rows.isEmpty()) {
            return null;
        }
        IntentRow intent = rows.get(0);
        List<UUID> existing = mapper.findExistingOrderId(intent.orderId());
        if (!existing.isEmpty()) {
            return null;
        }
        deps.release(intent.orderId());
        mapper.expireIntent(intent.id());
        return null;
    });
}
private void validate(String idempotencyKey, CreateOrderRequest request) {
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._-]{1,128}")) {
        throw OrderProblem.badRequest("invalid idempotency key");
    }
    if (request == null) {
        throw OrderProblem.badRequest("request is required");
    }
    String customerId = request.customerId();
    if (customerId == null || !customerId.matches("[A-Za-z0-9_-]{1,64}")) {
        throw OrderProblem.badRequest("invalid customer id");
    }
    List<OrderItemRequest> items = request.items();
    if (items == null || items.isEmpty() || items.size() > 20) {
        throw OrderProblem.badRequest("items must contain between 1 and 20 entries");
    }
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    for (OrderItemRequest item : items) {
        if (item == null) {
            throw OrderProblem.badRequest("item is required");
        }
        int menuItemId = item.menuItemId();
        if (menuItemId <= 0) {
            throw OrderProblem.badRequest("invalid menu item id");
        }
        if (!seen.add(menuItemId)) {
            throw OrderProblem.badRequest("duplicate menu item id");
        }
        int quantity = item.quantity();
        if (quantity < 1 || quantity > 99) {
            throw OrderProblem.badRequest("invalid quantity");
        }
    }
}
    private static String hash(List<OrderItemRequest> items) {
        List<OrderItemRequest> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(OrderItemRequest::menuItemId));
        StringBuilder sb = new StringBuilder();
        for (OrderItemRequest item : sorted) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(item.menuItemId()).append(':').append(item.quantity());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OrderResponse findOrder(String customerId, String requestKey, String requestHash) {
        List<OrderKey> rows = mapper.findOrderKeyByCustomerAndRequest(customerId, requestKey);
        if (rows.isEmpty()) {
            return null;
        }
        OrderKey row = rows.getFirst();
        UUID orderId = row.id();
        String storedHash = row.requestHash();
        if (!java.util.Objects.equals(storedHash, requestHash)) {
            throw OrderProblem.idempotencyConflict();
        }
        return mapper.loadOrder(orderId);
    }

}
