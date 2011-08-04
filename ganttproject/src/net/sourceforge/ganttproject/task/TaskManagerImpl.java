/*
 * Created on 05.07.2003
 *
 */
package net.sourceforge.ganttproject.task;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.sourceforge.ganttproject.CustomPropertyListener;
import net.sourceforge.ganttproject.CustomPropertyManager;
import net.sourceforge.ganttproject.GPLogger;
import net.sourceforge.ganttproject.GanttCalendar;
import net.sourceforge.ganttproject.GanttTask;
import net.sourceforge.ganttproject.calendar.AlwaysWorkingTimeCalendarImpl;
import net.sourceforge.ganttproject.calendar.CalendarFactory;
import net.sourceforge.ganttproject.calendar.GPCalendar;
import net.sourceforge.ganttproject.gui.options.model.DefaultStringOption;
import net.sourceforge.ganttproject.gui.options.model.GP1XOptionConverter;
import net.sourceforge.ganttproject.gui.options.model.StringOption;
import net.sourceforge.ganttproject.language.GanttLanguage;
import net.sourceforge.ganttproject.resource.HumanResource;
import net.sourceforge.ganttproject.resource.HumanResourceManager;
import net.sourceforge.ganttproject.task.algorithm.AdjustTaskBoundsAlgorithm;
import net.sourceforge.ganttproject.task.algorithm.AlgorithmCollection;
import net.sourceforge.ganttproject.task.algorithm.CriticalPathAlgorithm;
import net.sourceforge.ganttproject.task.algorithm.CriticalPathAlgorithmImpl;
import net.sourceforge.ganttproject.task.algorithm.FindPossibleDependeesAlgorithm;
import net.sourceforge.ganttproject.task.algorithm.FindPossibleDependeesAlgorithmImpl;
import net.sourceforge.ganttproject.task.algorithm.ProjectBoundsAlgorithm;
import net.sourceforge.ganttproject.task.algorithm.RecalculateTaskCompletionPercentageAlgorithm;
import net.sourceforge.ganttproject.task.algorithm.RecalculateTaskScheduleAlgorithm;
import net.sourceforge.ganttproject.task.algorithm.ProjectBoundsAlgorithm.Result;
import net.sourceforge.ganttproject.task.dependency.EventDispatcher;
import net.sourceforge.ganttproject.task.dependency.TaskDependency;
import net.sourceforge.ganttproject.task.dependency.TaskDependencyCollection;
import net.sourceforge.ganttproject.task.dependency.TaskDependencyCollectionImpl;
import net.sourceforge.ganttproject.task.dependency.TaskDependencyConstraint;
import net.sourceforge.ganttproject.task.dependency.TaskDependencyException;
import net.sourceforge.ganttproject.task.dependency.constraint.FinishFinishConstraintImpl;
import net.sourceforge.ganttproject.task.dependency.constraint.FinishStartConstraintImpl;
import net.sourceforge.ganttproject.task.dependency.constraint.StartFinishConstraintImpl;
import net.sourceforge.ganttproject.task.dependency.constraint.StartStartConstraintImpl;
import net.sourceforge.ganttproject.task.event.TaskDependencyEvent;
import net.sourceforge.ganttproject.task.event.TaskHierarchyEvent;
import net.sourceforge.ganttproject.task.event.TaskListener;
import net.sourceforge.ganttproject.task.event.TaskPropertyEvent;
import net.sourceforge.ganttproject.task.event.TaskScheduleEvent;
import net.sourceforge.ganttproject.task.hierarchy.TaskHierarchyManagerImpl;
import net.sourceforge.ganttproject.time.TimeUnit;

/**
 * @author bard
 */
public class TaskManagerImpl implements TaskManager {
    private static final GPCalendar RESTLESS_CALENDAR = new AlwaysWorkingTimeCalendarImpl();

    private final TaskHierarchyManagerImpl myHierarchyManager;

    private final TaskDependencyCollectionImpl myDependencyCollection;

