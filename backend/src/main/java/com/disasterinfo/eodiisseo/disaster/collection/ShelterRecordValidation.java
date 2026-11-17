package com.disasterinfo.eodiisseo.disaster.collection;

import java.math.*;
import java.util.Map;

/** Eligibility for the explicit, admitted shelter projection; not proof of designation, availability or CRS. */
public final class ShelterRecordValidation {
    private ShelterRecordValidation() {}
    public static String identity(Map<String,Object> row){return identifier(row.get("ARCD"))+":"+identifier(row.get("ACMDFCLTY_SN"));}
    private static String identifier(Object value){
        if(!(value instanceof String||value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof BigInteger))
            throw new IllegalArgumentException("Stable shelter identifier required");
        String text=value.toString();
        if(text.isBlank()||text.length()>10||text.indexOf(':')>=0||text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Stable shelter identifier required");
        return text; // No coercion of objects/fractions, trimming or loss of leading zeros.
    }
    public static void validateForProjection(Map<String,Object> row){
        identity(row);
        // 1 is only the existing positive-write representation, NOT verified legal/live availability.
        // No status-changing observation can reach the legacy writer without a separate official contract.
        if(!"1".equals(java.util.Objects.toString(row.get("USE_SE_CD"),"")))
            throw new IllegalArgumentException("Shelter status contract required");
        Object name=row.get("VT_ACMDFCLTY_NM");
        if(!(name instanceof String text)||text.isBlank()||text.length()>200||text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Shelter name cannot be projected");
        coordinate(row.get("LA"),90);coordinate(row.get("LO"),180);
        // No address geocoding, guessed axis swap, local CRS conversion or fabricated point.
        optionalNonnegative(row.get("VT_ACMD_PSBL_NMPR"),true);
        optionalNonnegative(row.get("FCLTY_AR"),false);
        for(String key:java.util.List.of("RN_DTL_ADRES","EQK_ACMDFCLTY_ADRES")){
            Object value=row.get(key);if(value!=null&&(!(value instanceof String t)||t.length()>300||t.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Shelter address cannot be projected");
        }
    }
    public static void requireSameKnownCoordinates(Map<String,Object> row,Number oldLat,Number oldLon){
        if(oldLat!=null&&oldLon!=null&&(Double.compare(coordinate(row.get("LA"),90),oldLat.doubleValue())!=0
                ||Double.compare(coordinate(row.get("LO"),180),oldLon.doubleValue())!=0))
            throw new IllegalArgumentException("Shelter coordinate change requires official evidence");
    }
    private static double coordinate(Object value,int max){
        BigDecimal n=decimal(value);double d=n.doubleValue();
        if(!Double.isFinite(d)||d==0||d < -max||d > max)throw new IllegalArgumentException("Invalid shelter coordinate");
        return d;
    }
    private static BigDecimal decimal(Object value){
        if(!(value instanceof String||value instanceof Number))throw new IllegalArgumentException("Numeric shelter fact required");
        try{return new BigDecimal(value.toString());}catch(NumberFormatException invalid){throw new IllegalArgumentException("Numeric shelter fact required");}
    }
    private static void optionalNonnegative(Object value,boolean integer){
        if(value==null||value instanceof String s&&s.isBlank())return;
        BigDecimal n=decimal(value);if(n.signum()<0||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Invalid shelter quantity");
        if(integer)try{n.intValueExact();}catch(ArithmeticException invalid){throw new IllegalArgumentException("Invalid shelter capacity");}
    }
}
