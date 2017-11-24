package com.cronutils.model.time;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.cronutils.mapper.WeekDay;
import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.field.CronField;
import com.cronutils.model.field.CronFieldName;
import com.cronutils.model.field.definition.DayOfWeekFieldDefinition;
import com.cronutils.model.field.expression.Always;
import com.cronutils.model.field.expression.QuestionMark;
import com.cronutils.model.time.generator.FieldValueGenerator;
import com.cronutils.model.time.generator.NoSuchValueException;
import com.cronutils.utils.Preconditions;
import com.cronutils.utils.VisibleForTesting;

import static com.cronutils.model.field.CronFieldName.DAY_OF_MONTH;
import static com.cronutils.model.field.CronFieldName.DAY_OF_WEEK;
import static com.cronutils.model.field.CronFieldName.DAY_OF_YEAR;
import static com.cronutils.model.field.CronFieldName.HOUR;
import static com.cronutils.model.field.CronFieldName.MINUTE;
import static com.cronutils.model.field.CronFieldName.MONTH;
import static com.cronutils.model.field.CronFieldName.SECOND;
import static com.cronutils.model.field.CronFieldName.YEAR;
import static com.cronutils.model.field.value.SpecialChar.QUESTION_MARK;
import static com.cronutils.model.time.generator.FieldValueGeneratorFactory.createDayOfMonthValueGeneratorInstance;
import static com.cronutils.model.time.generator.FieldValueGeneratorFactory.createDayOfWeekValueGeneratorInstance;
import static com.cronutils.model.time.generator.FieldValueGeneratorFactory.createDayOfYearValueGeneratorInstance;
import static com.cronutils.utils.Predicates.not;
import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.time.temporal.TemporalAdjusters.lastDayOfMonth;

/*
 * Copyright 2014 jmrozanec
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Calculates execution time given a cron pattern.
 */
public class ExecutionTime {

    private static final LocalTime MAX_SECONDS = LocalTime.MAX.truncatedTo(SECONDS);

    private CronDefinition cronDefinition;
    private FieldValueGenerator yearsValueGenerator;
    private CronField daysOfWeekCronField;
    private CronField daysOfMonthCronField;
    private CronField daysOfYearCronField;

    private TimeNode months;
    private TimeNode hours;
    private TimeNode minutes;
    private TimeNode seconds;

    @VisibleForTesting
    ExecutionTime(CronDefinition cronDefinition, FieldValueGenerator yearsValueGenerator, CronField daysOfWeekCronField,
            CronField daysOfMonthCronField, CronField daysOfYearCronField, TimeNode months, TimeNode hours,
            TimeNode minutes, TimeNode seconds) {
        this.cronDefinition = Preconditions.checkNotNull(cronDefinition);
        this.yearsValueGenerator = Preconditions.checkNotNull(yearsValueGenerator);
        this.daysOfWeekCronField = Preconditions.checkNotNull(daysOfWeekCronField);
        this.daysOfMonthCronField = Preconditions.checkNotNull(daysOfMonthCronField);
        this.daysOfYearCronField = daysOfYearCronField;
        this.months = Preconditions.checkNotNull(months);
        this.hours = Preconditions.checkNotNull(hours);
        this.minutes = Preconditions.checkNotNull(minutes);
        this.seconds = Preconditions.checkNotNull(seconds);
    }

    /**
     * Creates execution time for given Cron.
     *
     * @param cron - Cron instance
     * @return ExecutionTime instance
     */
    public static ExecutionTime forCron(Cron cron) {
        Map<CronFieldName, CronField> fields = cron.retrieveFieldsAsMap();
        ExecutionTimeBuilder executionTimeBuilder = new ExecutionTimeBuilder(cron.getCronDefinition());
        for (CronFieldName name : CronFieldName.values()) {
            if (fields.get(name) != null) {
                switch (name) {
                    case SECOND:
                        executionTimeBuilder.forSecondsMatching(fields.get(name));
                        break;
                    case MINUTE:
                        executionTimeBuilder.forMinutesMatching(fields.get(name));
                        break;
                    case HOUR:
                        executionTimeBuilder.forHoursMatching(fields.get(name));
                        break;
                    case DAY_OF_WEEK:
                        executionTimeBuilder.forDaysOfWeekMatching(fields.get(name));
                        break;
                    case DAY_OF_MONTH:
                        executionTimeBuilder.forDaysOfMonthMatching(fields.get(name));
                        break;
                    case MONTH:
                        executionTimeBuilder.forMonthsMatching(fields.get(name));
                        break;
                    case YEAR:
                        executionTimeBuilder.forYearsMatching(fields.get(name));
                        break;
                    case DAY_OF_YEAR:
                        executionTimeBuilder.forDaysOfYearMatching(fields.get(name));
                        break;
                    default:
                        break;
                }
            }
        }
        return executionTimeBuilder.build();
    }