    private final AlgorithmCollection myAlgorithmCollection;

    private final List<TaskListener> myListeners = new ArrayList<TaskListener>();

    private int myMaxID = -1;

    private Task myRoot;

    private final TaskManagerConfig myConfig;

    private final TaskNamePrefixOption myTaskNamePrefixOption = new TaskNamePrefixOption();

    private final TaskContainmentHierarchyFacade.Factory myFacadeFactory;

    private boolean areEventsEnabled = true;

    private static class TaskMap {
        private final Map<Integer, Task> myId2task = new HashMap<Integer, Task>();
        private TaskDocumentOrderComparator myComparator;
        private boolean isModified = true;
        private Task[] myArray;
        private final TaskManagerImpl myManager;

        TaskMap(TaskManagerImpl taskManager) {
            myComparator = new TaskDocumentOrderComparator(taskManager);
            myManager = taskManager;
        }

        void addTask(Task task) {
            myId2task.put(new Integer(task.getTaskID()), task);
            isModified = true;
        }

        Task getTask(int id) {
            return myId2task.get(new Integer(id));
        }

        public Task[] getTasks() {
            if (isModified) {
                myArray = myId2task.values().toArray(new Task[myId2task.size()]);
                Arrays.sort(myArray, myComparator);
                isModified = false;
            }
            return myArray;
        }

        public void clear() {
            myId2task.clear();
            isModified = true;
        }

        public void removeTask(Task task) {
            myId2task.remove(new Integer(task.getTaskID()));
            Task[] nestedTasks = myManager.getTaskHierarchy().getNestedTasks(task);
            for (int i=0; i<nestedTasks.length; i++) {
                removeTask(nestedTasks[i]);
            }
            isModified = true;
        }

        public int size() {
            return myId2task.size();
        }

        public boolean isEmpty() {
            return myId2task.isEmpty();
        }

        void setDirty() {
            isModified = true;
        }
    }
    private final TaskMap myTaskMap = new TaskMap(this);

    private final CustomColumnsStorage myCustomColumnStorage;

    private final CustomPropertyListenerImpl myCustomPropertyListener;

    TaskManagerImpl(
            TaskContainmentHierarchyFacade.Factory containmentFacadeFactory,
            TaskManagerConfig config, CustomColumnsStorage columnStorage) {
        myCustomPropertyListener = new CustomPropertyListenerImpl(this);
        myCustomColumnStorage = columnStorage==null ? new CustomColumnsStorage() : columnStorage;
        myCustomColumnStorage.addCustomColumnsListener(getCustomPropertyListener());
        myConfig = config;
        myHierarchyManager = new TaskHierarchyManagerImpl();
        EventDispatcher dispatcher = new EventDispatcher() {
            public void fireDependencyAdded(TaskDependency dep) {
                TaskManagerImpl.this.fireDependencyAdded(dep);
            }

            public void fireDependencyRemoved(TaskDependency dep) {
                TaskManagerImpl.this.fireDependencyRemoved(dep);
            }
        };
        myDependencyCollection = new TaskDependencyCollectionImpl(containmentFacadeFactory, dispatcher) {
            private TaskContainmentHierarchyFacade myTaskHierarchy;

            protected TaskContainmentHierarchyFacade getTaskHierarchy() {
                if (myTaskHierarchy == null) {
                    myTaskHierarchy = TaskManagerImpl.this.getTaskHierarchy();
                }
                return myTaskHierarchy;
            }
        };
        myFacadeFactory = containmentFacadeFactory == null ? new FacadeFactoryImpl()
                : containmentFacadeFactory;
        // clear();
        {
            Calendar c = CalendarFactory.newCalendar();
            Date today = c.getTime();
            myRoot = new GanttTask(null, new GanttCalendar(today), 1, this, -1);
            myRoot.setStart(new GanttCalendar(today));
            myRoot.setDuration(createLength(getConfig().getTimeUnitStack()
                    .getDefaultTimeUnit(), 1));
            myRoot.setExpand(true);

        }

        FindPossibleDependeesAlgorithm alg1 = new FindPossibleDependeesAlgorithmImpl() {
            protected TaskContainmentHierarchyFacade createContainmentFacade() {
                return TaskManagerImpl.this.getTaskHierarchy();
            }

        };
        AdjustTaskBoundsAlgorithm alg3 = new AdjustTaskBoundsAlgorithm() {
            protected TaskContainmentHierarchyFacade createContainmentFacade() {
                return TaskManagerImpl.this.getTaskHierarchy();
            }
        };
        RecalculateTaskScheduleAlgorithm alg2 = new RecalculateTaskScheduleAlgorithm(
                alg3) {
            protected TaskContainmentHierarchyFacade createContainmentFacade() {
                return TaskManagerImpl.this.getTaskHierarchy();
            }
        };
        RecalculateTaskCompletionPercentageAlgorithm alg4 = new RecalculateTaskCompletionPercentageAlgorithm() {
            protected TaskContainmentHierarchyFacade createContainmentFacade() {
                return TaskManagerImpl.this.getTaskHierarchy();
            }
        };
        ProjectBoundsAlgorithm alg5 = new ProjectBoundsAlgorithm();
        CriticalPathAlgorithm alg6 = new CriticalPathAlgorithmImpl(this, getCalendar());
        myAlgorithmCollection = new AlgorithmCollection(this, alg1, alg2, alg3, alg4, alg5, alg6);
    }

