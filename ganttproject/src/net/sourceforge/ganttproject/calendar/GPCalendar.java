/*
 * Created on 18.10.2004
 */
package net.sourceforge.ganttproject.calendar;

import java.net.URL;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import net.sourceforge.ganttproject.GanttProject;
import net.sourceforge.ganttproject.task.TaskLength;
import net.sourceforge.ganttproject.time.TimeUnit;

/**
 * @author bard
 */
public interface GPCalendar {
    public enum MoveDirection {
        FORWARD, BACKWARD
    }

    List<CalendarActivityImpl> getActivities(Date startDate, Date endDate);

    List getActivities(Date startDate, TimeUnit timeUnit, long l);

    void setWeekDayType(int day, DayType type);

    DayType getWeekDayType(int day);

    /**
     * @return true when weekends are only shown and taken into
     *  account for the task scheduling.
     */
    public boolean getOnlyShowWeekends();

    /**
     * @param onlyShowWeekends must be set to true if weekends are
     *  only shown and not taken into account for the task scheduling
     */
    public void setOnlyShowWeekends(boolean onlyShowWeekends);

    void setPublicHoliDayType(int month, int date);

    public void setPublicHoliDayType(Date curDayStart);

    public boolean isPublicHoliDay(Date curDayStart);

    public boolean isNonWorkingDay(Date curDayStart);

    public DayType getDayTypeDate(Date curDayStart);

    public void setPublicHolidays(URL calendar, GanttProject gp);

    public Collection<Date> getPublicHolidays();

    public enum DayType {
        WORKING, NON_WORKING, WEEKEND, HOLIDAY
    }

    Date findClosestWorkingTime(Date time);

    /**
     * Adds <code>shift</code> period to <code>input</code> date taking into
     * account this calendar working/non-working time If input date corresponds
     * to friday midnight and this calendar if configured to have a weekend on
     * saturday and sunday then adding a shift of "1 day" will result to the
     * midnight of the next monday
     */
    Date shiftDate(Date input, TaskLength shift);

    Date findClosest(Date time, TimeUnit timeUnit, MoveDirection direction, DayType dayType);

    GPCalendar PLAIN = new AlwaysWorkingTimeCalendarImpl();
    String EXTENSION_POINT_ID = "net.sourceforge.ganttproject.calendar";

}
