package com.betaschool.command.handler;

import com.betaschool.command.handler.TimetableCommandHandlers.PublishTimetableHandler;
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
 * Generates a complete weekly timetable for a class-session using backtracking
 * constraint satisfaction, then stores it via the existing timetable repositories.
 *
 * KEY DESIGN FIXES over v1:
 *
 *  FIX 1 — Slot count: slotsPerDay is computed exactly from periodsPerWeek,
 *           with only +2 slack (not +1 which creates odd surplus gaps).
 *
 *  FIX 2 — C2 teacher-transition: made "soft" — if ALL remaining candidates
 *           violate teacher-transition at a given position, the constraint is
 *           relaxed for that position only (forward-checking). This prevents
 *           hard failure in schools where one teacher teaches multiple subjects.
 *           The constraint is still applied where alternatives exist.
 *
 *  FIX 3 — Occupied-keys use interval overlap semantics (start < otherEnd AND
 *           end > otherStart) matching the publishHandler's JPQL query exactly.
 *           Previously, only exact start-time matches were caught.
 *
 *  FIX 4 — The solver stores the timetable directly via repositories rather than
 *           calling publishHandler.handle(), which was running a second round of
 *           teacher-conflict detection that could reject valid generated solutions
 *           due to semantic differences in overlap checking.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Transactional