    private CustomPropertyListener getCustomPropertyListener() {
        return myCustomPropertyListener;
    }

    public GanttTask getTask(int taskId) {
        return (GanttTask) myTaskMap.getTask(taskId);
    }

    public Task getRootTask() {
        return myRoot;
    }

    public Task[] getTasks() {
        return myTaskMap.getTasks();
        //return (Task[]) myId2task.values().toArray(new Task[myId2task.size()]);
    }

    public void projectClosed() {
        myTaskMap.clear();
        setMaxID(-1);
        myDependencyCollection.clear();
        {
            Calendar c = CalendarFactory.newCalendar();
            Date today = c.getTime();
            myRoot = new GanttTask(null, new GanttCalendar(today), 1, this, -1);
            myRoot.setStart(new GanttCalendar(today));
            myRoot.setDuration(createLength(getConfig().getTimeUnitStack()
                    .getDefaultTimeUnit(), 1));
            myRoot.setExpand(true);
        }
        fireTaskModelReset();
    }


    public void projectOpened() {
        processCriticalPath(getRootTask());
        myAlgorithmCollection.getRecalculateTaskCompletionPercentageAlgorithm().run(getRootTask());
    }

    public void deleteTask(Task tasktoRemove) {
        myTaskMap.removeTask(tasktoRemove);
    }

    public GanttTask createTask() {
        GanttTask result = createTask(-1);
        return result;
    }

    public GanttTask createTask(int taskID) {
        GanttTask result = new GanttTask("", new GanttCalendar(), 1, this,
                taskID);
        if (result.getTaskID() >= getMaxID()) {
            setMaxID(result.getTaskID() + 1);
        }
        // result.setTaskID(taskID);
        // getTaskHierarchy().move(result, getRootTask());
        // result.move(getRootTask());
        fireTaskAdded(result);
        return result;
    }

    public void registerTask(Task task) {
        int taskID = task.getTaskID();
        if (myTaskMap.getTask(taskID) == null) { // if the taskID is
            // not in the map
            myTaskMap.addTask(task);
            if (getMaxID() < taskID) {
                setMaxID(taskID + 1);
            }
        } else { // taskID has been in the map. the newTask will not be added
            throw new RuntimeException(
                    "There is a task that already has the ID " + taskID);
        }
    }

    boolean isRegistered(TaskImpl task) {
        return myTaskMap.getTask(task.getTaskID())!=null;
    }

    public int getTaskCount() {
        return myTaskMap.size();
    }

