package io.github.akane599.umbra.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/** PanelServer.Gesture.parse(): what the app's touch lock sends for replay (TouchRelay.Gesture.encode). */
public class InjectGestureTest {

    private static final int TOUCHSCREEN = 4098;

    @Test
    public void parsesATap() {
        PanelServer.Gesture g = PanelServer.Gesture.parse(
                TOUCHSCREEN + " 0,0,0,0:1:100.5:200.0:1.0:1.0 1,40,0,0:1:100.5:200.0:0.0:1.0");
        assertEquals(TOUCHSCREEN, g.source);
        assertArrayEquals(new int[] {0, 1}, g.actions);
        assertArrayEquals(new long[] {0, 40}, g.times);
        assertArrayEquals(new int[] {0}, g.ids[1]);
        assertArrayEquals(new int[] {1}, g.tools[1]);
        assertArrayEquals(new float[] {100.5f, 200f, 0f, 1f}, g.coords[1], 0f);
    }

    private static final int SECOND_DOWN = 5 | (1 << 8);
    private static final int SECOND_UP = 6 | (1 << 8);
    private static final int FIRST_UP = 6;

    @Test
    public void parsesEveryPointerOfASample() {
        PanelServer.Gesture g = PanelServer.Gesture.parse(TOUCHSCREEN
                + " 0,0,0,0:1:1.0:2.0:1.0:1.0"
                + " " + SECOND_DOWN + ",5,0,0:1:1.0:2.0:1.0:1.0,3:2:3.0:4.0:0.5:0.25"
                + " " + SECOND_UP + ",7,0,0:1:1.0:2.0:1.0:1.0,3:2:3.0:4.0:0.0:0.25"
                + " 1,9,0,0:1:1.0:2.0:0.0:1.0");
        assertEquals(SECOND_DOWN, g.actions[1]);
        assertArrayEquals(new int[] {0, 3}, g.ids[1]);
        assertArrayEquals(new int[] {1, 2}, g.tools[1]);
        assertArrayEquals(new float[] {1f, 2f, 1f, 1f, 3f, 4f, 0.5f, 0.25f}, g.coords[1], 0f);
    }

    @Test
    public void aCancelKeepsOnlyThePointersStillDown() {
        // The first finger lifts before the second: the cancel after that must not list it.
        PanelServer.Gesture g = PanelServer.Gesture.parse(TOUCHSCREEN
                + " 0,0,0,0:1:1.0:1.0:1.0:1.0"
                + " " + SECOND_DOWN + ",5,0,0:1:1.0:1.0:1.0:1.0,3:1:2.0:2.0:1.0:1.0"
                + " 2,6,0,0:1:1.0:1.5:1.0:1.0,3:1:2.0:2.5:1.0:1.0"
                + " " + FIRST_UP + ",7,0,0:1:1.0:1.5:0.0:1.0,3:1:2.0:2.5:1.0:1.0"
                + " 1,9,0,3:1:2.0:2.5:0.0:1.0");
        assertArrayEquals(new int[] {0}, g.downAfter(0));
        assertArrayEquals(new int[] {0, 1}, g.downAfter(1));
        assertArrayEquals(new int[] {0, 1}, g.downAfter(2));
        assertArrayEquals("the lifted first finger is gone", new int[] {1}, g.downAfter(3));
        assertArrayEquals(new int[] {}, g.downAfter(4));
    }

