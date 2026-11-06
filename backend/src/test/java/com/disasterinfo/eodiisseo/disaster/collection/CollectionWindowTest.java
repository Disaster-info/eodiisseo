package com.disasterinfo.eodiisseo.disaster.collection;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
class CollectionWindowTest {
    @Test void overlapDoesNotSkipAndNeverPrecedesConfiguredStart(){
        var initial=LocalDate.of(2026,10,20);var today=LocalDate.of(2026,11,3);
        var first=CollectionWindow.next(initial,null,today);
        assertThat(first).isEqualTo(new CollectionWindow(initial,initial.plusDays(2)));
        var next=CollectionWindow.next(initial,first.to(),today);
        assertThat(next.from()).isEqualTo(initial.plusDays(1));
        assertThat(next.to()).isEqualTo(initial.plusDays(3));
        assertThat(CollectionWindow.next(initial,initial,today).from()).isEqualTo(initial);
        assertThat(CollectionWindow.next(initial,today,today).to()).isEqualTo(today);
    }
    @Test void futureOrMissingContractIsRejected(){
        LocalDate day=LocalDate.of(2026,11,3);
        assertThatThrownBy(()->CollectionWindow.next(day.plusDays(1),null,day)).isInstanceOf(CollectionFailure.class);
        assertThatThrownBy(()->CollectionWindow.next(day,day.plusDays(1),day)).isInstanceOf(CollectionFailure.class);
        assertThatThrownBy(()->CollectionWindow.next(null,null,day)).isInstanceOf(CollectionFailure.class);
    }
}