    public TaskLength getProjectLength() {
        if (myTaskMap.isEmpty()) {
            return createLength(getConfig().getTimeUnitStack()
                    .getDefaultTimeUnit(), 0);
        }
        Result result = getAlgorithmCollection().getProjectBoundsAlgorithm()
                .getBounds(Arrays.asList(myTaskMap.getTasks()));
        return createLength(
                getConfig().getTimeUnitStack().getDefaultTimeUnit(),
                result.lowerBound, result.upperBound);
    }

    public Date getProjectStart() {
        if (myTaskMap.isEmpty()) {
            return myRoot.getStart().getTime();
        }
        Result result = getAlgorithmCollection().getProjectBoundsAlgorithm()
                .getBounds(Arrays.asList(myTaskMap.getTasks()));
        return result.lowerBound;
    }

    public Date getProjectEnd(){
        if (myTaskMap.isEmpty()) {
            return myRoot.getStart().getTime();
        }
        Result result = getAlgorithmCollection().getProjectBoundsAlgorithm()
                .getBounds(Arrays.asList(myTaskMap.getTasks()));
        return result.upperBound;
    }

    public int getProjectCompletion() {
        return myRoot.getCompletionPercentage();
    }

    public String encode(TaskLength taskLength) {
        StringBuffer result = new StringBuffer(String.valueOf(taskLength.getLength()));
        result.append(myConfig.getTimeUnitStack().encode(taskLength.getTimeUnit()));
        return result.toString();
    }

    public TaskLength createLength(String lengthAsString) throws DurationParsingException {
        int state = 0;
        StringBuffer valueBuffer = new StringBuffer();
        Integer currentValue = null;
        TaskLength currentLength = null;
        lengthAsString += " ";
        for (int i=0; i<lengthAsString.length(); i++) {
            char nextChar = lengthAsString.charAt(i);
            if (Character.isDigit(nextChar)) {
                switch (state) {
                    case 0:
                        if (currentValue!=null) {
                            throw new DurationParsingException();
                        }
                        state = 1;
                        valueBuffer.setLength(0);
                    case 1:
                        valueBuffer.append(nextChar);
                        break;
                    case 2:
                        TimeUnit timeUnit = findTimeUnit(valueBuffer.toString());
                        if (timeUnit==null) {
                            throw new DurationParsingException(valueBuffer.toString());
                        }
                        assert currentValue!=null;
                        TaskLength localResult = createLength(timeUnit, currentValue.floatValue());
                        if (currentLength==null) {
                            currentLength = localResult;
                        }
                        else {
                            if (currentLength.getTimeUnit().isConstructedFrom(timeUnit)) {
                                float recalculatedLength = currentLength.getLength(timeUnit);
                                currentLength = createLength(timeUnit, localResult.getValue()+recalculatedLength);
                            }
                            else {
                                throw new DurationParsingException();
                            }
                        }
                        state = 1;
                        currentValue = null;
                        valueBuffer.setLength(0);
                        valueBuffer.append(nextChar);
                        break;
                }
            }
            else if(Character.isWhitespace(nextChar)) {
                switch (state) {
                    case 0:
                        break;
                    case 1:
                        currentValue = Integer.valueOf(valueBuffer.toString());
                        state = 0;
                        break;
                    case 2:
                        TimeUnit timeUnit = findTimeUnit(valueBuffer.toString());
                        if (timeUnit==null) {
                            throw new DurationParsingException(valueBuffer.toString());
                        }
                        assert currentValue!=null;
                        TaskLength localResult = createLength(timeUnit, currentValue.floatValue());
                        if (currentLength==null) {
                            currentLength = localResult;
                        }
                        else {
                            if (currentLength.getTimeUnit().isConstructedFrom(timeUnit)) {
                                float recalculatedLength = currentLength.getLength(timeUnit);
                                currentLength = createLength(timeUnit, localResult.getValue()+recalculatedLength);
                            }
                            else {
                                throw new DurationParsingException();
                            }
                        }
                        state = 0;
                        currentValue = null;
                        break;
                }
            }
            else {
                switch (state) {
                    case 1:
                        currentValue = Integer.valueOf(valueBuffer.toString());
                    case 0:
                        if (currentValue==null) {
                            throw new DurationParsingException();
                        }
                        state = 2;
                        valueBuffer.setLength(0);
                    case 2:
                        valueBuffer.append(nextChar);
                        break;
                }
            }
        }
        if (currentValue!=null) {
            currentValue = Integer.valueOf(valueBuffer.toString());
            TimeUnit dayUnit = findTimeUnit("d");
            currentLength = createLength(dayUnit, currentValue.floatValue());
        }
        return currentLength;
    }

