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
 * ── OCCURRENCE STRUCTURE ─────────────────────────────────────────────────
 *  periods=1    → 1 single
 *  periods=2    → 2 singles on different days (default) OR 1 double (forceDouble=true)
 *  periods=3    → 1 double + 1 single on different days
 *  periods=4    → 2 doubles on different days
 *  periods≥5    → max doubles, remaining singles, each occurrence on distinct day
 *
 * ── ACTIVITY SEMANTICS ───────────────────────────────────────────────────
 *  Global (onlyOnDays null/empty):
 *    Appears on ALL days. Does NOT replace subject slots — it shifts their
 *    clock times. slotsPerDay is unaffected by global activities.
 *
 *  Day-specific (onlyOnDays populated):
 *    Appears ONLY on those days AND replaces subject slots on those days.
 *    The activity duration is carved out of the subject-slot budget:
 *      replacedSlots = floor(durationMinutes / slotDurationMinutes)
 *    Those days have a lower slotsPerDay than others.
 *    Phase 1 tracks per-day free-slot capacity accordingly.
 *
 *  isLastOfDay=true: activity is always placed at end of day, after all
 *    subject slots, regardless of afterSlotNumber.
 *
 * ── CLOSING TIME ─────────────────────────────────────────────────────────
 *  When schoolClosingTime is provided, slotsPerDay for each day is derived as:
 *    floor((closingTime - startTime - globalActivityMinutes
 *           - daySpecificActivityMinutes_for_this_day) / slotDurationMinutes)
 *  When null, uses ceil(totalPeriods/numDays)+2 slack.
 *
 * ── DOUBLE PLACEMENT AROUND ACTIVITIES ───────────────────────────────────
 *  Double periods are never split by an activity. If consecutive positions
 *  (pos, pos+1) have an activity between them in the template, that pair is
 *  skipped. Positions immediately before an activity are preferred.
 *
 * ── END-TIME BALANCING ───────────────────────────────────────────────────
 *  After Phase 2, days ending more than 2 subject-slots earlier than the
 *  latest day have their subjects shifted later to reduce the gap to ≤ 2.
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

        Map<Long, Set<DayOfWeek>> unavailable =
                buildUnavailabilityMap(cmd.teacherUnavailableDays());

        List<ClassSubjectEntity> classSubjects =
                classSubjectRepo.findByClassSessionIdAndSchoolId(
                        cmd.classSessionId(), schoolId);
        Map<Long, ClassSubjectEntity> subjectById = classSubjects.stream()
                .collect(Collectors.toMap(ClassSubjectEntity::getId, s -> s));

        List<Occurrence> occurrences =
                buildOccurrences(cmd.subjectFrequencies(), subjectById,
                        operatingDays.size());

        // Compute per-day slot capacity
        Map<DayOfWeek, Integer> perDaySlots =
                computePerDaySlots(cmd, operatingDays, occurrences);

        // Build per-day templates (activities, clock times)
        Map<DayOfWeek, DayTemplate> templates =
                buildDayTemplates(cmd, operatingDays, perDaySlots);

        validateFeasibility(occurrences, operatingDays, perDaySlots);

        List<OccupiedInterval> occupied =
                loadOccupiedIntervals(schoolId, cmd.classSessionId());

        DayTemplate baseTemplate = templates.get(operatingDays.get(0));

        // ── Phase 1: day assignment ───────────────────────────────────────
        Map<DayOfWeek, List<Occurrence>> dayAssignment = null;
        Random rng = new Random();

        for (int a = 0; a < MAX_PHASE1_ATTEMPTS && dayAssignment == null; a++) {
            dayAssignment = phase1DayAssign(
                    occurrences, operatingDays, perDaySlots,
                    unavailable, occupied, baseTemplate, rng);
        }

        if (dayAssignment == null) {
            int minSlots = perDaySlots.values().stream()
                    .mapToInt(Integer::intValue).min().orElse(0);
            throw new BusinessRuleViolationException(
                    "Could not assign subjects to days after " + MAX_PHASE1_ATTEMPTS
                            + " attempts.\n"
                            + diagnose(occurrences, subjectById, operatingDays,
                            unavailable, occupied, baseTemplate, minSlots));
        }

        // ── Phase 2: slot placement within each day ───────────────────────
        Map<DayOfWeek, List<PlacedSlot>> placed = null;
        for (int a = 0; a < MAX_PHASE2_ATTEMPTS && placed == null; a++) {
            placed = phase2SlotPlace(dayAssignment, operatingDays, templates,
                    subjectById, occupied, rng);
        }

        if (placed == null)
            throw new BusinessRuleViolationException(
                    "Could not arrange slots within days after " + MAX_PHASE2_ATTEMPTS
                            + " attempts. Adjust subject frequencies or teacher assignments.");

        // ── End-time balancing ────────────────────────────────────────────
        placed = balanceEndTimes(placed, operatingDays, perDaySlots);

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
    //  Per-day slot computation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Computes how many subject slots each operating day has.
     *
     * Global activities (onlyOnDays null/empty):
     *   Their duration is subtracted from ALL days when closingTime is given,
     *   but they do NOT reduce slotsPerDay — they shift clock times only.
     *
     * Day-specific activities (onlyOnDays populated):
     *   They REPLACE subject slots on their days:
     *     reducedSlots = floor(durationMinutes / slotDurationMinutes)
     *   Those days get slotsPerDay - reducedSlots.
     *
     * When schoolClosingTime is provided:
     *   Base slots per day = floor(
     *     (closingTime - startTime - globalActivityMinutes) / slotDurationMinutes)
     *   Then subtract day-specific replacements per day.
     *
     * When schoolClosingTime is null:
     *   Base = ceil(totalPeriods / numDays) + 2 slack, then subtract day-specific.
     */
    private Map<DayOfWeek, Integer> computePerDaySlots(
            GenerateTimetableCommand cmd,
            List<DayOfWeek> operatingDays,
            List<Occurrence> occurrences) {

        List<GenerateTimetableCommand.ActivitySpec> activities =
                cmd.activities() == null ? List.of() : cmd.activities();

        int slotMins = cmd.slotDurationMinutes();
        int numDays  = operatingDays.size();

        // Total global activity minutes (applies to all days, shifts clocks, not slots)
        int globalActivityMins = activities.stream()
                .filter(a -> !a.isDaySpecific())
                .mapToInt(GenerateTimetableCommand.ActivitySpec::durationMinutes)
                .sum();

        int baseSlots;
        if (cmd.schoolClosingTime() != null) {
            long totalDayMins = java.time.Duration.between(
                    cmd.schoolStartTime(), cmd.schoolClosingTime()).toMinutes();
            if (totalDayMins <= 0)
                throw new BusinessRuleViolationException(
                        "schoolClosingTime must be after schoolStartTime.");
            long availableMins = totalDayMins - globalActivityMins;
            if (availableMins <= 0)
                throw new BusinessRuleViolationException(
                        "Global activities consume the entire school day. "
                                + "Reduce global activity durations or extend the closing time.");
            baseSlots = (int) (availableMins / slotMins);
        } else {
            int totalPeriods = occurrences.stream()
                    .mapToInt(o -> o.isDouble() ? 2 : 1).sum();
            baseSlots = Math.min((int) Math.ceil((double) totalPeriods / numDays) + 2, 14);
        }

        // Apply day-specific slot replacements
        Map<DayOfWeek, Integer> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            int replaced = activities.stream()
                    .filter(GenerateTimetableCommand.ActivitySpec::isDaySpecific)
                    .filter(a -> appliesToDay(a, day))
                    .mapToInt(a -> a.durationMinutes() / slotMins)
                    .sum();
            int slots = Math.max(baseSlots - replaced, 1); // at least 1 slot
            result.put(day, slots);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Day template building
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds one DayTemplate per operating day.
     *
     * Global activities: inserted at their afterSlotNumber position in every day.
     * Day-specific activities: inserted only in their days, at their position,
     *   with the subject-slot count already reduced in perDaySlots.
     * isLastOfDay activities: always appended at the very end.
     */
    private Map<DayOfWeek, DayTemplate> buildDayTemplates(
            GenerateTimetableCommand cmd,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> perDaySlots) {

        List<GenerateTimetableCommand.ActivitySpec> all =
                cmd.activities() == null ? List.of() : cmd.activities();

        Map<DayOfWeek, DayTemplate> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            int slotsThisDay = perDaySlots.get(day);

            // Activities for this day, sorted: non-last by afterSlotNumber, last ones at end
            List<GenerateTimetableCommand.ActivitySpec> dayActivities = all.stream()
                    .filter(a -> appliesToDay(a, day))
                    .sorted(Comparator
                            .comparingInt((GenerateTimetableCommand.ActivitySpec a) ->
                                    a.isLast() ? Integer.MAX_VALUE : a.afterSlotNumber())
                            .thenComparingInt(
                                    GenerateTimetableCommand.ActivitySpec::durationMinutes))
                    .collect(Collectors.toList());

            result.put(day, buildSingleDayTemplate(
                    cmd.schoolStartTime(), cmd.slotDurationMinutes(),
                    slotsThisDay, dayActivities));
        }
        return result;
    }

    private DayTemplate buildSingleDayTemplate(
            LocalTime start, int slotMins, int slotsPerDay,
            List<GenerateTimetableCommand.ActivitySpec> activities) {

        LocalTime cursor = start;
        List<DaySlot> slots = new ArrayList<>();

        // Separate isLastOfDay activities — they go at the end unconditionally
        List<GenerateTimetableCommand.ActivitySpec> lastActivities = activities.stream()
                .filter(GenerateTimetableCommand.ActivitySpec::isLast)
                .collect(Collectors.toList());
        List<GenerateTimetableCommand.ActivitySpec> positioned = activities.stream()
                .filter(a -> !a.isLast())
                .sorted(Comparator.comparingInt(
                        GenerateTimetableCommand.ActivitySpec::afterSlotNumber))
                .collect(Collectors.toList());

        int actIdx = 0;

        // Activities before all subjects (afterSlotNumber == 0)
        while (actIdx < positioned.size()
                && positioned.get(actIdx).afterSlotNumber() == 0) {
            var a = positioned.get(actIdx++);
            LocalTime end = cursor.plusMinutes(a.durationMinutes());
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, a.label()));
            cursor = end;
        }

        // Subject slots interleaved with positioned activities
        for (int s = 1; s <= slotsPerDay; s++) {
            LocalTime end = cursor.plusMinutes(slotMins);
            slots.add(new DaySlot(DaySlotType.SUBJECT, cursor, end, null));
            cursor = end;
            while (actIdx < positioned.size()
                    && positioned.get(actIdx).afterSlotNumber() == s) {
                var a = positioned.get(actIdx++);
                LocalTime actEnd = cursor.plusMinutes(a.durationMinutes());
                slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, actEnd, a.label()));
                cursor = actEnd;
            }
        }

        // Remaining positioned activities (afterSlotNumber > slotsPerDay → append)
        while (actIdx < positioned.size()) {
            var a = positioned.get(actIdx++);
            LocalTime end = cursor.plusMinutes(a.durationMinutes());
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, a.label()));
            cursor = end;
        }

        // isLastOfDay activities — always after everything else
        for (var a : lastActivities) {
            LocalTime end = cursor.plusMinutes(a.durationMinutes());
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, a.label()));
            cursor = end;
        }

        return new DayTemplate(slots, slotsPerDay);
    }

    private boolean appliesToDay(
            GenerateTimetableCommand.ActivitySpec spec, DayOfWeek day) {
        if (!spec.isDaySpecific()) return true;
        for (String d : spec.onlyOnDays()) {
            try { if (DayOfWeek.valueOf(d.toUpperCase().trim()) == day) return true; }
            catch (IllegalArgumentException ignored) {}
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phase 1 — Day assignment
    // ─────────────────────────────────────────────────────────────────────

    private Map<DayOfWeek, List<Occurrence>> phase1DayAssign(
            List<Occurrence> occurrences, List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> perDaySlots,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied,
            DayTemplate baseTemplate, Random rng) {

        Map<DayOfWeek, Integer> freeSlots = new LinkedHashMap<>(perDaySlots);
        Map<Long, Set<DayOfWeek>> subjectDays = new HashMap<>();
        Map<DayOfWeek, List<Occurrence>> result = new LinkedHashMap<>();
        for (DayOfWeek d : operatingDays) result.put(d, new ArrayList<>());

        List<Occurrence> ordered = sortByConstrainedness(
                occurrences, operatingDays, perDaySlots,
                unavailable, occupied, baseTemplate, rng);

        return p1Backtrack(ordered, 0, result, freeSlots, subjectDays,
                operatingDays, perDaySlots, unavailable, occupied, baseTemplate);
    }

    private Map<DayOfWeek, List<Occurrence>> p1Backtrack(
            List<Occurrence> occs, int idx,
            Map<DayOfWeek, List<Occurrence>> assignment,
            Map<DayOfWeek, Integer> freeSlots,
            Map<Long, Set<DayOfWeek>> subjectDays,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> perDaySlots,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied,
            DayTemplate baseTemplate) {

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
                    operatingDays, perDaySlots, unavailable, occupied, baseTemplate);
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
        Long[]    slotAssign = new Long[n];
        boolean[] isDouble   = new boolean[n];

        Set<Integer> activityBoundaries = activityBoundarySet(template);

        List<Occurrence> doubles = occs.stream().filter(Occurrence::isDouble)
                .collect(Collectors.toList());
        List<Occurrence> singles = occs.stream().filter(o -> !o.isDouble())
                .collect(Collectors.toList());
        Collections.shuffle(doubles, rng);
        Collections.shuffle(singles, rng);

        // Place doubles first — prefer positions just before an activity boundary
        for (Occurrence d : doubles) {
            Long tid = d.teacherId();
            List<Integer> candidates = buildDoubleCandidates(
                    n, slotAssign, activityBoundaries, tid, day, template, occupied);
            if (candidates.isEmpty()) return null;
            int pos = candidates.get(0);
            slotAssign[pos]     = d.classSubjectId();
            slotAssign[pos + 1] = d.classSubjectId();
            isDouble[pos]       = true;
            isDouble[pos + 1]   = true;
        }

        // Place singles
        List<Integer> freePos = new ArrayList<>();
        for (int i = 0; i < n; i++) if (slotAssign[i] == null) freePos.add(i);
        Collections.shuffle(freePos, rng);

        for (Occurrence s : singles) {
            boolean placed = false;
            Long tid = s.teacherId();
            for (int pos : freePos) {
                if (slotAssign[pos] != null) continue;
                if (tid != null && hasConflict(
                        occupied, tid, day, template.subjectSlotTime(pos))) continue;
                slotAssign[pos] = s.classSubjectId();
                placed = true;
                break;
            }
            if (!placed) return null;
        }

        reorderForTeacherTransition(slotAssign, isDouble, subjectById, n);

        List<PlacedSlot> result = new ArrayList<>();
        for (int i = 0; i < n; i++)
            if (slotAssign[i] != null)
                result.add(new PlacedSlot(slotAssign[i], i, isDouble[i]));
        return result;
    }

    /**
     * Returns subject-slot indices where an activity appears immediately after
     * (i.e. between slot i and slot i+1 in the template sequence).
     * Double periods must never span such a boundary.
     */
    private Set<Integer> activityBoundarySet(DayTemplate template) {
        Set<Integer> boundaries = new HashSet<>();
        int subIdx = -1;
        for (DaySlot ds : template.slots()) {
            if (ds.type() == DaySlotType.SUBJECT) {
                subIdx++;
            } else if (subIdx >= 0) {
                boundaries.add(subIdx); // activity after subject slot subIdx
            }
        }
        return boundaries;
    }

    /**
     * Ordered list of valid starting positions for a double period.
     * Positions crossing an activity boundary are excluded.
     * Positions immediately before a boundary are preferred (double fits
     * cleanly before a break rather than being split).
     */
    private List<Integer> buildDoubleCandidates(
            int n, Long[] slotAssign, Set<Integer> activityBoundaries,
            Long teacherId, DayOfWeek day, DayTemplate template,
            List<OccupiedInterval> occupied) {

        List<Integer> preferred = new ArrayList<>();
        List<Integer> normal    = new ArrayList<>();

        for (int pos = 0; pos < n - 1; pos++) {
            if (slotAssign[pos] != null || slotAssign[pos + 1] != null) continue;
            if (activityBoundaries.contains(pos)) continue; // boundary between pos and pos+1
            if (teacherId != null) {
                if (hasConflict(occupied, teacherId, day, template.subjectSlotTime(pos))
                        || hasConflict(occupied, teacherId, day, template.subjectSlotTime(pos + 1)))
                    continue;
            }
            if (activityBoundaries.contains(pos + 1)) preferred.add(pos);
            else                                       normal.add(pos);
        }
        List<Integer> result = new ArrayList<>(preferred);
        result.addAll(normal);
        return result;
    }

    /** Soft teacher-transition improvement via bubble-sort-style swaps. */
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
     * Shifts subjects later in days that end too early.
     * Gap tolerance: ≤ 2 subject-slot positions.
     * Days ending more than 2 slots below the maximum are shifted forward.
     */
    private Map<DayOfWeek, List<PlacedSlot>> balanceEndTimes(
            Map<DayOfWeek, List<PlacedSlot>> placed,
            List<DayOfWeek> operatingDays,
            Map<DayOfWeek, Integer> perDaySlots) {

        Map<DayOfWeek, Integer> lastIdx = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            List<PlacedSlot> ps = placed.getOrDefault(day, List.of());
            lastIdx.put(day, ps.stream().mapToInt(PlacedSlot::position).max().orElse(-1));
        }

        int maxLast = lastIdx.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        Map<DayOfWeek, List<PlacedSlot>> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            int last = lastIdx.getOrDefault(day, -1);
            int gap  = maxLast - last;
            int dayMax = perDaySlots.getOrDefault(day, maxLast + 1) - 1;

            if (gap <= 2 || last < 0) {
                result.put(day, placed.getOrDefault(day, List.of()));
                continue;
            }

            int shift = Math.min(gap - 2, dayMax - last);
            if (shift <= 0) { result.put(day, placed.getOrDefault(day, List.of())); continue; }

            Set<Integer> used = new HashSet<>();
            List<PlacedSlot> shifted = new ArrayList<>();
            for (PlacedSlot ps : placed.get(day)) {
                int pos = Math.min(ps.position() + shift, dayMax);
                while (used.contains(pos) && pos > 0) pos--;
                used.add(pos);
                shifted.add(new PlacedSlot(ps.classSubjectId(), pos, ps.isDouble()));
            }
            result.put(day, shifted);
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
                        "ClassSubject id=" + csId + " not in this class-session.");
            int periods = freq.periodsPerWeek();
            if (periods < 1)
                throw new BusinessRuleViolationException(
                        "periodsPerWeek ≥ 1 required for classSubjectId=" + csId);

            ClassSubjectEntity cs = subjectById.get(csId);
            boolean forceDouble = Boolean.TRUE.equals(freq.forceDouble());
            List<Occurrence> occs = deriveOccurrences(
                    csId, getTeacherId(cs), periods, forceDouble);

            if (occs.size() > numDays)
                throw new BusinessRuleViolationException(
                        "Subject id=" + csId + " needs " + occs.size()
                                + " occurrences but only " + numDays + " days available.");

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
                    "Missing from subjectFrequencies: " + String.join(", ", omitted));

        return all;
    }

    private List<Occurrence> deriveOccurrences(
            Long csId, Long teacherId, int periods, boolean forceDouble) {

        List<Occurrence> r = new ArrayList<>();
        if (periods == 1) {
            r.add(new Occurrence(csId, teacherId, false));
        } else if (periods == 2) {
            if (forceDouble) {
                r.add(new Occurrence(csId, teacherId, true));
            } else {
                r.add(new Occurrence(csId, teacherId, false));
                r.add(new Occurrence(csId, teacherId, false));
            }
        } else {
            for (int i = 0; i < periods / 2; i++)
                r.add(new Occurrence(csId, teacherId, true));
            for (int i = 0; i < periods % 2; i++)
                r.add(new Occurrence(csId, teacherId, false));
        }
        return r;
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
            Map<DayOfWeek, Integer> perDaySlots,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied,
            DayTemplate baseTemplate, Random rng) {

        List<Occurrence> copy = new ArrayList<>(occs);
        Collections.shuffle(copy, rng);
        copy.sort(Comparator.comparingInt(o -> {
            int needed = o.isDouble() ? 2 : 1;
            Long tid = o.teacherId();
            int legal = 0;
            for (DayOfWeek d : days) {
                if (perDaySlots.getOrDefault(d, 0) < needed) continue;
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
            Map<DayOfWeek, Integer> perDaySlots) {
        int total = occs.stream().mapToInt(o -> o.isDouble() ? 2 : 1).sum();
        int avail = perDaySlots.values().stream().mapToInt(Integer::intValue).sum();
        if (total > avail)
            throw new BusinessRuleViolationException(
                    "Total periods (" + total + ") exceed total available slots ("
                            + avail + ") across all days. Reduce periodsPerWeek, "
                            + "extend closing time, or reduce day-specific activity durations.");
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
            List<OccupiedInterval> occupied, DayTemplate template, int slotsPerDay) {

        StringBuilder sb = new StringBuilder();
        Set<Long> seen = new HashSet<>();
        for (Occurrence o : occs) {
            if (!seen.add(o.classSubjectId())) continue;
            ClassSubjectEntity cs = subjectById.get(o.classSubjectId());
            String name = cs.getSubject() != null
                    ? cs.getSubject().getName() : "id=" + o.classSubjectId();
            Long tid = o.teacherId();
            if (tid == null) {
                sb.append("• '").append(name).append("' has no teacher assigned.\n");
                continue;
            }
            long free = days.stream()
                    .filter(d -> !unavailable.getOrDefault(tid, Set.of()).contains(d))
                    .filter(d -> !isTeacherFullyBlockedOnDay(
                            tid, d, 1, template, occupied))
                    .count();
            if (free == 0)
                sb.append("• Teacher of '").append(name)
                        .append("' has no available days.\n");
        }
        if (sb.isEmpty())
            sb.append("Reduce periodsPerWeek, add more days, or vary teacher assignments.");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Value types
    // ─────────────────────────────────────────────────────────────────────

    private record Occurrence(Long classSubjectId, Long teacherId, boolean isDouble) {}
    private record OccupiedInterval(
            Long teacherId, DayOfWeek day, LocalTime start, LocalTime end) {}
    private enum DaySlotType { SUBJECT, ACTIVITY }
    private record DaySlot(
            DaySlotType type, LocalTime start, LocalTime end, String label) {}
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
