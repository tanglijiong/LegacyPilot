package io.legacypilot.fixtures.orders;

public final class OrderController {
  private final OrderService service;

  public OrderController(OrderService service) {
    this.service = service;
  }

  public ApiResponse get(String id) {
    try {
      return ApiResponse.ok(service.get(id));
    } catch (OrderNotFoundException exception) {
      return ApiResponse.notFound("ORDER_NOT_FOUND");
    }
  }
}