    /**
     * Provide nearest date for next execution.
     *
     * @param date - ZonedDateTime instance. If null, a NullPointerException will be raised.
     * @return Optional ZonedDateTime instance, never null. Contains next execution time or empty.
     */
    public Optional<ZonedDateTime> nextExecution(ZonedDateTime date) {
        Preconditions.checkNotNull(date);
        try {
            ZonedDateTime nextMatch = nextClosestMatch(date);
            if (nextMatch.equals(date)) {
                nextMatch = nextClosestMatch(date.plusSeconds(1));
            }
            return Optional.of(nextMatch);
        } catch (NoSuchValueException e) {
            return Optional.empty();
        }
    }

    /**
     * If date is not match, will return next closest match.
     * If date is match, will return this date.
     *
     * @param date - reference ZonedDateTime instance - never null;
     * @return ZonedDateTime instance, never null. Value obeys logic specified above.
     * @throws NoSuchValueException
     */
    private ZonedDateTime nextClosestMatch(ZonedDateTime date) throws NoSuchValueException {
        ExecutionTimeResult result = new ExecutionTimeResult(date, false);
        do {
            result = potentialNextClosestMatch(result.getTime());
        } while (!result.isMatch());
        return result.getTime();
    }

    private ExecutionTimeResult potentialNextClosestMatch(ZonedDateTime date) throws NoSuchValueException {
        List<Integer> year = yearsValueGenerator.generateCandidates(date.getYear(), date.getYear());
        int lowestMonth = months.getValues().get(0);
        int lowestHour = hours.getValues().get(0);
        int lowestMinute = minutes.getValues().get(0);
        int lowestSecond = seconds.getValues().get(0);

        if (year.isEmpty()) {
            return getNextPotentialYear(date, lowestMonth, lowestHour, lowestMinute, lowestSecond);
        }
        if (!months.getValues().contains(date.getMonthValue())) {
            return getNextPotentialMonth(date, lowestHour, lowestMinute, lowestSecond);
        }

        Optional<TimeNode> optionalDays = generateDays(cronDefinition, date);
        if (!optionalDays.isPresent()) {
            return new ExecutionTimeResult(toBeginOfNextMonth(date), false);
        }
        TimeNode node = optionalDays.get();

        if (!node.getValues().contains(date.getDayOfMonth())) {
            return getNextPotentialDayOfMonth(date, lowestHour, lowestMinute, lowestSecond, node);
        }
        if (!hours.getValues().contains(date.getHour())) {
            return getNextPotentialHour(date, lowestMinute, lowestSecond);
        }
        if (!minutes.getValues().contains(date.getMinute())) {
            return getNextPotentialMinute(date, lowestSecond);
        }
        if (!seconds.getValues().contains(date.getSecond())) {
            return getNextPotentialSecond(date);
        }
        return new ExecutionTimeResult(date, true);
    }

    private ExecutionTimeResult getNextPotentialYear(ZonedDateTime date, int lowestMonth, int lowestHour, int lowestMinute, int lowestSecond)
            throws NoSuchValueException {
        int newYear = yearsValueGenerator.generateNextValue(date.getYear());
        Optional<TimeNode> optionalDays = generateDays(cronDefinition, ZonedDateTime.of(
                LocalDate.of(newYear, lowestMonth, 1),
                LocalTime.MIN,
                date.getZone())
        );
        if (optionalDays.isPresent()) {
            List<Integer> days = optionalDays.get().getValues();
            return new ExecutionTimeResult(ZonedDateTime.of(
                    LocalDate.of(newYear, lowestMonth, days.get(0)),
                    LocalTime.of(lowestHour, lowestMinute, lowestSecond), date.getZone()), false);
        } else {
            return new ExecutionTimeResult(toBeginOfNextMonth(date), false);
        }
    }

