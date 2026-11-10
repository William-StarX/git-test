package io.jdda.takeout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static io.jdda.takeout.OrderModels.OrderPlaced;

@Configuration
class OrderMessaging {
    static final String EXCHANGE = "takeout.orders";
    static final String PLACED = "order.placed";
    static final String PLACED_QUEUE = "takeout.order.placed";

    @Bean
    Declarables orderTopology() {
        var orders = new DirectExchange(EXCHANGE);
        var dead = new DirectExchange("takeout.orders.dead");
        var placed = QueueBuilder.durable(PLACED_QUEUE).deadLetterExchange(dead.getName())
                .deadLetterRoutingKey("order.dead").build();
        var rejected = QueueBuilder.durable("takeout.order.dead").build();
        return new Declarables(orders, dead, placed, rejected,
                BindingBuilder.bind(placed).to(orders).with(PLACED),
                BindingBuilder.bind(rejected).to(dead).with("order.dead"));
    }
}

@Component
class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    OutboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.transactions = transactions;
        this.json = json;
    }

    @Scheduled(fixedDelay = 1000)
    void flush() {
        try {
            for (int i = 0; i < 32; i++) {
                if (!Boolean.TRUE.equals(transactions.execute(status -> publishNext()))) return;
            }
        } catch (RuntimeException failure) {
            log.warn("Outbox publish deferred: {}", failure.getMessage());
        }
    }

    private boolean publishNext() {
        var pending = jdbc.query("""
                SELECT event_id, order_id FROM outbox_event WHERE published_at IS NULL
                ORDER BY created_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new OrderPlaced(rs.getObject("event_id", UUID.class), rs.getObject("order_id", UUID.class)));
        if (pending.isEmpty()) return false;

        OrderPlaced event = pending.getFirst();
        var correlation = new CorrelationData(event.eventId().toString());
        try {
            rabbit.convertAndSend(OrderMessaging.EXCHANGE, OrderMessaging.PLACED, json.writeValueAsString(event), message -> {
                message.getMessageProperties().setMessageId(event.eventId().toString());
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }, correlation);
            var confirmation = correlation.getFuture().get(3, TimeUnit.SECONDS);
            if (!confirmation.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("Broker did not confirm a routable order event.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Broker confirmation interrupted.", interrupted);
        } catch (ExecutionException | TimeoutException | JsonProcessingException failure) {
            throw new IllegalStateException("Order event confirmation failed.", failure);
        }

        // A crash between the broker acknowledgement and this commit may redeliver the event.
        jdbc.update("UPDATE outbox_event SET published_at=clock_timestamp() WHERE event_id=?", event.eventId());
        return true;
    }
}

@Component
class OrderConfirmation {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    OrderConfirmation(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @RabbitListener(queues = OrderMessaging.PLACED_QUEUE)
    @Transactional
    public void confirm(String payload) {
        OrderPlaced event;
        try {
            event = json.readValue(payload, OrderPlaced.class);
        } catch (JsonProcessingException error) {
            throw new AmqpRejectAndDontRequeueException("Malformed order event.", error);
        }
        if (event == null || event.eventId() == null || event.orderId() == null) {
            throw new AmqpRejectAndDontRequeueException("Order event has no identity.");
        }
        boolean known = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM outbox_event WHERE event_id=? AND order_id=?)
                """, Boolean.class, event.eventId(), event.orderId()));
        if (!known) throw new AmqpRejectAndDontRequeueException("Order event does not match the outbox.");

        int firstDelivery = jdbc.update("""
                INSERT INTO processed_event (event_id, order_id) VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.orderId());
        if (firstDelivery == 0) return;
        jdbc.update("""
                UPDATE takeout_order SET status='CONFIRMED', confirmed_at=clock_timestamp()
                WHERE id=? AND status='PENDING'
                """, event.orderId());
    }
}