    private TimeUnit findTimeUnit(String code) {
        return myConfig.getTimeUnitStack().findTimeUnit(code);
    }

    public TaskLength createLength(TimeUnit unit, float length) {
        return new TaskLengthImpl(unit, length);
    }

    public TaskLength createLength(long count) {
        return new TaskLengthImpl(getConfig().getTimeUnitStack()
                .getDefaultTimeUnit(), count);
    }

    public TaskLength createLength(TimeUnit timeUnit, Date startDate, Date endDate) {
        return getConfig().getTimeUnitStack().createDuration(timeUnit, startDate, endDate);
    }

    public Date shift(Date original, TaskLength duration) {
        GPCalendar calendar = RESTLESS_CALENDAR;
        return calendar.shiftDate(original, duration);
    }

    public TaskDependencyCollection getDependencyCollection() {
        return myDependencyCollection;
    }

    public AlgorithmCollection getAlgorithmCollection() {
        return myAlgorithmCollection;
    }

    public TaskHierarchyManagerImpl getHierarchyManager() {
        return myHierarchyManager;
    }

    public TaskDependencyConstraint createConstraint(final int constraintID) {
        return createConstraint(TaskDependencyConstraint.Type.getType(constraintID));
    }

    public TaskDependencyConstraint createConstraint(final TaskDependencyConstraint.Type type) {
        TaskDependencyConstraint result;
        switch (type) {
        case finishstart:
            result = new FinishStartConstraintImpl();
            break;
        case finishfinish:
            result = new FinishFinishConstraintImpl();
            break;
        case startfinish:
            result = new StartFinishConstraintImpl();
            break;
        case startstart:
            result = new StartStartConstraintImpl();
            break;
        default:
            throw new IllegalArgumentException("Unknown constraint type=" + type);
        }
        return result;
    }

    public int getMaxID() {
        return myMaxID;
    }

    private void setMaxID(int id) {
        myMaxID = id;
    }

    void increaseMaxID() {
        myMaxID++;
    }

    public void addTaskListener(TaskListener listener) {
        myListeners.add(listener);
    }

    public GPCalendar getCalendar() {
        return getConfig().getCalendar();
    }

    public void fireTaskProgressChanged(Task changedTask) {
        if (areEventsEnabled) {
            TaskPropertyEvent e = new TaskPropertyEvent(changedTask);
            for (int i = 0; i < myListeners.size(); i++) {
                TaskListener next = myListeners.get(i);
                next.taskProgressChanged(e);
            }
        }
    }

    void fireTaskScheduleChanged(Task changedTask, GanttCalendar oldStartDate,
            GanttCalendar oldFinishDate) {
        if (areEventsEnabled) {
            TaskScheduleEvent e = new TaskScheduleEvent(changedTask, oldStartDate,
                    oldFinishDate, changedTask.getStart(), changedTask.getEnd());
            // List copy = new ArrayList(myListeners);
            // myListeners.clear();
            for (int i = 0; i < myListeners.size(); i++) {
                TaskListener next = myListeners.get(i);
                next.taskScheduleChanged(e);
            }
        }
    }

