/*
GanttProject is an opensource project management tool.
Copyright (C) 2003-2011 GanttProject team

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
import java.io.File;

import net.sourceforge.ganttproject.GanttProject;
import net.sourceforge.ganttproject.gui.OpenFileDialog;
import net.sourceforge.ganttproject.io.GanttXMLOpen;
import net.sourceforge.ganttproject.parser.DependencyTagHandler;
import net.sourceforge.ganttproject.parser.ResourceTagHandler;
import net.sourceforge.ganttproject.parser.RoleTagHandler;
import net.sourceforge.ganttproject.resource.HumanResourceManager;
import net.sourceforge.ganttproject.roles.RoleManager;
import net.sourceforge.ganttproject.task.TaskManager;

/**
 * Import resources action, the user selects a GanttProject file to import the
 * resources from
 */
public class ResourceImportAction extends ResourceAction {
    private final TaskManager myTaskManager;

    private final GanttProject myProject;

    private final RoleManager myRoleManager;

    private OpenFileDialog myOpenDialog;

    public ResourceImportAction(HumanResourceManager resourceManager, TaskManager taskManager, RoleManager roleManager,
            GanttProject project) {
        super("resource.import", resourceManager);
        myTaskManager = taskManager;
        myRoleManager = roleManager;
        myProject = project;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        final File file = getResourcesFile();
        if (file != null) {
            myProject.getUndoManager().undoableEdit(getLocalizedDescription(), new Runnable() {
                public void run() {
                    GanttXMLOpen loader = new GanttXMLOpen(myTaskManager);
                    ResourceTagHandler tagHandler = new ResourceTagHandler(getManager(), myRoleManager, myProject
                            .getResourceCustomPropertyManager());
                    DependencyTagHandler dependencyHandler = new DependencyTagHandler(loader.getContext(),
                            myTaskManager, myProject.getUIFacade());
                    RoleTagHandler rolesHandler = new RoleTagHandler(RoleManager.Access.getInstance());
                    loader.addTagHandler(tagHandler);
                    loader.addTagHandler(dependencyHandler);
                    loader.addTagHandler(rolesHandler);
                    loader.load(file);
                }
            });
        }
    }

    private File getResourcesFile() {
        if (myOpenDialog == null) {
            myOpenDialog = new OpenFileDialog();
        }
        return myOpenDialog.show();
    }

    @Override
    protected String getIconFilePrefix() {
        return "impres_";
    }
}