    @Test
    public void rejectsFingersThatDontAddUp() {
        String p0 = "0:1:1.0:1.0:1.0:1.0";
        String p1 = "1:1:2.0:2.0:1.0:1.0";
        String down = " 0,0,0," + p0;
        String up = " 1,9,0," + p0;
        rejects(TOUCHSCREEN + down + " " + SECOND_DOWN + ",5,0," + p0 + "," + p1 + up,
                "an UP while a second finger is still down");
        rejects(TOUCHSCREEN + down + " " + SECOND_DOWN + ",5,0," + p0 + "," + p0 + up, "the same pointer twice");
        rejects(TOUCHSCREEN + down + " " + (5 | (1 << 8)) + ",5,0," + p0 + up, "a pointer index out of range");
        rejects(TOUCHSCREEN + down + " 5,5,0," + p0 + "," + p1 + " " + SECOND_UP + ",7,0," + p0 + "," + p1 + up,
                "a finger going down that is already down");
        rejects(TOUCHSCREEN + down + " 2,5,0," + p1 + up, "a move of a finger that isn't down");
        rejects(TOUCHSCREEN + down + " 2,5,0," + p0 + "," + p1 + up, "a move with an extra finger");
        rejects(TOUCHSCREEN + down + " " + SECOND_UP + ",5,0," + p0 + "," + p1 + up, "lifting a finger that isn't down");
        rejects(TOUCHSCREEN + down + " 3,5,0," + p0 + up, "a cancel in the middle");
        rejects(TOUCHSCREEN + down + " 7,5,0," + p0 + up, "a hover in the middle");
        rejects(TOUCHSCREEN + down + " 1,5,0," + p0 + " 0,6,0," + p0 + up, "two touches in one");
        rejects(TOUCHSCREEN + down + " " + (2 | (1 << 8)) + ",5,0," + p0 + up, "a move with a pointer index");
        rejects(TOUCHSCREEN + " 0,0,0,32:1:1.0:1.0:1.0:1.0 1,9,0,32:1:1.0:1.0:0.0:1.0", "a pointer id over 31");
        rejects(TOUCHSCREEN + " 0,0,0," + p0 + "," + p1 + " 1,9,0," + p0 + "," + p1, "a DOWN with two fingers");
    }

    @Test
    public void keepsTheMouseButtons() {
        PanelServer.Gesture g = PanelServer.Gesture.parse(
                "8194 0,0,2,0:3:5.0:5.0:1.0:1.0 1,10,0,0:3:5.0:5.0:0.0:1.0");
        assertArrayEquals(new int[] {2, 0}, g.buttons);
    }

    @Test
    public void rejectsWhatCouldLeaveATouchHangingOrIsNotATouch() {
        String tap = " 0,0,0,0:1:1.0:1.0:1.0:1.0 1,0,0,0:1:1.0:1.0:0.0:1.0";
        rejects("", "nothing");
        rejects(String.valueOf(TOUCHSCREEN), "no samples");
        rejects(TOUCHSCREEN + " 0,0,0,0:1:1.0:1.0:1.0:1.0", "a lone DOWN would leave the pointer down");
        rejects(TOUCHSCREEN + " 2,0,0,0:1:1.0:1.0:1.0:1.0 1,0,0,0:1:1.0:1.0:0.0:1.0", "not starting with DOWN");
        rejects(TOUCHSCREEN + " 0,0,0,0:1:1.0:1.0:1.0:1.0 2,5,0,0:1:1.0:1.0:1.0:1.0", "not ending with UP");
        rejects(TOUCHSCREEN + " 0,10,0,0:1:1.0:1.0:1.0:1.0 1,5,0,0:1:1.0:1.0:0.0:1.0", "time going back");
        rejects(TOUCHSCREEN + " 0,0,0,0:1:1.0:1.0:1.0:1.0 1,600000,0,0:1:1.0:1.0:0.0:1.0", "far too long");
        rejects("257" + tap, "a keyboard source");
        rejects(TOUCHSCREEN + " 0,0,0 1,0,0,0:1:1.0:1.0:0.0:1.0", "a sample without pointers");
        rejects(TOUCHSCREEN + " 0,0,0,0:1:1.0:1.0:1.0 1,0,0,0:1:1.0:1.0:0.0:1.0", "a pointer field missing");
        rejects(TOUCHSCREEN + " 0,0,0,0:1:NaN:1.0:1.0:1.0 1,0,0,0:1:1.0:1.0:0.0:1.0", "not a coordinate");
        rejects(TOUCHSCREEN + " 0,x,0,0:1:1.0:1.0:1.0:1.0 1,0,0,0:1:1.0:1.0:0.0:1.0", "not a number");
        StringBuilder many = new StringBuilder(TOUCHSCREEN + " 0,0,0");
        for (int i = 0; i < 17; i++) many.append(',').append(i).append(":1:1.0:1.0:1.0:1.0");
        rejects(many + " 1,0,0,0:1:1.0:1.0:0.0:1.0", "more pointers than a MotionEvent takes");
    }

    private static void rejects(String arg, String why) {
        try {
            PanelServer.Gesture.parse(arg);
        } catch (IllegalArgumentException expected) {
            return;
        }
        fail("accepted " + why + ": " + arg);
    }
}