    private ExecutionTimeResult getNextPotentialMonth(ZonedDateTime date, int lowestHour, int lowestMinute, int lowestSecond)
            throws NoSuchValueException {
        NearestValue nearestValue;
        nearestValue = months.getNextValue(date.getMonthValue(), 0);
        int nextMonths = nearestValue.getValue();
        if (nearestValue.getShifts() > 0) {
            return new ExecutionTimeResult(date.truncatedTo(DAYS).withMonth(1).withDayOfMonth(1).plusYears(nearestValue.getShifts()), false);
        }
        Optional<TimeNode> optionalDays = generateDays(cronDefinition,
                ZonedDateTime.of(LocalDateTime.of(date.getYear(), nextMonths, 1, 0, 0), date.getZone()));
        if (optionalDays.isPresent()) {
            List<Integer> days = optionalDays.get().getValues();
            return new ExecutionTimeResult(
                    date.truncatedTo(SECONDS).withMonth(nextMonths).withDayOfMonth(days.get(0))
                            .with(LocalTime.of(lowestHour, lowestMinute, lowestSecond)), false);
        } else {
            return new ExecutionTimeResult(toBeginOfNextMonth(date), false);
        }
    }

    private ExecutionTimeResult getNextPotentialDayOfMonth(ZonedDateTime date, int lowestHour, int lowestMinute, int lowestSecond, TimeNode node)
            throws NoSuchValueException {
        NearestValue nearestValue = node.getNextValue(date.getDayOfMonth(), 0);
        if (nearestValue.getShifts() > 0) {
            return new ExecutionTimeResult(date.truncatedTo(DAYS).withDayOfMonth(1).plusMonths(nearestValue.getShifts()), false);
        }
        if (nearestValue.getValue() < date.getDayOfMonth()) {
            return new ExecutionTimeResult(date.truncatedTo(SECONDS).plusMonths(1).withDayOfMonth(nearestValue.getValue())
                    .with(LocalTime.of(lowestHour, lowestMinute, lowestSecond)), false);
        }
        return new ExecutionTimeResult(date.truncatedTo(SECONDS).withDayOfMonth(nearestValue.getValue())
                .with(LocalTime.of(lowestHour, lowestMinute, lowestSecond)), false);
    }

    private ExecutionTimeResult getNextPotentialHour(ZonedDateTime date, int lowestMinute, int lowestSecond) throws NoSuchValueException {
        NearestValue nearestValue = hours.getNextValue(date.getHour(), 0);
        int nextHours = nearestValue.getValue();
        if (nearestValue.getShifts() > 0) {
            return new ExecutionTimeResult(date.truncatedTo(DAYS).plusDays(nearestValue.getShifts()), false);
        }
        if (nearestValue.getValue() < date.getHour()) {
            return new ExecutionTimeResult(date.truncatedTo(SECONDS).plusDays(1).with(LocalTime.of(nextHours, lowestMinute, lowestSecond)), false);
        }
        return new ExecutionTimeResult(date.truncatedTo(SECONDS).with(LocalTime.of(nextHours, lowestMinute, lowestSecond)), false);
    }

    private ExecutionTimeResult getNextPotentialMinute(final ZonedDateTime date, int lowestSecond) {
        NearestValue nearestValue = minutes.getNextValue(date.getMinute(), 0);
        int nextMinutes = nearestValue.getValue();
        if (nearestValue.getShifts() > 0) {
            return new ExecutionTimeResult(date.truncatedTo(HOURS).plusHours(nearestValue.getShifts()), false);
        }
        if (nearestValue.getValue() < date.getMinute()) {
            return new ExecutionTimeResult(date.truncatedTo(SECONDS).plusHours(1).withMinute(nextMinutes).withSecond(lowestSecond), false);
        }
        return new ExecutionTimeResult(date.truncatedTo(SECONDS).withMinute(nextMinutes).withSecond(lowestSecond), false);
    }

    private ExecutionTimeResult getNextPotentialSecond(ZonedDateTime date) {
        NearestValue nearestValue;
        nearestValue = seconds.getNextValue(date.getSecond(), 0);
        int nextSeconds = nearestValue.getValue();
        if (nearestValue.getShifts() > 0) {
            return new ExecutionTimeResult(date.truncatedTo(MINUTES).plusMinutes(nearestValue.getShifts()), false);
        }
        if (nearestValue.getValue() < date.getSecond()) {
            return new ExecutionTimeResult(date.truncatedTo(SECONDS).withMinute(1).withSecond(nextSeconds), false);
        }
        return new ExecutionTimeResult(date.truncatedTo(SECONDS).withSecond(nextSeconds), false);
    }

