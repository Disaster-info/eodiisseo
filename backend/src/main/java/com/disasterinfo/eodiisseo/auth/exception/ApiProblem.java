package com.disasterinfo.eodiisseo.auth.exception;

public class ApiProblem extends RuntimeException {
    private final int status;
    private final String code;
    public ApiProblem(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiProblem bad(String message) { return new ApiProblem(400, "INVALID_REQUEST", message); }
    public static ApiProblem unauthorized() { return new ApiProblem(401, "UNAUTHORIZED", "인증정보를 확인하세요."); }
    public static ApiProblem forbidden() { return new ApiProblem(403, "FORBIDDEN", "접근 권한이 없습니다."); }
    public static ApiProblem missing(String code) { return new ApiProblem(404, code, "요청한 정보가 없습니다."); }
    public static ApiProblem conflict(String code) { return new ApiProblem(409, code, "현재 상태에서는 처리할 수 없습니다."); }
}
