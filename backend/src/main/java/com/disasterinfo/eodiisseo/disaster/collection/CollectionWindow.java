package com.disasterinfo.eodiisseo.disaster.collection;
import java.time.LocalDate;
/** Three-day provider window with one-day overlap, never advances across a missing day. */
public record CollectionWindow(LocalDate from,LocalDate to) {
    public static CollectionWindow next(LocalDate initial,LocalDate completed,LocalDate today){
        if(initial==null||today==null||initial.isAfter(today)||(completed!=null&&completed.isAfter(today)))throw new CollectionFailure("WINDOW_LIMIT");
        LocalDate start=completed==null?initial:completed.minusDays(1).isBefore(initial)?initial:completed.minusDays(1);
        LocalDate end=start.plusDays(2).isAfter(today)?today:start.plusDays(2);
        return new CollectionWindow(start,end);
    }
}