    private ZonedDateTime toBeginOfNextMonth(final ZonedDateTime datetime) {
        return datetime.truncatedTo(DAYS).plusMonths(1).withDayOfMonth(1);
    }

    /**
     * If date is not match, will return previous closest match.
     * If date is match, will return this date.
     *
     * @param date - reference ZonedDateTime instance - never null;
     * @return ZonedDateTime instance, never null. Value obeys logic specified above.
     * @throws NoSuchValueException
     */
    private ZonedDateTime previousClosestMatch(ZonedDateTime date) throws NoSuchValueException {
        ExecutionTimeResult result = new ExecutionTimeResult(date, false);
        do {
            result = potentialPreviousClosestMatch(result.getTime());
        } while (!result.isMatch());
        return result.getTime();
    }

    private ExecutionTimeResult potentialPreviousClosestMatch(ZonedDateTime date) throws NoSuchValueException {
        List<Integer> year = yearsValueGenerator.generateCandidates(date.getYear(), date.getYear());
        Optional<TimeNode> optionalDays = generateDays(cronDefinition, date);
        TimeNode days;
        if (optionalDays.isPresent()) {
            days = optionalDays.get();
        } else {
            return new ExecutionTimeResult(toEndOfPreviousMonth(date), false);
        }
        int highestMonth = months.getValues().get(months.getValues().size() - 1);
        int highestDay = days.getValues().get(days.getValues().size() - 1);
        int highestHour = hours.getValues().get(hours.getValues().size() - 1);
        int highestMinute = minutes.getValues().get(minutes.getValues().size() - 1);
        int highestSecond = seconds.getValues().get(seconds.getValues().size() - 1);

        if (year.isEmpty()) {
            return getPreviousPotentialYear(date, days, highestMonth, highestDay, highestHour, highestMinute, highestSecond);
        }
        if (!months.getValues().contains(date.getMonthValue())) {
            return getPreviousPotentialMonth(date, highestDay, highestHour, highestMinute, highestSecond);
        }
        if (!days.getValues().contains(date.getDayOfMonth())) {
            return getPreviousPotentialDayOfMonth(date, days, highestHour, highestMinute, highestSecond);
        }
        if (!hours.getValues().contains(date.getHour())) {
            return getPreviousPotentialHour(date, highestMinute, highestSecond);
        }
        if (!minutes.getValues().contains(date.getMinute())) {
            return getPreviousPotentialMinute(date, highestSecond);
        }
        if (!seconds.getValues().contains(date.getSecond())) {
            return getPreviousPotentialSecond(date);
        }
        return new ExecutionTimeResult(date, true);
    }

    private ExecutionTimeResult getPreviousPotentialYear(ZonedDateTime date, TimeNode days, int highestMonth, int highestDay,
            int highestHour, int highestMinute, int highestSecond) throws NoSuchValueException {
        NearestValue nearestValue;
        ZonedDateTime newDate;
        int previousYear = yearsValueGenerator.generatePreviousValue(date.getYear());
        if (highestDay > 28) {
            int highestDayOfMonth = LocalDate.of(previousYear, highestMonth, 1).lengthOfMonth();
            if (highestDay > highestDayOfMonth) {
                nearestValue = days.getPreviousValue(highestDay, 1);
                if (nearestValue.getShifts() > 0) {
                    newDate = ZonedDateTime.of(
                            LocalDate.of(previousYear, highestMonth, 1),
                            MAX_SECONDS,
                            date.getZone()
                    ).minusMonths(nearestValue.getShifts()).with(lastDayOfMonth());
                    return new ExecutionTimeResult(newDate, false);
                } else {
                    highestDay = nearestValue.getValue();
                }
            }
        }
        return new ExecutionTimeResult(ZonedDateTime.of(
                LocalDate.of(previousYear, highestMonth, highestDay),
                LocalTime.of(highestHour, highestMinute, highestSecond),
                date.getZone()),
                false);
    }

