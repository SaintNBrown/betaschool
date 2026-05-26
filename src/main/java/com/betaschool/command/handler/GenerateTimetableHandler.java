package com.betaschool.command.handler;

import com.betaschool.command.model.TimetableCommand.*;
import com.betaschool.infrastructure.persistence.entity.*;
import com.betaschool.infrastructure.persistence.entity.TimetableSlotEntity.DayOfWeek;
import com.betaschool.infrastructure.persistence.entity.TimetableSlotEntity.SlotType;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.shared.CommandHandler;
import com.betaschool.shared.exception.BusinessRuleViolationException;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.SchoolIdInjector;
import com.betaschool.tenant.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Two-phase timetable generator.
 *
 * ── OCCURRENCE STRUCTURE ────────────────────────────────────────────────────
 *   periods  structure
 *      1     1 single
 *      2     2 singles on different days (default) OR 1 double (if forceDouble=true)
 *      3     1 double + 1 single on different days
 *      4     2 doubles on different days
 *      5     2 doubles + 1 single on three different days
 *      ...   max doubles, min singles, each occurrence on a distinct day
 *
 * ── PHASE 1 — DAY ASSIGNMENT ─────────────────────────────────────────────
 *   Assigns each occurrence to a day (backtracking).
 *   DA1  No two occurrences of same subject on same day
 *   DA2  Teacher unavailability
 *   DA3  Day has enough free subject-slots (uses per-day slot count)
 *   DA4  Teacher not fully cross-class-blocked on that day
 *
 * ── PHASE 2 — SLOT PLACEMENT WITHIN EACH DAY ────────────────────────────
 *   SP1  Doubles occupy two consecutive subject-slot positions.
 *        If an activity falls between the two candidate consecutive positions,
 *        skip that pair. Prefer the pair immediately before such an activity
 *        if 2+ free slots exist there.
 *   SP2  Teacher-transition: soft reorder to avoid same-teacher consecutive groups.
 *   SP3  Cross-class teacher interval conflict.
 *
 * ── END-TIME BALANCING ──────────────────────────────────────────────────
 *   After Phase 2, the last filled subject-slot end-time is compared across
 *   all operating days. Days that end more than 2 subject-slots earlier than
 *   the latest day have their subjects shifted later (empty slots inserted at
 *   the front of the subject-slot sequence) to reduce the imbalance to ≤ 2 slots.
 *   End-time balancing only applies to days that share the same subject slot count;
 *   days with fewer slots due to day-specific activities are excluded from
 *   balancing (their reduced count is intentional).
 *
 * ── DAY-SPECIFIC ACTIVITIES ─────────────────────────────────────────────
 *   ActivitySpec.onlyOnDays restricts an activity to specific days.
 *
 *   ► If an activity applies to ALL operating days, it is treated as a pure
 *     clock-shifter: subject slot COUNT stays the same on every day, and the
 *     activity simply pushes subsequent slots' start times forward.
 *
 *   ► If an activity applies to only SOME days, it REPLACES subject slots on
 *     those days. The number of subject slots replaced equals
 *     ceil(activity.durationMinutes / slotDurationMinutes). Those days end
 *     up with fewer subject slots than the base count, which is intentional
 *     (e.g. an assembly that takes 2 periods only on Monday means Monday has
 *     2 fewer teachable periods that day).
 *
 *   ► ActivitySpec.isLastOfDay (optional, default false): when true, this
 *     activity is placed at the END of the day's timeline and no subject slots
 *     or other activities are scheduled after it. Useful for dismissal
 *     assemblies, closing prayers, etc.
 *
 * ── SCHOOL CLOSING TIME ─────────────────────────────────────────────────
 *   GenerateTimetableCommand.schoolClosingTime (optional LocalTime):
 *   When provided, the subject slot count for each day is capped so that the
 *   last subject slot's end time does not exceed this value. Activities marked
 *   isLastOfDay are placed after the last subject slot; if their end time
 *   would exceed schoolClosingTime a warning is logged but they are still
 *   included (the closing activity IS the school day's end).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Transactional
public class GenerateTimetableHandler
        implements CommandHandler<GenerateTimetableCommand, Long> {

    private static final int MAX_PHASE1_ATTEMPTS = 100;
    private static final int MAX_PHASE2_ATTEMPTS =  50;

    private final JpaClassSessionRepository  classSessionRepo;
    private final JpaClassSubjectRepository  classSubjectRepo;
    private final JpaTimetableSlotRepository slotRepo;
    private final JpaTimetableRepository     timetableRepo;

    // ─────────────────────────────────────────────────────────────────────
    //  Entry point
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public Long handle(GenerateTimetableCommand cmd) {
        Long schoolId    = SchoolIdInjector.require();
        Long currentUser = TenantContext.getUserId();

        ClassSessionEntity classSession =
                classSessionRepo.findByIdAndSchoolId(cmd.classSessionId(), schoolId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "ClassSession", cmd.classSessionId()));

        List<DayOfWeek> operatingDays = parseOperatingDays(cmd.operatingDays());
        int numDays = operatingDays.size();

        Map<Long, Set<DayOfWeek>> unavailable =
                buildUnavailabilityMap(cmd.teacherUnavailableDays());

        List<ClassSubjectEntity> classSubjects =
                classSubjectRepo.findByClassSessionIdAndSchoolId(
                        cmd.classSessionId(), schoolId);
        Map<Long, ClassSubjectEntity> subjectById = classSubjects.stream()
                .collect(Collectors.toMap(ClassSubjectEntity::getId, s -> s));

        // ── Compute the base (maximum) subject slots per day ──────────────
        // This is derived from total periods + buffer, then capped by
        // schoolClosingTime if provided.
        List<GenerateTimetableCommand.SubjectFrequency> frequencies = cmd.subjectFrequencies();
        int totalPeriods = frequencies.stream()
                .mapToInt(GenerateTimetableCommand.SubjectFrequency::periodsPerWeek)
                .sum();

        int baseSlotsPerDay = Math.min(
                (int) Math.ceil((double) totalPeriods / numDays) + 2, 14);

        // Cap by school closing time if supplied
        if (cmd.schoolClosingTime() != null) {
            int maxByTime = computeMaxSlotsByClosingTime(
                    cmd.schoolStartTime(), cmd.slotDurationMinutes(),
                    cmd.schoolClosingTime(), cmd.activities(), operatingDays);
            baseSlotsPerDay = Math.min(baseSlotsPerDay, maxByTime);
        }

        // ── Build per-day templates ───────────────────────────────────────
        // Each day template knows its own subject slot count (may be less than
        // baseSlotsPerDay when day-specific activities consume subject slots).
        Map<DayOfWeek, DayTemplate> templates =
                buildDayTemplates(cmd, operatingDays, baseSlotsPerDay);

        // Per-day slot counts (used by Phase 1 feasibility)
        Map<DayOfWeek, Integer> slotsPerDayMap = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays)
            slotsPerDayMap.put(day, templates.get(day).subjectSlotCount());

        List<Occurrence> occurrences =
                buildOccurrences(frequencies, subjectById, numDays);

        validateFeasibility(occurrences, operatingDays, slotsPerDayMap);

        List<OccupiedInterval> occupied =
                loadOccupiedIntervals(schoolId, cmd.classSessionId());

        // Use the first day's template for Phase 1 teacher-blocking checks
        DayTemplate baseTemplate = templates.get(operatingDays.get(0));

        // ── Phase 1: day assignment ───────────────────────────────────────
        Map<DayOfWeek, List<Occurrence>> dayAssignment = null;
        Random rng = new Random();

        for (int a = 0; a < MAX_PHASE1_ATTEMPTS && dayAssignment == null; a++) {
            dayAssignment = phase1DayAssign(occurrences, operatingDays, slotsPerDayMap,
                    unavailable, occupied, baseTemplate, rng);
        }

        if (dayAssignment == null) {
            throw new BusinessRuleViolationException(
                    "Could not assign subjects to days after " + MAX_PHASE1_ATTEMPTS
                            + " attempts.\n"
                            + diagnose(occurrences, subjectById, operatingDays,
                            unavailable, occupied, baseTemplate, slotsPerDayMap));
        }

        // ── Phase 2: slot placement ───────────────────────────────────────
        Map<DayOfWeek, List<PlacedSlot>> placed = null;

        for (int a = 0; a < MAX_PHASE2_ATTEMPTS && placed == null; a++) {
            placed = phase2SlotPlace(dayAssignment, operatingDays, templates,
                    subjectById, occupied, rng);
        }

        if (placed == null) {
            throw new BusinessRuleViolationException(
                    "Could not arrange slots within days after " + MAX_PHASE2_ATTEMPTS
                            + " attempts. Adjust subject frequencies or teacher assignments.");
        }

        // ── End-time balancing (only across days with equal slot counts) ──
        placed = balanceEndTimes(placed, operatingDays, slotsPerDayMap);

        // ── Persist ───────────────────────────────────────────────────────
        timetableRepo.deactivateAllForClassSession(cmd.classSessionId(), schoolId);
        int nextVersion = timetableRepo.findMaxVersionByClassSessionIdAndSchoolId(
                cmd.classSessionId(), schoolId) + 1;

        String notes = (cmd.notes() != null && !cmd.notes().isBlank())
                ? cmd.notes() : "Auto-generated v" + nextVersion;

        TimetableEntity timetable = timetableRepo.save(TimetableEntity.builder()
                .schoolId(schoolId).classSession(classSession)
                .version(nextVersion).active(true).notes(notes)
                .createdBy(currentUser).build());

        List<TimetableSlotEntity> entities =
                buildEntities(placed, operatingDays, templates, schoolId, subjectById);
        entities.forEach(s -> s.setTimetable(timetable));
        slotRepo.saveAll(entities);

        log.info("Generated timetable id={} v={} classSession={} school={}",
                timetable.getId(), nextVersion, cmd.classSessionId(), schoolId);
        return timetable.getId();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Closing-time slot cap
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Computes the maximum number of subject slots that fit before
     * {@code schoolClosingTime} on any operating day, accounting for
     * activities that apply to ALL days (which shift all slot start times).
     *
     * Day-specific activities are not considered here because per-day
     * slot counts are resolved in {@link #buildDayTemplates}; this method
     * only needs to cap the base slot count shared across all days.
     *
     * Algorithm:
     *   Simulate the day timeline using only universal activities (onlyOnDays
     *   is null/empty), inserting subject slot blocks of {@code slotMins}
     *   minutes, and count how many fit before {@code closingTime}.
     */
    private int computeMaxSlotsByClosingTime(
            LocalTime start, int slotMins, LocalTime closingTime,
            List<GenerateTimetableCommand.ActivitySpec> allActivities,
            List<DayOfWeek> operatingDays) {

        // Only universal activities matter for the base cap
        List<GenerateTimetableCommand.ActivitySpec> universalActivities =
                (allActivities == null ? List.<GenerateTimetableCommand.ActivitySpec>of() : allActivities)
                        .stream()
                        .filter(a -> a.onlyOnDays() == null || a.onlyOnDays().isEmpty())
                        .filter(a -> !Boolean.TRUE.equals(a.isLastOfDay())) // last-of-day activities come after subjects
                        .sorted(Comparator.comparingInt(
                                GenerateTimetableCommand.ActivitySpec::afterSlotNumber))
                        .collect(Collectors.toList());

        LocalTime cursor = start;
        int slots = 0;
        int actIdx = 0;
        final int MAX_SLOTS = 14;

        // Activities before slot 1
        while (actIdx < universalActivities.size()
                && universalActivities.get(actIdx).afterSlotNumber() == 0) {
            cursor = cursor.plusMinutes(universalActivities.get(actIdx++).durationMinutes());
        }

        for (int s = 1; s <= MAX_SLOTS; s++) {
            LocalTime slotEnd = cursor.plusMinutes(slotMins);
            if (!slotEnd.isAfter(closingTime)) {
                slots++;
                cursor = slotEnd;
            } else {
                break;
            }
            // Advance cursor past activities after this slot
            while (actIdx < universalActivities.size()
                    && universalActivities.get(actIdx).afterSlotNumber() == s) {
                LocalTime actEnd = cursor.plusMinutes(
                        universalActivities.get(actIdx++).durationMinutes());
                // If activity itself overflows closing time, stop counting subject slots
                if (actEnd.isAfter(closingTime)) break;
                cursor = actEnd;
            }
        }

        if (slots == 0)
            throw new BusinessRuleViolationException(
                    "schoolClosingTime " + closingTime
                            + " does not allow even one subject slot starting at "
                            + start + " with " + slotMins + "-minute slots.");
        return slots;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Day template building (per-day, day-specific activities)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds one {@link DayTemplate} per operating day.
     *
     * Universal activities (onlyOnDays null/empty) shift the clock but do not
     * change the subject slot count — every day gets the same baseSlotsPerDay.
     *
     * Day-specific activities (onlyOnDays non-empty, covering only a subset of
     * operating days) REPLACE subject slots on the days they appear on. The
     * number of slots consumed is {@code ceil(activity.durationMinutes / slotMins)}.
     * Those days therefore end up with fewer subject slots.
     *
     * Activities marked {@code isLastOfDay=true} are appended at the end of the
     * day's slot list after all subject slots, regardless of afterSlotNumber.
     */
    private Map<DayOfWeek, DayTemplate> buildDayTemplates(
            GenerateTimetableCommand cmd,
            List<DayOfWeek> operatingDays,
            int baseSlotsPerDay) {

        List<GenerateTimetableCommand.ActivitySpec> allActivities =
                cmd.activities() == null ? List.of() : cmd.activities();

        // Determine which activities are truly universal (all days)
        Set<String> operatingDayNames = operatingDays.stream()
                .map(Enum::name).collect(Collectors.toSet());

        Map<DayOfWeek, DayTemplate> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            List<GenerateTimetableCommand.ActivitySpec> dayActivities =
                    allActivities.stream()
                            .filter(a -> appliesToDay(a, day))
                            .collect(Collectors.toList());

            // Separate last-of-day from inline activities
            List<GenerateTimetableCommand.ActivitySpec> lastOfDayActivities =
                    dayActivities.stream()
                            .filter(a -> Boolean.TRUE.equals(a.isLastOfDay()))
                            .collect(Collectors.toList());

            List<GenerateTimetableCommand.ActivitySpec> inlineActivities =
                    dayActivities.stream()
                            .filter(a -> !Boolean.TRUE.equals(a.isLastOfDay()))
                            .sorted(Comparator.comparingInt(
                                    GenerateTimetableCommand.ActivitySpec::afterSlotNumber))
                            .collect(Collectors.toList());

            // Identify which inline activities are day-specific (not universal)
            List<GenerateTimetableCommand.ActivitySpec> daySpecificInline =
                    inlineActivities.stream()
                            .filter(a -> isDaySpecific(a, operatingDayNames))
                            .collect(Collectors.toList());

            // Compute how many subject slots are consumed by day-specific inline activities
            int slotsConsumedByDaySpecific = daySpecificInline.stream()
                    .mapToInt(a -> (int) Math.ceil(
                            (double) a.durationMinutes() / cmd.slotDurationMinutes()))
                    .sum();

            int effectiveSlotsPerDay = Math.max(0,
                    baseSlotsPerDay - slotsConsumedByDaySpecific);

            result.put(day, buildSingleDayTemplate(
                    cmd.schoolStartTime(), cmd.slotDurationMinutes(),
                    effectiveSlotsPerDay, inlineActivities, lastOfDayActivities,
                    cmd.schoolClosingTime()));
        }
        return result;
    }

    /**
     * Returns true when this activity does NOT apply to all operating days —
     * i.e. it has an explicit, non-empty onlyOnDays list that does not cover
     * every operating day.
     */
    private boolean isDaySpecific(
            GenerateTimetableCommand.ActivitySpec spec,
            Set<String> operatingDayNames) {
        if (spec.onlyOnDays() == null || spec.onlyOnDays().isEmpty()) return false;
        Set<String> restricted = spec.onlyOnDays().stream()
                .map(d -> d.toUpperCase().trim())
                .collect(Collectors.toSet());
        // It is day-specific if it doesn't cover ALL operating days
        return !restricted.containsAll(operatingDayNames);
    }

    /** Whether an ActivitySpec applies to a given day. */
    private boolean appliesToDay(
            GenerateTimetableCommand.ActivitySpec spec, DayOfWeek day) {
        if (spec.onlyOnDays() == null || spec.onlyOnDays().isEmpty()) return true;
        for (String d : spec.onlyOnDays()) {
            try {
                if (DayOfWeek.valueOf(d.toUpperCase().trim()) == day) return true;
            } catch (IllegalArgumentException ignored) {}
        }
        return false;
    }

    /**
     * Builds the full slot list for a single day.
     *
     * Day-specific inline activities are inserted at their anchor point
     * (afterSlotNumber) and replace the equivalent number of subject slots
     * that follow them — those subject slots simply aren't emitted.
     *
     * Last-of-day activities are appended unconditionally after all subject
     * slots (and any non-last-of-day activities), then optionally capped by
     * schoolClosingTime.
     *
     * @param slotsPerDay    effective subject slot count for this day (already
     *                       reduced by day-specific activity replacements)
     * @param inlineActivities  activities inserted inline, sorted by afterSlotNumber
     * @param lastOfDayActivities  activities appended at end of day
     * @param closingTime    optional hard cap; slots/activities beyond this are dropped
     */
    private DayTemplate buildSingleDayTemplate(
            LocalTime start, int slotMins, int slotsPerDay,
            List<GenerateTimetableCommand.ActivitySpec> inlineActivities,
            List<GenerateTimetableCommand.ActivitySpec> lastOfDayActivities,
            LocalTime closingTime) {

        LocalTime cursor = start;
        List<DaySlot> slots = new ArrayList<>();
        int actIdx = 0;
        int subjectSlotsEmitted = 0;

        // Activities anchored before all subjects (afterSlotNumber == 0)
        while (actIdx < inlineActivities.size()
                && inlineActivities.get(actIdx).afterSlotNumber() == 0) {
            var a = inlineActivities.get(actIdx++);
            LocalTime end = cursor.plusMinutes(a.durationMinutes());
            if (closingTime != null && end.isAfter(closingTime)) break;
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, a.label()));
            cursor = end;
        }

        for (int s = 1; s <= slotsPerDay; s++) {
            LocalTime end = cursor.plusMinutes(slotMins);
            if (closingTime != null && end.isAfter(closingTime)) break;
            slots.add(new DaySlot(DaySlotType.SUBJECT, cursor, end, null));
            subjectSlotsEmitted++;
            cursor = end;

            // Inline activities anchored after slot s
            while (actIdx < inlineActivities.size()
                    && inlineActivities.get(actIdx).afterSlotNumber() == s) {
                var a = inlineActivities.get(actIdx++);
                LocalTime actEnd = cursor.plusMinutes(a.durationMinutes());
                if (closingTime != null && actEnd.isAfter(closingTime)) break;
                slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, actEnd, a.label()));
                cursor = actEnd;
            }
        }

        // Remaining inline activities (afterSlotNumber > slotsPerDay → end of day)
        while (actIdx < inlineActivities.size()) {
            var a = inlineActivities.get(actIdx++);
            LocalTime end = cursor.plusMinutes(a.durationMinutes());
            if (closingTime != null && end.isAfter(closingTime)) break;
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, a.label()));
            cursor = end;
        }

        // Last-of-day activities — appended after all subject slots
        for (var a : lastOfDayActivities) {
            LocalTime end = cursor.plusMinutes(a.durationMinutes());
            // Log a warning if this overflows closing time, but still include it —
            // a last-of-day activity IS the school day's end.
            if (closingTime != null && end.isAfter(closingTime)) {
                log.warn("Last-of-day activity '{}' ends at {} which exceeds schoolClosingTime {}; "
                                + "it is still included as the terminal event of the day.",
                        a.label(), end, closingTime);
            }
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, a.label()));
            cursor = end;
        }

        return new DayTemplate(slots, subjectSlotsEmitted);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phase 1 — Day assignment
    // ─────────────────────────────────────────────────────────────────────

    private Map<DayOfWeek, List<Occurrence>> phase1DayAssign(
            List<Occurrence> occurrences, List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> slotsPerDayMap,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied, DayTemplate baseTemplate, Random rng) {

        Map<DayOfWeek, Integer> freeSlots = new LinkedHashMap<>();
        for (DayOfWeek d : operatingDays)
            freeSlots.put(d, slotsPerDayMap.getOrDefault(d, 0));

        Map<Long, Set<DayOfWeek>> subjectDays = new HashMap<>();
        Map<DayOfWeek, List<Occurrence>> result = new LinkedHashMap<>();
        for (DayOfWeek d : operatingDays) result.put(d, new ArrayList<>());

        List<Occurrence> ordered = sortByConstrainedness(
                occurrences, operatingDays, slotsPerDayMap,
                unavailable, occupied, baseTemplate, freeSlots, rng);

        return p1Backtrack(ordered, 0, result, freeSlots, subjectDays,
                operatingDays, slotsPerDayMap, unavailable, occupied, baseTemplate);
    }

    private Map<DayOfWeek, List<Occurrence>> p1Backtrack(
            List<Occurrence> occs, int idx,
            Map<DayOfWeek, List<Occurrence>> assignment,
            Map<DayOfWeek, Integer> freeSlots,
            Map<Long, Set<DayOfWeek>> subjectDays,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> slotsPerDayMap,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied, DayTemplate baseTemplate) {

        if (idx == occs.size()) return assignment;

        Occurrence occ = occs.get(idx);
        int needed = occ.isDouble() ? 2 : 1;
        Long teacherId = occ.teacherId();

        List<DayOfWeek> shuffledDays = new ArrayList<>(operatingDays);
        Collections.shuffle(shuffledDays);

        for (DayOfWeek day : shuffledDays) {
            if (subjectDays.getOrDefault(occ.classSubjectId(), Set.of()).contains(day))
                continue;
            if (teacherId != null
                    && unavailable.getOrDefault(teacherId, Set.of()).contains(day))
                continue;
            if (freeSlots.getOrDefault(day, 0) < needed) continue;
            if (teacherId != null && isTeacherFullyBlockedOnDay(
                    teacherId, day, needed, baseTemplate, occupied)) continue;

            assignment.get(day).add(occ);
            freeSlots.merge(day, -needed, Integer::sum);
            subjectDays.computeIfAbsent(occ.classSubjectId(),
                    k -> new HashSet<>()).add(day);

            var sub = p1Backtrack(occs, idx + 1, assignment, freeSlots, subjectDays,
                    operatingDays, slotsPerDayMap, unavailable, occupied, baseTemplate);
            if (sub != null) return sub;

            assignment.get(day).remove(occ);
            freeSlots.merge(day, needed, Integer::sum);
            subjectDays.get(occ.classSubjectId()).remove(day);
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phase 2 — Slot placement within each day
    // ─────────────────────────────────────────────────────────────────────

    private Map<DayOfWeek, List<PlacedSlot>> phase2SlotPlace(
            Map<DayOfWeek, List<Occurrence>> dayAssignment,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, DayTemplate> templates,
            Map<Long, ClassSubjectEntity> subjectById,
            List<OccupiedInterval> occupied, Random rng) {

        Map<DayOfWeek, List<PlacedSlot>> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            List<Occurrence> occs = dayAssignment.getOrDefault(day, List.of());
            if (occs.isEmpty()) { result.put(day, List.of()); continue; }

            List<PlacedSlot> placed = p2PlaceDay(
                    day, occs, templates.get(day), subjectById, occupied, rng);
            if (placed == null) return null;
            result.put(day, placed);
        }
        return result;
    }

    private List<PlacedSlot> p2PlaceDay(
            DayOfWeek day, List<Occurrence> occs, DayTemplate template,
            Map<Long, ClassSubjectEntity> subjectById,
            List<OccupiedInterval> occupied, Random rng) {

        int n = template.subjectSlotCount();
        Long[]    slotAssign  = new Long[n];
        boolean[] isDouble    = new boolean[n];

        Set<Integer> activityAfterSlot = activityBoundarySet(template);

        List<Occurrence> doubles = occs.stream().filter(Occurrence::isDouble)
                .collect(Collectors.toList());
        List<Occurrence> singles = occs.stream().filter(o -> !o.isDouble())
                .collect(Collectors.toList());
        Collections.shuffle(doubles, rng);
        Collections.shuffle(singles, rng);

        // ── Place doubles ─────────────────────────────────────────────────
        for (Occurrence d : doubles) {
            boolean placed = false;
            Long tid = d.teacherId();

            List<Integer> candidates = buildDoubleCandidates(n, slotAssign,
                    activityAfterSlot, tid, day, template, occupied);

            for (int pos : candidates) {
                slotAssign[pos]     = d.classSubjectId();
                slotAssign[pos + 1] = d.classSubjectId();
                isDouble[pos]       = true;
                isDouble[pos + 1]   = true;
                placed = true;
                break;
            }
            if (!placed) return null;
        }

        // ── Place singles ─────────────────────────────────────────────────
        List<Integer> freePos = new ArrayList<>();
        for (int i = 0; i < n; i++) if (slotAssign[i] == null) freePos.add(i);
        Collections.shuffle(freePos, rng);

        for (Occurrence s : singles) {
            boolean placed = false;
            Long tid = s.teacherId();
            for (int pos : freePos) {
                if (slotAssign[pos] != null) continue;
                if (tid != null && hasConflict(occupied, tid, day,
                        template.subjectSlotTime(pos))) continue;
                slotAssign[pos] = s.classSubjectId();
                placed = true;
                break;
            }
            if (!placed) return null;
        }

        // ── Soft teacher-transition reorder ───────────────────────────────
        reorderForTeacherTransition(slotAssign, isDouble, subjectById, n);

        List<PlacedSlot> result = new ArrayList<>();
        for (int i = 0; i < n; i++)
            if (slotAssign[i] != null)
                result.add(new PlacedSlot(slotAssign[i], i, isDouble[i]));
        return result;
    }

    private Set<Integer> activityBoundarySet(DayTemplate template) {
        Set<Integer> boundaries = new HashSet<>();
        int subjectIdx = -1;
        for (DaySlot ds : template.slots()) {
            if (ds.type() == DaySlotType.SUBJECT) {
                subjectIdx++;
            } else {
                if (subjectIdx >= 0) boundaries.add(subjectIdx);
            }
        }
        return boundaries;
    }

    private List<Integer> buildDoubleCandidates(
            int n, Long[] slotAssign, Set<Integer> activityAfterSlot,
            Long teacherId, DayOfWeek day, DayTemplate template,
            List<OccupiedInterval> occupied) {

        List<Integer> preferred = new ArrayList<>();
        List<Integer> normal    = new ArrayList<>();

        for (int pos = 0; pos < n - 1; pos++) {
            if (slotAssign[pos] != null || slotAssign[pos + 1] != null) continue;
            if (activityAfterSlot.contains(pos)) continue;

            if (teacherId != null) {
                if (hasConflict(occupied, teacherId, day, template.subjectSlotTime(pos))
                        || hasConflict(occupied, teacherId, day, template.subjectSlotTime(pos + 1)))
                    continue;
            }

            if (activityAfterSlot.contains(pos + 1)) {
                preferred.add(pos);
            } else {
                normal.add(pos);
            }
        }

        List<Integer> result = new ArrayList<>();
        result.addAll(preferred);
        result.addAll(normal);
        return result;
    }

    private void reorderForTeacherTransition(
            Long[] slots, boolean[] isDouble,
            Map<Long, ClassSubjectEntity> subjectById, int n) {

        boolean improved = true;
        int passes = 0;
        while (improved && passes++ < n * 2) {
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                if (slots[i] == null || slots[i + 1] == null) continue;
                if (slots[i].equals(slots[i + 1])) continue;
                Long tA = getTeacherId(subjectById.get(slots[i]));
                Long tB = getTeacherId(subjectById.get(slots[i + 1]));
                if (tA == null || !tA.equals(tB)) continue;

                if (i + 2 >= n || slots[i + 2] == null) continue;
                if (isDouble[i + 1] && slots[i + 1].equals(slots[i + 2])) continue;
                if (isDouble[i + 2] && slots[i + 1].equals(slots[i + 2])) continue;

                Long tC = getTeacherId(subjectById.get(slots[i + 2]));
                if (tC != null && !tC.equals(tA)) {
                    Long tmp = slots[i + 1]; slots[i + 1] = slots[i + 2]; slots[i + 2] = tmp;
                    boolean tmpB = isDouble[i + 1]; isDouble[i + 1] = isDouble[i + 2]; isDouble[i + 2] = tmpB;
                    improved = true;
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  End-time balancing
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Shifts subjects later in days that end too early relative to other days,
     * but only among days that share the same (maximum) subject slot count.
     *
     * Days with a reduced slot count due to day-specific activities are excluded
     * from balancing — their shorter day is intentional by design.
     */
    private Map<DayOfWeek, List<PlacedSlot>> balanceEndTimes(
            Map<DayOfWeek, List<PlacedSlot>> placed,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> slotsPerDayMap) {

        // Find the most common (maximum) slot count — only balance days with that count
        int maxSlotCount = slotsPerDayMap.values().stream()
                .mapToInt(Integer::intValue).max().orElse(0);

        Set<DayOfWeek> balancedDays = operatingDays.stream()
                .filter(d -> slotsPerDayMap.getOrDefault(d, 0) == maxSlotCount)
                .collect(Collectors.toSet());

        Map<DayOfWeek, Integer> lastIdx = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            if (!balancedDays.contains(day)) continue;
            List<PlacedSlot> ps = placed.getOrDefault(day, List.of());
            int last = ps.stream()
                    .mapToInt(PlacedSlot::position)
                    .max().orElse(-1);
            lastIdx.put(day, last);
        }

        int maxLast = lastIdx.values().stream()
                .mapToInt(Integer::intValue).max().orElse(0);

        Map<DayOfWeek, List<PlacedSlot>> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            if (!balancedDays.contains(day)) {
                // Day-specific reduced days pass through unchanged
                result.put(day, placed.getOrDefault(day, List.of()));
                continue;
            }

            int last = lastIdx.getOrDefault(day, -1);
            int gap  = maxLast - last;

            if (gap <= 2 || last < 0) {
                result.put(day, placed.getOrDefault(day, List.of()));
                continue;
            }

            int shift = gap - 2;
            List<PlacedSlot> shifted = placed.get(day).stream()
                    .map(ps -> {
                        int newPos = Math.min(ps.position() + shift, maxSlotCount - 1);
                        return new PlacedSlot(ps.classSubjectId(), newPos, ps.isDouble());
                    })
                    .collect(Collectors.toList());

            Set<Integer> usedPositions = new HashSet<>();
            List<PlacedSlot> deduped = new ArrayList<>();
            for (PlacedSlot ps : shifted) {
                int pos = ps.position();
                while (usedPositions.contains(pos) && pos > 0) pos--;
                usedPositions.add(pos);
                deduped.add(new PlacedSlot(ps.classSubjectId(), pos, ps.isDouble()));
            }
            result.put(day, deduped);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Occurrence building
    // ─────────────────────────────────────────────────────────────────────

    private List<Occurrence> buildOccurrences(
            List<GenerateTimetableCommand.SubjectFrequency> frequencies,
            Map<Long, ClassSubjectEntity> subjectById, int numDays) {

        List<Occurrence> all = new ArrayList<>();
        Set<Long> covered = new HashSet<>();

        for (var freq : frequencies) {
            Long csId = freq.classSubjectId();
            if (!subjectById.containsKey(csId))
                throw new BusinessRuleViolationException(
                        "ClassSubject id=" + csId + " does not belong to this class-session.");
            int periods = freq.periodsPerWeek();
            if (periods < 1)
                throw new BusinessRuleViolationException(
                        "periodsPerWeek must be ≥ 1 for classSubjectId=" + csId);

            ClassSubjectEntity cs = subjectById.get(csId);
            Long teacherId = getTeacherId(cs);
            boolean forceDouble = Boolean.TRUE.equals(freq.forceDouble());

            List<Occurrence> occs = deriveOccurrences(csId, teacherId, periods, forceDouble);

            if (occs.size() > numDays)
                throw new BusinessRuleViolationException(
                        "Subject id=" + csId + " needs " + occs.size()
                                + " occurrences for " + periods + " periods, but only "
                                + numDays + " operating days available.");

            all.addAll(occs);
            covered.add(csId);
        }

        List<String> omitted = subjectById.values().stream()
                .filter(cs -> !covered.contains(cs.getId()))
                .map(cs -> "'" + (cs.getSubject() != null
                        ? cs.getSubject().getName() : "id=" + cs.getId())
                        + "' (classSubjectId=" + cs.getId() + ")")
                .collect(Collectors.toList());
        if (!omitted.isEmpty())
            throw new BusinessRuleViolationException(
                    "Missing from subjectFrequencies (would be omitted): "
                            + String.join(", ", omitted));

        return all;
    }

    private List<Occurrence> deriveOccurrences(
            Long csId, Long teacherId, int periods, boolean forceDouble) {

        List<Occurrence> result = new ArrayList<>();
        if (periods == 1) {
            result.add(new Occurrence(csId, teacherId, false));
        } else if (periods == 2) {
            if (forceDouble) {
                result.add(new Occurrence(csId, teacherId, true));
            } else {
                result.add(new Occurrence(csId, teacherId, false));
                result.add(new Occurrence(csId, teacherId, false));
            }
        } else {
            int doubles = periods / 2;
            int singles = periods % 2;
            for (int i = 0; i < doubles; i++)
                result.add(new Occurrence(csId, teacherId, true));
            for (int i = 0; i < singles; i++)
                result.add(new Occurrence(csId, teacherId, false));
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Entity conversion
    // ─────────────────────────────────────────────────────────────────────

    private List<TimetableSlotEntity> buildEntities(
            Map<DayOfWeek, List<PlacedSlot>> placed,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, DayTemplate> templates,
            Long schoolId,
            Map<Long, ClassSubjectEntity> subjectById) {

        List<TimetableSlotEntity> result = new ArrayList<>();
        int sortOrder = 1;

        for (DayOfWeek day : operatingDays) {
            DayTemplate template = templates.get(day);
            Map<Integer, Long> posToSubject = new HashMap<>();
            for (PlacedSlot ps : placed.getOrDefault(day, List.of()))
                posToSubject.put(ps.position(), ps.classSubjectId());

            int subjectSlotIdx = 0;
            for (DaySlot ds : template.slots()) {
                if (ds.type() == DaySlotType.ACTIVITY) {
                    result.add(TimetableSlotEntity.builder()
                            .schoolId(schoolId).dayOfWeek(day)
                            .startTime(ds.start()).endTime(ds.end())
                            .slotType(SlotType.ACTIVITY)
                            .activityLabel(ds.label())
                            .sortOrder(sortOrder++).build());
                } else {
                    Long csId = posToSubject.get(subjectSlotIdx);
                    if (csId != null) {
                        result.add(TimetableSlotEntity.builder()
                                .schoolId(schoolId).dayOfWeek(day)
                                .startTime(ds.start()).endTime(ds.end())
                                .slotType(SlotType.SUBJECT)
                                .classSubject(subjectById.get(csId))
                                .sortOrder(sortOrder++).build());
                    }
                    subjectSlotIdx++;
                }
            }
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private List<Occurrence> sortByConstrainedness(
            List<Occurrence> occs, List<DayOfWeek> days,
            Map<DayOfWeek, Integer> slotsPerDayMap,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied, DayTemplate baseTemplate,
            Map<DayOfWeek, Integer> freeSlots, Random rng) {

        List<Occurrence> copy = new ArrayList<>(occs);
        Collections.shuffle(copy, rng);
        copy.sort(Comparator.comparingInt(o -> {
            int needed = o.isDouble() ? 2 : 1;
            Long tid = o.teacherId();
            int legal = 0;
            for (DayOfWeek d : days) {
                if (freeSlots.getOrDefault(d, 0) < needed) continue;
                if (tid != null) {
                    if (unavailable.getOrDefault(tid, Set.of()).contains(d)) continue;
                    if (isTeacherFullyBlockedOnDay(tid, d, needed, baseTemplate, occupied))
                        continue;
                }
                legal++;
            }
            return legal;
        }));
        return copy;
    }

    private boolean isTeacherFullyBlockedOnDay(
            Long teacherId, DayOfWeek day, int needed,
            DayTemplate template, List<OccupiedInterval> occupied) {
        int free = 0;
        for (int i = 0; i < template.subjectSlotCount(); i++) {
            if (!hasConflict(occupied, teacherId, day, template.subjectSlotTime(i)))
                free++;
            if (free >= needed) return false;
        }
        return true;
    }

    private boolean hasConflict(List<OccupiedInterval> occupied,
                                Long teacherId, DayOfWeek day, SlotTime t) {
        for (OccupiedInterval oi : occupied) {
            if (!oi.teacherId().equals(teacherId) || oi.day() != day) continue;
            if (oi.start().isBefore(t.end()) && oi.end().isAfter(t.start())) return true;
        }
        return false;
    }

    private List<OccupiedInterval> loadOccupiedIntervals(
            Long schoolId, Long excludeClassSessionId) {
        return slotRepo.findAllActiveSubjectSlotsForSchoolExcludingClass(
                        schoolId, excludeClassSessionId).stream()
                .filter(s -> s.getClassSubject() != null
                        && s.getClassSubject().getTeacherAssignment() != null)
                .map(s -> new OccupiedInterval(
                        s.getClassSubject().getTeacherAssignment().getTeacher().getId(),
                        s.getDayOfWeek(), s.getStartTime(), s.getEndTime()))
                .collect(Collectors.toList());
    }

    private void validateFeasibility(
            List<Occurrence> occs, List<DayOfWeek> days,
            Map<DayOfWeek, Integer> slotsPerDayMap) {

        int total = occs.stream().mapToInt(o -> o.isDouble() ? 2 : 1).sum();
        int avail = slotsPerDayMap.values().stream().mapToInt(Integer::intValue).sum();
        if (total > avail)
            throw new BusinessRuleViolationException(
                    "Total periods (" + total + ") exceed available slots across all days ("
                            + avail + "). Some days may have reduced slots due to day-specific activities.");
    }

    private List<DayOfWeek> parseOperatingDays(List<String> raw) {
        List<DayOfWeek> result = new ArrayList<>();
        for (String d : raw) {
            try { result.add(DayOfWeek.valueOf(d.toUpperCase().trim())); }
            catch (IllegalArgumentException e) {
                throw new BusinessRuleViolationException("Invalid day: '" + d + "'");
            }
        }
        if (result.isEmpty())
            throw new BusinessRuleViolationException("At least one operating day is required.");
        return result;
    }

    private Map<Long, Set<DayOfWeek>> buildUnavailabilityMap(
            List<GenerateTimetableCommand.TeacherUnavailability> specs) {
        if (specs == null) return Map.of();
        Map<Long, Set<DayOfWeek>> map = new HashMap<>();
        for (var spec : specs) {
            Set<DayOfWeek> days = new HashSet<>();
            for (String d : spec.unavailableDays()) {
                try { days.add(DayOfWeek.valueOf(d.toUpperCase().trim())); }
                catch (IllegalArgumentException e) {
                    throw new BusinessRuleViolationException(
                            "Invalid day for teacher " + spec.teacherId() + ": '" + d + "'");
                }
            }
            map.put(spec.teacherId(), days);
        }
        return map;
    }

    private static Long getTeacherId(ClassSubjectEntity s) {
        if (s == null || s.getTeacherAssignment() == null
                || s.getTeacherAssignment().getTeacher() == null) return null;
        return s.getTeacherAssignment().getTeacher().getId();
    }

    private String diagnose(
            List<Occurrence> occs, Map<Long, ClassSubjectEntity> subjectById,
            List<DayOfWeek> days, Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied, DayTemplate template,
            Map<DayOfWeek, Integer> slotsPerDayMap) {

        StringBuilder sb = new StringBuilder();
        Set<Long> seen = new HashSet<>();
        for (Occurrence o : occs) {
            if (!seen.add(o.classSubjectId())) continue;
            ClassSubjectEntity cs = subjectById.get(o.classSubjectId());
            String name = cs.getSubject() != null
                    ? cs.getSubject().getName() : "id=" + o.classSubjectId();
            Long tid = o.teacherId();
            if (tid == null) { sb.append("• '").append(name).append("' has no teacher.\n"); continue; }
            long free = days.stream()
                    .filter(d -> !unavailable.getOrDefault(tid, Set.of()).contains(d))
                    .filter(d -> !isTeacherFullyBlockedOnDay(tid, d, 1, template, occupied))
                    .count();
            if (free == 0)
                sb.append("• Teacher of '").append(name)
                        .append("' has no available days.\n");
        }
        if (sb.isEmpty())
            sb.append("Reduce periodsPerWeek, add more days, increase available slots, "
                    + "or vary teacher assignments. Note: day-specific activities may have "
                    + "reduced available subject slots on some days.");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Value types
    // ─────────────────────────────────────────────────────────────────────

    private record Occurrence(Long classSubjectId, Long teacherId, boolean isDouble) {}

    private record OccupiedInterval(
            Long teacherId, DayOfWeek day, LocalTime start, LocalTime end) {}

    private enum DaySlotType { SUBJECT, ACTIVITY }

    private record DaySlot(DaySlotType type, LocalTime start, LocalTime end, String label) {}

    private record DayTemplate(List<DaySlot> slots, int subjectSlotCount) {
        SlotTime subjectSlotTime(int idx) {
            int count = 0;
            for (DaySlot s : slots) {
                if (s.type() == DaySlotType.SUBJECT) {
                    if (count == idx) return new SlotTime(s.start(), s.end());
                    count++;
                }
            }
            throw new IllegalArgumentException("slotIdx " + idx + " out of range");
        }
    }

    private record SlotTime(LocalTime start, LocalTime end) {}

    private record PlacedSlot(Long classSubjectId, int position, boolean isDouble) {}
}