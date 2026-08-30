package io.legacypilot.fixtures.orders;

public final class OrderController {
  private final OrderService service;

  public OrderController(OrderService service) {
    this.service = service;
  }

  public ApiResponse get(String id) {
    return ApiResponse.ok(service.get(id));
  }
}
