package com.disasterinfo.eodiisseo.area;

public final class AdministrativeAreaCode {
    private AdministrativeAreaCode() {}

    /** Only legal SIGUNGU format and the explicitly equivalent Sejong scope. */
    public static String catalogKey(String code) {
        if (code == null) return null;
        String key = code.matches("[0-9]{5}00000") ? code.substring(0, 5) : code;
        return "36".equals(key) || "36000".equals(key) ? "36110" : key;
    }
}