    private ExecutionTimeResult getPreviousPotentialMonth(ZonedDateTime date, int highestDay, int highestHour,
            int highestMinute, int highestSecond) throws NoSuchValueException {
        NearestValue nearestValue;
        ZonedDateTime newDate;
        nearestValue = months.getPreviousValue(date.getMonthValue(), 0);
        int previousMonths = nearestValue.getValue();
        if (nearestValue.getShifts() > 0) {
            newDate = ZonedDateTime.of(
                    LocalDate.of(date.getYear(), 12, 31),
                    MAX_SECONDS,
                    date.getZone()
            ).minusYears(nearestValue.getShifts());
            return new ExecutionTimeResult(newDate, false);
        }
        return new ExecutionTimeResult(date.withMonth(previousMonths).withDayOfMonth(highestDay)
                .with(LocalTime.of(highestHour, highestMinute, highestSecond)), false);
    }

    private ExecutionTimeResult getPreviousPotentialDayOfMonth(ZonedDateTime date, TimeNode days, int highestHour, int highestMinute,
            int highestSecond) throws NoSuchValueException {
        NearestValue nearestValue;
        ZonedDateTime newDate;
        nearestValue = days.getPreviousValue(date.getDayOfMonth(), 0);
        if (nearestValue.getShifts() > 0) {
            newDate = ZonedDateTime.of(
                    LocalDate.of(date.getYear(), date.getMonthValue(), 1),
                    MAX_SECONDS,
                    date.getZone()
            ).minusMonths(nearestValue.getShifts()).with(lastDayOfMonth());
            return new ExecutionTimeResult(newDate, false);
        }
        return new ExecutionTimeResult(date.withDayOfMonth(nearestValue.getValue())
                .with(LocalTime.of(highestHour, highestMinute, highestSecond)), false);
    }

    private ExecutionTimeResult getPreviousPotentialHour(ZonedDateTime date, int highestMinute, int highestSecond) throws NoSuchValueException {
        NearestValue nearestValue;
        ZonedDateTime newDate;
        nearestValue = hours.getPreviousValue(date.getHour(), 0);
        if (nearestValue.getShifts() > 0) {
            newDate = date.truncatedTo(DAYS).plusDays(1).minusSeconds(1).minusDays(nearestValue.getShifts());
            return new ExecutionTimeResult(newDate, false);
        }
        return new ExecutionTimeResult(date.with(LocalTime.of(nearestValue.getValue(), highestMinute, highestSecond)), false);
    }

    private ExecutionTimeResult getPreviousPotentialMinute(ZonedDateTime date, int highestSecond) throws NoSuchValueException {
        NearestValue nearestValue;
        ZonedDateTime newDate;
        nearestValue = minutes.getPreviousValue(date.getMinute(), 0);
        if (nearestValue.getShifts() > 0) {
            newDate = date.truncatedTo(HOURS).plusHours(1).minusSeconds(1).minusHours(nearestValue.getShifts());
            return new ExecutionTimeResult(newDate, false);
        }
        return new ExecutionTimeResult(date.withMinute(nearestValue.getValue()).withSecond(highestSecond), false);
    }

    private ExecutionTimeResult getPreviousPotentialSecond(ZonedDateTime date) throws NoSuchValueException {
        NearestValue nearestValue;
        ZonedDateTime newDate;
        nearestValue = seconds.getPreviousValue(date.getSecond(), 0);
        int previousSeconds = nearestValue.getValue();
        if (nearestValue.getShifts() > 0) {
            newDate = date.truncatedTo(MINUTES).plusMinutes(1).minusSeconds(1).minusMinutes(nearestValue.getShifts());
            return new ExecutionTimeResult(newDate, false);
        }
        return new ExecutionTimeResult(date.withSecond(previousSeconds), false);
    }

    private ZonedDateTime toEndOfPreviousMonth(final ZonedDateTime datetime) {
        final ZonedDateTime previousMonth = datetime.minusMonths(1).with(lastDayOfMonth());
        int highestHour = hours.getValues().get(hours.getValues().size() - 1);
        int highestMinute = minutes.getValues().get(minutes.getValues().size() - 1);
        int highestSecond = seconds.getValues().get(seconds.getValues().size() - 1);
        return ZonedDateTime
                .of(previousMonth.getYear(), previousMonth.getMonth().getValue(), previousMonth.getDayOfMonth(), highestHour, highestMinute, highestSecond, 0,
                        previousMonth.getZone());
    }

