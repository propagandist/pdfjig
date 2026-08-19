package io.github.propagandist.pdfjig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RotationTest {

    @Test
    @DisplayName("負の角度と 360 度超は正規化する")
    void normalizesDegrees() {
        assertEquals(Rotation.COUNTERCLOCKWISE_90, Rotation.ofDegrees(-90));
        assertEquals(Rotation.CLOCKWISE_90, Rotation.ofDegrees(450));
        assertEquals(Rotation.NONE, Rotation.ofDegrees(720));
    }

    @Test
    @DisplayName("回転の合成は 360 度で巻き戻る")
    void addsRotations() {
        assertEquals(Rotation.HALF_TURN, Rotation.CLOCKWISE_90.plus(Rotation.CLOCKWISE_90));
        assertEquals(Rotation.NONE, Rotation.CLOCKWISE_90.plus(Rotation.COUNTERCLOCKWISE_90));
    }

    @Test
    @DisplayName("90 度単位でない角度は拒否する")
    void rejectsNonRightAngles() {
        assertThrows(IllegalArgumentException.class, () -> Rotation.ofDegrees(45));
    }
}
