package io.github.luoyan.adventureworldgen.cost;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdventurePreferenceTest {
    @Test void preferenceIsSmoothFiniteAndStrongerAgainstEarlyLateGameContent() {
        assertEquals(0,AdventurePreference.penalty(5,5));
        double last=0;
        for(int i=1;i<=100;i++) {
            double error=i/10.0,value=AdventurePreference.penalty(5,5+error);
            assertTrue(Double.isFinite(value)&&value>last);last=value;
            assertTrue(AdventurePreference.penalty(5,5-error)>=value);
        }
        assertTrue(Math.abs(AdventurePreference.penalty(5,5.35001)-AdventurePreference.penalty(5,5.34999))<0.00001);
    }
}
