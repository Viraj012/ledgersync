package com.ledgersync.order;

import com.ledgersync.sync.SyncJob;
import com.ledgersync.sync.SyncJobRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OrderController {

    public record OrderDetails(Order order, List<SyncJob> syncJobs) {
    }

    private final OrderRepository orders;
    private final SyncJobRepository syncJobs;

    public OrderController(OrderRepository orders, SyncJobRepository syncJobs) {
        this.orders = orders;
        this.syncJobs = syncJobs;
    }

    @GetMapping("/orders/{id}")
    public OrderDetails get(@PathVariable long id) {
        Order order = orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order " + id + " not found"));
        return new OrderDetails(order, syncJobs.findByOrderId(id));
    }
}