    private void fireDependencyAdded(TaskDependency newDependency) {
        if (areEventsEnabled) {
            TaskDependencyEvent e = new TaskDependencyEvent(
                    getDependencyCollection(), newDependency);
            for (int i = 0; i < myListeners.size(); i++) {
                TaskListener next = myListeners.get(i);
                next.dependencyAdded(e);
            }
        }
    }

    private void fireDependencyRemoved(TaskDependency dep) {
        TaskDependencyEvent e = new TaskDependencyEvent(
                getDependencyCollection(), dep);
        for (int i = 0; i < myListeners.size(); i++) {
            TaskListener next = myListeners.get(i);
            next.dependencyRemoved(e);
        }
    }

    private void fireTaskAdded(Task task) {
        if (areEventsEnabled) {
            TaskHierarchyEvent e = new TaskHierarchyEvent(this, task, null,
                    getTaskHierarchy().getContainer(task));
            for (int i = 0; i < myListeners.size(); i++) {
                TaskListener next = myListeners.get(i);
                next.taskAdded(e);
            }
        }
    }

    void fireTaskPropertiesChanged(Task task) {
        if (areEventsEnabled) {
            TaskPropertyEvent e = new TaskPropertyEvent(task);
            for (int i = 0; i < myListeners.size(); i++) {
                TaskListener next = myListeners.get(i);
                next.taskPropertiesChanged(e);
            }
        }
    }

    private void fireTaskModelReset() {
        if (areEventsEnabled) {
            for (int i = 0; i < myListeners.size(); i++) {
                TaskListener next = myListeners.get(i);
                next.taskModelReset();
            }
        }
    }

    public TaskManagerConfig getConfig() {
        return myConfig;
    }

    private final class FacadeImpl implements
            TaskContainmentHierarchyFacade {
        //private final Task myRoot;

        private List<Task> myPathBuffer = new ArrayList<Task>();

//        public FacadeImpl(Task root) {
//            myRoot = root;
//        }

        public Task[] getNestedTasks(Task container) {
            return container.getNestedTasks();
        }

        public Task[] getDeepNestedTasks(Task container) {
            ArrayList<Task> result = new ArrayList<Task>();
            addDeepNestedTasks(container, result);
            return result.toArray(new Task[result.size()]);
        }

        private void addDeepNestedTasks(Task container, ArrayList<Task> result) {
            Task[] nested = container.getNestedTasks();
            result.addAll(Arrays.asList(nested));
            for (int i = 0; i < nested.length; i++) {
                addDeepNestedTasks(nested[i], result);
            }
        }

        public boolean hasNestedTasks(Task container) {
            return container.getNestedTasks().length > 0;
        }

        public Task getRootTask() {
            return TaskManagerImpl.this.getRootTask();
        }

        public Task getContainer(Task nestedTask) {
            return nestedTask.getSupertask();
        }

        public boolean areUnrelated(Task first, Task second) {
            myPathBuffer.clear();
            for (Task container = getContainer(first); container != null; container = getContainer(container)) {
                myPathBuffer.add(container);
            }
            if (myPathBuffer.contains(second)) {
                return false;
            }
            myPathBuffer.clear();
            for (Task container = getContainer(second); container != null; container = getContainer(container)) {
                myPathBuffer.add(container);
            }
            if (myPathBuffer.contains(first)) {
                return false;
            }
            return true;
        }

        public void move(Task whatMove, Task whereMove) {
            whatMove.move(whereMove);
        }

        public int getDepth(Task task) {
            int depth = 0;
            while (task != myRoot) {
                task = task.getSupertask();
                depth++;
            }
            return depth;
        }

        public int compareDocumentOrder(Task task1, Task task2) {
            if (task1==task2) {
                return 0;
            }
            List<Task> buffer1 = new ArrayList<Task>();
            for (Task container = task1; container != null; container = getContainer(container)) {
                buffer1.add(0,container);
            }
            List<Task> buffer2 = new ArrayList<Task>();
            for (Task container = task2; container != null; container = getContainer(container)) {
                buffer2.add(0,container);
            }
            if (buffer1.get(0)!=getRootTask() && buffer2.get(0)==getRootTask()) {
                return -1;
            }
            if (buffer1.get(0)==getRootTask() && buffer2.get(0)!=getRootTask()) {
                return 1;
            }

            int i=0;
            Task commonRoot = null;
            while (true) {
                if (i==buffer1.size()) {
                    return -1;
                }
                if (i==buffer2.size()) {
                    return 1;
                }
                Task root1 = buffer1.get(i);
                Task root2 = buffer2.get(i);
                if (root1!=root2) {
                    assert commonRoot!=null : "Failure comparing task="+task1+" and task="+task2+"\n. Path1="+buffer1+"\nPath2="+buffer2;
                    Task[] nestedTasks = commonRoot.getNestedTasks();
                    for (int j=0; j<nestedTasks.length; j++) {
                        if (nestedTasks[j]==root1) {
                            return -1;
                        }
                        if (nestedTasks[j]==root2) {
                            return 1;
                        }
                    }
                    throw new IllegalStateException("We should not be here");
                }
                i++;
                commonRoot = root1;
            }
        }

        public boolean contains(Task task) {
            throw new UnsupportedOperationException();
        }

        public List<Task> getTasksInDocumentOrder() {
            throw new UnsupportedOperationException();
        }
    }