    private Optional<TimeNode> generateDays(CronDefinition cronDefinition, ZonedDateTime date) {
        if (isGenerateDaysAsDoY(cronDefinition)) {
            return generateDayCandidatesUsingDoY(date);
        }
        //If DoW is not supported in custom definition, we just return an empty list.
        if (cronDefinition.getFieldDefinition(DAY_OF_WEEK) != null && cronDefinition.getFieldDefinition(DAY_OF_MONTH) != null) {
            return generateDaysDoWAndDoMSupported(cronDefinition, date);
        }
        if (cronDefinition.getFieldDefinition(DAY_OF_WEEK) == null) {
            return Optional.of(generateDayCandidatesUsingDoM(date));
        }
        return Optional
                .of(generateDayCandidatesUsingDoW(date, ((DayOfWeekFieldDefinition) cronDefinition.getFieldDefinition(DAY_OF_WEEK)).getMondayDoWValue()));
    }

    private boolean isGenerateDaysAsDoY(CronDefinition cronDefinition) {
        if (!cronDefinition.containsFieldDefinition(DAY_OF_YEAR)) {
            return false;
        }

        if (!cronDefinition.getFieldDefinition(DAY_OF_YEAR).getConstraints().getSpecialChars().contains(QUESTION_MARK)) {
            return true;
        }

        return !(daysOfYearCronField.getExpression() instanceof QuestionMark);
    }

    private Optional<TimeNode> generateDayCandidatesUsingDoY(ZonedDateTime reference) {
        final int year = reference.getYear();
        final int month = reference.getMonthValue();
        LocalDate date = LocalDate.of(year, 1, 1);
        int lengthOfYear = date.lengthOfYear();

        List<Integer> candidates = createDayOfYearValueGeneratorInstance(daysOfYearCronField, year).generateCandidates(1, lengthOfYear);

        int low = LocalDate.of(year, month, 1).getDayOfYear();
        int high = month == 12
                ? LocalDate.of(year, 12, 31).getDayOfYear() + 1
                : LocalDate.of(year, month + 1, 1).getDayOfYear();

        List<Integer> collectedCandidates = candidates.stream().filter(dayOfYear -> dayOfYear >= low && dayOfYear < high)
                .map(dayOfYear -> LocalDate.ofYearDay(reference.getYear(), dayOfYear).getDayOfMonth())
                .collect(Collectors.toList());

        return Optional.of(collectedCandidates).filter(not(List::isEmpty)).map(TimeNode::new);
    }

    private Optional<TimeNode> generateDaysDoWAndDoMSupported(CronDefinition cronDefinition, ZonedDateTime date) {
        boolean questionMarkSupported = cronDefinition.getFieldDefinition(DAY_OF_WEEK).getConstraints().getSpecialChars().contains(QUESTION_MARK);
        if (questionMarkSupported) {
            List<Integer> candidates = generateDayCandidatesQuestionMarkSupportedUsingDoWAndDoM(
                    date.getYear(),
                    date.getMonthValue(),
                    ((DayOfWeekFieldDefinition) cronDefinition.getFieldDefinition(DAY_OF_WEEK)).getMondayDoWValue()
            );
            return Optional.of(candidates).filter(not(List::isEmpty)).map(TimeNode::new);
        } else {
            List<Integer> candidates = generateDayCandidatesQuestionMarkNotSupportedUsingDoWAndDoM(
                    date.getYear(), date.getMonthValue(),
                    ((DayOfWeekFieldDefinition)
                            cronDefinition.getFieldDefinition(DAY_OF_WEEK)
                    ).getMondayDoWValue()
            );
            return Optional.of(candidates).filter(not(List::isEmpty)).map(TimeNode::new);
        }
    }

    /**
     * Provide nearest time for next execution.
     *
     * @param date - ZonedDateTime instance. If null, a NullPointerException will be raised.
     * @return Duration instance, never null. Time to next execution.
     */
    public Optional<Duration> timeToNextExecution(ZonedDateTime date) {
        Optional<ZonedDateTime> next = nextExecution(date);

        return next.map(zonedDateTime -> Duration.between(date, zonedDateTime));
    }

    /**
     * Provide nearest date for last execution.
     *
     * @param date - ZonedDateTime instance. If null, a NullPointerException will be raised.
     * @return Optional ZonedDateTime instance, never null. Last execution time or empty.
     */
    public Optional<ZonedDateTime> lastExecution(ZonedDateTime date) {
        Preconditions.checkNotNull(date);
        try {
            ZonedDateTime previousMatch = previousClosestMatch(date);
            if (previousMatch.equals(date)) {
                previousMatch = previousClosestMatch(date.minusSeconds(1));
            }
            return Optional.of(previousMatch);
        } catch (NoSuchValueException e) {
            return Optional.empty();
        }
    }

