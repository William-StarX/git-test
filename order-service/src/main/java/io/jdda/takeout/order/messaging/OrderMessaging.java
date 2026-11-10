package io.jdda.takeout.order.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.jdda.takeout.order.mapper.OrderOutboxMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static io.jdda.takeout.order.domain.OrderModels.OrderPlaced;

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
    private final OrderOutboxMapper mapper;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    OutboxRelay(OrderOutboxMapper mapper, RabbitTemplate rabbit, TransactionTemplate transactions, ObjectMapper json) {
        this.mapper = mapper;
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
        var pending = mapper.lockNextUnpublishedEventSkipLocked();
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
        mapper.markOutboxEventPublished(event.eventId());
        return true;
    }
}

@Component
class OrderConfirmation {
    private final OrderOutboxMapper mapper;
    private final ObjectMapper json;

    OrderConfirmation(OrderOutboxMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
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
        boolean known = Boolean.TRUE.equals(mapper.containsMatchingOutboxEvent(event.eventId(), event.orderId()));
        if (!known) throw new AmqpRejectAndDontRequeueException("Order event does not match the outbox.");

        int firstDelivery = mapper.insertProcessedEventIfAbsent(event.eventId(), event.orderId());
        if (firstDelivery == 0) return;
        mapper.confirmPendingOrder(event.orderId());
    }
}
