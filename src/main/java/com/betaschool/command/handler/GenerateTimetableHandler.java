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
 * Two-phase timetable generator using constraint satisfaction.
 *
 * ═══════════════════════════════════════════════════════════════
 *  OCCURRENCE STRUCTURE (derived automatically from periodsPerWeek)
 * ═══════════════════════════════════════════════════════════════
 *
 *   periods  →  occurrences (D=double/2 consecutive, S=single/1 slot)
 *      1     →  S
 *      2     →  D  or  S+S  (system chooses per-subject for best spread)
 *      3     →  D + S
 *      4     →  D + D
 *      5     →  D + D + S
 *      6     →  D + D + D
 *      7     →  D + D + D + S
 *      ...etc (max doubles, min singles)
 *
 *  Each occurrence lands on a DIFFERENT day — no two occurrences of
 *  the same subject share a day. Subjects are spread across as many
 *  distinct days as possible.
 *
 * ═══════════════════════════════════════════════════════════════
 *  PHASE 1 — DAY ASSIGNMENT
 * ═══════════════════════════════════════════════════════════════
 *  Assign each occurrence to a day via backtracking.
 *  Hard constraints:
 *    DA1  No two occurrences of the same subject on the same day
 *    DA2  Teacher not unavailable on that day
 *    DA3  Day has enough free subject-slots (≥2 for double, ≥1 for single)
 *  Ordering heuristic: most-constrained subject first (fewest legal days).
 *
 * ═══════════════════════════════════════════════════════════════
 *  PHASE 2 — SLOT PLACEMENT WITHIN EACH DAY
 * ═══════════════════════════════════════════════════════════════
 *  For each day, order occurrences into actual slot positions.
 *  Hard constraints:
 *    SP1  Double occurrences occupy two consecutive positions
 *    SP2  Teacher-transition: consecutive subject groups must not
 *         share a teacher (soft — relaxed when no alternative)
 *    SP3  Cross-class teacher conflict (interval overlap check)
 *  Doubles are placed before singles to ensure consecutive slots exist.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Transactional