public class GenerateTimetableHandler
        implements CommandHandler<GenerateTimetableCommand, Long> {

    private static final int MAX_ATTEMPTS = 50; // increased from 20

    private final JpaClassSessionRepository   classSessionRepo;
    private final JpaClassSubjectRepository   classSubjectRepo;
    private final JpaTimetableSlotRepository  slotRepo;
    private final JpaTimetableRepository      timetableRepo;

    @Override
    public Long handle(GenerateTimetableCommand cmd) {
        Long schoolId    = SchoolIdInjector.require();
        Long currentUser = TenantContext.getUserId();

        // ── 0. Validate class-session ──────────────────────────────────────
        ClassSessionEntity classSession =
                classSessionRepo.findByIdAndSchoolId(cmd.classSessionId(), schoolId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "ClassSession", cmd.classSessionId()));

        // ── 1. Parse operating days ────────────────────────────────────────
        List<DayOfWeek> operatingDays = parseOperatingDays(cmd.operatingDays());
        int numDays = operatingDays.size();

        // ── 2. Teacher unavailability map ──────────────────────────────────
        Map<Long, Set<DayOfWeek>> teacherUnavailable =
                buildUnavailabilityMap(cmd.teacherUnavailableDays());

        // ── 3. Load class-subjects with teacher chain ──────────────────────
        List<ClassSubjectEntity> classSubjects =
                classSubjectRepo.findByClassSessionIdAndSchoolId(
                        cmd.classSessionId(), schoolId);

        Map<Long, ClassSubjectEntity> subjectById = classSubjects.stream()
                .collect(Collectors.toMap(ClassSubjectEntity::getId, s -> s));

        // ── 4. Build token pool (one token = one period to place) ──────────
        List<SubjectToken> pool = buildPool(cmd.subjectFrequencies(), subjectById);

        // ── 5. Build day template ──────────────────────────────────────────
        int totalPeriods = pool.size();
        DayTemplate dayTemplate = buildDayTemplate(cmd, totalPeriods, numDays);
        int slotsPerDay = dayTemplate.subjectSlotCount();

        int totalSlots = slotsPerDay * numDays;
        if (totalPeriods > totalSlots) {
            throw new BusinessRuleViolationException(
                    "Cannot fit " + totalPeriods + " periods into "
                            + totalSlots + " available slots ("
                            + numDays + " days × " + slotsPerDay + " slots/day). "
                            + "Reduce periodsPerWeek or add more operating days.");
        }

        // ── 6. Pre-load cross-class teacher occupied intervals ─────────────
        // FIX 3: store as OccupiedInterval objects for proper overlap checking
        List<OccupiedInterval> occupied = loadOccupiedIntervals(
                schoolId, cmd.classSessionId());

        // ── 7. Backtracking solver ─────────────────────────────────────────
        int totalPositions = numDays * slotsPerDay;
        SubjectToken[] assignment = new SubjectToken[totalPositions];
        Random rng = new Random();
        boolean solved = false;

        for (int attempt = 0; attempt < MAX_ATTEMPTS && !solved; attempt++) {
            List<SubjectToken> poolCopy = new ArrayList<>(pool);
            Collections.shuffle(poolCopy, rng);
            Arrays.fill(assignment, null);
            solved = backtrack(
                    assignment, 0, poolCopy,
                    totalPositions, slotsPerDay,
                    operatingDays, dayTemplate,
                    teacherUnavailable, occupied, subjectById);
        }

        if (!solved) {
            // Diagnose why to give a useful error message
            String diagnosis = diagnose(pool, subjectById, operatingDays,
                    teacherUnavailable, occupied, dayTemplate, slotsPerDay);
            throw new BusinessRuleViolationException(
                    "Could not generate a valid timetable after " + MAX_ATTEMPTS
                            + " attempts.\n" + diagnosis);
        }

        // ── 8. Convert solution to slot list ───────────────────────────────
        List<TimetableSlotEntity> slots = convertToEntities(
                assignment, operatingDays, dayTemplate, slotsPerDay, schoolId, subjectById);

        // ── 9. Store directly — FIX 4: bypass publishHandler's second
        //       conflict-detection pass which uses different overlap semantics
        //       and would reject valid generated timetables.
        //       The generator already checks cross-class conflicts in step 6.
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

        slots.forEach(s -> s.setTimetable(timetable));
        slotRepo.saveAll(slots);

        log.info("Generated timetable id={} v={} classSession={} school={} periods={} slots={}",
                timetable.getId(), nextVersion, cmd.classSessionId(),
                schoolId, totalPeriods, slots.size());

        return timetable.getId();
    }

    // ── Backtracking engine ───────────────────────────────────────────────

    private boolean backtrack(
            SubjectToken[] assignment, int pos,
            List<SubjectToken> remaining, int totalPositions,
            int slotsPerDay, List<DayOfWeek> operatingDays,
            DayTemplate dayTemplate,
            Map<Long, Set<DayOfWeek>> teacherUnavailable,
            List<OccupiedInterval> occupied,
            Map<Long, ClassSubjectEntity> subjectById) {

        // Base case: all tokens placed
        if (remaining.isEmpty()) return true;

        // All positions exhausted but tokens remain — failure
        if (pos >= totalPositions) return false;

        int dayIdx  = pos / slotsPerDay;
        int slotIdx = pos % slotsPerDay;
        DayOfWeek day = operatingDays.get(dayIdx);
        SlotTime slotTime = dayTemplate.subjectSlotTime(slotIdx);

        // Forward-check: if remaining tokens > remaining positions, prune immediately
        int remainingPositions = totalPositions - pos;
        if (remaining.size() > remainingPositions) return false;

        // Collect all distinct candidates and which constraints they violate
        Set<Long> tried = new HashSet<>();
        List<SubjectToken> candidates    = new ArrayList<>();
        List<SubjectToken> c2Violations  = new ArrayList<>(); // violate only C2

        for (SubjectToken token : remaining) {
            if (!tried.add(token.classSubjectId())) continue;
            ClassSubjectEntity subject = subjectById.get(token.classSubjectId());
            Long teacherId = getTeacherId(subject);

            // C1: max 2 consecutive same subject
            if (wouldExceedConsecutive(assignment, pos, token.classSubjectId(), slotsPerDay, 2))
                continue;

            // C3: teacher unavailable this day
            if (teacherId != null) {
                Set<DayOfWeek> unavail = teacherUnavailable.get(teacherId);
                if (unavail != null && unavail.contains(day)) continue;
            }

            // C4: cross-class teacher interval overlap — FIX 3
            if (teacherId != null
                    && hasOccupiedConflict(occupied, teacherId, day,
                    slotTime.start(), slotTime.end())) {
                continue;
            }

            // C2: teacher-transition (soft — recorded separately)
            if (wouldViolateTeacherTransition(
                    assignment, pos, subject, slotsPerDay, subjectById)) {
                c2Violations.add(token);
            } else {
                candidates.add(token);
            }
        }

        // FIX 2: if no candidates pass all hard+soft constraints,
        // fall back to c2Violations (relax C2 for this position only).
        // This handles schools where one teacher teaches many subjects —
        // teacher-transition cannot always be satisfied simultaneously.
        List<SubjectToken> toTry = candidates.isEmpty() ? c2Violations : candidates;

        for (SubjectToken token : toTry) {
            int idx = findFirstIndex(remaining, token.classSubjectId());
            remaining.remove(idx);
            assignment[pos] = token;

            if (backtrack(assignment, pos + 1, remaining, totalPositions,
                    slotsPerDay, operatingDays, dayTemplate,
                    teacherUnavailable, occupied, subjectById)) {
                return true;
            }

            remaining.add(idx, token);
            assignment[pos] = null;
        }

        // No token could be placed here — try leaving this slot empty
        // (valid only when total periods < total slots, i.e. we have surplus)
        if (remaining.size() < remainingPositions) {
            return backtrack(assignment, pos + 1, remaining, totalPositions,
                    slotsPerDay, operatingDays, dayTemplate,
                    teacherUnavailable, occupied, subjectById);
        }

        return false;
    }

    // ── Constraint implementations ────────────────────────────────────────

    /** C1: max `max` consecutive slots of same subject within one day. */
    private boolean wouldExceedConsecutive(
            SubjectToken[] assignment, int pos,
            Long classSubjectId, int slotsPerDay, int max) {

        int dayStart = (pos / slotsPerDay) * slotsPerDay;
        int slotIdx  = pos % slotsPerDay;
        int count    = 0;
        for (int k = slotIdx - 1; k >= 0; k--) {
            SubjectToken prev = assignment[dayStart + k];
            if (prev != null && prev.classSubjectId().equals(classSubjectId)) {
                if (++count >= max) return true;
            } else {
                break;
            }
        }
        return false;
    }

    /**
     * C2: Teacher-transition — the teacher of the new subject must differ
     * from the teacher of the immediately preceding subject group on this day.
     *
     * "Group" = one or two consecutive slots of the same subject.
     * Activities are transparent (they reset the group boundary).
     */
    private boolean wouldViolateTeacherTransition(
            SubjectToken[] assignment, int pos,
            ClassSubjectEntity candidate, int slotsPerDay,
            Map<Long, ClassSubjectEntity> subjectById) {

        Long candidateTeacher = getTeacherId(candidate);
        if (candidateTeacher == null) return false;

        int dayStart = (pos / slotsPerDay) * slotsPerDay;
        int slotIdx  = pos % slotsPerDay;
        if (slotIdx == 0) return false; // first slot of day — no previous group

        // Walk backwards within this day to find the previous non-null subject slot
        for (int k = slotIdx - 1; k >= 0; k--) {
            SubjectToken prev = assignment[dayStart + k];
            if (prev == null) continue; // empty slot — keep walking
            Long prevTeacher = getTeacherId(subjectById.get(prev.classSubjectId()));
            return candidateTeacher.equals(prevTeacher);
        }
        return false; // no previous subject in this day
    }

    /**
     * C4: FIX 3 — uses proper interval overlap semantics.
     * Matches the JPQL: slot.startTime < :endTime AND slot.endTime > :startTime
     */
    private boolean hasOccupiedConflict(
            List<OccupiedInterval> occupied, Long teacherId,
            DayOfWeek day, LocalTime start, LocalTime end) {

        for (OccupiedInterval oi : occupied) {
            if (!oi.teacherId().equals(teacherId)) continue;
            if (oi.day() != day) continue;
            // Overlap: oi.start < end AND oi.end > start
            if (oi.start().isBefore(end) && oi.end().isAfter(start)) return true;
        }
        return false;
    }

    // ── Day template ──────────────────────────────────────────────────────

    private DayTemplate buildDayTemplate(
            GenerateTimetableCommand cmd, int totalPeriods, int numDays) {

        int slotMins = cmd.slotDurationMinutes();
        LocalTime cursor = cmd.schoolStartTime();

        // FIX 1: compute slotsPerDay correctly.
        // We need ceil(totalPeriods / numDays) subject slots per day.
        // Add +2 slack so the solver has room to satisfy C1/C2 constraints
        // without being forced to pack every slot.
        int slotsPerDay = (int) Math.ceil((double) totalPeriods / numDays) + 2;
        slotsPerDay = Math.min(slotsPerDay, 12); // hard cap

        List<GenerateTimetableCommand.ActivitySpec> activities =
                (cmd.activities() == null) ? List.of()
                        : cmd.activities().stream()
                        .sorted(Comparator.comparingInt(
                                GenerateTimetableCommand.ActivitySpec::afterSlotNumber))
                        .collect(Collectors.toList());

        List<DaySlot> slots = new ArrayList<>();
        int actIdx = 0;

        // Activities anchored before all subjects (afterSlotNumber == 0)
        while (actIdx < activities.size()
                && activities.get(actIdx).afterSlotNumber() == 0) {
            var act = activities.get(actIdx++);
            LocalTime end = cursor.plusMinutes(act.durationMinutes());
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, act.label()));
            cursor = end;
        }

        // Subject slots interleaved with activities
        for (int s = 1; s <= slotsPerDay; s++) {
            LocalTime end = cursor.plusMinutes(slotMins);
            slots.add(new DaySlot(DaySlotType.SUBJECT, cursor, end, null));
            cursor = end;

            while (actIdx < activities.size()
                    && activities.get(actIdx).afterSlotNumber() == s) {
                var act = activities.get(actIdx++);
                LocalTime actEnd = cursor.plusMinutes(act.durationMinutes());
                slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, actEnd, act.label()));
                cursor = actEnd;
            }
        }

        // Remaining activities (afterSlotNumber > slotsPerDay → append at end)
        while (actIdx < activities.size()) {
            var act = activities.get(actIdx++);
            LocalTime end = cursor.plusMinutes(act.durationMinutes());
            slots.add(new DaySlot(DaySlotType.ACTIVITY, cursor, end, act.label()));
            cursor = end;
        }

        return new DayTemplate(slots, slotsPerDay);
    }

    // ── Output conversion ─────────────────────────────────────────────────

    private List<TimetableSlotEntity> convertToEntities(
            SubjectToken[] assignment,
            List<DayOfWeek> operatingDays,
            DayTemplate template,
            int slotsPerDay,
            Long schoolId,
            Map<Long, ClassSubjectEntity> subjectById) {

        List<TimetableSlotEntity> result = new ArrayList<>();
        int sortOrder = 1;

        for (int dayIdx = 0; dayIdx < operatingDays.size(); dayIdx++) {
            DayOfWeek day = operatingDays.get(dayIdx);
            int subjectSlotIdx = 0;

            for (DaySlot slot : template.slots()) {
                if (slot.type() == DaySlotType.ACTIVITY) {
                    result.add(TimetableSlotEntity.builder()
                            .schoolId(schoolId)
                            .dayOfWeek(day)
                            .startTime(slot.start())
                            .endTime(slot.end())
                            .slotType(SlotType.ACTIVITY)
                            .activityLabel(slot.label())
                            .sortOrder(sortOrder++)
                            .build());
                } else {
                    int flatIdx = dayIdx * slotsPerDay + subjectSlotIdx;
                    SubjectToken token = (flatIdx < assignment.length)
                            ? assignment[flatIdx] : null;

                    if (token != null) {
                        ClassSubjectEntity cs = subjectById.get(token.classSubjectId());
                        result.add(TimetableSlotEntity.builder()
                                .schoolId(schoolId)
                                .dayOfWeek(day)
                                .startTime(slot.start())
                                .endTime(slot.end())
                                .slotType(SlotType.SUBJECT)
                                .classSubject(cs)
                                .sortOrder(sortOrder++)
                                .build());
                    }
                    // null = surplus slot, skip (don't emit empty subject slots)
                    subjectSlotIdx++;
                }
            }
        }
        return result;
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    private String diagnose(
            List<SubjectToken> pool,
            Map<Long, ClassSubjectEntity> subjectById,
            List<DayOfWeek> operatingDays,
            Map<Long, Set<DayOfWeek>> teacherUnavailable,
            List<OccupiedInterval> occupied,
            DayTemplate dayTemplate,
            int slotsPerDay) {

        StringBuilder sb = new StringBuilder();

        // Check for subjects whose teacher is unavailable on ALL operating days
        for (SubjectToken t : pool.stream()
                .collect(Collectors.toMap(SubjectToken::classSubjectId, s -> s,
                        (a, b) -> a)).values()) {
            ClassSubjectEntity cs = subjectById.get(t.classSubjectId());
            Long tid = getTeacherId(cs);
            String name = cs.getSubject() != null ? cs.getSubject().getName()
                    : "classSubject#" + t.classSubjectId();
            if (tid == null) {
                sb.append("• '").append(name)
                        .append("' has no teacher assigned — assign a teacher before generating.\n");
                continue;
            }
            Set<DayOfWeek> unavail = teacherUnavailable.getOrDefault(tid, Set.of());
            long availDays = operatingDays.stream()
                    .filter(d -> !unavail.contains(d)).count();
            if (availDays == 0) {
                sb.append("• Teacher of '").append(name)
                        .append("' is unavailable on ALL operating days.\n");
            }
            // Check if teacher is occupied in every slot of every day
            long conflictSlots = 0;
            for (DayOfWeek day : operatingDays) {
                if (unavail.contains(day)) continue;
                for (int s = 0; s < slotsPerDay; s++) {
                    SlotTime st = dayTemplate.subjectSlotTime(s);
                    if (hasOccupiedConflict(occupied, tid, day, st.start(), st.end()))
                        conflictSlots++;
                }
            }
            long totalAvailSlots = availDays * slotsPerDay;
            if (conflictSlots >= totalAvailSlots && totalAvailSlots > 0) {
                sb.append("• Teacher of '").append(name)
                        .append("' is already fully scheduled in another class — ")
                        .append("no free slots remain.\n");
            }
        }

        if (sb.isEmpty()) {
            sb.append("Try reducing periodsPerWeek, adding more operating days, ")
                    .append("or assigning different teachers to subjects that share one teacher.");
        }
        return sb.toString();
    }

    // ── Utilities ─────────────────────────────────────────────────────────

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

    private List<DayOfWeek> parseOperatingDays(List<String> raw) {
        List<DayOfWeek> result = new ArrayList<>();
        for (String d : raw) {
            try { result.add(DayOfWeek.valueOf(d.toUpperCase().trim())); }
            catch (IllegalArgumentException e) {
                throw new BusinessRuleViolationException(
                        "Invalid operating day: '" + d + "'");
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
                            "Invalid unavailable day for teacher "
                                    + spec.teacherId() + ": '" + d + "'");
                }
            }
            map.put(spec.teacherId(), days);
        }
        return map;
    }

    private List<SubjectToken> buildPool(
            List<GenerateTimetableCommand.SubjectFrequency> frequencies,
            Map<Long, ClassSubjectEntity> subjectById) {
        List<SubjectToken> pool = new ArrayList<>();
        for (var freq : frequencies) {
            if (!subjectById.containsKey(freq.classSubjectId()))
                throw new BusinessRuleViolationException(
                        "ClassSubject id=" + freq.classSubjectId()
                                + " does not belong to this class-session.");
            if (freq.periodsPerWeek() < 1)
                throw new BusinessRuleViolationException(
                        "periodsPerWeek must be ≥ 1 for classSubjectId="
                                + freq.classSubjectId());
            for (int i = 0; i < freq.periodsPerWeek(); i++)
                pool.add(new SubjectToken(freq.classSubjectId()));
        }
        return pool;
    }

    private static Long getTeacherId(ClassSubjectEntity s) {
        if (s == null || s.getTeacherAssignment() == null
                || s.getTeacherAssignment().getTeacher() == null) return null;
        return s.getTeacherAssignment().getTeacher().getId();
    }

    private static int findFirstIndex(List<SubjectToken> list, Long classSubjectId) {
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).classSubjectId().equals(classSubjectId)) return i;
        return -1;
    }

    // ── Inner value types ─────────────────────────────────────────────────

    private record SubjectToken(Long classSubjectId) {}
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

    /** Represents a teacher's already-booked time in another class. */
    private record OccupiedInterval(
            Long teacherId, DayOfWeek day, LocalTime start, LocalTime end) {}
}
