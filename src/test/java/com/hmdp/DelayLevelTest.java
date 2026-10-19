package com.hmdp;
import com.hmdp.order.DelayedOrderChecks;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DelayLevelTest {
    @Test void checksAreScheduledNoLaterThanRemainingTime(){
        assertEquals(14,DelayedOrderChecks.levelFor(900)); // first check in 10min, then remaining 5min
        assertEquals(9,DelayedOrderChecks.levelFor(300));
        assertEquals(5,DelayedOrderChecks.levelFor(60));
        assertEquals(1,DelayedOrderChecks.levelFor(1));
    }
}