    private class FacadeFactoryImpl implements
            TaskContainmentHierarchyFacade.Factory {
//        private final Task myRoot;
//
//        FacadeFactoryImpl(Task root) {
//            myRoot = root;
//        }

        public TaskContainmentHierarchyFacade createFacede() {
            return new FacadeImpl();
        }
    }

    public TaskContainmentHierarchyFacade getTaskHierarchy() {
        // if (myTaskContainment==null) {
        return myFacadeFactory.createFacede();
        // }
        // return myTaskContainment;
    }

    public TaskManager emptyClone() {
        return new TaskManagerImpl(null, myConfig, null);
    }

    public Map<Task, Task> importData(TaskManager taskManager) {
        Task importRoot = taskManager.getRootTask();
        Map<Task, Task> original2imported = new HashMap<Task, Task>();
        importData(importRoot, getRootTask(), original2imported);
        TaskDependency[] deps = taskManager.getDependencyCollection()
                .getDependencies();
        for (int i = 0; i < deps.length; i++) {
            Task nextDependant = deps[i].getDependant();
            Task nextDependee = deps[i].getDependee();
            Task importedDependant = original2imported
                    .get(nextDependant);
            Task importedDependee = original2imported.get(nextDependee);
            try {
                TaskDependency dependency = getDependencyCollection()
                        .createDependency(importedDependant, importedDependee,
                                new FinishStartConstraintImpl());
                dependency.setConstraint(deps[i].getConstraint());
                dependency.setDifference(deps[i].getDifference());
                dependency.setHardness(deps[i].getHardness());
            } catch (TaskDependencyException e) {
                if (!GPLogger.log(e)) {
                    e.printStackTrace(System.err);
                }
            }
        }
        return original2imported;
    }

