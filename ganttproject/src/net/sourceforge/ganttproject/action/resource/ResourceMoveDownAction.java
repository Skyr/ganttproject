/*
GanttProject is an opensource project management tool.
Copyright (C) 2011 GanttProject Team

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
package net.sourceforge.ganttproject.action.resource;

import java.awt.event.ActionEvent;

import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;

import net.sourceforge.ganttproject.ResourceTreeTable;
import net.sourceforge.ganttproject.action.GPAction;

public class ResourceMoveDownAction extends GPAction implements TreeSelectionListener {
    private final ResourceTreeTable myTable;

    public ResourceMoveDownAction(ResourceTreeTable table) {
        super("resource.move.down");
        myTable = table;
        setEnabled(false);
        table.getTree().getSelectionModel().addTreeSelectionListener(this);
    }

    public void valueChanged(TreeSelectionEvent e) {
        setEnabled(myTable.canMoveSelectionDown());
    }

    @Override
    protected String getIconFilePrefix() {
        return "down_";
    }

    @Override
    public void actionPerformed(ActionEvent e) {
         myTable.downResource();
    }
}