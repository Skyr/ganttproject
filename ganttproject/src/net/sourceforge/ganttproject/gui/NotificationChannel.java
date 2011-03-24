/*
GanttProject is an opensource project management tool. License: GPL2
Copyright (C) 2011 Dmitry Barashev

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/
package net.sourceforge.ganttproject.gui;

import java.awt.Color;

import javax.swing.JComponent;

public enum NotificationChannel {
    RSS(Color.YELLOW.brighter()), ERROR(Color.RED.brighter().brighter());

    private final Color myColor;
    private JComponent myComponent;
    private boolean isVisible;

    NotificationChannel(Color color) {
        myColor = color;
    }

    Color getColor() {
        return myColor;
    }

    void setComponent(JComponent component) {
        myComponent = component;
    }

    JComponent getComponent() {
        return myComponent;
    }

    boolean isVisible() {
        return isVisible;
    }

    void setVisible(boolean b) {
        isVisible = b;
    }

}