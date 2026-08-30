package io.legacypilot.fixtures.orders;

public record ApiResponse(int status, Object body, String error) {
  public static ApiResponse ok(Object body) {
    return new ApiResponse(200, body, "");
  }

  public static ApiResponse notFound(String error) {
    return new ApiResponse(404, null, error);
  }
}