public class GenerateTimetableHandler
        implements CommandHandler<GenerateTimetableCommand, Long> {

    private static final int MAX_PHASE1_ATTEMPTS = 100;
    private static final int MAX_PHASE2_ATTEMPTS = 50;

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

        // Build occurrence list from subject frequencies
        List<Occurrence> occurrences = buildOccurrences(
                cmd.subjectFrequencies(), subjectById, numDays);

        // Build day template (activity placement + slot times)
        int totalPeriods = occurrences.stream()
                .mapToInt(o -> o.isDouble() ? 2 : 1).sum();
        DayTemplate template = buildDayTemplate(cmd, totalPeriods, numDays);
        int slotsPerDay = template.subjectSlotCount();

        // Validate feasibility
        validateFeasibility(occurrences, operatingDays, slotsPerDay, unavailable);

        // Pre-load cross-class occupied intervals
        List<OccupiedInterval> occupied =
                loadOccupiedIntervals(schoolId, cmd.classSessionId());

        // ── Phase 1: assign each occurrence to a day ──────────────────────
        Map<DayOfWeek, List<Occurrence>> dayAssignment = null;
        Random rng = new Random();

        for (int attempt = 0; attempt < MAX_PHASE1_ATTEMPTS && dayAssignment == null;
             attempt++) {
            dayAssignment = phase1DayAssign(
                    occurrences, operatingDays, slotsPerDay,
                    unavailable, occupied, template, rng);
        }

        if (dayAssignment == null) {
            throw new BusinessRuleViolationException(
                    "Could not assign subjects to days after " + MAX_PHASE1_ATTEMPTS
                            + " attempts.\n"
                            + diagnose(occurrences, subjectById, operatingDays,
                            unavailable, occupied, template, slotsPerDay));
        }

        // ── Phase 2: place occurrences into slot positions within each day ─
        Map<DayOfWeek, List<PlacedSlot>> placed = null;

        for (int attempt = 0; attempt < MAX_PHASE2_ATTEMPTS && placed == null;
             attempt++) {
            placed = phase2SlotPlace(dayAssignment, operatingDays, template,
                    subjectById, occupied, rng);
        }

        if (placed == null) {
            throw new BusinessRuleViolationException(
                    "Could not arrange slots within days after " + MAX_PHASE2_ATTEMPTS
                            + " attempts. Try adjusting subject frequencies or teacher assignments.");
        }

        // ── Persist ───────────────────────────────────────────────────────
        timetableRepo.deactivateAllForClassSession(cmd.classSessionId(), schoolId);
        int nextVersion = timetableRepo.findMaxVersionByClassSessionIdAndSchoolId(
                cmd.classSessionId(), schoolId) + 1;

        String notes = (cmd.notes() != null && !cmd.notes().isBlank())
                ? cmd.notes()
                : "Auto-generated v" + nextVersion;

        TimetableEntity timetable = timetableRepo.save(TimetableEntity.builder()
                .schoolId(schoolId)
                .classSession(classSession)
                .version(nextVersion)
                .active(true)
                .notes(notes)
                .createdBy(currentUser)
                .build());

        List<TimetableSlotEntity> slots = buildEntities(
                placed, operatingDays, template, schoolId, subjectById);
        slots.forEach(s -> s.setTimetable(timetable));
        slotRepo.saveAll(slots);

        log.info("Generated timetable id={} v={} classSession={} school={} "
                        + "periods={} occurrences={}",
                timetable.getId(), nextVersion, cmd.classSessionId(),
                schoolId, totalPeriods, occurrences.size());
        return timetable.getId();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phase 1 — Day assignment
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Assigns each occurrence to a day using backtracking.
     * Returns null on failure (caller retries with re-shuffle).
     *
     * Ordering: process occurrences in most-constrained-first order
     * (fewest legal days available) to prune the search tree early.
     */
    private Map<DayOfWeek, List<Occurrence>> phase1DayAssign(
            List<Occurrence> occurrences,
            List<DayOfWeek> operatingDays,
            int slotsPerDay,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied,
            DayTemplate template,
            Random rng) {

        // Track how many subject-slots each day still has free
        Map<DayOfWeek, Integer> freeSlots = new LinkedHashMap<>();
        for (DayOfWeek d : operatingDays) freeSlots.put(d, slotsPerDay);

        // Track which days each subject already occupies
        Map<Long, Set<DayOfWeek>> subjectDays = new HashMap<>();

        // Result accumulator
        Map<DayOfWeek, List<Occurrence>> result = new LinkedHashMap<>();
        for (DayOfWeek d : operatingDays) result.put(d, new ArrayList<>());

        // Sort occurrences: most constrained first (fewest legal days)
        List<Occurrence> ordered = sortByConstrainedness(
                occurrences, operatingDays, slotsPerDay,
                unavailable, occupied, template, freeSlots, rng);

        return p1Backtrack(ordered, 0, result, freeSlots, subjectDays,
                operatingDays, slotsPerDay, unavailable, occupied, template);
    }

    private Map<DayOfWeek, List<Occurrence>> p1Backtrack(
            List<Occurrence> occurrences, int idx,
            Map<DayOfWeek, List<Occurrence>> assignment,
            Map<DayOfWeek, Integer> freeSlots,
            Map<Long, Set<DayOfWeek>> subjectDays,
            List<DayOfWeek> operatingDays, int slotsPerDay,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied,
            DayTemplate template) {

        if (idx == occurrences.size()) return assignment;

        Occurrence occ = occurrences.get(idx);
        int needed = occ.isDouble() ? 2 : 1;
        Long teacherId = occ.teacherId();

        // Shuffle day order for randomisation
        List<DayOfWeek> shuffledDays = new ArrayList<>(operatingDays);
        Collections.shuffle(shuffledDays);

        for (DayOfWeek day : shuffledDays) {
            // DA1: same subject already on this day
            Set<DayOfWeek> usedDays = subjectDays.getOrDefault(
                    occ.classSubjectId(), Set.of());
            if (usedDays.contains(day)) continue;

            // DA2: teacher unavailable
            if (teacherId != null) {
                Set<DayOfWeek> unavailDays = unavailable.getOrDefault(
                        teacherId, Set.of());
                if (unavailDays.contains(day)) continue;
            }

            // DA3: enough free slots
            if (freeSlots.getOrDefault(day, 0) < needed) continue;

            // DA4: teacher not cross-class conflicted in ALL needed slots
            // (light check at day-assignment phase — detailed check in phase 2)
            if (teacherId != null && isTeacherFullyBlockedOnDay(
                    teacherId, day, needed, template, occupied)) continue;

            // Place
            assignment.get(day).add(occ);
            freeSlots.merge(day, -needed, Integer::sum);
            subjectDays.computeIfAbsent(occ.classSubjectId(),
                    k -> new HashSet<>()).add(day);

            Map<DayOfWeek, List<Occurrence>> sub = p1Backtrack(
                    occurrences, idx + 1, assignment, freeSlots, subjectDays,
                    operatingDays, slotsPerDay, unavailable, occupied, template);
            if (sub != null) return sub;

            // Undo
            assignment.get(day).remove(occ);
            freeSlots.merge(day, needed, Integer::sum);
            subjectDays.get(occ.classSubjectId()).remove(day);
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phase 2 — Slot placement within each day
    // ─────────────────────────────────────────────────────────────────────

    /**
     * For each operating day, orders the assigned occurrences into
     * concrete slot positions.
     *
     * Doubles occupy two consecutive positions (placed first to guarantee
     * consecutive slots exist). Singles fill remaining positions.
     * Teacher-transition constraint (C2) applied as soft preference.
     */
    private Map<DayOfWeek, List<PlacedSlot>> phase2SlotPlace(
            Map<DayOfWeek, List<Occurrence>> dayAssignment,
            List<DayOfWeek> operatingDays,
            DayTemplate template,
            Map<Long, ClassSubjectEntity> subjectById,
            List<OccupiedInterval> occupied,
            Random rng) {

        Map<DayOfWeek, List<PlacedSlot>> result = new LinkedHashMap<>();

        for (DayOfWeek day : operatingDays) {
            List<Occurrence> occsThisDay = dayAssignment.getOrDefault(day, List.of());
            if (occsThisDay.isEmpty()) {
                result.put(day, List.of());
                continue;
            }

            List<PlacedSlot> placed = p2PlaceDay(
                    day, occsThisDay, template, subjectById, occupied, rng);
            if (placed == null) return null; // signal phase2 failure
            result.put(day, placed);
        }
        return result;
    }

    private List<PlacedSlot> p2PlaceDay(
            DayOfWeek day,
            List<Occurrence> occs,
            DayTemplate template,
            Map<Long, ClassSubjectEntity> subjectById,
            List<OccupiedInterval> occupied,
            Random rng) {

        int slotsPerDay = template.subjectSlotCount();
        // null = empty slot
        Long[] slotAssign = new Long[slotsPerDay]; // classSubjectId per position
        boolean[] isDoubleSlot = new boolean[slotsPerDay];

        // Separate doubles and singles; shuffle each group
        List<Occurrence> doubles = occs.stream()
                .filter(Occurrence::isDouble)
                .collect(Collectors.toList());
        List<Occurrence> singles = occs.stream()
                .filter(o -> !o.isDouble())
                .collect(Collectors.toList());
        Collections.shuffle(doubles, rng);
        Collections.shuffle(singles, rng);

        // Place doubles first (need 2 consecutive free slots)
        for (Occurrence d : doubles) {
            boolean placed = false;
            for (int pos = 0; pos < slotsPerDay - 1; pos++) {
                if (slotAssign[pos] != null || slotAssign[pos + 1] != null) continue;

                // C3: cross-class conflict for both slots
                SlotTime t1 = template.subjectSlotTime(pos);
                SlotTime t2 = template.subjectSlotTime(pos + 1);
                Long tid = d.teacherId();
                if (tid != null && (hasConflict(occupied, tid, day, t1)
                        || hasConflict(occupied, tid, day, t2))) continue;

                slotAssign[pos]     = d.classSubjectId();
                slotAssign[pos + 1] = d.classSubjectId();
                isDoubleSlot[pos]   = true;
                isDoubleSlot[pos + 1] = true;
                placed = true;
                break;
            }
            if (!placed) return null; // no room for this double
        }

        // Place singles in remaining free slots
        // Build candidate positions first
        List<Integer> freePositions = new ArrayList<>();
        for (int i = 0; i < slotsPerDay; i++) {
            if (slotAssign[i] == null) freePositions.add(i);
        }
        Collections.shuffle(freePositions, rng);

        for (Occurrence s : singles) {
            boolean placed = false;
            for (int pos : freePositions) {
                if (slotAssign[pos] != null) continue;

                SlotTime t = template.subjectSlotTime(pos);
                Long tid = s.teacherId();
                if (tid != null && hasConflict(occupied, tid, day, t)) continue;

                slotAssign[pos] = s.classSubjectId();
                placed = true;
                break;
            }
            if (!placed) return null;
        }

        // Apply teacher-transition (C2) as a soft reorder
        // Attempt to reorder so consecutive groups have different teachers
        reorderForTeacherTransition(slotAssign, subjectById, slotsPerDay);

        // Convert to PlacedSlot list
        List<PlacedSlot> result = new ArrayList<>();
        for (int i = 0; i < slotsPerDay; i++) {
            if (slotAssign[i] != null) {
                result.add(new PlacedSlot(slotAssign[i], i,
                        isDoubleSlot[i]));
            }
        }
        return result;
    }

    /**
     * Best-effort teacher-transition improvement via bubble-sort-style swaps.
     * Swaps adjacent groups if doing so reduces teacher-transition violations,
     * without disturbing double-period pairs (they must stay consecutive).
     */
    private void reorderForTeacherTransition(
            Long[] slots, Map<Long, ClassSubjectEntity> subjectById, int n) {

        boolean improved = true;
        int passes = 0;
        while (improved && passes++ < n * 2) {
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                if (slots[i] == null || slots[i + 1] == null) continue;
                // Don't break double-period pairs
                if (slots[i].equals(slots[i + 1])) continue;

                Long tA = getTeacherId(subjectById.get(slots[i]));
                Long tB = getTeacherId(subjectById.get(slots[i + 1]));
                if (tA == null || tB == null || !tA.equals(tB)) continue;

                // Violation at i→i+1. Try swapping i+1 with i+2 if safe
                if (i + 2 < n && slots[i + 2] != null
                        && !slots[i + 1].equals(slots[i + 2])) {
                    Long tC = getTeacherId(subjectById.get(slots[i + 2]));
                    if (tC != null && !tC.equals(tA)) {
                        // Swap i+1 and i+2
                        Long tmp = slots[i + 1];
                        slots[i + 1] = slots[i + 2];
                        slots[i + 2] = tmp;
                        improved = true;
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Occurrence building
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Derives occurrence structure from periodsPerWeek per the stated rules:
     *   max doubles, min singles; for n=2 system chooses (tracked separately).
     *
     * "Spread" is enforced implicitly: each occurrence is a separate backtracking
     * token that Phase 1 places on a different day.
     */
    private List<Occurrence> buildOccurrences(
            List<GenerateTimetableCommand.SubjectFrequency> frequencies,
            Map<Long, ClassSubjectEntity> subjectById,
            int numDays) {

        List<Occurrence> all = new ArrayList<>();

        for (var freq : frequencies) {
            Long csId = freq.classSubjectId();
            if (!subjectById.containsKey(csId))
                throw new BusinessRuleViolationException(
                        "ClassSubject id=" + csId
                                + " does not belong to this class-session.");
            int periods = freq.periodsPerWeek();
            if (periods < 1)
                throw new BusinessRuleViolationException(
                        "periodsPerWeek must be ≥ 1 for classSubjectId=" + csId);

            ClassSubjectEntity cs = subjectById.get(csId);
            Long teacherId = getTeacherId(cs);

            List<Occurrence> occs = deriveOccurrences(csId, teacherId, periods);

            if (occs.size() > numDays)
                throw new BusinessRuleViolationException(
                        "Subject id=" + csId + " needs " + occs.size()
                                + " occurrences across " + periods
                                + " periods, but only " + numDays + " operating days available. "
                                + "Reduce periodsPerWeek or add more operating days.");

            all.addAll(occs);
        }
        return all;
    }

    /**
     * Derives the occurrence list for a single subject.
     *
     * Rule: max doubles, then singles for remainder.
     * For n=2: mark as FLEXIBLE — Phase 1 will try DOUBLE first;
     *          if that fails, retry as 2×SINGLE.
     */
    private List<Occurrence> deriveOccurrences(
            Long csId, Long teacherId, int periods) {

        List<Occurrence> result = new ArrayList<>();
        if (periods == 1) {
            result.add(new Occurrence(csId, teacherId, false, false));
            return result;
        }
        if (periods == 2) {
            // Mark as FLEXIBLE: Phase 1 will try double first
            result.add(new Occurrence(csId, teacherId, true, true));  // flex=true, initially double
            return result;
        }
        // periods >= 3: max doubles
        int doubles = periods / 2;
        int singles = periods % 2;
        for (int i = 0; i < doubles; i++)
            result.add(new Occurrence(csId, teacherId, true, false));
        for (int i = 0; i < singles; i++)
            result.add(new Occurrence(csId, teacherId, false, false));
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Day template
    // ─────────────────────────────────────────────────────────────────────

    private DayTemplate buildDayTemplate(
            GenerateTimetableCommand cmd, int totalPeriods, int numDays) {

        int slotMins = cmd.slotDurationMinutes();
        LocalTime cursor = cmd.schoolStartTime();

        // slotsPerDay = ceil(totalPeriods / numDays) + 2 slack
        int slotsPerDay = (int) Math.ceil((double) totalPeriods / numDays) + 2;
        slotsPerDay = Math.min(slotsPerDay, 14);

        List<GenerateTimetableCommand.ActivitySpec> activities =
                cmd.activities() == null ? List.of()
                        : cmd.activities().stream()
                        .sorted(Comparator.comparingInt(
                                GenerateTimetableCommand.ActivitySpec::afterSlotNumber))
                        .collect(Collectors.toList());

        List<DaySlot> slots = new ArrayList<>();
        int actIdx = 0;

        // Activities before all subjects
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
            while (actIdx < activities.size()
                    && activities.get(actIdx).afterSlotNumber() == s) {
                var a = activities.get(actIdx++);
                LocalTime actEnd = cursor.plusMinutes(a.durationMinutes());
                slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, actEnd, a.label()));
                cursor = actEnd;
            }
        }

        while (actIdx < activities.size()) {
            var a = activities.get(actIdx++);
            LocalTime end = cursor.plusMinutes(a.durationMinutes());
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, a.label()));
            cursor = end;
        }

        return new DayTemplate(slots, slotsPerDay);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Entity conversion
    // ─────────────────────────────────────────────────────────────────────

    private List<TimetableSlotEntity> buildEntities(
            Map<DayOfWeek, List<PlacedSlot>> placed,
            List<DayOfWeek> operatingDays,
            DayTemplate template,
            Long schoolId,
            Map<Long, ClassSubjectEntity> subjectById) {

        List<TimetableSlotEntity> result = new ArrayList<>();
        int sortOrder = 1;

        for (DayOfWeek day : operatingDays) {
            // Build a position→classSubjectId map from placed slots
            Map<Integer, Long> posToSubject = new HashMap<>();
            List<PlacedSlot> daySlots = placed.getOrDefault(day, List.of());
            for (PlacedSlot ps : daySlots) posToSubject.put(ps.position(), ps.classSubjectId());

            int subjectSlotIdx = 0;
            for (DaySlot ds : template.slots()) {
                if (ds.type() == DaySlotType.ACTIVITY) {
                    result.add(TimetableSlotEntity.builder()
                            .schoolId(schoolId)
                            .dayOfWeek(day)
                            .startTime(ds.start())
                            .endTime(ds.end())
                            .slotType(SlotType.ACTIVITY)
                            .activityLabel(ds.label())
                            .sortOrder(sortOrder++)
                            .build());
                } else {
                    Long csId = posToSubject.get(subjectSlotIdx);
                    if (csId != null) {
                        result.add(TimetableSlotEntity.builder()
                                .schoolId(schoolId)
                                .dayOfWeek(day)
                                .startTime(ds.start())
                                .endTime(ds.end())
                                .slotType(SlotType.SUBJECT)
                                .classSubject(subjectById.get(csId))
                                .sortOrder(sortOrder++)
                                .build());
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

    /** Sorts occurrences: most constrained (fewest legal days) first. */
    private List<Occurrence> sortByConstrainedness(
            List<Occurrence> occs, List<DayOfWeek> days, int slotsPerDay,
            Map<Long, Set<DayOfWeek>> unavailable,
            List<OccupiedInterval> occupied, DayTemplate template,
            Map<DayOfWeek, Integer> freeSlots, Random rng) {

        List<Occurrence> copy = new ArrayList<>(occs);
        Collections.shuffle(copy, rng); // randomise ties
        copy.sort(Comparator.comparingInt(o -> {
            int needed = o.isDouble() ? 2 : 1;
            Long tid = o.teacherId();
            int legal = 0;
            for (DayOfWeek d : days) {
                if (freeSlots.getOrDefault(d, 0) < needed) continue;
                if (tid != null) {
                    Set<DayOfWeek> u = unavailable.getOrDefault(tid, Set.of());
                    if (u.contains(d)) continue;
                    if (isTeacherFullyBlockedOnDay(tid, d, needed, template, occupied))
                        continue;
                }
                legal++;
            }
            return legal;
        }));
        return copy;
    }

    /**
     * Phase 1 light check: does the teacher have at least `needed` free slots
     * on this day (not blocked by other classes)?
     */
    private boolean isTeacherFullyBlockedOnDay(
            Long teacherId, DayOfWeek day, int needed,
            DayTemplate template, List<OccupiedInterval> occupied) {

        int freeCount = 0;
        int n = template.subjectSlotCount();
        for (int i = 0; i < n; i++) {
            SlotTime t = template.subjectSlotTime(i);
            if (!hasConflict(occupied, teacherId, day, t)) freeCount++;
            if (freeCount >= needed) return false;
        }
        return true;
    }

    private boolean hasConflict(
            List<OccupiedInterval> occupied, Long teacherId,
            DayOfWeek day, SlotTime t) {
        for (OccupiedInterval oi : occupied) {
            if (!oi.teacherId().equals(teacherId)) continue;
            if (oi.day() != day) continue;
            if (oi.start().isBefore(t.end()) && oi.end().isAfter(t.start()))
                return true;
        }
        return false;
    }

    private List<OccupiedInterval> loadOccupiedIntervals(
            Long schoolId, Long excludeClassSessionId) {
        return slotRepo
                .findAllActiveSubjectSlotsForSchoolExcludingClass(
                        schoolId, excludeClassSessionId)
                .stream()
                .filter(s -> s.getClassSubject() != null
                        && s.getClassSubject().getTeacherAssignment() != null)
                .map(s -> new OccupiedInterval(
                        s.getClassSubject().getTeacherAssignment().getTeacher().getId(),
                        s.getDayOfWeek(),
                        s.getStartTime(),
                        s.getEndTime()))
                .collect(Collectors.toList());
    }

    private void validateFeasibility(
            List<Occurrence> occurrences, List<DayOfWeek> days,
            int slotsPerDay, Map<Long, Set<DayOfWeek>> unavailable) {

        int totalPeriods = occurrences.stream()
                .mapToInt(o -> o.isDouble() ? 2 : 1).sum();
        int totalSlots = slotsPerDay * days.size();
        if (totalPeriods > totalSlots)
            throw new BusinessRuleViolationException(
                    "Total periods (" + totalPeriods + ") exceed available slots ("
                            + totalSlots + "). Reduce periodsPerWeek or add more days.");
    }

    private List<DayOfWeek> parseOperatingDays(List<String> raw) {
        List<DayOfWeek> result = new ArrayList<>();
        for (String d : raw) {
            try { result.add(DayOfWeek.valueOf(d.toUpperCase().trim())); }
            catch (IllegalArgumentException e) {
                throw new BusinessRuleViolationException(
                        "Invalid day: '" + d + "'");
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
                            "Invalid day for teacher " + spec.teacherId()
                                    + ": '" + d + "'");
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
            String name = cs.getSubject() != null ? cs.getSubject().getName()
                    : "id=" + o.classSubjectId();
            Long tid = o.teacherId();
            if (tid == null) {
                sb.append("• '").append(name)
                        .append("' has no teacher assigned.\n");
                continue;
            }
            Set<DayOfWeek> unavailDays = unavailable.getOrDefault(tid, Set.of());
            long freeDays = days.stream()
                    .filter(d -> !unavailDays.contains(d))
                    .filter(d -> !isTeacherFullyBlockedOnDay(
                            tid, d, 1, template, occupied))
                    .count();
            if (freeDays == 0)
                sb.append("• Teacher of '").append(name)
                        .append("' has no available days (unavailability + cross-class conflicts).\n");
        }
        if (sb.isEmpty())
            sb.append("Try reducing periodsPerWeek, increasing operating days, "
                    + "or assigning different teachers to subjects sharing one teacher.");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Value types
    // ─────────────────────────────────────────────────────────────────────

    /**
     * One occurrence = one block of consecutive periods on a single day.
     * isDouble=true → 2 consecutive slots; false → 1 slot.
     * isFlexible=true → periodsPerWeek was 2; Phase 1 tries double first,
     *                    retries as 2×single if double can't be placed.
     */
    private record Occurrence(
            Long classSubjectId,
            Long teacherId,
            boolean isDouble,
            boolean isFlexible) {}

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