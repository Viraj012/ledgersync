package com.ledgersync.webhook;

import com.ledgersync.order.Order;
import com.ledgersync.order.OrderRepository;
import com.ledgersync.order.OrderStatus;
import com.ledgersync.sync.JobType;
import com.ledgersync.sync.SyncJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {

    private final StripeEventRepository events;
    private final OrderRepository orders;
    private final SyncJobRepository syncJobs;

    public WebhookService(StripeEventRepository events, OrderRepository orders, SyncJobRepository syncJobs) {
        this.events = events;
        this.orders = orders;
        this.syncJobs = syncJobs;
    }

    /**
     * Records the event, applies it, and queues the ERP sync, all in ONE transaction (outbox pattern).
     * Either all three rows land or none do, so a crash can never leave an order without its sync job,
     * or a recorded event whose order change was lost.
     */
    @Transactional
    public WebhookResult handle(String eventId, String eventType, StripeAction action) {
        boolean ignored = action instanceof StripeAction.Ignored;

        if (!events.insertIfAbsent(eventId, eventType, ignored ? "IGNORED" : "PROCESSED")) {
            return WebhookResult.DUPLICATE;
        }

        switch (action) {
            case StripeAction.PaymentSucceeded payment -> recordPayment(payment);
            case StripeAction.ChargeRefunded refund -> recordRefund(refund);
            case StripeAction.Ignored none -> { }
        }
        return ignored ? WebhookResult.IGNORED : WebhookResult.PROCESSED;
    }

    private void recordPayment(StripeAction.PaymentSucceeded payment) {
        orders.insertIfAbsent(payment.paymentIntentId(), payment.customerEmail(), payment.amountCents(), payment.currency())
                .ifPresent(orderId -> syncJobs.insert(orderId, JobType.CREATE_INVOICE));
    }

    private void recordRefund(StripeAction.ChargeRefunded refund) {
        Order order = orders.findByPaymentIntentIdForUpdate(refund.paymentIntentId())
                .orElseThrow(() -> new OrderNotYetKnownException(refund.paymentIntentId()));

        // Stripe sends the cumulative refunded total. Taking the max means an older event arriving late
        // can't roll the total backwards.
        long refunded = Math.min(order.amountCents(), Math.max(order.refundedCents(), refund.amountRefundedCents()));
        if (refunded == order.refundedCents()) {
            return;
        }
        orders.updateRefund(order.id(), refunded, OrderStatus.forRefund(order.amountCents(), refunded));
        syncJobs.insert(order.id(), JobType.RECORD_REFUND);
    }
}
