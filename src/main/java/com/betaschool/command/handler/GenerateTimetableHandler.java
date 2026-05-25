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
 *   DA3  Day has enough free subject-slots
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
 *
 * ── DAY-SPECIFIC ACTIVITIES ─────────────────────────────────────────────
 *   ActivitySpec.onlyOnDays restricts an activity to specific days.
 *   Each day gets its own DayTemplate computed independently; subject slot
 *   COUNT is always the same across all days.
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

        List<Occurrence> occurrences =
                buildOccurrences(cmd.subjectFrequencies(), subjectById, numDays);

        int totalPeriods = occurrences.stream()
                .mapToInt(o -> o.isDouble() ? 2 : 1).sum();

        // Compute slotsPerDay (consistent across all days)
        int slotsPerDay = Math.min((int) Math.ceil((double) totalPeriods / numDays) + 2, 14);

        // Build per-day templates (activities may differ by day)
        Map<DayOfWeek, DayTemplate> templates =
                buildDayTemplates(cmd, operatingDays, slotsPerDay);

        validateFeasibility(occurrences, operatingDays, slotsPerDay);

        List<OccupiedInterval> occupied =
                loadOccupiedIntervals(schoolId, cmd.classSessionId());

        // Use the first day's template for Phase 1 feasibility checks
        // (subject slot times are the same or very similar across days)
        DayTemplate baseTemplate = templates.get(operatingDays.get(0));

        // ── Phase 1: day assignment ───────────────────────────────────────
        Map<DayOfWeek, List<Occurrence>> dayAssignment = null;
        Random rng = new Random();

        for (int a = 0; a < MAX_PHASE1_ATTEMPTS && dayAssignment == null; a++) {
            dayAssignment = phase1DayAssign(occurrences, operatingDays, slotsPerDay,
                    unavailable, occupied, baseTemplate, rng);
        }

        if (dayAssignment == null) {
            throw new BusinessRuleViolationException(
                    "Could not assign subjects to days after " + MAX_PHASE1_ATTEMPTS
                            + " attempts.\n"
                            + diagnose(occurrences, subjectById, operatingDays,
                            unavailable, occupied, baseTemplate, slotsPerDay));
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

        // ── End-time balancing ────────────────────────────────────────────
        placed = balanceEndTimes(placed, operatingDays, slotsPerDay);

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
    //  Day template building (per-day, day-specific activities)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds one DayTemplate per operating day.
     * The subject slot COUNT (slotsPerDay) is identical for every day.
     * Day-specific activities only appear in the template for their days,
     * shifting those days' clock times but not the slot count.
     */
    private Map<DayOfWeek, DayTemplate> buildDayTemplates(
            GenerateTimetableCommand cmd,
            List<DayOfWeek> operatingDays,
            int slotsPerDay) {

        // Pre-parse onlyOnDays for each activity spec
        List<GenerateTimetableCommand.ActivitySpec> allActivities =
                cmd.activities() == null ? List.of() : cmd.activities();

        Map<DayOfWeek, DayTemplate> result = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
            // Filter activities that apply to this day
            List<GenerateTimetableCommand.ActivitySpec> dayActivities =
                    allActivities.stream()
                            .filter(a -> appliesToDay(a, day))
                            .sorted(Comparator.comparingInt(
                                    GenerateTimetableCommand.ActivitySpec::afterSlotNumber))
                            .collect(Collectors.toList());

            result.put(day, buildSingleDayTemplate(
                    cmd.schoolStartTime(), cmd.slotDurationMinutes(),
                    slotsPerDay, dayActivities));
        }
        return result;
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

    private DayTemplate buildSingleDayTemplate(
            LocalTime start, int slotMins, int slotsPerDay,
            List<GenerateTimetableCommand.ActivitySpec> activities) {

        LocalTime cursor = start;
        List<DaySlot> slots = new ArrayList<>();
        int actIdx = 0;

        // Activities anchored before all subjects (afterSlotNumber == 0)
        while (actIdx < activities.size()
                && activities.get(actIdx).afterSlotNumber() == 0) {
            var a = activities.get(actIdx++);
            LocalTime end = cursor.plusMinutes(a.durationMinutes());
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, a.label()));
            cursor = end;
        }

        for (int s = 1; s <= slotsPerDay; s++) {
            LocalTime end = cursor.plusMinutes(slotMins);
            slots.add(new DaySlot(DaySlotType.SUBJECT, cursor, end, null));
            cursor = end;
            // Activities anchored after slot s
            while (actIdx < activities.size()
                    && activities.get(actIdx).afterSlotNumber() == s) {
                var a = activities.get(actIdx++);
                LocalTime actEnd = cursor.plusMinutes(a.durationMinutes());
                slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, actEnd, a.label()));
                cursor = actEnd;
            }
        }

        // Remaining activities (afterSlotNumber > slotsPerDay → end of day)
        while (actIdx < activities.size()) {
            var a = activities.get(actIdx++);
            LocalTime end = cursor.plusMinutes(a.durationMinutes());
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, a.label()));
            cursor = end;
        }

        return new DayTemplate(slots, slotsPerDay);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phase 1 — Day assignment
    // ─────────────────────────────────────────────────────────────────────

    private Map<DayOfWeek, List<Occurrence>> phase1DayAssign(
            List<Occurrence> occurrences, List<DayOfWeek> operatingDays,
            int slotsPerDay, Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied, DayTemplate baseTemplate, Random rng) {

        Map<DayOfWeek, Integer> freeSlots = new LinkedHashMap<>();
        for (DayOfWeek d : operatingDays) freeSlots.put(d, slotsPerDay);

        Map<Long, Set<DayOfWeek>> subjectDays = new HashMap<>();
        Map<DayOfWeek, List<Occurrence>> result = new LinkedHashMap<>();
        for (DayOfWeek d : operatingDays) result.put(d, new ArrayList<>());

        List<Occurrence> ordered = sortByConstrainedness(
                occurrences, operatingDays, slotsPerDay,
                unavailable, occupied, baseTemplate, freeSlots, rng);

        return p1Backtrack(ordered, 0, result, freeSlots, subjectDays,
                operatingDays, slotsPerDay, unavailable, occupied, baseTemplate);
    }

    private Map<DayOfWeek, List<Occurrence>> p1Backtrack(
            List<Occurrence> occs, int idx,
            Map<DayOfWeek, List<Occurrence>> assignment,
            Map<DayOfWeek, Integer> freeSlots,
            Map<Long, Set<DayOfWeek>> subjectDays,
            List<DayOfWeek> operatingDays, int slotsPerDay,
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
                    operatingDays, slotsPerDay, unavailable, occupied, baseTemplate);
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

        // Build an "activity-boundary" set: slot index pairs (i, i+1) that
        // are SPLIT by an activity in the template.
        // SP1 fix: never place a double across an activity boundary.
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

            // SP1: prefer positions immediately BEFORE an activity boundary
            // if 2 consecutive free slots exist there — avoids splitting doubles.
            // Then fall through to normal scan if not found.
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

    /**
     * Returns the set of subject-slot indices i where an activity appears
     * AFTER slot i (i.e. between slot i and slot i+1 in the template).
     * A double must not span across such a boundary.
     */
    private Set<Integer> activityBoundarySet(DayTemplate template) {
        Set<Integer> boundaries = new HashSet<>();
        int subjectIdx = -1;
        for (DaySlot ds : template.slots()) {
            if (ds.type() == DaySlotType.SUBJECT) {
                subjectIdx++;
            } else {
                // ACTIVITY slot — if it comes right after a subject slot,
                // mark subjectIdx as a boundary
                if (subjectIdx >= 0) boundaries.add(subjectIdx);
            }
        }
        return boundaries;
    }

    /**
     * Builds an ordered list of starting positions for a double period,
     * respecting the activity-boundary constraint (SP1).
     *
     * Ordering:
     *   1. Positions immediately before an activity boundary (prefer these —
     *      double fits neatly before the break rather than being split).
     *   2. All other valid consecutive-free positions that don't cross a boundary.
     */
    private List<Integer> buildDoubleCandidates(
            int n, Long[] slotAssign, Set<Integer> activityAfterSlot,
            Long teacherId, DayOfWeek day, DayTemplate template,
            List<OccupiedInterval> occupied) {

        List<Integer> preferred = new ArrayList<>(); // just before a boundary
        List<Integer> normal    = new ArrayList<>();

        for (int pos = 0; pos < n - 1; pos++) {
            if (slotAssign[pos] != null || slotAssign[pos + 1] != null) continue;
            // SP1: skip if activity falls between pos and pos+1
            if (activityAfterSlot.contains(pos)) continue;

            // C4: cross-class teacher conflict for both slots
            if (teacherId != null) {
                if (hasConflict(occupied, teacherId, day, template.subjectSlotTime(pos))
                        || hasConflict(occupied, teacherId, day, template.subjectSlotTime(pos + 1)))
                    continue;
            }

            // Prefer pos+1 is a boundary (double fits just before a break)
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

    /**
     * Soft teacher-transition improvement — bubble-sort-style adjacent swaps.
     * Never breaks double-period pairs (both slots of a double move together).
     */
    private void reorderForTeacherTransition(
            Long[] slots, boolean[] isDouble,
            Map<Long, ClassSubjectEntity> subjectById, int n) {

        boolean improved = true;
        int passes = 0;
        while (improved && passes++ < n * 2) {
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                if (slots[i] == null || slots[i + 1] == null) continue;
                if (slots[i].equals(slots[i + 1])) continue; // same subject
                Long tA = getTeacherId(subjectById.get(slots[i]));
                Long tB = getTeacherId(subjectById.get(slots[i + 1]));
                if (tA == null || !tA.equals(tB)) continue;

                // Violation at i→i+1. Try swapping i+1 with i+2.
                // But don't break a double: if isDouble[i+1] && slots[i+1]==slots[i+2], skip.
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
     * Shifts subjects later in days that end too early relative to other days.
     *
     * Algorithm:
     *   1. Find the last filled slot index per day.
     *   2. Find the maximum last-slot index across all days.
     *   3. For any day whose last-slot index is more than 2 below the max,
     *      shift all its placed slots forward by (gap - 2) positions,
     *      so the imbalance shrinks to at most 2 slots.
     *
     * "Shifting forward" = increasing each PlacedSlot.position by the offset,
     * capped so no slot exceeds slotsPerDay-1.
     */
    private Map<DayOfWeek, List<PlacedSlot>> balanceEndTimes(
            Map<DayOfWeek, List<PlacedSlot>> placed,
            List<DayOfWeek> operatingDays, int slotsPerDay) {

        // Find last filled slot index per day
        Map<DayOfWeek, Integer> lastIdx = new LinkedHashMap<>();
        for (DayOfWeek day : operatingDays) {
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
            int last = lastIdx.getOrDefault(day, -1);
            int gap  = maxLast - last; // how many slots earlier this day ends

            if (gap <= 2 || last < 0) {
                result.put(day, placed.getOrDefault(day, List.of()));
                continue;
            }

            // Shift forward by (gap - 2) to bring within 2-slot tolerance
            int shift = gap - 2;
            List<PlacedSlot> shifted = placed.get(day).stream()
                    .map(ps -> {
                        int newPos = Math.min(ps.position() + shift, slotsPerDay - 1);
                        return new PlacedSlot(ps.classSubjectId(), newPos, ps.isDouble());
                    })
                    .collect(Collectors.toList());

            // Verify no two slots land on same position after shift (dedup if needed)
            Set<Integer> usedPositions = new HashSet<>();
            List<PlacedSlot> deduped = new ArrayList<>();
            int overflow = slotsPerDay - 2; // start packing from here if collision
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

        // Validate all class-subjects are covered
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

    /**
     * Derives occurrence tokens for a subject.
     *
     * periods == 1: 1 single
     * periods == 2, forceDouble=false (default): 2 singles on different days
     * periods == 2, forceDouble=true:  1 double on one day
     * periods >= 3: max doubles + remaining singles, each on a distinct day
     */
    private List<Occurrence> deriveOccurrences(
            Long csId, Long teacherId, int periods, boolean forceDouble) {

        List<Occurrence> result = new ArrayList<>();
        if (periods == 1) {
            result.add(new Occurrence(csId, teacherId, false));
        } else if (periods == 2) {
            if (forceDouble) {
                result.add(new Occurrence(csId, teacherId, true));
            } else {
                // Two singles on DIFFERENT days (DA1 enforces different days)
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
            List<Occurrence> occs, List<DayOfWeek> days, int slotsPerDay,
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
            List<Occurrence> occs, List<DayOfWeek> days, int slotsPerDay) {
        int total = occs.stream().mapToInt(o -> o.isDouble() ? 2 : 1).sum();
        int avail = slotsPerDay * days.size();
        if (total > avail)
            throw new BusinessRuleViolationException(
                    "Total periods (" + total + ") exceed available slots (" + avail + ").");
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
            sb.append("Reduce periodsPerWeek, add more days, or vary teacher assignments.");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Value types
    // ─────────────────────────────────────────────────────────────────────

    /** One occurrence block: a single (1 slot) or double (2 consecutive slots) on one day. */
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