    private void importData(Task importRoot, Task root, Map<Task, Task> original2imported) {
        Task[] nested = importRoot.getManager().getTaskHierarchy()
                .getNestedTasks(importRoot);
        for (int i = nested.length - 1; i >= 0; i--) {
            Task nextImported = createTask(nested[i].getTaskID());
            registerTask(nextImported);
            nextImported.setName(nested[i].getName());
            nextImported.setStart(nested[i].getStart().clone());
            nextImported.setDuration(nested[i].getDuration());
            nextImported.setMilestone(nested[i].isMilestone());
            nextImported.setColor(nested[i].getColor());
            nextImported.setShape(nested[i].getShape());
            nextImported.setCompletionPercentage(nested[i]
                    .getCompletionPercentage());
            nextImported.setNotes(nested[i].getNotes());
            nextImported.setTaskInfo(nested[i].getTaskInfo());
            nextImported.setExpand(nested[i].getExpand());
            if (nested[i].getThird() != null) {
                nextImported.setThirdDate(nested[i].getThird().clone());
                nextImported.setThirdDateConstraint(nested[i]
                        .getThirdDateConstraint());
            }

            CustomColumnsValues customValues = nested[i].getCustomValues();
            Collection<CustomColumn> customColums = myCustomColumnStorage.getCustomColums();
            for (Iterator<CustomColumn> it=customColums.iterator(); it.hasNext();) {
                CustomColumn nextColumn = it.next();
                Object value = customValues.getValue(nextColumn.getName());
                if (value!=null) {
                    try {
                        nextImported.getCustomValues().setValue(nextColumn.getName(), value);
                    } catch (CustomColumnsException e) {
                        if (!GPLogger.log(e)) {
                            e.printStackTrace(System.err);
                        }
                    }
                }
            }
            // System.out.println ("Import : " + nextImported.getTaskID() + "
            // -->> " + nextImported.getName());

            original2imported.put(nested[i], nextImported);
            getTaskHierarchy().move(nextImported, root);
            importData(nested[i], nextImported, original2imported);
        }
    }

    public Date findClosestWorkingTime(Date time) {
        return getCalendar().findClosestWorkingTime(time);
    }

    public void processCriticalPath(Task root) {
        try {
            myAlgorithmCollection.getRecalculateTaskScheduleAlgorithm().run();
        } catch (TaskDependencyException e) {
            if (!GPLogger.log(e)) {
                e.printStackTrace(System.err);
            }
        }
        Task[] tasks = myAlgorithmCollection.getCriticalPathAlgorithm().getCriticalTasks();
        resetCriticalPath();
        for (int i = 0; i < tasks.length; i++) {
            tasks[i].setCritical(true);
        }
    }

    private void resetCriticalPath() {
        Task[] allTasks = getTasks();
        for (int i=0; i<allTasks.length; i++) {
            allTasks[i].setCritical(false);
        }
    }

    public void importAssignments(TaskManager importedTaskManager,
            HumanResourceManager hrManager, Map<Task, Task> original2importedTask,
            Map<HumanResource, HumanResource> original2importedResource) {
        Task[] tasks = importedTaskManager.getTasks();
        for (int i = 0; i < tasks.length; i++) {
            ResourceAssignment[] assignments = tasks[i].getAssignments();
            for (int j = 0; j < assignments.length; j++) {
                Task task = getTask(original2importedTask.get(tasks[i])
                        .getTaskID());
                ResourceAssignment assignment = task.getAssignmentCollection()
                        .addAssignment(original2importedResource
                                .get(assignments[j].getResource()));
                assignment.setLoad(assignments[j].getLoad());
                assignment.setCoordinator(assignments[j].isCoordinator());
            }
        }
    }

    void onTaskMoved(TaskImpl task) {
        if (!isRegistered(task)) {
            registerTask(task);
        }
        myTaskMap.setDirty();
    }

    public void setEventsEnabled(boolean enabled) {
        areEventsEnabled = enabled;
    }

    public CustomColumnsStorage getCustomColumnStorage() {
        return myCustomColumnStorage;
    }

    public CustomPropertyManager getCustomPropertyManager() {
        return new CustomColumnsManager(getCustomColumnStorage());
    }

    public URL getProjectDocument() {
        return myConfig.getProjectDocumentURL();
    }

    private static class TaskNamePrefixOption extends DefaultStringOption implements GP1XOptionConverter {
        public TaskNamePrefixOption() {
            super("taskNamePrefix");
            setValue(GanttLanguage.getInstance().getText("newTask"), true);
        }
        @Override
        public String getTagName() {
            return "task-name";
        }
        @Override
        public String getAttributeName() {
            return "prefix";
        }
        @Override
        public void loadValue(String legacyValue) {
            setValue(legacyValue, true);
        }
    }
    @Override
    public StringOption getTaskNamePrefixOption() {
        return myTaskNamePrefixOption;
    }


}
