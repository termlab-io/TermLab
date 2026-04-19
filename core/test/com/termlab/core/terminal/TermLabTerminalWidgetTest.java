package com.termlab.core.terminal;

import com.jediterm.core.input.MouseEvent;
import com.jediterm.core.input.MouseWheelEvent;
import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.emulator.mouse.MouseButtonCodes;
import com.jediterm.terminal.emulator.mouse.TerminalMouseListener;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TermLabTerminalWidgetTest {

    @Test
    void secondaryClickIsDeliveredOnRelease() {
        RecordingTerminalMouseListener listener = new RecordingTerminalMouseListener();
        TermLabTerminalWidget.RemoteMouseGestureRouter router =
            new TermLabTerminalWidget.RemoteMouseGestureRouter(listener);

        router.mousePressed(new Point(3, 4), rightPressEvent());
        assertEquals(List.of(), listener.events);

        router.mouseReleased(new Point(3, 4), rightReleaseEvent());

        assertEquals(List.of("pressed:2@3,4", "released:2@3,4"), listener.events);
    }

    @Test
    void wheelCancelsPendingSecondaryClick() {
        RecordingTerminalMouseListener listener = new RecordingTerminalMouseListener();
        TermLabTerminalWidget.RemoteMouseGestureRouter router =
            new TermLabTerminalWidget.RemoteMouseGestureRouter(listener);

        router.mousePressed(new Point(8, 9), rightPressEvent());
        router.mouseWheelMoved(new Point(8, 9), wheelEvent(-1));
        router.mouseReleased(new Point(8, 9), rightReleaseEvent());

        assertEquals(List.of("wheel:4@8,9"), listener.events);
    }

    @Test
    void secondaryDragFlushesPendingPressBeforeDragging() {
        RecordingTerminalMouseListener listener = new RecordingTerminalMouseListener();
        TermLabTerminalWidget.RemoteMouseGestureRouter router =
            new TermLabTerminalWidget.RemoteMouseGestureRouter(listener);

        router.mousePressed(new Point(1, 2), rightPressEvent());
        router.mouseDragged(new Point(5, 6), rightDragEvent());
        router.mouseReleased(new Point(5, 6), rightReleaseEvent());

        assertEquals(List.of("pressed:2@1,2", "dragged:2@5,6", "released:2@5,6"), listener.events);
    }

    private static java.awt.event.MouseEvent rightPressEvent() {
        return new java.awt.event.MouseEvent(
            new JPanel(),
            java.awt.event.MouseEvent.MOUSE_PRESSED,
            1L,
            InputEvent.BUTTON3_DOWN_MASK,
            10,
            10,
            1,
            true,
            java.awt.event.MouseEvent.BUTTON3
        );
    }

    private static java.awt.event.MouseEvent rightReleaseEvent() {
        return new java.awt.event.MouseEvent(
            new JPanel(),
            java.awt.event.MouseEvent.MOUSE_RELEASED,
            2L,
            0,
            10,
            10,
            1,
            true,
            java.awt.event.MouseEvent.BUTTON3
        );
    }

    private static java.awt.event.MouseEvent rightDragEvent() {
        return new java.awt.event.MouseEvent(
            new JPanel(),
            java.awt.event.MouseEvent.MOUSE_DRAGGED,
            2L,
            InputEvent.BUTTON3_DOWN_MASK,
            20,
            20,
            0,
            false,
            java.awt.event.MouseEvent.NOBUTTON
        );
    }

    private static java.awt.event.MouseWheelEvent wheelEvent(int wheelRotation) {
        return new java.awt.event.MouseWheelEvent(
            new JPanel(),
            java.awt.event.MouseEvent.MOUSE_WHEEL,
            3L,
            0,
            10,
            10,
            0,
            false,
            java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            wheelRotation
        );
    }

    private static final class RecordingTerminalMouseListener implements TerminalMouseListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void mousePressed(int x, int y, MouseEvent event) {
            events.add("pressed:" + event.getButtonCode() + "@" + x + "," + y);
        }

        @Override
        public void mouseReleased(int x, int y, MouseEvent event) {
            events.add("released:" + event.getButtonCode() + "@" + x + "," + y);
        }

        @Override
        public void mouseMoved(int x, int y, MouseEvent event) {
        }

        @Override
        public void mouseDragged(int x, int y, MouseEvent event) {
            events.add("dragged:" + event.getButtonCode() + "@" + x + "," + y);
        }

        @Override
        public void mouseWheelMoved(int x, int y, MouseWheelEvent event) {
            events.add("wheel:" + event.getButtonCode() + "@" + x + "," + y);
        }
    }
}