    /**
     * Provide nearest time from last execution.
     *
     * @param date - ZonedDateTime instance. If null, a NullPointerException will be raised.
     * @return Duration instance, never null. Time from last execution.
     */
    public Optional<Duration> timeFromLastExecution(ZonedDateTime date) {
        return lastExecution(date).map(zonedDateTime -> Duration.between(zonedDateTime, date));
    }

    /**
     * Provide feedback if a given date matches the cron expression.
     *
     * @param date - ZonedDateTime instance. If null, a NullPointerException will be raised.
     * @return true if date matches cron expression requirements, false otherwise.
     */
    public boolean isMatch(ZonedDateTime date) {
        // Issue #200: Truncating the date to the least granular precision supported by different cron systems.
        // For Quartz, it's seconds while for Unix & Cron4J it's minutes.
        boolean isSecondGranularity = cronDefinition.containsFieldDefinition(SECOND);
        if (isSecondGranularity) {
            date = date.truncatedTo(SECONDS);
        } else {
            date = date.truncatedTo(ChronoUnit.MINUTES);
        }

        Optional<ZonedDateTime> last = lastExecution(date);
        if (last.isPresent()) {
            Optional<ZonedDateTime> next = nextExecution(last.get());
            if (next.isPresent()) {
                return next.get().equals(date);
            } else {
                boolean everythingInRange = false;
                try {
                    everythingInRange = dateValuesInExpectedRanges(nextClosestMatch(date), date);
                } catch (NoSuchValueException ignored) {
                    // Why is this ignored?
                }
                try {
                    everythingInRange = dateValuesInExpectedRanges(previousClosestMatch(date), date);
                } catch (NoSuchValueException ignored) {
                    // Why is this ignored?
                }
                return everythingInRange;
            }
        }
        return false;
    }

    private boolean dateValuesInExpectedRanges(ZonedDateTime validCronDate, ZonedDateTime date) {
        boolean everythingInRange = true;
        if (cronDefinition.getFieldDefinition(YEAR) != null) {
            everythingInRange = validCronDate.getYear() == date.getYear();
        }
        if (cronDefinition.getFieldDefinition(MONTH) != null) {
            everythingInRange = everythingInRange && validCronDate.getMonthValue() == date.getMonthValue();
        }
        if (cronDefinition.getFieldDefinition(DAY_OF_MONTH) != null) {
            everythingInRange = everythingInRange && validCronDate.getDayOfMonth() == date.getDayOfMonth();
        }
        if (cronDefinition.getFieldDefinition(DAY_OF_WEEK) != null) {
            everythingInRange = everythingInRange && validCronDate.getDayOfWeek().getValue() == date.getDayOfWeek().getValue();
        }
        if (cronDefinition.getFieldDefinition(HOUR) != null) {
            everythingInRange = everythingInRange && validCronDate.getHour() == date.getHour();
        }
        if (cronDefinition.getFieldDefinition(MINUTE) != null) {
            everythingInRange = everythingInRange && validCronDate.getMinute() == date.getMinute();
        }
        if (cronDefinition.getFieldDefinition(SECOND) != null) {
            everythingInRange = everythingInRange && validCronDate.getSecond() == date.getSecond();
        }
        return everythingInRange;
    }

    private List<Integer> generateDayCandidatesQuestionMarkNotSupportedUsingDoWAndDoM(int year, int month, WeekDay mondayDoWValue) {
        LocalDate date = LocalDate.of(year, month, 1);
        int lengthOfMonth = date.lengthOfMonth();
        if (daysOfMonthCronField.getExpression() instanceof Always && daysOfWeekCronField.getExpression() instanceof Always) {
            return createDayOfMonthValueGeneratorInstance(daysOfMonthCronField, year, month)
                    .generateCandidates(1, lengthOfMonth)
                    .stream().distinct().sorted()
                    .collect(Collectors.toList());
        } else if (daysOfMonthCronField.getExpression() instanceof Always) {
            return createDayOfWeekValueGeneratorInstance(daysOfWeekCronField, year, month, mondayDoWValue)
                    .generateCandidates(1, lengthOfMonth)
                    .stream().distinct().sorted()
                    .collect(Collectors.toList());
        } else if (daysOfWeekCronField.getExpression() instanceof Always) {
            return createDayOfMonthValueGeneratorInstance(daysOfMonthCronField, year, month)
                    .generateCandidates(1, lengthOfMonth)
                    .stream().distinct().sorted()
                    .collect(Collectors.toList());
        } else {
            List<Integer> dayOfWeekCandidates = createDayOfWeekValueGeneratorInstance(daysOfWeekCronField,
                    year, month, mondayDoWValue).generateCandidates(1, lengthOfMonth);
            List<Integer> dayOfMonthCandidates = createDayOfMonthValueGeneratorInstance(daysOfMonthCronField, year, month)
                    .generateCandidates(1, lengthOfMonth);
            if (cronDefinition.isMatchDayOfWeekAndDayOfMonth()) {
                Set<Integer> intersection = new HashSet<>(dayOfWeekCandidates);
                return dayOfMonthCandidates
                        .stream().filter(intersection::contains)
                        .distinct().sorted()
                        .collect(Collectors.toList());
            } else {
                return Stream.concat(dayOfWeekCandidates.stream(), dayOfMonthCandidates.stream())
                        .distinct().sorted()
                        .collect(Collectors.toList());
            }
        }
    }

    private List<Integer> generateDayCandidatesQuestionMarkSupportedUsingDoWAndDoM(int year, int month, WeekDay mondayDoWValue) {
        LocalDate date = LocalDate.of(year, month, 1);
        int lengthOfMonth = date.lengthOfMonth();
        if (daysOfMonthCronField.getExpression() instanceof Always && daysOfWeekCronField.getExpression() instanceof Always) {
            return createDayOfMonthValueGeneratorInstance(daysOfMonthCronField, year, month)
                    .generateCandidates(1, lengthOfMonth)
                    .stream().distinct().sorted()
                    .collect(Collectors.toList());
        } else if (daysOfMonthCronField.getExpression() instanceof QuestionMark) {
            return createDayOfWeekValueGeneratorInstance(daysOfWeekCronField, year, month, mondayDoWValue)
                    .generateCandidates(1, lengthOfMonth)
                    .stream().distinct().sorted()
                    .collect(Collectors.toList());
        } else if (daysOfWeekCronField.getExpression() instanceof QuestionMark) {
            return createDayOfMonthValueGeneratorInstance(daysOfMonthCronField, year, month)
                    .generateCandidates(1, lengthOfMonth)
                    .stream().distinct().sorted()
                    .collect(Collectors.toList());
        } else {
            return Stream.concat(
                    createDayOfWeekValueGeneratorInstance(daysOfWeekCronField, year, month, mondayDoWValue).generateCandidates(1, lengthOfMonth).stream(),
                    createDayOfMonthValueGeneratorInstance(daysOfMonthCronField, year, month).generateCandidates(1, lengthOfMonth).stream()
            ).distinct().sorted().collect(Collectors.toList());
        }
    }

    private TimeNode generateDayCandidatesUsingDoM(ZonedDateTime reference) {
        LocalDate date = LocalDate.of(reference.getYear(), reference.getMonthValue(), 1);
        int lengthOfMonth = date.lengthOfMonth();
        List<Integer> candidates = createDayOfMonthValueGeneratorInstance(daysOfMonthCronField, reference.getYear(), reference.getMonthValue())
                .generateCandidates(1, lengthOfMonth)
                .stream().distinct().sorted()
                .collect(Collectors.toList());
        return new TimeNode(candidates);
    }

    private TimeNode generateDayCandidatesUsingDoW(ZonedDateTime reference, WeekDay mondayDoWValue) {
        LocalDate date = LocalDate.of(reference.getYear(), reference.getMonthValue(), 1);
        int lengthOfMonth = date.lengthOfMonth();
        List<Integer> candidates = createDayOfWeekValueGeneratorInstance(daysOfWeekCronField, reference.getYear(), reference.getMonthValue(), mondayDoWValue)
                .generateCandidates(1, lengthOfMonth)
                .stream().distinct().sorted()
                .collect(Collectors.toList());
        return new TimeNode(candidates);
    }

    private static final class ExecutionTimeResult {
        private ZonedDateTime time;
        private boolean isMatch;

        private ExecutionTimeResult(ZonedDateTime time, boolean isMatch) {
            this.time = time;
            this.isMatch = isMatch;
        }

        public ZonedDateTime getTime() {
            return time;
        }

        public boolean isMatch() {
            return isMatch;
        }

        @Override
        public String toString() {
            return "ExecutionTimeResult{" + "time=" + time + ", isMatch=" + isMatch + '}';
        }
    }
}
